(ns reference-app.integration-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [reference-app.logging :as logging]
            [reference-app.test-support :as support]))

(deftest app-skeleton-serves-health-ready-and-module-routes-test
  (testing "app skeleton initializes accounts and catalog routes with correlation headers"
    (let [{:keys [handler test-db-resources]} (support/make-test-system)]
      (try
        (let [health-response (handler {:request-method :get
                                        :uri "/healthz"
                                        :remote-addr "198.51.100.10"
                                        :headers {}})
              ready-response (handler (support/req "/readyz"))
              accounts-response (handler (support/req "/accounts/me" {"user-id" "u-alice"}))
              catalog-response (handler (support/req "/catalog/slots" {"status" "open"}))
              booking-create-response (handler (support/req "/bookings/actions/create" {"slot-id" "slot-09-00"
                                                                                        "user-id" "u-alice"}))]
          (Thread/sleep 250)
          (let [notifications-response (handler (support/req "/notifications"))
                audit-response (handler (support/req "/audit"))]
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
            (support/cleanup-temp! resource)))))))

(deftest metrics-endpoint-is-available-test
  (testing "metrics endpoint returns prometheus payload"
    (let [{:keys [handler test-db-resources]} (support/make-test-system)]
      (try
        (let [response (handler {:request-method :get
                                 :uri "/metrics"
                                 :remote-addr "198.51.100.10"
                                 :headers {}})]
          (is (= 200 (:status response)))
          (is (str/includes? (str (:body response)) "http_server_requests_total")))
        (finally
          (doseq [resource test-db-resources]
            (support/cleanup-temp! resource)))))))

(deftest guard-demo-login-ban-and-unban-flow-test
  (testing "repeated failed demo-login attempts trigger ban and admin unban restores access"
    (let [{:keys [handler test-db-resources]} (support/make-test-system)]
      (try
        (let [failed-1 (handler (support/req "/auth/demo-login" {"login" "missing"} "203.0.113.10"))
              failed-2 (handler (support/req "/auth/demo-login" {"login" "missing"} "203.0.113.10"))
              banned (handler (support/req "/auth/demo-login" {"login" "alice"} "203.0.113.10"))
              unban (handler (support/req "/ops/guard/unban" {"ip" "203.0.113.10"} "198.51.100.200"))
              success (handler (support/req "/auth/demo-login" {"login" "alice"} "203.0.113.10"))]
          (is (= 401 (:status failed-1)))
          (is (= 429 (:status failed-2)))
          (is (= 429 (:status banned)))
          (is (= 200 (:status unban)))
          (is (= 200 (:status success))))
        (finally
          (doseq [resource test-db-resources]
            (support/cleanup-temp! resource)))))))

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
          {:keys [handler test-db-resources]} (support/make-test-system logger)]
      (try
        (handler (support/req "/auth/demo-login" {"login" "missing"} "203.0.113.10"))
        (handler (support/req "/auth/demo-login" {"login" "missing"} "203.0.113.10"))
        (handler (support/req "/ops/guard/unban" {"ip" "203.0.113.10"} "198.51.100.200"))
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
            (support/cleanup-temp! resource)))))))

(deftest reset-mode-restores-canonical-demo-state-test
  (testing "reset mode recreates canonical startup state on repeated start"
    (let [{:keys [handler test-db-resources]} (support/make-test-system nil :reset)]
      (try
        (let [initial-bookings (handler (support/req "/bookings"))
              initial-notifications (handler (support/req "/notifications"))
              initial-audit (handler (support/req "/audit"))
              create-response (handler (support/req "/bookings/actions/create" {"slot-id" "slot-09-00"
                                                                                "user-id" "u-alice"}))]
          (is (= 200 (:status initial-bookings)))
          (is (= [] (support/response-body initial-bookings)))
          (is (= [] (support/response-body initial-notifications)))
          (is (= [] (support/response-body initial-audit)))
          (is (= 200 (:status create-response)))
          (Thread/sleep 250)
          (let [dirty-bookings (support/response-body (handler (support/req "/bookings")))
                dirty-notifications (support/response-body (handler (support/req "/notifications")))
                dirty-audit (support/response-body (handler (support/req "/audit")))]
            (is (= 1 (count dirty-bookings)))
            (is (= 1 (count dirty-notifications)))
            (is (pos? (count dirty-audit)))))
        (let [{:keys [handler]} (support/make-test-system-on-existing-paths test-db-resources :reset)
              bookings-after-reset (support/response-body (handler (support/req "/bookings")))
              notifications-after-reset (support/response-body (handler (support/req "/notifications")))
              audit-after-reset (support/response-body (handler (support/req "/audit")))
              alice (support/response-body (handler (support/req "/accounts/me" {"user-id" "u-alice"})))
              open-slots (support/response-body (handler (support/req "/catalog/slots" {"status" "open"})))]
          (is (= [] bookings-after-reset))
          (is (= [] notifications-after-reset))
          (is (= [] audit-after-reset))
          (is (= "u-alice" (:id alice)))
          (is (= 2 (count open-slots))))
        (finally
          (doseq [resource test-db-resources]
            (support/cleanup-temp! resource)))))))

(deftest continue-mode-keeps-existing-state-test
  (testing "continue mode preserves local sqlite data between starts"
    (let [{:keys [handler test-db-resources]} (support/make-test-system nil :reset)]
      (try
        (let [create-response (handler (support/req "/bookings/actions/create" {"slot-id" "slot-09-00"
                                                                                "user-id" "u-alice"}))]
          (is (= 200 (:status create-response))))
        (Thread/sleep 250)
        (let [{:keys [handler]} (support/make-test-system-on-existing-paths test-db-resources :continue)
              bookings (support/response-body (handler (support/req "/bookings")))
              notifications (support/response-body (handler (support/req "/notifications")))
              audit (support/response-body (handler (support/req "/audit")))]
          (is (= 1 (count bookings)))
          (is (= 1 (count notifications)))
          (is (pos? (count audit))))
        (finally
          (doseq [resource test-db-resources]
            (support/cleanup-temp! resource)))))))
