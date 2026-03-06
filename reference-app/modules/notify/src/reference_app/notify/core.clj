(ns reference-app.notify.core
  (:require [clojure.string :as str]
            [event-bus :as bus]
            [lcmm.router :as router]
            [reference-app.notify.db :as db]))

(def supported-backends #{"jdbc" "sqlite"})

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

(defn- notification-message [{:keys [booking-id slot-id user-id]}]
  (str "booking-created booking-id=" booking-id " slot-id=" slot-id " user-id=" user-id))

(defn- handle-booking-created [store logger bus-instance envelope]
  (let [{:keys [booking-id] :as payload} (:payload envelope)
        notification-id (str (java.util.UUID/randomUUID))
        message (notification-message payload)]
    (db/create-notification! store {:id notification-id
                                    :booking-id booking-id
                                    :message message})
    (logger :info {:component ::notify
                   :event :notification-created
                   :notification-id notification-id
                   :booking-id booking-id})
    (bus/publish bus-instance
                 :notify/booking-created
                 {:booking-id booking-id
                  :message message}
                 {:parent-envelope envelope
                  :module :notify})))

(defn- handle-list-notifications [store _logger _request]
  (edn-response 200 (vec (db/list-notifications store))))

(defn init!
  [{:keys [bus router logger] :as deps}]
  (when-not bus
    (throw (ex-info "Bus is required" {:reason :missing-bus})))
  (when-not router
    (throw (ex-info "Router is required" {:reason :missing-router})))
  (when-not logger
    (throw (ex-info "Logger is required" {:reason :missing-logger})))
  (logger :info {:component ::notify :event :module-initializing})
  (let [store (resolve-store! deps)]
    (db/init-schema! store)
    (bus/subscribe bus
                   :booking/created
                   (fn [bus-instance envelope]
                     (handle-booking-created store logger bus-instance envelope))
                   {:meta ::booking-created-handler})
    (router/add-route! router
                       :get "/notifications"
                       (partial handle-list-notifications store logger)
                       {:name ::list-notifications})
    (logger :info {:component ::notify :event :module-initialized})
    {:module :notify
     :store store}))
