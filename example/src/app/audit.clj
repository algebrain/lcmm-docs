(ns app.audit
  (:require [app.sqlite :as sqlite]
            [event-bus :as bus]
            [lcmm.router :as router]))

(defn- ok [body]
  {:status 200
   :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body body})

(defn- audit-envelope! [db logger envelope]
  (let [payload (:payload envelope)
        event-type (:event-type envelope)
        event-type-str (if-let [event-ns (namespace event-type)]
                         (str event-ns "/" (name event-type))
                         (name event-type))
        booking-id (or (:booking-id payload) (:id payload))
        details (pr-str {:payload payload
                         :correlation-id (:correlation-id envelope)
                         :causation-path (:causation-path envelope)})]
    (sqlite/add-audit! db {:event-type event-type-str
                           :booking-id booking-id
                           :details details})
    (logger :info {:component ::audit
                   :event :audit-recorded
                   :event-type (:event-type envelope)
                   :booking-id booking-id})))

(defn handle-list-audit [db _request]
  (ok (pr-str (sqlite/list-audit db))))

(defn init! [{:keys [bus router logger db]}]
  (logger :info {:component ::audit :event :module-initializing})
  (doseq [event-type [:booking/create-requested
                      :booking/created
                      :booking/rejected
                      :notify/booking-created]]
    (bus/subscribe bus
                   event-type
                   (fn [_ envelope]
                     (audit-envelope! db logger envelope))
                   {:meta [:audit-handler event-type]}))
  (router/add-route! router
                     :get "/audit/list"
                     (partial handle-list-audit db)
                     {:name ::list-audit})
  (logger :info {:component ::audit :event :module-initialized}))
