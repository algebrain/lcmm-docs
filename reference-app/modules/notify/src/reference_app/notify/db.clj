(ns reference-app.notify.db
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.string :as str])
  (:import [java.time Instant]))

(defprotocol Store
  (create-notification! [this notification])
  (list-notifications [this]))

(defrecord JdbcStore [db-spec]
  Store
  (create-notification! [_ {:keys [id booking-id message]}]
    (jdbc/insert! db-spec
                  :notification_records
                  {:id id
                   :booking_id booking-id
                   :message message
                   :created_at (str (Instant/now))})
    {:id id :booking_id booking-id :message message})
  (list-notifications [_]
    (jdbc/query db-spec
                ["select id, booking_id, message, created_at
                  from notification_records
                  order by created_at asc, id asc"])))

(defn make-jdbc-store [{:keys [db-spec datasource]}]
  (let [spec (or db-spec datasource)]
    (when-not spec
      (throw (ex-info "DB spec or datasource is required"
                      {:reason :invalid-db-resource})))
    (->JdbcStore spec)))

(defn make-sqlite-store [{:keys [path]}]
  (when (str/blank? path)
    (throw (ex-info "SQLite path is required"
                    {:reason :invalid-sqlite-config})))
  (make-jdbc-store {:db-spec {:classname "org.sqlite.JDBC"
                              :subprotocol "sqlite"
                              :subname path}}))

(defn init-schema! [store]
  (jdbc/execute! (:db-spec store)
                 ["create table if not exists notification_records (
                    id text primary key,
                    booking_id text not null,
                    message text not null,
                    created_at text not null
                  )"]))
