(ns app.main
  (:require [app.audit :as audit]
            [app.booking :as booking]
            [app.notify :as notify]
            [app.sqlite :as sqlite]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [configure.core :as cfg]
            [event-bus :as bus]
            [lcmm.router :as router]
            [org.httpkit.server :as http-kit]
            [ring.middleware.params :refer [wrap-params]])
  (:gen-class))

(defn make-app-logger []
  (fn [level data]
    (let [log-data (if (map? data) data {:message data})]
      (case level
        :info  (log/info log-data)
        :warn  (log/warn log-data)
        :error (log/error log-data)
        :debug (log/debug log-data)
        :trace (log/trace log-data)
        (log/info log-data)))))

(defn- nested-config-value [m keys]
  (reduce (fn [acc part]
            (when (map? acc)
              (or (get acc part)
                  (get acc (keyword part)))))
          m
          keys))

(defn- config-value [config dot-key default]
  (let [parts (str/split dot-key #"\.")]
    (or (get config dot-key)
        (get config (keyword dot-key))
        (nested-config-value config parts)
        default)))

(defn- ensure-parent-dir! [path]
  (let [f (java.io.File. path)
        parent (.getParentFile f)]
    (when (some? parent)
      (.mkdirs parent))))

(defn parse-cli-args [args]
  (reduce (fn [acc arg]
            (cond
              (= arg "--")
              acc

              (= arg "--reset")
              (assoc acc :mode :reset)

              (= arg "--continue")
              (assoc acc :mode :continue)

              (str/starts-with? arg "--port=")
              (let [value (subs arg (count "--port="))]
                (try
                  (assoc acc :port (Long/parseLong value))
                  (catch NumberFormatException _
                    (throw (ex-info "Invalid port value"
                                    {:reason :invalid-port
                                     :arg arg})))))

              :else
              (throw (ex-info "Unknown argument"
                              {:reason :unknown-arg
                               :arg arg}))))
          {:mode :reset
           :port nil}
          args))

(defn- sqlite-sidecar-paths [db-path]
  [(str db-path "-journal")
   (str db-path "-wal")
   (str db-path "-shm")])

(defn prepare-db! [db-path mode]
  (ensure-parent-dir! db-path)
  (when (= mode :reset)
    (doseq [path (cons db-path (sqlite-sidecar-paths db-path))]
      (let [file (io/file path)]
        (when (.exists file)
          (.delete file))))))

(defn port-available? [port]
  (with-open [socket (java.net.ServerSocket.)]
    (.setReuseAddress socket false)
    (.bind socket (java.net.InetSocketAddress. "127.0.0.1" (int port)))
    true))

(defn validate-startup! [db-path mode port]
  (try
    (port-available? port)
    (catch java.net.BindException _
      (throw (ex-info "Port is already in use"
                      {:reason :port-in-use
                       :port port}))))
  (prepare-db! db-path mode))

(defn- load-app-config [logger]
  (let [{:keys [config meta]} (cfg/load-config {:module-name "booking"
                                                :config "./resources/booking_config.toml"
                                                :allow-relative? true
                                                :env-only? false
                                                :allowed-keys #{"http.port" "db.path" "app.name"}
                                                :required #{"http.port" "db.path"}
                                                :types {"http.port" :int}
                                                :logger logger})]
    (logger :info {:component ::main
                   :event :config-loaded
                   :source (:source meta)
                   :file (:file meta)
                   :env-keys (:env-keys meta)})
    config))

(defn- make-global-middleware [logger]
  (fn [handler]
    (fn [request]
      (try
        (logger :info {:component ::main
                       :event :http-request
                       :method (:request-method request)
                       :uri (:uri request)})
        (handler request)
        (catch Exception e
          (logger :error {:component ::main
                          :event :http-error
                          :exception e
                          :uri (:uri request)})
          {:status 500
           :headers {"Content-Type" "text/plain; charset=utf-8"}
           :body "Internal server error"})))))

(defn -main [& args]
  (let [{:keys [mode port]} (parse-cli-args args)
        logger (make-app-logger)
        config (load-app-config logger)
        port (long (or port (config-value config "http.port" 3006)))
        db-path (str (config-value config "db.path" "./data/example2.db"))
        _ (validate-startup! db-path mode port)
        schema-registry {:booking/create-requested {"1.0" [:map [:slot :string] [:name :string]]}
                         :booking/created {"1.0" [:map [:booking-id :string] [:slot :string] [:name :string]]}
                         :booking/rejected {"1.0" [:map [:slot :string] [:name :string] [:reason :string]]}
                         :notify/booking-created {"1.0" [:map [:booking-id :string] [:message :string]]}}
        event-bus (bus/make-bus {:logger logger :schema-registry schema-registry})
        app-router (router/make-router)
        db (sqlite/make-store db-path)
        _ (sqlite/init-schema! db)
        deps {:bus event-bus :router app-router :logger logger :db db :config config}]

    (logger :info {:component ::main :event :initializing-modules :startup-mode mode})
    (booking/init! deps)
    (notify/init! deps)
    (audit/init! deps)

    (let [global-middleware (make-global-middleware logger)
          router-handler (router/as-ring-handler app-router {:middleware [global-middleware]})
          app-handler (wrap-params router-handler)
          server (http-kit/run-server app-handler {:port port})]
      (logger :info {:component ::main
                     :event :server-started
                     :port port
                     :db-path db-path
                     :startup-mode mode})
      (println (str "Server running on http://localhost:" port))

      (.addShutdownHook (Runtime/getRuntime)
                        (Thread.
                         #(do
                            (logger :info {:component ::main :event :shutdown-started})
                            (when (instance? java.io.Closeable event-bus)
                              (.close ^java.io.Closeable event-bus))
                            (sqlite/close-store! db)
                            (server)
                            (logger :info {:component ::main :event :shutdown-complete})))))))
