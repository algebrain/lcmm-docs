(ns app.hello
  (:require [lcmm.router :as router]
            [event-bus :as bus]))

(defonce current-name (atom "World"))

(defn handle-name-changed
  "Event handler to update the current name.
   Сигнатура [bus envelope] по BUS.md — позволяет публиковать производные события."
  [bus envelope logger]
  (let [new-name (get-in envelope [:payload :name])]
    (logger :info {:component ::hello, :event :name-changed-event, :new-name new-name, :envelope envelope})
    (reset! current-name new-name)
    (logger :info {:component ::hello, :event :atom-updated, :current-name @current-name})
    ;; Пример: публикация события после обработки
    (bus/publish bus
                 :hello/greeting-updated
                 {:greeting (str "Hello " new-name)}
                 {:parent-envelope envelope})))

(defn handle-get-hello
  "HTTP handler to return the greeting."
  [logger request]
  (try
    (logger :info {:component ::hello, :event :get-hello-request, :current-name @current-name})
    {:status 200
     :headers {"Content-Type" "text/plain; charset=utf-8"}
     :body (str "Hello " @current-name)}
    (catch Exception e
      (logger :error {:component ::hello, :event :get-hello-error, :exception e})
      {:status 500
       :headers {"Content-Type" "text/plain"}
       :body "Internal server error"})))

(defn init!
  "Initializes the 'hello' module."
  [{:keys [bus router logger]}]
  (logger :info {:component ::hello, :event :module-initializing, :current-name @current-name})
  
  ;; Подписка на событие :name/changed
  ;; Обработчик получает [bus envelope] и может публиковать события
  (bus/subscribe bus
                 :name/changed
                 (fn [bus envelope]
                   ;; Замыкание захватывает logger из init!
                   (logger :info {:component ::hello, :event :subscription-callback, :envelope envelope})
                   (handle-name-changed bus envelope logger))
                 {:meta ::hello-name-handler})

  ;; Подписка на событие :hello/greeting-updated для логирования
  (bus/subscribe bus
                 :hello/greeting-updated
                 (fn [bus envelope]
                   (logger :info {:component ::hello, :event :greeting-updated, :payload (:payload envelope)})))

  (logger :info {:component ::hello, :event :module-initialized})

  (router/add-route! router
                     :get "/hello"
                     (partial handle-get-hello logger)
                     {:name ::get-hello}))
