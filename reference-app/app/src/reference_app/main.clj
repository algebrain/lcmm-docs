(ns reference-app.main
  (:require [org.httpkit.server :as http-kit]
            [reference-app.config :as config]
            [reference-app.system :as system])
  (:gen-class))

(defn -main []
  (let [{:keys [config handler]} (system/make-system)
        port (long (config/config-value config "http.port" 3006))]
    (println (str "Reference app running on http://localhost:" port))
    (http-kit/run-server handler {:port port})))
