(ns reference-app.integration-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [reference-app.logging :as logging]
            [reference-app.system :as system]))

(defn- temp-db-path [prefix filename]
  (let [tmp-dir (.toFile (java.nio.file.Files/createTempDirectory prefix (make-array java.nio.file.attribute.FileAttribute 0)))]
    {:dir tmp-dir
     :path (str (.getAbsolutePath tmp-dir) java.io.File/separator filename)}))

(defn- cleanup-temp! [{:keys [dir]}]
  (when (and dir (.exists ^java.io.File dir))
    (doseq [f (reverse (file-seq dir))]
      (.delete ^java.io.File f))))

(defn- make-test-system
  ([] (make-test-system nil))
  ([logger]
   (let [accounts-db (temp-db-path "reference-app-accounts-" "accounts.db")
         catalog-db (temp-db-path "reference-app-catalog-" "catalog.db")
         booking-db (temp-db-path "reference-app-booking-" "booking.db")
         notify-db (temp-db-path "reference-app-notify-" "notify.db")
         audit-db (temp-db-path "reference-app-audit-" "audit.db")
         system-map (system/make-system {"accounts.db_path" (:path accounts-db)
                                         "catalog.db_path" (:path catalog-db)
                                         "booking.db_path" (:path booking-db)
                                         "notify.db_path" (:path notify-db)
                                         "audit.db_path" (:path audit-db)
                                         "guard.rate_limit" 100
                                         "guard.login.max_failures" 2
                                         "guard.login.window_sec" 60
                                         "guard.ban_ttl_sec" 600}
                                        {:logger logger})]
     (assoc system-map
            :test-db-resources [accounts-db catalog-db booking-db notify-db audit-db]))))

(defn- req
  ([uri] (req uri {} "198.51.100.10"))
  ([uri query] (req uri query "198.51.100.10"))
  ([uri query remote-addr]
   {:request-method :get
    :uri uri
    :query-params query
    :remote-addr remote-addr
    :headers {}}))

(deftest app-skeleton-serves-health-ready-and-module-routes-test
  (testing "app skeleton initializes accounts and catalog routes with correlation headers"
    (let [{:keys [handler test-db-resources]} (make-test-system)]
      (try
        (let [health-response (handler {:request-method :get
                                        :uri "/healthz"
                                        :remote-addr "198.51.100.10"
                                        :headers {}})
              ready-response (handler (req "/readyz"))
              accounts-response (handler (req "/accounts/me" {"user-id" "u-alice"}))
              catalog-response (handler (req "/catalog/slots" {"status" "open"}))
              booking-create-response (handler (req "/bookings/actions/create" {"slot-id" "slot-09-00"
                                                                                "user-id" "u-alice"}))]
          (Thread/sleep 250)
          (let [notifications-response (handler (req "/notifications"))
                audit-response (handler (req "/audit"))]
            (is (= 200 (:status health-response)))
            (is (= 200 (:status ready-response)))
            (is (= 200 (:status accounts-response)))
            (is (= 200 (:status catalog-response)))
            (is (= 200 (:status booking-create-response)))
            (is (= 200 (:status notifications-response)))
            (is (= 200 (:status audit-response)))
            (is (contains? (:headers health-response) "x-correlation-id"))
            (is (contains? (:headers health-response) "x-request-id"))))
        (finally
          (doseq [resource test-db-resources]
            (cleanup-temp! resource)))))))

(deftest metrics-endpoint-is-available-test
  (testing "metrics endpoint returns prometheus payload"
    (let [{:keys [handler test-db-resources]} (make-test-system)]
      (try
        (let [response (handler {:request-method :get
                                 :uri "/metrics"
                                 :remote-addr "198.51.100.10"
                                 :headers {}})]
          (is (= 200 (:status response)))
          (is (str/includes? (str (:body response)) "http_server_requests_total")))
        (finally
          (doseq [resource test-db-resources]
            (cleanup-temp! resource)))))))

(deftest guard-demo-login-ban-and-unban-flow-test
  (testing "repeated failed demo-login attempts trigger ban and admin unban restores access"
    (let [{:keys [handler test-db-resources]} (make-test-system)]
      (try
        (let [failed-1 (handler (req "/auth/demo-login" {"login" "missing"} "203.0.113.10"))
              failed-2 (handler (req "/auth/demo-login" {"login" "missing"} "203.0.113.10"))
              banned (handler (req "/auth/demo-login" {"login" "alice"} "203.0.113.10"))
              unban (handler (req "/ops/guard/unban" {"ip" "203.0.113.10"} "198.51.100.200"))
              success (handler (req "/auth/demo-login" {"login" "alice"} "203.0.113.10"))]
          (is (= 401 (:status failed-1)))
          (is (= 429 (:status failed-2)))
          (is (= 429 (:status banned)))
          (is (= 200 (:status unban)))
          (is (= 200 (:status success))))
        (finally
          (doseq [resource test-db-resources]
            (cleanup-temp! resource)))))))

(deftest logger-redacts-sensitive-fields-and-normalizes-exceptions-test
  (testing "app logger redacts nested secrets and serializes exceptions"
    (let [entries (atom [])
          logger (logging/make-app-logger {:sink entries
                                           :writer nil})]
      (logger :error {:component ::test
                      :event :sample
                      :authorization "Bearer secret-token"
                      :nested {:password "qwerty"
                               :token "abc"}
                      :exception (ex-info "boom" {:secret "hidden"})})
      (let [entry (first @entries)]
        (is (= :error (:level entry)))
        (is (= "***" (:authorization entry)))
        (is (= "***" (get-in entry [:nested :password])))
        (is (= "***" (get-in entry [:nested :token])))
        (is (= "clojure.lang.ExceptionInfo" (get-in entry [:exception :class])))
        (is (= "boom" (get-in entry [:exception :message])))))))

(deftest guard-flow-produces-structured-security-logs-test
  (testing "failed login and unban flow emit security logs with correlation ids"
    (let [entries (atom [])
          logger (logging/make-app-logger {:sink entries
                                           :writer nil})
          {:keys [handler test-db-resources]} (make-test-system logger)]
      (try
        (handler (req "/auth/demo-login" {"login" "missing"} "203.0.113.10"))
        (handler (req "/auth/demo-login" {"login" "missing"} "203.0.113.10"))
        (handler (req "/ops/guard/unban" {"ip" "203.0.113.10"} "198.51.100.200"))
        (let [guard-events (filter #(= :security/guard-event (:event %)) @entries)
              failed-events (filter #(= :security/demo-login-failed (:event %)) @entries)
              unban-events (filter #(= :security/guard-unban-requested (:event %)) @entries)]
          (is (seq guard-events))
          (is (seq failed-events))
          (is (seq unban-events))
          (is (every? :correlation-id guard-events))
          (is (every? :request-id guard-events))
          (is (every? #(map? (:guard-event %)) guard-events)))
        (finally
          (doseq [resource test-db-resources]
            (cleanup-temp! resource)))))))
