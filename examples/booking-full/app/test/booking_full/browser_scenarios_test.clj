(ns booking-full.browser-scenarios-test
  (:require [booking-full.test-support :as support]
            [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org.httpkit.server :as http-kit]))

(defn- start-http-server [handler]
  (let [server (http-kit/run-server handler {:port 0
                                             :legacy-return-value? false})]
    {:server server
     :port (http-kit/server-port server)}))

(defn- stop-http-server! [server]
  (when server
    (http-kit/server-stop! server {:timeout 1000})))

(defn- encode-query [query]
  (when (seq query)
    (str "?"
         (str/join "&"
                   (map (fn [[k v]]
                          (str (java.net.URLEncoder/encode (name k) "UTF-8")
                               "="
                               (java.net.URLEncoder/encode (str v) "UTF-8")))
                        query)))))

(defn- http-get
  ([port path]
   (http-get port path nil))
  ([port path query]
   (let [client (java.net.http.HttpClient/newHttpClient)
         url (str "http://127.0.0.1:" port path (encode-query query))
         request (-> (java.net.http.HttpRequest/newBuilder
                      (java.net.URI/create url))
                     (.GET)
                     (.build))]
     (.send client request
            (java.net.http.HttpResponse$BodyHandlers/ofString)))))

(defn- response-status [response]
  (.statusCode response))

(defn- response-text [response]
  (.body response))

(defn- read-edn-forms [s]
  (let [reader (java.io.PushbackReader. (java.io.StringReader. s))
        eof (Object.)]
    (loop [forms []]
      (let [form (edn/read {:eof eof} reader)]
        (if (identical? eof form)
          forms
          (recur (conj forms form)))))))

(defn- response-body [response]
  (let [forms (read-edn-forms (response-text response))]
    (cond
      (= 1 (count forms))
      (first forms)

      (every? #(and (vector? %) (= 2 (count %))) forms)
      (into {} forms)

      :else
      forms)))

(defn- audit-event-types [records]
  (set (map :event_type records)))

(deftest browser-address-bar-scenarios-test
  (testing "booking-full passes the documented browser scenarios through real HTTP requests"
    (let [{:keys [handler test-db-resources]} (support/make-test-system nil :reset)]
      (try
        (let [{:keys [server port]} (start-http-server handler)]
          (try
            (testing "app-level endpoints answer on a clean start"
              (let [health-response (http-get port "/healthz")
                    ready-response (http-get port "/readyz")
                    metrics-response (http-get port "/metrics")
                    health-body (response-body health-response)
                    ready-body (response-body ready-response)]
                (is (= 200 (response-status health-response)))
                (is (= "ok" (:status health-body)))
                (is (string? (:correlation-id health-body)))
                (is (string? (:request-id health-body)))
                (is (= 200 (response-status ready-response)))
                (is (= "ok" (:status ready-body)))
                (is (= [{:name :read-providers
                         :critical? true
                         :ok? true}]
                       (:checks ready-body)))
                (is (= 200 (response-status metrics-response)))
                (is (str/includes? (response-text metrics-response)
                                   "http_server_requests_total"))))

            (testing "accounts endpoints expose canonical seeded users"
              (let [me-response (http-get port "/accounts/me" {"user-id" "u-alice"})
                    admin-response (http-get port "/accounts/users/u-admin")
                    missing-response (http-get port "/accounts/users/missing-user")
                    me-body (response-body me-response)
                    admin-body (response-body admin-response)]
                (is (= 200 (response-status me-response)))
                (is (= "u-alice" (:id me-body)))
                (is (= "alice" (:login me-body)))
                (is (not (contains? me-body :password)))
                (is (= 200 (response-status admin-response)))
                (is (= "u-admin" (:id admin-body)))
                (is (= "admin" (:login admin-body)))
                (is (= 404 (response-status missing-response)))
                (is (= "user not found" (response-text missing-response)))))

            (testing "catalog endpoints expose canonical seeded slots"
              (let [all-slots-response (http-get port "/catalog/slots")
                    open-slots-response (http-get port "/catalog/slots" {"status" "open"})
                    slot-response (http-get port "/catalog/slots/slot-09-00")
                    missing-slot-response (http-get port "/catalog/slots/missing-slot")
                    all-slots (response-body all-slots-response)
                    open-slots (response-body open-slots-response)
                    slot-ids (set (map :id all-slots))
                    open-slot-ids (set (map :id open-slots))
                    slot-body (response-body slot-response)]
                (is (= 200 (response-status all-slots-response)))
                (is (every? slot-ids ["slot-09-00" "slot-10-00" "slot-11-00"]))
                (is (= 200 (response-status open-slots-response)))
                (is (= 2 (count open-slots)))
                (is (= #{"slot-09-00" "slot-10-00"} open-slot-ids))
                (is (every? #(= "open" (:status %)) open-slots))
                (is (= 200 (response-status slot-response)))
                (is (= "slot-09-00" (:id slot-body)))
                (is (= "open" (:status slot-body)))
                (is (= 404 (response-status missing-slot-response)))
                (is (= "slot not found" (response-text missing-slot-response)))))

            (testing "side-effect stores start empty after reset"
              (let [bookings-response (http-get port "/bookings")
                    notifications-response (http-get port "/notifications")
                    audit-response (http-get port "/audit")]
                (is (= 200 (response-status bookings-response)))
                (is (= [] (response-body bookings-response)))
                (is (= 200 (response-status notifications-response)))
                (is (= [] (response-body notifications-response)))
                (is (= 200 (response-status audit-response)))
                (is (= [] (response-body audit-response)))))

            (testing "successful booking scenario updates bookings, notifications, and audit"
              (let [create-response (http-get port
                                              "/bookings/actions/create"
                                              {"slot-id" "slot-09-00"
                                               "user-id" "u-alice"})
                    booking (response-body create-response)
                    booking-id (:id booking)]
                (is (= 200 (response-status create-response)))
                (is (string? booking-id))
                (is (= "slot-09-00" (:slot_id booking)))
                (is (= "u-alice" (:user_id booking)))
                (is (= "created" (:status booking)))
                (Thread/sleep 250)
                (let [booking-response (http-get port (str "/bookings/" booking-id))
                      all-bookings-response (http-get port "/bookings")
                      user-bookings-response (http-get port "/bookings" {"user-id" "u-alice"})
                      notifications-response (http-get port "/notifications")
                      audit-response (http-get port "/audit")
                      booking-by-id (response-body booking-response)
                      all-bookings (response-body all-bookings-response)
                      user-bookings (response-body user-bookings-response)
                      notifications (response-body notifications-response)
                      audit-records (response-body audit-response)
                      notification (first notifications)]
                  (is (= 200 (response-status booking-response)))
                  (is (= booking-id (:id booking-by-id)))
                  (is (= "slot-09-00" (:slot_id booking-by-id)))
                  (is (= "u-alice" (:user_id booking-by-id)))
                  (is (= 200 (response-status all-bookings-response)))
                  (is (= 1 (count all-bookings)))
                  (is (= booking-id (:id (first all-bookings))))
                  (is (= 200 (response-status user-bookings-response)))
                  (is (= 1 (count user-bookings)))
                  (is (= booking-id (:id (first user-bookings))))
                  (is (= 200 (response-status notifications-response)))
                  (is (= 1 (count notifications)))
                  (is (= booking-id (:booking_id notification)))
                  (is (str/includes? (:message notification) "booking-created"))
                  (is (= 200 (response-status audit-response)))
                  (is (<= 3 (count audit-records)))
                  (is (set/subset?
                       #{"booking/create-requested"
                         "booking/created"
                         "notify/booking-created"}
                       (audit-event-types audit-records))))))

            (testing "negative booking scenarios return documented statuses"
              (let [missing-slot-id (http-get port "/bookings/actions/create" {"user-id" "u-alice"})
                    missing-user-id (http-get port "/bookings/actions/create" {"slot-id" "slot-09-00"})
                    unknown-user (http-get port
                                           "/bookings/actions/create"
                                           {"slot-id" "slot-09-00"
                                            "user-id" "missing-user"})
                    unknown-slot (http-get port
                                           "/bookings/actions/create"
                                           {"slot-id" "missing-slot"
                                            "user-id" "u-alice"})
                    closed-slot (http-get port
                                          "/bookings/actions/create"
                                          {"slot-id" "slot-11-00"
                                           "user-id" "u-alice"})
                    missing-booking (http-get port "/bookings/missing-booking")]
                (is (= 400 (response-status missing-slot-id)))
                (is (= "slot-id is required" (response-text missing-slot-id)))
                (is (= 400 (response-status missing-user-id)))
                (is (= "user-id is required" (response-text missing-user-id)))
                (is (= 404 (response-status unknown-user)))
                (is (= "user not found" (response-text unknown-user)))
                (is (= 404 (response-status unknown-slot)))
                (is (= "slot not found" (response-text unknown-slot)))
                (is (= 409 (response-status closed-slot)))
                (is (= "slot not open" (response-text closed-slot)))
                (is (= 404 (response-status missing-booking)))
                (is (= "booking not found" (response-text missing-booking)))))

            (testing "guard scenario matches the documented browser flow"
              (let [success-before-ban (http-get port "/auth/demo-login" {"login" "alice"})
                    failed-1 (http-get port "/auth/demo-login" {"login" "missing"})
                    failed-2 (http-get port "/auth/demo-login" {"login" "missing"})
                    blocked-success (http-get port "/auth/demo-login" {"login" "alice"})
                    unban (http-get port "/ops/guard/unban" {"ip" "127.0.0.1"})
                    success-after-unban (http-get port "/auth/demo-login" {"login" "alice"})
                    success-before-ban-body (response-body success-before-ban)
                    failed-1-body (response-body failed-1)
                    unban-body (response-body unban)
                    success-after-unban-body (response-body success-after-unban)]
                (is (= 200 (response-status success-before-ban)))
                (is (= true (:ok success-before-ban-body)))
                (is (= "alice" (get-in success-before-ban-body [:user :login])))
                (is (= 401 (response-status failed-1)))
                (is (= "invalid_credentials" (:code failed-1-body)))
                (is (= 429 (response-status failed-2)))
                (is (= 429 (response-status blocked-success)))
                (is (= 200 (response-status unban)))
                (is (= true (:ok unban-body)))
                (is (= 200 (response-status success-after-unban)))
                (is (= true (:ok success-after-unban-body)))))
            (finally
              (stop-http-server! server))))
        (finally
          (doseq [resource test-db-resources]
            (support/cleanup-temp! resource)))))))
