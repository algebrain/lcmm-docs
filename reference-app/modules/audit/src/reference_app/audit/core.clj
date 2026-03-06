(ns reference-app.audit.core
  (:require [clojure.string :as str]
            [event-bus :as bus]
            [lcmm.router :as router]
            [reference-app.audit.db :as db]))

(def supported-backends #{"jdbc" "sqlite"})

(def tracked-events
  [:booking/create-requested
   :booking/created
   :booking/rejected
   :notify/booking-created])

(defn- edn-response [status body]
  {:status status
   :headers {"Content-Type" "application/edn; charset=utf-8"}
   :body (pr-str body)})

(defn- config-value [config dot-key]
  (let [parts (str/split dot-key #"\.")]
    (or (get config dot-key)
        (get config (keyword dot-key))
        (reduce (fn [acc part]
                  (when (map? acc)
                    (or (get acc part)
                        (get acc (keyword part)))))
                config
                parts))))

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
        store)

      :else
      (throw (ex-info "Storage backend is not configured"
                      {:reason :storage-not-configured
                       :mode mode
                       :backend backend})))))

(defn- event-type->string [event-type]
  (if-let [ns-part (namespace event-type)]
    (str ns-part "/" (name event-type))
    (name event-type)))

(defn- summarize-envelope [envelope]
  (pr-str {:payload (:payload envelope)
           :module (:module envelope)
           :message-id (:message-id envelope)}))

(defn- record-envelope! [store logger envelope]
  (let [event-type (:event-type envelope)
        record-id (str (java.util.UUID/randomUUID))]
    (db/create-audit-record! store
                             {:id record-id
                              :event-type (event-type->string event-type)
                              :correlation-id (:correlation-id envelope)
                              :causation-path (pr-str (:causation-path envelope))
                              :details (summarize-envelope envelope)})
    (logger :info {:component ::audit
                   :event :audit-record-created
                   :audit-id record-id
                   :event-type event-type})))

(defn- handle-list-audit [store _logger _request]
  (edn-response 200 (vec (db/list-audit-records store))))

(defn init!
  [{:keys [bus router logger] :as deps}]
  (when-not bus
    (throw (ex-info "Bus is required" {:reason :missing-bus})))
  (when-not router
    (throw (ex-info "Router is required" {:reason :missing-router})))
  (when-not logger
    (throw (ex-info "Logger is required" {:reason :missing-logger})))
  (logger :info {:component ::audit :event :module-initializing})
  (let [store (resolve-store! deps)]
    (db/init-schema! store)
    (doseq [event-type tracked-events]
      (bus/subscribe bus
                     event-type
                     (fn [_ envelope]
                       (record-envelope! store logger envelope))
                     {:meta [::audit-handler event-type]}))
    (router/add-route! router
                       :get "/audit"
                       (partial handle-list-audit store logger)
                       {:name ::list-audit})
    (logger :info {:component ::audit :event :module-initialized})
    {:module :audit
     :store store}))
