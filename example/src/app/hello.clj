(ns app.hello
  (:require [lcmm.router :as router]
            [event-bus :as bus]))

(defonce current-name (atom "World"))

(defn handle-name-changed
  "Event handler: update current name and publish derived greeting."
  [bus logger envelope]
  (let [new-name (get-in envelope [:payload :name])]
    (logger :info {:component ::hello, :event :name-changed, :name new-name})
    (reset! current-name new-name)
    (bus/publish bus
                 :hello/greeting-updated
                 {:greeting (str "Hello " new-name)}
                 {:parent-envelope envelope})))

(defn handle-get-hello
  "HTTP handler to return the greeting."
  [logger request]
  (logger :info {:component ::hello, :event :get-hello, :current-name @current-name})
  {:status 200
   :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body (str "Hello " @current-name)})

(defn init!
  "Initializes the 'hello' module."
  [{:keys [bus router logger]}]
  (logger :info {:component ::hello, :event :module-initializing})

  ;; Подписка на событие :name/changed
  (bus/subscribe bus
                 :name/changed
                 (fn [bus envelope]
                   (handle-name-changed bus logger envelope))
                 {:meta ::hello-name-handler})

  (logger :info {:component ::hello, :event :module-initialized})

  (router/add-route! router
                     :get "/hello"
                     (partial handle-get-hello logger)
                     {:name ::get-hello}))
