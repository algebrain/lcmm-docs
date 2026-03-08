(ns reference-app.main
  (:require [clojure.string :as str]
            [org.httpkit.server :as http-kit]
            [reference-app.config :as config]
            [reference-app.system :as system])
  (:gen-class))

(defn- parse-port [value]
  (try
    (Long/parseLong value)
    (catch Throwable _
      nil)))

(defn- usage []
  (str/join
   \newline
   ["Usage: clj -M:run-main -- [--reset|--continue] [--port=<N>]"
    ""
    "Modes:"
    "  --reset      Start from a clean demo state (default)"
    "  --continue   Keep existing local SQLite data"
    ""
    "Options:"
    "  --port=<N>   Override HTTP port"
    "  --help       Show this help"]))

(defn- parse-args [args]
  (reduce (fn [{:keys [startup-mode override-config] :as acc} arg]
            (cond
              (= arg "--")
              acc

              (= arg "--reset")
              (assoc acc :startup-mode :reset)

              (= arg "--continue")
              (assoc acc :startup-mode :continue)

              (str/starts-with? arg "--port=")
              (if-let [port (parse-port (subs arg (count "--port=")))]
                (assoc acc :override-config (assoc override-config "http.port" port))
                (throw (ex-info "Invalid port value"
                                {:reason :invalid-port
                                 :arg arg})))

              (= arg "--help")
              (assoc acc :help? true)

              :else
              (throw (ex-info "Unknown command line argument"
                              {:reason :unknown-arg
                               :arg arg}))))
          {:startup-mode :reset
           :override-config {}
           :help? false}
          args))

(defn -main [& args]
  (let [{:keys [startup-mode override-config help?]} (parse-args args)]
    (if help?
      (println (usage))
      (let [{:keys [config handler]} (system/make-system override-config
                                                          {:startup-mode startup-mode})
            port (long (config/config-value config "http.port" 3006))
            stop-server (http-kit/run-server handler {:port port})
            stop-signal (promise)
            shutdown-hook (Thread.
                           (fn []
                             (try
                               (stop-server :timeout 1000)
                               (finally
                                 (deliver stop-signal :shutdown)))))]
        (.addShutdownHook (Runtime/getRuntime) shutdown-hook)
        (println (str "Reference app running on http://localhost:" port))
        (println (str "Startup mode: " (name startup-mode)))
        (try
          @stop-signal
          (catch InterruptedException _
            (stop-server :timeout 1000)
            (Thread/currentThread))
          (finally
            (try
              (.removeShutdownHook (Runtime/getRuntime) shutdown-hook)
              (catch IllegalStateException _
                nil))))))))
