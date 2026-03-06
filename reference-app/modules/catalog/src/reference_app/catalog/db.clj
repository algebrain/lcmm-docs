(ns reference-app.catalog.db
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.string :as str])
  (:import [java.time Instant]))

(defprotocol Store
  (get-slot-by-id [this slot-id])
  (list-slots [this])
  (list-slots-by-status [this status]))

(defrecord JdbcStore [db-spec]
  Store
  (get-slot-by-id [_ slot-id]
    (first (jdbc/query db-spec
                       ["select id, label, status, starts_at, created_at
                         from catalog_slots
                         where id = ?"
                        slot-id])))
  (list-slots [_]
    (jdbc/query db-spec
                ["select id, label, status, starts_at, created_at
                  from catalog_slots
                  order by starts_at asc, id asc"]))
  (list-slots-by-status [_ status]
    (jdbc/query db-spec
                ["select id, label, status, starts_at, created_at
                  from catalog_slots
                  where status = ?
                  order by starts_at asc, id asc"
                 status])))

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
                 ["create table if not exists catalog_slots (
                    id text primary key,
                    label text not null,
                    status text not null,
                    starts_at text not null,
                    created_at text not null
                  )"]))

(defn seed-slots! [store slots]
  (doseq [{:keys [id label status starts-at]} slots]
    (jdbc/insert! (:db-spec store)
                  :catalog_slots
                  {:id id
                   :label label
                   :status status
                   :starts_at starts-at
                   :created_at (str (Instant/now))})))

(defn ensure-seed-slots! [store]
  (when (empty? (list-slots store))
    (seed-slots! store
                 [{:id "slot-09-00"
                   :label "Consultation 09:00"
                   :status "open"
                   :starts-at "2026-03-06T09:00:00Z"}
                  {:id "slot-10-00"
                   :label "Consultation 10:00"
                   :status "open"
                   :starts-at "2026-03-06T10:00:00Z"}
                  {:id "slot-11-00"
                   :label "Consultation 11:00"
                   :status "closed"
                   :starts-at "2026-03-06T11:00:00Z"}])))
