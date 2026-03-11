(ns booking-full.test-support
  (:require [clojure.edn :as edn]
            [booking-full.system :as system]))

(defn temp-db-path [prefix filename]
  (let [tmp-dir (.toFile (java.nio.file.Files/createTempDirectory prefix (make-array java.nio.file.attribute.FileAttribute 0)))]
    {:dir tmp-dir
     :path (str (.getAbsolutePath tmp-dir) java.io.File/separator filename)}))

(defn cleanup-temp! [{:keys [dir]}]
  (when (and dir (.exists ^java.io.File dir))
    (doseq [f (reverse (file-seq dir))]
      (.delete ^java.io.File f))))

(defn make-test-system
  ([] (make-test-system nil :reset))
  ([logger] (make-test-system logger :reset))
  ([logger startup-mode]
   (let [accounts-db (temp-db-path "booking-full-accounts-" "accounts.db")
         catalog-db (temp-db-path "booking-full-catalog-" "catalog.db")
         booking-db (temp-db-path "booking-full-booking-" "booking.db")
         notify-db (temp-db-path "booking-full-notify-" "notify.db")
         audit-db (temp-db-path "booking-full-audit-" "audit.db")
         system-map (system/make-system {"accounts.db_path" (:path accounts-db)
                                         "catalog.db_path" (:path catalog-db)
                                         "booking.db_path" (:path booking-db)
                                         "notify.db_path" (:path notify-db)
                                         "audit.db_path" (:path audit-db)
                                         "guard.rate_limit" 100
                                         "guard.login_max_failures" 2
                                         "guard.login_window_sec" 60
                                         "guard.ban_ttl_sec" 600}
                                        {:logger logger
                                         :startup-mode startup-mode})]
     (assoc system-map
            :test-db-resources [accounts-db catalog-db booking-db notify-db audit-db]))))

(defn make-test-system-on-existing-paths
  [db-resources startup-mode]
  (system/make-system {"accounts.db_path" (:path (nth db-resources 0))
                       "catalog.db_path" (:path (nth db-resources 1))
                       "booking.db_path" (:path (nth db-resources 2))
                       "notify.db_path" (:path (nth db-resources 3))
                       "audit.db_path" (:path (nth db-resources 4))
                       "guard.rate_limit" 100
                       "guard.login_max_failures" 2
                       "guard.login_window_sec" 60
                       "guard.ban_ttl_sec" 600}
                      {:startup-mode startup-mode}))

(defn req
  ([uri] (req uri {} "198.51.100.10"))
  ([uri query] (req uri query "198.51.100.10"))
  ([uri query remote-addr]
   {:request-method :get
    :uri uri
    :query-params query
    :remote-addr remote-addr
    :headers {}}))

(defn response-body [response]
  (edn/read-string (str (:body response))))

(defn response-text [response]
  (str (:body response)))
