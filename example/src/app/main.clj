(ns app.main
  (:require [event-bus :as bus]
            [lcmm.router :as router]
            [org.httpkit.server :as http-kit]
            [clojure.tools.logging :as log]
            [ring.middleware.params :refer [wrap-params]]
            [app.name :as name]
            [app.hello :as hello])
  (:gen-class))

(defn make-app-logger
  "Создаёт структурированный логгер по LOGGING.md.
   Интегрируется с clojure.tools.logging для production использования."
  []
  (fn [level data]
    (let [log-data (if (map? data) data {:message data})]
      (case level
        :info  (log/info log-data)
        :warn  (log/warn log-data)
        :error (log/error log-data)
        :debug (log/debug log-data)
        :trace (log/trace log-data)
        (log/info log-data)))))

(defn- make-global-middleware
  "Создаёт глобальный middleware для обработки ошибок и логирования HTTP-запросов."
  [logger]
  (fn [handler]
    (fn [request]
      (try
        (logger :info {:component ::main, :event :http-request, :method (:request-method request), :uri (:uri request)})
        (handler request)
        (catch Exception e
          (logger :error {:component ::main, :event :http-error, :exception e, :uri (:uri request)})
          {:status 500
           :headers {"Content-Type" "text/plain"}
           :body "Internal server error"})))))

(defn -main [& args]
  (let [logger (make-app-logger)
        bus    (bus/make-bus {:logger logger})
        router (router/make-router)
        deps   {:bus bus :router router :logger logger}]

    (logger :info {:component ::main, :event :initializing-modules})

    ;; Инициализация модулей
    (name/init! deps)
    (hello/init! deps)

    ;; Компиляция роутера с глобальным middleware
    ;; wrap-params применяется ПОВЕРХ роутера — это правильный уровень для парсинга параметров
    (let [global-middleware (make-global-middleware logger)
          router-handler (router/as-ring-handler router {:middleware [global-middleware]})
          app-handler (wrap-params router-handler)
          port    3005
          server  (http-kit/run-server app-handler {:port port})]

      (logger :info {:component ::main, :event :server-started, :port port})
      (println (str "Server running on http://localhost:" port))

      ;; Регистрация shutdown hook для корректного завершения
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread.
                         #(do
                            (logger :info {:component ::main, :event :shutdown-started})
                            (.close ^java.io.Closeable bus)  ; закрытие event-bus
                            (server)                         ; остановка http-kit сервера
                            (logger :info {:component ::main, :event :shutdown-complete})))))))
