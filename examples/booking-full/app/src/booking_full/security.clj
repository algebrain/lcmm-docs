(ns booking-full.security
  (:require [clojure.string :as str]
            [lcmm-guard.backend.protocols :as guard.protocols]
            [lcmm-guard.backend.in-memory :as backend]
            [lcmm-guard.ban-store :as ban-store]
            [lcmm-guard.core :as guard]
            [lcmm-guard.detector :as detector]
            [lcmm-guard.rate-limiter :as rate-limiter]
            [lcmm.read-provider-registry :as rpr]
            [lcmm.router :as router]
            [booking-full.config :as config]))

(defn- now-sec []
  (quot (System/currentTimeMillis) 1000))

(defn- query-param [request key]
  (or (get-in request [:query-params key])
      (get-in request [:query-params (name key)])))

(def ^:private loopback-ip-aliases
  #{"::1"
    "0:0:0:0:0:0:0:1"
    "::ffff:127.0.0.1"})

(defn- normalize-ip [ip]
  (let [ip-str (some-> ip str str/trim)]
    (cond
      (str/blank? ip-str)
      ip

      (contains? loopback-ip-aliases (str/lower-case ip-str))
      "127.0.0.1"

      :else
      ip-str)))

(defn- normalize-request-ip [request]
  (update request :remote-addr normalize-ip))

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
    (assoc guard-instance
           ::counter-store counter-store
           ::detector-kinds [:validation-failed :auth-failed :suspicious]
           ::detector-endpoints ["*" "/auth/demo-login"])))

(defn- guard-response [{:keys [action ip]}]
  (case action
    :allow nil
    :degraded-allow nil
    :rate-limited {:status 429
                   :headers {"Content-Type" "application/edn; charset=utf-8"}
                   :body (pr-str {:code "rate_limited"
                                  :message "Too many requests"
                                  :ip ip})}
    :banned {:status 429
             :headers {"Content-Type" "application/edn; charset=utf-8"}
             :body (pr-str {:code "ip_banned"
                            :message "IP temporarily banned"
                            :ip ip})}
    :degraded-block {:status 503
                     :headers {"Content-Type" "application/edn; charset=utf-8"}
                     :body (pr-str {:code "guard_unavailable"
                                    :message "Security guard unavailable"})}
    {:status 500
     :headers {"Content-Type" "application/edn; charset=utf-8"}
     :body (pr-str {:code "unknown_guard_action"
                    :action (str action)})}))

(defn- request-log-context [request]
  (let [request (normalize-request-ip request)]
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

(defn wrap-guard [handler guard-instance logger]
  (fn [request]
    (let [request (normalize-request-ip request)]
      (if (contains? guard-bypass-paths (:uri request))
        (handler request)
        (let [result (guard/evaluate-request! guard-instance
                                              {:request request
                                               :now (now-sec)
                                               :correlation-id (:lcmm/correlation-id request)})
              short-circuit (guard-response result)]
          (log-guard-events! logger request result)
          (if short-circuit
            short-circuit
            (handler request)))))))

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

(defn- clear-counter-key!
  [counter-store key]
  (when counter-store
    (guard.protocols/prune-before-bucket! counter-store key Long/MAX_VALUE)))

(defn- reset-guard-state-for-ip!
  [guard-instance ip]
  (let [counter-store (::counter-store guard-instance)
        detector-kinds (::detector-kinds guard-instance)
        detector-endpoints (::detector-endpoints guard-instance)]
    (clear-counter-key! counter-store [:rate-limit ip])
    (doseq [kind detector-kinds
            endpoint detector-endpoints]
      (clear-counter-key! counter-store [:detector kind ip endpoint]))))

(defn- handle-demo-login [guard-instance get-user-by-login logger request]
  (let [request (normalize-request-ip request)
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
          (let [result (guard/evaluate-request! guard-instance
                                                {:request request
                                                 :kind :auth-failed
                                                 :endpoint "/auth/demo-login"
                                                 :code :invalid-credentials
                                                 :now (now-sec)
                                                 :correlation-id (:lcmm/correlation-id request)})
                short-circuit (guard-response result)]
            (log-guard-events! logger request result)
            (logger :warn (merge {:component ::security
                                  :event :security/demo-login-failed
                                  :login login}
                                 (request-log-context request)))
            (if short-circuit
              short-circuit
              (login-failure-response))))))))

(defn- handle-unban [guard-instance logger request]
  (let [ip (normalize-ip (query-param request :ip))]
    (if (str/blank? (some-> ip str))
      {:status 400
       :headers {"Content-Type" "application/edn; charset=utf-8"}
       :body (pr-str {:code "ip_required"
                      :message "ip is required"})}
      (let [result (guard/unban-ip! guard-instance
                                    {:ip ip
                                     :reason :manual
                                     :now (now-sec)
                                     :correlation-id (:lcmm/correlation-id request)})]
        (when (:ok? result)
          (reset-guard-state-for-ip! guard-instance ip))
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
