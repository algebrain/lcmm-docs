(ns reference-app.storage
  (:require [reference-app.config :as config]))

(defn- ensure-parent-dir! [path]
  (let [f (java.io.File. path)
        parent (.getParentFile f)]
    (when parent
      (.mkdirs parent))))

(defn sqlite-db-resource [path]
  (ensure-parent-dir! path)
  {:backend-type "sqlite"
   :db-spec {:classname "org.sqlite.JDBC"
             :subprotocol "sqlite"
             :subname path}})

(defn accounts-db-resource [app-config]
  (sqlite-db-resource (config/config-value app-config "accounts.db_path" "./data/accounts.db")))

(defn catalog-db-resource [app-config]
  (sqlite-db-resource (config/config-value app-config "catalog.db_path" "./data/catalog.db")))

(defn booking-db-resource [app-config]
  (sqlite-db-resource (config/config-value app-config "booking.db_path" "./data/booking.db")))

(defn notify-db-resource [app-config]
  (sqlite-db-resource (config/config-value app-config "notify.db_path" "./data/notify.db")))

(defn audit-db-resource [app-config]
  (sqlite-db-resource (config/config-value app-config "audit.db_path" "./data/audit.db")))
