(ns booking.db
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.string :as str])
  (:import [java.time Instant]))

(defprotocol Store
  (create-booking! [this booking])
  (get-booking-by-id [this booking-id])
  (list-bookings [this])
  (list-bookings-by-user [this user-id]))

(defrecord JdbcStore [db-spec]
  Store
  (create-booking! [_ {:keys [id slot-id user-id status]}]
    (jdbc/insert! db-spec
                  :booking_records
                  {:id id
                   :slot_id slot-id
                   :user_id user-id
                   :status status
                   :created_at (str (Instant/now))})
    {:id id :slot_id slot-id :user_id user-id :status status})
  (get-booking-by-id [_ booking-id]
    (first (jdbc/query db-spec
                       ["select id, slot_id, user_id, status, created_at
                         from booking_records
                         where id = ?"
                        booking-id])))
  (list-bookings [_]
    (jdbc/query db-spec
                ["select id, slot_id, user_id, status, created_at
                  from booking_records
                  order by created_at asc, id asc"]))
  (list-bookings-by-user [_ user-id]
    (jdbc/query db-spec
                ["select id, slot_id, user_id, status, created_at
                  from booking_records
                  where user_id = ?
                  order by created_at asc, id asc"
                 user-id])))

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
                 ["create table if not exists booking_records (
                    id text primary key,
                    slot_id text not null,
                    user_id text not null,
                    status text not null,
                    created_at text not null
                  )"]))
