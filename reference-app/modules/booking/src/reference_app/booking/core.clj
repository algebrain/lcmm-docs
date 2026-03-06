(ns reference-app.booking.core
  (:require [clojure.string :as str]
            [event-bus :as bus]
            [lcmm.http.core :as http]
            [lcmm.read-provider-registry :as rpr]
            [lcmm.router :as router]
            [reference-app.booking.db :as db]))

(def supported-backends #{"jdbc" "sqlite"})

(defn- edn-response [status body]
  {:status status
   :headers {"Content-Type" "application/edn; charset=utf-8"}
   :body (pr-str body)})

(defn- text-response [status body]
  {:status status
   :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body body})

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

(defn- query-param [request key]
  (or (get-in request [:query-params key])
      (get-in request [:query-params (name key)])))

(defn- sanitize-booking [booking]
  (some-> booking
          (update :status str)))

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

(defn- booking-publish-opts [request]
  (http/->bus-publish-opts request {:module :booking}))

(defn- handle-create-booking [store logger get-user get-slot bus-instance request]
  (let [slot-id (query-param request :slot-id)
        user-id (query-param request :user-id)]
    (cond
      (str/blank? (some-> slot-id str))
      (text-response 400 "slot-id is required")

      (str/blank? (some-> user-id str))
      (text-response 400 "user-id is required")

      :else
      (let [user (get-user {:user-id user-id})
            slot (get-slot {:slot-id slot-id})]
        (bus/publish bus-instance
                     :booking/create-requested
                     {:slot-id slot-id :user-id user-id}
                     (booking-publish-opts request))
        (cond
          (and (map? user) (= :invalid-arg (:code user)))
          (text-response 400 (:message user))

          (and (map? slot) (= :invalid-arg (:code slot)))
          (text-response 400 (:message slot))

          (nil? user)
          (do
            (bus/publish bus-instance
                         :booking/rejected
                         {:slot-id slot-id :user-id user-id :reason "user-not-found"}
                         (booking-publish-opts request))
            (text-response 404 "user not found"))

          (nil? slot)
          (do
            (bus/publish bus-instance
                         :booking/rejected
                         {:slot-id slot-id :user-id user-id :reason "slot-not-found"}
                         (booking-publish-opts request))
            (text-response 404 "slot not found"))

          (not= "open" (:status slot))
          (do
            (bus/publish bus-instance
                         :booking/rejected
                         {:slot-id slot-id :user-id user-id :reason "slot-not-open"}
                         (booking-publish-opts request))
            (text-response 409 "slot not open"))

          :else
          (let [booking-id (str (java.util.UUID/randomUUID))
                booking (db/create-booking! store {:id booking-id
                                                   :slot-id slot-id
                                                   :user-id user-id
                                                   :status "created"})]
            (logger :info {:component ::booking
                           :event :booking-created
                           :booking-id booking-id
                           :slot-id slot-id
                           :user-id user-id})
            (bus/publish bus-instance
                         :booking/created
                         {:booking-id booking-id :slot-id slot-id :user-id user-id}
                         (booking-publish-opts request))
            (edn-response 200 (sanitize-booking booking))))))))

(defn- handle-get-booking [store _logger request]
  (let [booking-id (or (get-in request [:path-params :booking_id])
                       (get-in request [:path-params "booking_id"]))]
    (if-let [booking (sanitize-booking (db/get-booking-by-id store booking-id))]
      (edn-response 200 booking)
      (text-response 404 "booking not found"))))

(defn- handle-list-bookings [store _logger request]
  (let [user-id (query-param request :user-id)
        bookings (if (str/blank? (some-> user-id str))
                   (db/list-bookings store)
                   (db/list-bookings-by-user store user-id))]
    (edn-response 200 (mapv sanitize-booking bookings))))

(defn init!
  [{:keys [bus router logger read-provider-registry] :as deps}]
  (when-not bus
    (throw (ex-info "Bus is required" {:reason :missing-bus})))
  (when-not router
    (throw (ex-info "Router is required" {:reason :missing-router})))
  (when-not logger
    (throw (ex-info "Logger is required" {:reason :missing-logger})))
  (when-not read-provider-registry
    (throw (ex-info "Read-provider registry is required" {:reason :missing-read-provider-registry})))
  (logger :info {:component ::booking :event :module-initializing})
  (rpr/declare-requirements! read-provider-registry :booking #{:accounts/get-user-by-id :catalog/get-slot-by-id})
  (let [store (resolve-store! deps)
        get-user (rpr/require-provider read-provider-registry :accounts/get-user-by-id)
        get-slot (rpr/require-provider read-provider-registry :catalog/get-slot-by-id)]
    (db/init-schema! store)
    (router/add-route! router
                       :get "/bookings/actions/create"
                       (partial handle-create-booking store logger get-user get-slot bus)
                       {:name ::create-booking})
    (router/add-route! router
                       :get "/bookings/:booking_id"
                       (partial handle-get-booking store logger)
                       {:name ::get-booking})
    (router/add-route! router
                       :get "/bookings"
                       (partial handle-list-bookings store logger)
                       {:name ::list-bookings})
    (logger :info {:component ::booking :event :module-initialized})
    {:module :booking
     :store store}))
