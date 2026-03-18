(ns booking-full.security
  (:require [clojure.string :as str]
            [lcmm-guard.backend.in-memory :as backend]
            [lcmm-guard.ban-store :as ban-store]
            [lcmm-guard.core :as guard]
            [lcmm-guard.detector :as detector]
            [lcmm-guard.rate-limiter :as rate-limiter]
            [lcmm-guard.ring :as guard.ring]
            [lcmm.read-provider-registry :as rpr]
            [lcmm.router :as router]
            [booking-full.config :as config]))

(defn- now-sec []
  (quot (System/currentTimeMillis) 1000))

(defn- query-param [request key]
  (or (get-in request [:query-params key])
      (get-in request [:query-params (name key)])))

(defn- preprocess-guard-request [request]
  (guard.ring/preprocess-loopback-remote-addr request))

(defn make-guard [app-config]
  (let [counter-store (backend/make-counter-store)
        ttl-store (backend/make-ttl-store)
        rate-limit (long (config/config-value app-config "guard.rate_limit" 60))
        rate-window-sec (long (config/config-value app-config "guard.rate_window_sec" 60))
        login-window-sec (long (config/config-value app-config "guard.login_window_sec" 300))
        login-max-failures (long (config/config-value app-config "guard.login_max_failures" 20))
        ban-ttl-sec (long (config/config-value app-config "guard.ban_ttl_sec" 900))
        mode (keyword (str (config/config-value app-config "guard.mode" "fail-open")))
        guard-instance (guard/make-guard
                        {:ip-config {:trust-xff? false
                                     :trusted-proxies #{}}
                         :ban-store (ban-store/make-ban-store
                                     {:ttl-store ttl-store
                                      :allow-list #{}
                                      :default-ban-ttl-sec ban-ttl-sec})
                         :rate-limiter (rate-limiter/make-rate-limiter
                                        {:counter-store counter-store
                                         :limit rate-limit
                                         :window-sec rate-window-sec})
                         :detector (detector/make-detector
                                    {:counter-store counter-store
                                     :thresholds {:validation-failed 20
                                                  :auth-failed login-max-failures
                                                  :suspicious 20}
                                     :window-sec login-window-sec
                                     :bucket-sec 10})
                         :mode-policy {:mode mode}})]
    guard-instance))

(defn- request-log-context [request]
  (let [request (preprocess-guard-request request)]
    {:path (:uri request)
     :remote-addr (:remote-addr request)
     :correlation-id (:lcmm/correlation-id request)
     :request-id (:lcmm/request-id request)}))

(def ^:private guard-bypass-paths
  #{"/ops/guard/unban"})

(defn- log-guard-events! [logger request result]
  (doseq [event (:events result)]
    (logger :warn (merge {:component ::security
                          :event :security/guard-event
                          :guard-event event}
                         (request-log-context request)))))

(defn- guard-request-opts [request]
  {:request request
   :correlation-id (:lcmm/correlation-id request)})

(defn wrap-guard [handler guard-instance logger]
  (let [guarded-handler (guard.ring/wrap-guard
                         handler
                         guard-instance
                         {:now-fn now-sec
                          :preprocess-request preprocess-guard-request
                          :request->guard-opts guard-request-opts
                          :action->response guard.ring/default-action->response
                          :on-result (fn [request result]
                                       (log-guard-events! logger request result))})]
    (fn [request]
      (if (contains? guard-bypass-paths (:uri request))
        (handler request)
        (guarded-handler request)))))

(defn- login-success-response [user]
  {:status 200
   :headers {"Content-Type" "application/edn; charset=utf-8"}
   :body (pr-str {:ok true
                  :user user})})

(defn- login-failure-response []
  {:status 401
   :headers {"Content-Type" "application/edn; charset=utf-8"}
   :body (pr-str {:code "invalid_credentials"
                  :message "Invalid credentials"})})

(defn- unban-response [result]
  (if (:ok? result)
    {:status 200
     :headers {"Content-Type" "application/edn; charset=utf-8"}
     :body (pr-str {:ok true
                    :ip (:ip result)})}
    {:status 503
     :headers {"Content-Type" "application/edn; charset=utf-8"}
     :body (pr-str {:ok false
                    :ip (:ip result)
                    :action (:action result)})}))

(defn- handle-demo-login [guard-instance get-user-by-login logger request]
  (let [request (preprocess-guard-request request)
        login (query-param request :login)]
    (cond
      (str/blank? (some-> login str))
      {:status 400
       :headers {"Content-Type" "application/edn; charset=utf-8"}
       :body (pr-str {:code "login_required"
                      :message "login is required"})}

      :else
      (let [user (get-user-by-login {:login login})]
        (cond
          (and (map? user) (= :invalid-arg (:code user)))
          {:status 400
           :headers {"Content-Type" "application/edn; charset=utf-8"}
           :body (pr-str {:code "invalid_login"
                          :message (:message user)})}

          user
          (do
            (logger :info (merge {:component ::security
                                  :event :security/demo-login-succeeded
                                  :login login}
                                 (request-log-context request)))
            (login-success-response user))

          :else
          (let [result (guard.ring/report-auth-failure!
                        guard-instance
                        {:request request
                         :endpoint "/auth/demo-login"
                         :code :invalid-credentials
                         :now (now-sec)
                         :request->guard-opts guard-request-opts})
                short-circuit (guard.ring/default-action->response result)]
            (log-guard-events! logger request result)
            (logger :warn (merge {:component ::security
                                  :event :security/demo-login-failed
                                  :login login}
                                 (request-log-context request)))
            (if short-circuit
              short-circuit
              (login-failure-response))))))))

(defn- handle-unban [guard-instance logger request]
  (let [ip (query-param request :ip)]
    (if (str/blank? (some-> ip str))
      {:status 400
       :headers {"Content-Type" "application/edn; charset=utf-8"}
       :body (pr-str {:code "ip_required"
                      :message "ip is required"})}
      (let [result (guard/unban-and-reset-ip! guard-instance
                                              {:ip ip
                                               :reason :manual
                                               :now (now-sec)
                                               :correlation-id (:lcmm/correlation-id request)})]
        (log-guard-events! logger request result)
        (logger :info (merge {:component ::security
                              :event :security/guard-unban-requested
                              :ip ip
                              :ok? (:ok? result)}
                             (request-log-context request)))
        (unban-response result)))))

(defn install-security-routes! [router registry guard-instance logger]
  (let [get-user-by-login (rpr/require-provider registry :accounts/get-user-by-login)]
    (router/add-route! router
                       :get "/auth/demo-login"
                       (partial handle-demo-login guard-instance get-user-by-login logger)
                       {:name ::demo-login})
    (router/add-route! router
                       :get "/ops/guard/unban"
                       (partial handle-unban guard-instance logger)
                       {:name ::guard-unban})))
