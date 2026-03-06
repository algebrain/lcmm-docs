(ns reference-app.catalog.core
  (:require [clojure.string :as str]
            [lcmm.read-provider-registry :as rpr]
            [lcmm.router :as router]
            [reference-app.catalog.db :as db]))

(def supported-backends #{"jdbc" "sqlite"})

(defn- ok [body]
  {:status 200
   :headers {"Content-Type" "application/edn; charset=utf-8"}
   :body (pr-str body)})

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

(defn- query-param [request key]
  (or (get-in request [:query-params key])
      (get-in request [:query-params (name key)])))

(defn- sanitize-slot [slot]
  (some-> slot
          (update :status str)
          (update :label str)))

(defn- provider-invalid-arg [message]
  {:code :invalid-arg
   :message message
   :retryable? false})

(defn- handle-list-slots [store logger request]
  (let [status (query-param request :status)
        slots (if (str/blank? (some-> status str))
                (db/list-slots store)
                (db/list-slots-by-status store status))]
    (logger :info {:component ::catalog
                   :event :list-slots
                   :status-filter status
                   :count (count slots)})
    (ok (mapv sanitize-slot slots))))

(defn- handle-get-slot [store logger request]
  (let [slot-id (or (get-in request [:path-params :slot_id])
                    (get-in request [:path-params "slot_id"]))]
    (if-let [slot (sanitize-slot (db/get-slot-by-id store slot-id))]
      (do
        (logger :info {:component ::catalog
                       :event :get-slot
                       :slot-id slot-id})
        (ok slot))
      (not-found "slot not found"))))

(defn- register-providers! [registry store logger]
  (rpr/register-provider! registry
                          {:provider-id :catalog/get-slot-by-id
                           :module :catalog
                           :provider-fn (fn [{:keys [slot-id]}]
                                          (cond
                                            (str/blank? (some-> slot-id str))
                                            (provider-invalid-arg "slot-id must be non-empty string")

                                            :else
                                            (let [slot (sanitize-slot (db/get-slot-by-id store slot-id))]
                                              (logger :info {:component ::catalog
                                                             :event :provider-get-slot-by-id
                                                             :slot-id slot-id
                                                             :found? (boolean slot)})
                                              slot)))
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
        (db/ensure-seed-slots! store)
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
  (logger :info {:component ::catalog
                 :event :module-initializing})
  (let [store (resolve-store! deps)]
    (db/init-schema! store)
    (db/ensure-seed-slots! store)
    (register-providers! read-provider-registry store logger)
    (router/add-route! router
                       :get "/catalog/slots"
                       (partial handle-list-slots store logger)
                       {:name ::list-slots})
    (router/add-route! router
                       :get "/catalog/slots/:slot_id"
                       (partial handle-get-slot store logger)
                       {:name ::get-slot-by-id})
    (logger :info {:component ::catalog
                   :event :module-initialized})
    {:module :catalog
     :store store}))
