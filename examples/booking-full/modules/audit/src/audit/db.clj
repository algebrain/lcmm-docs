(ns audit.db
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.string :as str])
  (:import [java.time Instant]))

(defprotocol Store
  (create-audit-record! [this record])
  (list-audit-records [this]))

(defrecord JdbcStore [db-spec]
  Store
  (create-audit-record! [_ {:keys [id event-type correlation-id causation-path details]}]
    (jdbc/insert! db-spec
                  :audit_records
                  {:id id
                   :event_type event-type
                   :correlation_id correlation-id
                   :causation_path causation-path
                   :details details
                   :created_at (str (Instant/now))})
    {:id id
     :event_type event-type
     :correlation_id correlation-id
     :causation_path causation-path
     :details details})
  (list-audit-records [_]
    (jdbc/query db-spec
                ["select id, event_type, correlation_id, causation_path, details, created_at
                  from audit_records
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
                 ["create table if not exists audit_records (
                    id text primary key,
                    event_type text not null,
                    correlation_id text,
                    causation_path text,
                    details text not null,
                    created_at text not null
                  )"]))
