(ns app.notify
  (:require [app.sqlite :as sqlite]
            [event-bus :as bus]
            [lcmm.router :as router]))

(defn- ok [body]
  {:status 200
   :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body body})

(defn- handle-booking-created [bus db logger envelope]
  (let [{:keys [booking-id slot name]} (:payload envelope)
        message (str "notify booking=" booking-id " slot=" slot " name=" name)]
    (sqlite/add-notification! db {:booking-id booking-id :message message})
    (logger :info {:component ::notify :event :notification-stored :booking-id booking-id})
    (bus/publish bus
                 :notify/booking-created
                 {:booking-id booking-id :message message}
                 {:parent-envelope envelope
                  :module ::notify})))

(defn handle-list-notifications [db _request]
  (ok (pr-str (sqlite/list-notifications db))))

(defn init! [{:keys [bus router logger db]}]
  (logger :info {:component ::notify :event :module-initializing})
  (bus/subscribe bus
                 :booking/created
                 (fn [event-bus envelope]
                   (handle-booking-created event-bus db logger envelope))
                 {:meta ::booking-created-handler})
  (router/add-route! router
                     :get "/notify/list"
                     (partial handle-list-notifications db)
                     {:name ::list-notifications})
  (logger :info {:component ::notify :event :module-initialized}))
