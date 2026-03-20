(ns booking-full.websocket
  (:require [event-bus :as bus]
            [lcmm-guard.core :as guard]
            [lcmm.read-provider-registry :as rpr]
            [lcmm.router :as router]
            [lcmm-ws.codec :as ws.codec]
            [lcmm-ws.core :as ws.core]
            [lcmm-ws.flow :as ws.flow]
            [lcmm-ws.http-kit :as ws.http-kit]
            [lcmm-ws.limits :as ws.limits]
            [lcmm-ws.protocol :as ws.protocol]
            [lcmm-ws.transport :as ws.transport]))

(def ^:private max-message-bytes 8192)
(def ^:private max-subscriptions-per-session 32)

(defn make-hub []
  (ws.core/make-hub))

(defn make-transport-state []
  (ws.http-kit/make-transport))

(defn- now-sec []
  (quot (System/currentTimeMillis) 1000))

(defn- query-param [request key]
  (or (get-in request [:query-params key])
      (get-in request [:query-params (name key)])))

(defn- html-response [body]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body body})

(defn- text-response [status body]
  {:status status
   :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body body})

(defn- same-origin? [request]
  (let [origin (get-in request [:headers "origin"])
        host (get-in request [:headers "host"])]
    (and (string? origin)
         (string? host)
         (or (= origin (str "http://" host))
             (= origin (str "https://" host))))))

(defn- request-fact [remote-addr correlation-id]
  {:request {:remote-addr remote-addr
             :headers {}}
   :endpoint "/ws"
   :now (now-sec)
   :correlation-id correlation-id})

(defn- evaluate-security-fact! [guard-instance remote-addr correlation-id kind code]
  (guard/evaluate-request! guard-instance
                           (assoc (request-fact remote-addr correlation-id)
                                  :kind kind
                                  :code code)))

(defn- send-text! [transport-state connection-id text]
  (ws.transport/send-text! (:transport transport-state) connection-id text))

(defn- close-connection! [transport-state connection-id]
  (ws.transport/close-connection! (:transport transport-state) connection-id))

(defn- authorize-subscribe [{:keys [session topic]}]
  {:ok? (and (= :user (first topic))
             (= (get-in session [:subject :user-id])
                (second topic)))})

(defn- maybe-close-after-security! [hub transport-state connection-id result]
  (when (contains? #{:rate-limited :banned :degraded-block} (:action result))
    (send-text! transport-state connection-id
                (ws.codec/error-message "connection_rejected" "Connection rejected"))
    (close-connection! transport-state connection-id)
    (ws.core/unregister-session! hub connection-id)
    true))

(defn- handle-invalid-message! [hub transport-state guard-instance logger session]
  (let [result (evaluate-security-fact! guard-instance
                                        (:remote-addr session)
                                        (:correlation-id session)
                                        :validation-failed
                                        :invalid-message)]
    (logger :warn {:component ::websocket
                   :event :ws/invalid-message
                   :session-id (:session-id session)
                   :user-id (:user-id session)
                   :remote-addr (:remote-addr session)
                   :correlation-id (:correlation-id session)})
    (when-not (maybe-close-after-security! hub transport-state (:session-id session) result)
      (send-text! transport-state (:session-id session)
                  (ws.codec/error-message "invalid_message" "Invalid message")))))

(defn- handle-forbidden-subscribe! [hub transport-state guard-instance logger session]
  (let [result (evaluate-security-fact! guard-instance
                                        (:remote-addr session)
                                        (:correlation-id session)
                                        :suspicious
                                        :subscription-rejected)]
    (logger :warn {:component ::websocket
                   :event :ws/subscription-rejected
                   :session-id (:session-id session)
                   :user-id (:user-id session)
                   :remote-addr (:remote-addr session)
                   :correlation-id (:correlation-id session)})
    (when-not (maybe-close-after-security! hub transport-state (:session-id session) result)
      (send-text! transport-state (:session-id session)
                  (ws.codec/error-message "subscription_rejected" "Subscription rejected")))))

(defn- handle-subscribe! [hub transport-state logger session-id topic]
  (let [session (ws.core/get-session hub session-id)
        result (ws.flow/process-subscribe! {:hub hub
                                            :session-id session-id
                                            :topic topic
                                            :authorize-subscribe authorize-subscribe
                                            :max-subscriptions max-subscriptions-per-session})]
    (if (:ok? result)
      (do
        (logger :info {:component ::websocket
                       :event :ws/subscribed
                       :session-id session-id
                       :user-id (:user-id session)
                       :topic topic
                       :correlation-id (:correlation-id session)})
        (send-text! transport-state session-id
                    (ws.codec/subscribed (ws.protocol/internal-topic->wire topic)))
        ::ok)
      ::rejected)))

(defn- handle-unsubscribe! [hub transport-state logger session-id topic]
  (let [session (ws.core/get-session hub session-id)
        result (ws.flow/process-unsubscribe! {:hub hub
                                              :session-id session-id
                                              :topic topic})]
    (when (:ok? result)
      (logger :info {:component ::websocket
                     :event :ws/unsubscribed
                     :session-id session-id
                     :user-id (:user-id session)
                     :topic topic
                     :correlation-id (:correlation-id session)})
      (send-text! transport-state session-id
                  (ws.codec/unsubscribed (ws.protocol/internal-topic->wire topic))))))

(defn- on-message! [hub transport-state guard-instance logger session-id raw-message]
  (let [session (ws.core/get-session hub session-id)]
    (cond
      (nil? session)
      nil

      (ws.limits/message-too-large? max-message-bytes raw-message)
      (handle-invalid-message! hub transport-state guard-instance logger session)

      :else
      (let [message (ws.protocol/parse-client-message raw-message)]
        (case (:type message)
          :ping
          (do
            (logger :info {:component ::websocket
                           :event :ws/ping
                           :session-id session-id
                           :user-id (:user-id session)
                           :correlation-id (:correlation-id session)})
            (send-text! transport-state session-id (ws.codec/pong)))

          :subscribe
          (when (= ::rejected (handle-subscribe! hub transport-state logger session-id (:topic message)))
            (handle-forbidden-subscribe! hub transport-state guard-instance logger session))

          :unsubscribe
          (handle-unsubscribe! hub transport-state logger session-id (:topic message))

          (handle-invalid-message! hub transport-state guard-instance logger session))))))

(defn- booking-created->message [envelope]
  (let [{:keys [booking-id slot-id user-id]} (:payload envelope)]
    {:topic [:user user-id]
     :message {:type "event"
               :event "booking/created"
               :topic ["user" user-id]
               :payload {:bookingId booking-id
                         :userId user-id
                         :slotId slot-id}
               :correlationId (:correlation-id envelope)}}))

(defn- install-booking-push! [hub transport-state event-bus logger]
  (bus/subscribe event-bus
                 :booking/created
                 (fn [_ envelope]
                   (let [{:keys [topic message]} (booking-created->message envelope)
                         results (ws.core/send-to-topic! hub
                                                         (:transport transport-state)
                                                         topic
                                                         (ws.codec/encode message))]
                     (doseq [{:keys [session-id]} results]
                       (logger :info {:component ::websocket
                                      :event :ws/message-sent
                                      :session-id session-id
                                      :topic topic
                                      :correlation-id (:correlation-id envelope)}))))
                 {:meta ::booking-created-ws-push}))

(defn- ws-demo-page [initial-user-id]
  (str "<!doctype html>\n"
       "<html lang=\"en\">\n"
       "<head>\n"
       "<meta charset=\"utf-8\">\n"
       "<title>booking-full ws demo</title>\n"
       "<style>body{font-family:monospace;max-width:900px;margin:24px auto;padding:0 16px;}button,select{margin-right:8px;margin-bottom:8px;}#log{white-space:pre-wrap;border:1px solid #999;padding:12px;min-height:240px;}#status{font-weight:bold;}</style>\n"
       "</head>\n"
       "<body>\n"
       "<h1>booking-full ws demo</h1>\n"
       "<p>HTTP scenarios are still checked from the address bar. This page is only for manual websocket transport verification.</p>\n"
       "<label for=\"user-id\">User:</label>\n"
       "<select id=\"user-id\">\n"
       "<option value=\"u-alice\"" (if (= initial-user-id "u-alice") " selected" "") ">u-alice</option>\n"
       "<option value=\"u-admin\"" (if (= initial-user-id "u-admin") " selected" "") ">u-admin</option>\n"
       "</select>\n"
       "<div>\n"
       "<button id=\"load-snapshot\">Load Snapshot</button>\n"
       "<button id=\"connect\">Connect</button>\n"
       "<button id=\"subscribe\">Subscribe</button>\n"
       "<button id=\"disconnect\">Disconnect</button>\n"
       "<button id=\"create-booking\">Create Booking</button>\n"
       "</div>\n"
       "<p>Status: <span id=\"status\">idle</span></p>\n"
       "<div id=\"log\"></div>\n"
       "<script>\n"
       "let socket = null;\n"
       "let reconnectTimer = null;\n"
       "const statusEl = document.getElementById('status');\n"
       "const logEl = document.getElementById('log');\n"
       "const userEl = document.getElementById('user-id');\n"
       "function log(line){ logEl.textContent += line + '\\n'; }\n"
       "function setStatus(s){ statusEl.textContent = s; }\n"
       "async function loadSnapshot(){ const userId = userEl.value; const res = await fetch(`/bookings?user-id=${encodeURIComponent(userId)}`); const body = await res.text(); log(`[http] /bookings -> ${body}`); }\n"
       "function connect(){ if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) return; const userId = userEl.value; const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:'; socket = new WebSocket(`${protocol}//${location.host}/ws?user-id=${encodeURIComponent(userId)}`); setStatus('connecting'); socket.addEventListener('open', () => { setStatus('open'); log('[ws] open'); }); socket.addEventListener('message', (evt) => { log(`[ws] ${evt.data}`); }); socket.addEventListener('close', async () => { setStatus('closed'); log('[ws] close'); socket = null; await loadSnapshot(); clearTimeout(reconnectTimer); reconnectTimer = setTimeout(connect, 1000); }); socket.addEventListener('error', () => { log('[ws] error'); }); }\n"
       "function send(obj){ if (!socket || socket.readyState !== WebSocket.OPEN) { log('[ws] not open'); return; } socket.send(JSON.stringify(obj)); }\n"
       "window.wsDemo = { connect, loadSnapshot, send, currentSocket: () => socket };\n"
       "document.getElementById('load-snapshot').addEventListener('click', loadSnapshot);\n"
       "document.getElementById('connect').addEventListener('click', connect);\n"
       "document.getElementById('subscribe').addEventListener('click', () => send({ type: 'subscribe', topic: ['user', userEl.value] }));\n"
       "document.getElementById('disconnect').addEventListener('click', () => { clearTimeout(reconnectTimer); if (socket) socket.close(); setStatus('closed'); });\n"
       "document.getElementById('create-booking').addEventListener('click', async () => { const userId = userEl.value; const res = await fetch(`/bookings/actions/create?slot-id=slot-09-00&user-id=${encodeURIComponent(userId)}`); log(`[http] create booking -> ${await res.text()}`); });\n"
       "loadSnapshot();\n"
       "</script>\n"
       "</body>\n"
       "</html>\n"))

(defn- handle-ws-demo [request]
  (html-response (ws-demo-page (or (query-param request :user-id) "u-alice"))))

(defn- websocket-request? [request]
  (true? (:websocket? request)))

(defn- authorize-session! [get-user-by-id request]
  (let [user-id (query-param request :user-id)
        user (when (seq (str user-id))
               (get-user-by-id {:user-id user-id}))]
    (when (and (map? user) (not= :invalid-arg (:code user)))
      user)))

(defn- handle-ws [hub transport-state get-user-by-id guard-instance logger request]
  (cond
    (not (websocket-request? request))
    (text-response 400 "websocket upgrade required")

    (not (same-origin? request))
    (text-response 403 "origin rejected")

    :else
    (if-let [user (authorize-session! get-user-by-id request)]
      (let [remote-addr (:remote-addr request)
            correlation-id (:lcmm/correlation-id request)
            user-id (:id user)]
        (ws.http-kit/as-handler
         request
         {:transport-state transport-state
          :on-open (fn [{:keys [connection-id]}]
                     (ws.core/register-session!
                      hub
                      {:session-id connection-id
                       :connection-id connection-id
                       :user-id user-id
                       :subject {:user-id user-id}
                       :remote-addr remote-addr
                       :correlation-id correlation-id
                       :subscriptions #{}
                       :connected-at (now-sec)})
                     (logger :info {:component ::websocket
                                    :event :ws/session-opened
                                    :session-id connection-id
                                    :user-id user-id
                                    :remote-addr remote-addr
                                    :correlation-id correlation-id}))
          :on-text (fn [{:keys [connection-id text]}]
                     (on-message! hub transport-state guard-instance logger connection-id text))
          :on-close (fn [{:keys [connection-id status]}]
                      (ws.core/unregister-session! hub connection-id)
                      (logger :info {:component ::websocket
                                     :event :ws/session-closed
                                     :session-id connection-id
                                     :user-id user-id
                                     :status status
                                     :remote-addr remote-addr
                                     :correlation-id correlation-id}))}))
      (text-response 401 "websocket auth required"))))

(defn install-websocket-routes! [router read-provider-registry event-bus guard-instance logger hub transport-state]
  (let [get-user-by-id (rpr/require-provider read-provider-registry :accounts/get-user-by-id)]
    (install-booking-push! hub transport-state event-bus logger)
    (router/add-route! router :get "/ws-demo" handle-ws-demo {:name ::ws-demo})
    (router/add-route! router
                       :get "/ws"
                       (partial handle-ws hub transport-state get-user-by-id guard-instance logger)
                       {:name ::ws})))
