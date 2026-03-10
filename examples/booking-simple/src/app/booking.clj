(ns app.booking
  (:require [clojure.string :as str]
            [app.sqlite :as sqlite]
            [event-bus :as bus]
            [lcmm.router :as router]))

(defn- bad-request [message]
  {:status 400
   :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body message})

(defn- ok [body]
  {:status 200
   :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body body})

(defn- conflict [body]
  {:status 409
   :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body body})

(defn- not-found [body]
  {:status 404
   :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body body})

(defn- query-param [request k]
  (some-> (get-in request [:query-params k]) str/trim))

(defn handle-create-booking [bus db logger request]
  (let [slot (query-param request "slot")
        name (query-param request "name")]
    (cond
      (str/blank? slot) (bad-request "slot is required")
      (str/blank? name) (bad-request "name is required")
      :else
      (let [booking-id (str (java.util.UUID/randomUUID))]
        (bus/publish bus
                     :booking/create-requested
                     {:slot slot :name name}
                     {:module ::booking})
        (let [{:keys [ok? reason]} (sqlite/create-booking! db {:booking-id booking-id
                                                               :slot slot
                                                               :customer-name name})]
          (if ok?
            (do
              (logger :info {:component ::booking
                             :event :booking-created
                             :booking-id booking-id
                             :slot slot})
              (bus/publish bus
                           :booking/created
                           {:booking-id booking-id :slot slot :name name}
                           {:module ::booking})
              (ok (str "BOOKING CREATED id=" booking-id)))
            (do
              (logger :warn {:component ::booking
                             :event :booking-rejected
                             :slot slot
                             :reason reason})
              (bus/publish bus
                           :booking/rejected
                           {:slot slot :name name :reason (clojure.core/name reason)}
                           {:module ::booking})
              (conflict (str "BOOKING REJECTED reason=" (clojure.core/name reason))))))))))

(defn handle-get-booking [db request]
  (let [booking-id (query-param request "id")]
    (if (str/blank? booking-id)
      (bad-request "id is required")
      (if-let [booking (sqlite/get-booking db booking-id)]
        (ok (pr-str booking))
        (not-found "booking not found")))))

(defn handle-list-bookings [db _request]
  (ok (pr-str (sqlite/list-bookings db))))

(defn init! [{:keys [bus router logger db]}]
  (logger :info {:component ::booking :event :module-initializing})
  (router/add-route! router
                     :get "/booking/create"
                     (partial handle-create-booking bus db logger)
                     {:name ::create-booking})
  (router/add-route! router
                     :get "/booking/get"
                     (partial handle-get-booking db)
                     {:name ::get-booking})
  (router/add-route! router
                     :get "/booking/list"
                     (partial handle-list-bookings db)
                     {:name ::list-bookings})
  (logger :info {:component ::booking :event :module-initialized}))
