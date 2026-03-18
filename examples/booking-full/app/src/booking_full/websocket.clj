(ns booking-full.websocket
  (:require [clojure.string :as str]
            [event-bus :as bus]
            [lcmm-guard.core :as guard]
            [lcmm.read-provider-registry :as rpr]
            [lcmm.router :as router]
            [org.httpkit.server :as http-kit]))

(def ^:private max-message-bytes 8192)
(def ^:private max-subscriptions-per-session 32)

(defn make-hub []
  (atom {:sessions {}}))

(defn- now-sec []
  (quot (System/currentTimeMillis) 1000))

(defn- query-param [request key]
  (or (get-in request [:query-params key])
      (get-in request [:query-params (name key)])))

(defn- json-escape [s]
  (-> (str s)
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")
      (str/replace "\r" "\\r")
      (str/replace "\n" "\\n")
      (str/replace "\t" "\\t")))

(defn- json-string [s]
  (str "\"" (json-escape s) "\""))

(defn- user-topic-json [user-id]
  (str "[" (json-string "user") "," (json-string user-id) "]"))

(defn- event-message-json [{:keys [event topic user-id booking-id slot-id correlation-id]}]
  (str "{"
       "\"type\":\"event\","
       "\"event\":" (json-string event) ","
       "\"topic\":" topic ","
       "\"payload\":{"
       "\"bookingId\":" (json-string booking-id) ","
       "\"userId\":" (json-string user-id) ","
       "\"slotId\":" (json-string slot-id)
       "},"
       "\"correlationId\":" (json-string (or correlation-id ""))
       "}"))

(defn- subscribed-json [user-id]
  (str "{"
       "\"type\":\"subscribed\","
       "\"topic\":" (user-topic-json user-id)
       "}"))

(defn- unsubscribed-json [user-id]
  (str "{"
       "\"type\":\"unsubscribed\","
       "\"topic\":" (user-topic-json user-id)
       "}"))

(defn- pong-json []
  "{\"type\":\"pong\"}")

(defn- error-json [code message]
  (str "{"
       "\"type\":\"error\","
       "\"code\":" (json-string code) ","
       "\"message\":" (json-string message)
       "}"))

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

(defn- send! [channel text]
  (http-kit/send! channel text false))

(defn- session [hub session-id]
  (get-in @hub [:sessions session-id]))

(defn- register-session! [hub session-id session]
  (swap! hub assoc-in [:sessions session-id] session))

(defn- unregister-session! [hub session-id]
  (swap! hub update :sessions dissoc session-id))

(defn- update-session! [hub session-id f & args]
  (apply swap! hub update-in [:sessions session-id] f args))

(defn- matching-session-ids [hub topic]
  (->> (get-in @hub [:sessions])
       (keep (fn [[session-id {:keys [subscriptions]}]]
               (when (contains? subscriptions topic)
                 session-id)))))

(defn- parse-client-message [raw]
  (when (string? raw)
    (let [trimmed (str/trim raw)]
      (cond
        (re-matches #"\{\s*\"type\"\s*:\s*\"ping\"\s*\}" trimmed)
        {:type :ping}

        :else
        (let [subscribe-match (re-matches #"\{\s*\"type\"\s*:\s*\"subscribe\"\s*,\s*\"topic\"\s*:\s*\[\s*\"user\"\s*,\s*\"([^\"]+)\"\s*\]\s*\}" trimmed)
              unsubscribe-match (re-matches #"\{\s*\"type\"\s*:\s*\"unsubscribe\"\s*,\s*\"topic\"\s*:\s*\[\s*\"user\"\s*,\s*\"([^\"]+)\"\s*\]\s*\}" trimmed)]
          (cond
            subscribe-match
            {:type :subscribe
             :topic [:user (second subscribe-match)]}

            unsubscribe-match
            {:type :unsubscribe
             :topic [:user (second unsubscribe-match)]}

            :else
            nil))))))

(defn- subscription-allowed? [session topic]
  (and (= :user (first topic))
       (= (:user-id session) (second topic))))

(defn- maybe-close-after-security! [channel result]
  (when (contains? #{:rate-limited :banned :degraded-block} (:action result))
    (send! channel (error-json "connection_rejected" "Connection rejected"))
    (http-kit/close channel)
    true))

(defn- handle-invalid-message! [guard-instance logger session channel]
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
    (when-not (maybe-close-after-security! channel result)
      (send! channel (error-json "invalid_message" "Invalid message")))))

(defn- handle-forbidden-subscribe! [guard-instance logger session channel]
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
    (when-not (maybe-close-after-security! channel result)
      (send! channel (error-json "subscription_rejected" "Subscription rejected")))))

(defn- handle-subscribe! [hub logger session-id channel topic]
  (let [session (session hub session-id)
        subscriptions (:subscriptions session)]
    (cond
      (not (subscription-allowed? session topic))
      ::rejected

      (>= (count subscriptions) max-subscriptions-per-session)
      ::rejected

      :else
      (do
        (update-session! hub session-id update :subscriptions conj topic)
        (logger :info {:component ::websocket
                       :event :ws/subscribed
                       :session-id session-id
                       :user-id (:user-id session)
                       :topic topic
                       :correlation-id (:correlation-id session)})
        (send! channel (subscribed-json (:user-id session)))
        ::ok))))

(defn- handle-unsubscribe! [hub logger session-id channel topic]
  (let [session (session hub session-id)]
    (when (subscription-allowed? session topic)
      (update-session! hub session-id update :subscriptions disj topic)
      (logger :info {:component ::websocket
                     :event :ws/unsubscribed
                     :session-id session-id
                     :user-id (:user-id session)
                     :topic topic
                     :correlation-id (:correlation-id session)})
      (send! channel (unsubscribed-json (:user-id session))))))

(defn- on-message! [hub guard-instance logger session-id channel raw-message]
  (let [session (session hub session-id)]
    (cond
      (nil? session)
      nil

      (> (alength (.getBytes (str raw-message) "UTF-8")) max-message-bytes)
      (handle-invalid-message! guard-instance logger session channel)

      :else
      (let [message (parse-client-message raw-message)]
        (case (:type message)
          :ping
          (do
            (logger :info {:component ::websocket
                           :event :ws/ping
                           :session-id session-id
                           :user-id (:user-id session)
                           :correlation-id (:correlation-id session)})
            (send! channel (pong-json)))

          :subscribe
          (when (= ::rejected (handle-subscribe! hub logger session-id channel (:topic message)))
            (handle-forbidden-subscribe! guard-instance logger session channel))

          :unsubscribe
          (handle-unsubscribe! hub logger session-id channel (:topic message))

          (handle-invalid-message! guard-instance logger session channel))))))

(defn- ws-demo-page [initial-user-id]
  (str "<!doctype html>\n"
       "<html lang=\"ru\">\n"
       "<head>\n"
       "<meta charset=\"utf-8\">\n"
       "<title>booking-full ws demo</title>\n"
       "<style>body{font-family:monospace;max-width:900px;margin:24px auto;padding:0 16px;}button,select{margin-right:8px;margin-bottom:8px;}#log{white-space:pre-wrap;border:1px solid #999;padding:12px;min-height:240px;}#status{font-weight:bold;}</style>\n"
       "</head>\n"
       "<body>\n"
       "<h1>booking-full ws demo</h1>\n"
       "<p>HTTP-сценарии по-прежнему проверяются из адресной строки. Эта страница нужна только для ручной проверки веб-сокета.</p>\n"
       "<label for=\"user-id\">Пользователь:</label>\n"
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

(defn- handle-ws [hub get-user-by-id guard-instance logger request]
  (cond
    (not (websocket-request? request))
    (text-response 400 "websocket upgrade required")

    (not (same-origin? request))
    (text-response 403 "origin rejected")

    :else
    (if-let [user (authorize-session! get-user-by-id request)]
      (let [session-id (str (java.util.UUID/randomUUID))
            remote-addr (:remote-addr request)
            correlation-id (:lcmm/correlation-id request)
            user-id (:id user)]
        (http-kit/as-channel
         request
         {:on-open (fn [channel]
                     (register-session! hub session-id {:session-id session-id
                                                        :channel channel
                                                        :user-id user-id
                                                        :remote-addr remote-addr
                                                        :correlation-id correlation-id
                                                        :subscriptions #{}
                                                        :connected-at (now-sec)})
                     (logger :info {:component ::websocket
                                    :event :ws/session-opened
                                    :session-id session-id
                                    :user-id user-id
                                    :remote-addr remote-addr
                                    :correlation-id correlation-id}))
          :on-receive (fn [channel raw-message]
                        (on-message! hub guard-instance logger session-id channel raw-message))
          :on-close (fn [_ status]
                      (unregister-session! hub session-id)
                      (logger :info {:component ::websocket
                                     :event :ws/session-closed
                                     :session-id session-id
                                     :user-id user-id
                                     :status status
                                     :remote-addr remote-addr
                                     :correlation-id correlation-id}))}))
      (text-response 401 "websocket auth required"))))

(defn- install-booking-bridge! [hub event-bus logger]
  (bus/subscribe event-bus
                 :booking/created
                 (fn [_ envelope]
                   (let [{:keys [booking-id slot-id user-id]} (:payload envelope)
                         topic [:user user-id]
                         message (event-message-json {:event "booking/created"
                                                      :topic (user-topic-json user-id)
                                                      :user-id user-id
                                                      :booking-id booking-id
                                                      :slot-id slot-id
                                                      :correlation-id (:correlation-id envelope)})]
                     (doseq [session-id (matching-session-ids hub topic)]
                       (when-let [{:keys [channel]} (session hub session-id)]
                         (send! channel message)
                         (logger :info {:component ::websocket
                                        :event :ws/message-sent
                                        :session-id session-id
                                        :user-id user-id
                                        :topic topic
                                        :correlation-id (:correlation-id envelope)})))))
                 {:meta ::booking-created-ws-bridge}))

(defn install-websocket-routes! [router registry event-bus guard-instance logger hub]
  (let [get-user-by-id (rpr/require-provider registry :accounts/get-user-by-id)]
    (install-booking-bridge! hub event-bus logger)
    (router/add-route! router :get "/ws-demo" handle-ws-demo {:name ::ws-demo})
    (router/add-route! router
                       :get "/ws"
                       (partial handle-ws hub get-user-by-id guard-instance logger)
                       {:name ::ws})))
