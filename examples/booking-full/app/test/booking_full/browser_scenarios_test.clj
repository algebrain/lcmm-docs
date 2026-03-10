(ns booking-full.browser-scenarios-test
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [booking-full.test-support :as support]))

(defn- audit-event-types [records]
  (set (map :event_type records)))

(deftest browser-address-bar-scenarios-test
  (testing "booking-full passes the documented browser scenarios through Ring handler"
    (let [{:keys [handler test-db-resources]} (support/make-test-system nil :reset)]
      (try
        (testing "app-level endpoints answer on a clean start"
          (let [health-response (handler (support/req "/healthz"))
                ready-response (handler (support/req "/readyz"))
                metrics-response (handler (support/req "/metrics"))
                health-body (support/response-body health-response)
                ready-body (support/response-body ready-response)]
            (is (= 200 (:status health-response)))
            (is (= "ok" (:status health-body)))
            (is (string? (:correlation-id health-body)))
            (is (string? (:request-id health-body)))
            (is (= 200 (:status ready-response)))
            (is (= "ok" (:status ready-body)))
            (is (= [{:name :read-providers
                     :critical? true
                     :ok? true}]
                   (:checks ready-body)))
            (is (= 200 (:status metrics-response)))
            (is (str/includes? (support/response-text metrics-response)
                               "http_server_requests_total"))))

        (testing "accounts endpoints expose canonical seeded users"
          (let [me-response (handler (support/req "/accounts/me" {"user-id" "u-alice"}))
                admin-response (handler (support/req "/accounts/users/u-admin"))
                missing-response (handler (support/req "/accounts/users/missing-user"))
                me-body (support/response-body me-response)
                admin-body (support/response-body admin-response)]
            (is (= 200 (:status me-response)))
            (is (= "u-alice" (:id me-body)))
            (is (= "alice" (:login me-body)))
            (is (not (contains? me-body :password)))
            (is (= 200 (:status admin-response)))
            (is (= "u-admin" (:id admin-body)))
            (is (= "admin" (:login admin-body)))
            (is (= 404 (:status missing-response)))
            (is (= "user not found" (support/response-text missing-response)))))

        (testing "catalog endpoints expose canonical seeded slots"
          (let [all-slots-response (handler (support/req "/catalog/slots"))
                open-slots-response (handler (support/req "/catalog/slots" {"status" "open"}))
                slot-response (handler (support/req "/catalog/slots/slot-09-00"))
                missing-slot-response (handler (support/req "/catalog/slots/missing-slot"))
                all-slots (support/response-body all-slots-response)
                open-slots (support/response-body open-slots-response)
                slot-ids (set (map :id all-slots))
                open-slot-ids (set (map :id open-slots))
                slot-body (support/response-body slot-response)]
            (is (= 200 (:status all-slots-response)))
            (is (every? slot-ids ["slot-09-00" "slot-10-00" "slot-11-00"]))
            (is (= 200 (:status open-slots-response)))
            (is (= 2 (count open-slots)))
            (is (= #{"slot-09-00" "slot-10-00"} open-slot-ids))
            (is (every? #(= "open" (:status %)) open-slots))
            (is (= 200 (:status slot-response)))
            (is (= "slot-09-00" (:id slot-body)))
            (is (= "open" (:status slot-body)))
            (is (= 404 (:status missing-slot-response)))
            (is (= "slot not found" (support/response-text missing-slot-response)))))

        (testing "side-effect stores start empty after reset"
          (let [bookings-response (handler (support/req "/bookings"))
                notifications-response (handler (support/req "/notifications"))
                audit-response (handler (support/req "/audit"))]
            (is (= 200 (:status bookings-response)))
            (is (= [] (support/response-body bookings-response)))
            (is (= 200 (:status notifications-response)))
            (is (= [] (support/response-body notifications-response)))
            (is (= 200 (:status audit-response)))
            (is (= [] (support/response-body audit-response)))))

        (testing "successful booking scenario updates bookings, notifications, and audit"
          (let [create-response (handler (support/req "/bookings/actions/create"
                                                      {"slot-id" "slot-09-00"
                                                       "user-id" "u-alice"}))
                booking (support/response-body create-response)
                booking-id (:id booking)]
            (is (= 200 (:status create-response)))
            (is (string? booking-id))
            (is (= "slot-09-00" (:slot_id booking)))
            (is (= "u-alice" (:user_id booking)))
            (is (= "created" (:status booking)))
            (Thread/sleep 250)
            (let [booking-response (handler (support/req (str "/bookings/" booking-id)))
                  all-bookings-response (handler (support/req "/bookings"))
                  user-bookings-response (handler (support/req "/bookings" {"user-id" "u-alice"}))
                  notifications-response (handler (support/req "/notifications"))
                  audit-response (handler (support/req "/audit"))
                  booking-by-id (support/response-body booking-response)
                  all-bookings (support/response-body all-bookings-response)
                  user-bookings (support/response-body user-bookings-response)
                  notifications (support/response-body notifications-response)
                  audit-records (support/response-body audit-response)
                  notification (first notifications)]
              (is (= 200 (:status booking-response)))
              (is (= booking-id (:id booking-by-id)))
              (is (= "slot-09-00" (:slot_id booking-by-id)))
              (is (= "u-alice" (:user_id booking-by-id)))
              (is (= 200 (:status all-bookings-response)))
              (is (= 1 (count all-bookings)))
              (is (= booking-id (:id (first all-bookings))))
              (is (= 200 (:status user-bookings-response)))
              (is (= 1 (count user-bookings)))
              (is (= booking-id (:id (first user-bookings))))
              (is (= 200 (:status notifications-response)))
              (is (= 1 (count notifications)))
              (is (= booking-id (:booking_id notification)))
              (is (str/includes? (:message notification) "booking-created"))
              (is (= 200 (:status audit-response)))
              (is (<= 3 (count audit-records)))
              (is (set/subset?
                   #{"booking/create-requested"
                     "booking/created"
                     "notify/booking-created"}
                   (audit-event-types audit-records))))))

        (testing "negative booking scenarios return documented statuses"
          (let [missing-slot-id (handler (support/req "/bookings/actions/create" {"user-id" "u-alice"}))
                missing-user-id (handler (support/req "/bookings/actions/create" {"slot-id" "slot-09-00"}))
                unknown-user (handler (support/req "/bookings/actions/create"
                                                   {"slot-id" "slot-09-00"
                                                    "user-id" "missing-user"}))
                unknown-slot (handler (support/req "/bookings/actions/create"
                                                   {"slot-id" "missing-slot"
                                                    "user-id" "u-alice"}))
                closed-slot (handler (support/req "/bookings/actions/create"
                                                  {"slot-id" "slot-11-00"
                                                   "user-id" "u-alice"}))
                missing-booking (handler (support/req "/bookings/missing-booking"))]
            (is (= 400 (:status missing-slot-id)))
            (is (= "slot-id is required" (support/response-text missing-slot-id)))
            (is (= 400 (:status missing-user-id)))
            (is (= "user-id is required" (support/response-text missing-user-id)))
            (is (= 404 (:status unknown-user)))
            (is (= "user not found" (support/response-text unknown-user)))
            (is (= 404 (:status unknown-slot)))
            (is (= "slot not found" (support/response-text unknown-slot)))
            (is (= 409 (:status closed-slot)))
            (is (= "slot not open" (support/response-text closed-slot)))
            (is (= 404 (:status missing-booking)))
            (is (= "booking not found" (support/response-text missing-booking)))))

        (testing "guard scenario matches the documented browser flow"
          (let [success-before-ban (handler (support/req "/auth/demo-login" {"login" "alice"} "203.0.113.10"))
                failed-1 (handler (support/req "/auth/demo-login" {"login" "missing"} "203.0.113.10"))
                failed-2 (handler (support/req "/auth/demo-login" {"login" "missing"} "203.0.113.10"))
                blocked-success (handler (support/req "/auth/demo-login" {"login" "alice"} "203.0.113.10"))
                unban (handler (support/req "/ops/guard/unban" {"ip" "203.0.113.10"} "198.51.100.200"))
                success-after-unban (handler (support/req "/auth/demo-login" {"login" "alice"} "203.0.113.10"))
                success-before-ban-body (support/response-body success-before-ban)
                failed-1-body (support/response-body failed-1)
                unban-body (support/response-body unban)
                success-after-unban-body (support/response-body success-after-unban)]
            (is (= 200 (:status success-before-ban)))
            (is (= true (:ok success-before-ban-body)))
            (is (= "alice" (get-in success-before-ban-body [:user :login])))
            (is (= 401 (:status failed-1)))
            (is (= "invalid_credentials" (:code failed-1-body)))
            (is (= 429 (:status failed-2)))
            (is (= 429 (:status blocked-success)))
            (is (= 200 (:status unban)))
            (is (= true (:ok unban-body)))
            (is (= 200 (:status success-after-unban)))
            (is (= true (:ok success-after-unban-body)))))
        (finally
          (doseq [resource test-db-resources]
            (support/cleanup-temp! resource)))))))
