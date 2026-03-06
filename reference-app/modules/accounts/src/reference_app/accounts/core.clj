(ns reference-app.accounts.core
  (:require [clojure.string :as str]
            [lcmm.read-provider-registry :as rpr]
            [lcmm.router :as router]
            [reference-app.accounts.db :as db]))

(def supported-backends #{"jdbc" "sqlite"})

(defn- ok [body]
  {:status 200
   :headers {"Content-Type" "application/edn; charset=utf-8"}
   :body (pr-str body)})

(defn- bad-request [body]
  {:status 400
   :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body body})

(defn- not-found [body]
  {:status 404
   :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body body})

(defn- config-value
  [config dot-key]
  (let [parts (str/split dot-key #"\.")]
    (or (get config dot-key)
        (get config (keyword dot-key))
        (reduce (fn [acc part]
                  (when (map? acc)
                    (or (get acc part)
                        (get acc (keyword part)))))
                config
                parts))))

(defn- sanitize-user [user]
  (some-> user
          (dissoc :password)
          (update :role str)
          (update :login str)))

(defn- query-param [request key]
  (or (get-in request [:query-params key])
      (get-in request [:query-params (name key)])))

(defn- handle-get-me [store logger request]
  (if-let [user-id (query-param request :user-id)]
    (if-let [user (sanitize-user (db/get-user-by-id store user-id))]
      (do
        (logger :info {:component ::accounts
                       :event :get-me
                       :user-id user-id})
        (ok user))
      (not-found "user not found"))
    (bad-request "user-id is required")))

(defn- handle-get-user-by-id [store logger request]
  (let [user-id (or (get-in request [:path-params :user_id])
                    (get-in request [:path-params "user_id"]))]
    (cond
      (str/blank? (some-> user-id str))
      (not-found "user not found")

      :else
      (if-let [user (sanitize-user (db/get-user-by-id store user-id))]
        (do
          (logger :info {:component ::accounts
                         :event :get-user-by-id
                         :user-id user-id})
          (ok user))
        (not-found "user not found")))))

(defn- provider-result [user]
  (sanitize-user user))

(defn- provider-invalid-arg [message]
  {:code :invalid-arg
   :message message
   :retryable? false})

(defn- register-providers! [registry store logger]
  (rpr/register-provider! registry
                          {:provider-id :accounts/get-user-by-id
                           :module :accounts
                           :provider-fn (fn [{:keys [user-id]}]
                                          (cond
                                            (str/blank? (some-> user-id str))
                                            (provider-invalid-arg "user-id must be non-empty string")

                                            :else
                                            (let [user (provider-result (db/get-user-by-id store user-id))]
                                              (logger :info {:component ::accounts
                                                             :event :provider-get-user-by-id
                                                             :user-id user-id
                                                             :found? (boolean user)})
                                              user)))
                           :meta {:version "1.0"}})
  (rpr/register-provider! registry
                          {:provider-id :accounts/get-user-by-login
                           :module :accounts
                           :provider-fn (fn [{:keys [login]}]
                                          (cond
                                            (str/blank? (some-> login str))
                                            (provider-invalid-arg "login must be non-empty string")

                                            :else
                                            (let [user (provider-result (db/get-user-by-login store login))]
                                              (logger :info {:component ::accounts
                                                             :event :provider-get-user-by-login
                                                             :login login
                                                             :found? (boolean user)})
                                              user)))
                           :meta {:version "1.0"}}))

(defn- resolve-store! [{:keys [config db]}]
  (let [mode (config-value config "storage.mode")
        configured-backend (config-value config "storage.backend")
        allow-self-managed? (true? (config-value config "storage.allow-self-managed"))
        sqlite-path (config-value config "storage.sqlite.path")
        backend (or (:backend-type db) configured-backend)]
    (cond
      db
      (if (contains? supported-backends backend)
        (db/make-jdbc-store db)
        (throw (ex-info "Unsupported external backend"
                        {:reason :unsupported-backend
                         :backend backend})))

      (and (= mode "self-managed")
           (= configured-backend "sqlite")
           allow-self-managed?)
      (let [store (db/make-sqlite-store {:path sqlite-path})]
        (db/init-schema! store)
        (db/ensure-seed-users! store)
        store)

      :else
      (throw (ex-info "Storage backend is not configured"
                      {:reason :storage-not-configured
                       :mode mode
                       :backend backend})))))

(defn init!
  [{:keys [bus router logger read-provider-registry] :as deps}]
  (when-not bus
    (throw (ex-info "Bus is required"
                    {:reason :missing-bus})))
  (when-not router
    (throw (ex-info "Router is required"
                    {:reason :missing-router})))
  (when-not logger
    (throw (ex-info "Logger is required"
                    {:reason :missing-logger})))
  (when-not read-provider-registry
    (throw (ex-info "Read-provider registry is required"
                    {:reason :missing-read-provider-registry})))
  (logger :info {:component ::accounts
                 :event :module-initializing})
  (let [store (resolve-store! deps)]
    (db/init-schema! store)
    (db/ensure-seed-users! store)
    (register-providers! read-provider-registry store logger)
    (router/add-route! router
                       :get "/accounts/me"
                       (partial handle-get-me store logger)
                       {:name ::get-me})
    (router/add-route! router
                       :get "/accounts/users/:user_id"
                       (partial handle-get-user-by-id store logger)
                       {:name ::get-user-by-id})
    (logger :info {:component ::accounts
                   :event :module-initialized})
    {:module :accounts
     :store store}))
