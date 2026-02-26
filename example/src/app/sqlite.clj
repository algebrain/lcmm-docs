(ns app.sqlite
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.string :as str])
  (:import [java.time Instant]))

(defn make-store [db-path]
  {:db-spec {:classname "org.sqlite.JDBC"
             :subprotocol "sqlite"
             :subname db-path}})

(defn close-store! [_store]
  nil)

(defn- execute! [store sql-params]
  (jdbc/execute! (:db-spec store) sql-params))

(defn init-schema! [store]
  (execute! store ["create table if not exists bookings (
                    id text primary key,
                    slot text not null unique,
                    customer_name text not null,
                    created_at text not null
                  )"])
  (execute! store ["create table if not exists notifications (
                    id integer primary key autoincrement,
                    booking_id text not null,
                    message text not null,
                    created_at text not null
                  )"])
  (execute! store ["create table if not exists audit_log (
                    id integer primary key autoincrement,
                    event_type text not null,
                    booking_id text,
                    details text not null,
                    created_at text not null
                  )"]))

(defn create-booking! [store {:keys [booking-id slot customer-name]}]
  (try
    (execute! store ["insert into bookings (id, slot, customer_name, created_at) values (?, ?, ?, ?)"
                     booking-id slot customer-name (str (Instant/now))])
    {:ok? true
     :booking {:id booking-id :slot slot :customer-name customer-name}}
    (catch Throwable ex
      (if (str/includes? (str ex) "UNIQUE constraint failed: bookings.slot")
        {:ok? false :reason :slot-taken}
        (throw ex)))))

(defn get-booking [store booking-id]
  (first (jdbc/query (:db-spec store)
                     ["select id, slot, customer_name, created_at from bookings where id = ?" booking-id])))

(defn list-bookings [store]
  (jdbc/query (:db-spec store)
              ["select id, slot, customer_name, created_at from bookings order by created_at asc"]))

(defn add-notification! [store {:keys [booking-id message]}]
  (execute! store ["insert into notifications (booking_id, message, created_at) values (?, ?, ?)"
                   booking-id message (str (Instant/now))]))

(defn list-notifications [store]
  (jdbc/query (:db-spec store)
              ["select id, booking_id, message, created_at from notifications order by id asc"]))

(defn add-audit! [store {:keys [event-type booking-id details]}]
  (execute! store ["insert into audit_log (event_type, booking_id, details, created_at) values (?, ?, ?, ?)"
                   event-type booking-id details (str (Instant/now))]))

(defn list-audit [store]
  (jdbc/query (:db-spec store)
              ["select id, event_type, booking_id, details, created_at from audit_log order by id asc"]))
