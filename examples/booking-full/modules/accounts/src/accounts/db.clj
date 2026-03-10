(ns accounts.db
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.string :as str])
  (:import [java.time Instant]))

(defprotocol Store
  (get-user-by-id [this user-id])
  (get-user-by-login [this login])
  (list-users [this]))

(defrecord JdbcStore [db-spec]
  Store
  (get-user-by-id [_ user-id]
    (first (jdbc/query db-spec
                       ["select id, login, display_name, role, password, created_at
                         from accounts_users
                         where id = ?"
                        user-id])))
  (get-user-by-login [_ login]
    (first (jdbc/query db-spec
                       ["select id, login, display_name, role, password, created_at
                         from accounts_users
                         where login = ?"
                        login])))
  (list-users [_]
    (jdbc/query db-spec
                ["select id, login, display_name, role, password, created_at
                  from accounts_users
                  order by login asc"])))

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
                 ["create table if not exists accounts_users (
                    id text primary key,
                    login text not null unique,
                    display_name text not null,
                    role text not null,
                    password text not null,
                    created_at text not null
                  )"]))

(defn seed-users! [store users]
  (doseq [{:keys [id login display-name role password]} users]
    (jdbc/insert! (:db-spec store)
                  :accounts_users
                  {:id id
                   :login login
                   :display_name display-name
                   :role role
                   :password password
                   :created_at (str (Instant/now))})))

(defn ensure-seed-users! [store]
  (when (empty? (list-users store))
    (seed-users! store
                 [{:id "u-admin"
                   :login "admin"
                   :display-name "Admin User"
                   :role "admin"
                   :password "admin-pass"}
                  {:id "u-alice"
                   :login "alice"
                   :display-name "Alice Booker"
                   :role "user"
                   :password "alice-pass"}])))
