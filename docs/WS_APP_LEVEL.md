# WS_APP_LEVEL: как собирать `lcmm-ws` на уровне приложения

Этот документ фиксирует каноническую app-level модель использования `lcmm-ws`.

## 1. Что остается за приложением

Приложение по-прежнему владеет:

1. websocket endpoint;
2. handshake policy;
3. `Origin` policy;
4. auth;
5. `lcmm-guard` integration;
6. transport limits;
7. browser-facing message contract;
8. входящим app-owned ingress event.

`lcmm-ws` не забирает эти решения в библиотеку.

## 2. Что приложение создает

Канонический набор app-level сущностей:

1. `hub` через `lcmm-ws.core/make-hub`;
2. `transport-state` через `lcmm-ws.http-kit/make-transport`;
3. websocket handler;
4. app-level подписки на `event-bus` для outgoing push;
5. app-level publication path для `:app/external-message-received`, если есть incoming business flow.

Важно:

1. в новой рабочей модели приложение работает напрямую через `hub`, `transport-state` и app-level подписки на `event-bus`;
2. промежуточные websocket-specific registries в канонический путь не входят.

## 3. Канонический outgoing path

На исходящем пути приложение делает так:

1. модуль публикует доменное событие;
2. приложение подписывается на него через `event-bus`;
3. приложение проектирует событие в `topic + message`;
4. приложение вызывает `lcmm-ws.core/send-to-topic!`.

Примерно так:

```clojure
(bus/subscribe bus
               :booking/created
               (fn [_ envelope]
                 (let [topic [:user (get-in envelope [:payload :user-id])]
                       message {:type "event"
                                :event "booking/created"
                                :topic ["user" (get-in envelope [:payload :user-id])]
                                :payload {...}
                                :correlationId (:correlation-id envelope)}]
                   (ws/send-to-topic! hub transport topic (codec/encode message)))))
```

## 4. Канонический transport path

На websocket endpoint приложение делает так:

1. проверяет, можно ли открывать соединение;
2. принимает решение по `Origin`;
3. определяет subject сессии;
4. открывает websocket session через transport adapter;
5. на входящих сообщениях вызывает `lcmm-ws.protocol/parse-client-message`;
6. для `subscribe` вызывает `lcmm-ws.flow/process-subscribe!`;
7. для `unsubscribe` вызывает `lcmm-ws.flow/process-unsubscribe!`;
8. для `ping` отвечает `pong`;
9. само решает, какие ответы отправлять и какие security facts отдавать в `lcmm-guard`.

## 5. Как теперь выглядит authorize-subscribe

`process-subscribe!` вызывается не через module-level registry, а через явный app-level callback `authorize-subscribe`.

Пример:

```clojure
(ws.flow/process-subscribe!
 {:hub hub
  :session-id session-id
  :topic topic
  :authorize-subscribe
  (fn [{:keys [session topic]}]
    {:ok? (and (= :user (first topic))
               (= (get-in session [:subject :user-id])
                  (second topic)))})
  :max-subscriptions 32})
```

Это уменьшает число промежуточных сущностей, которые должен держать в голове разработчик приложения.

## 6. Канонический incoming path

Если websocket используется для входящих внешних сообщений, приложение делает так:

1. принимает raw websocket message;
2. валидирует transport envelope;
3. нормализует message;
4. публикует в `event-bus` app-owned ingress event `:app/external-message-received`;
5. модуль сам подписывается на это ingress event и интерпретирует его по своей бизнес-логике.

Зафиксированный ingress payload:

```clojure
{:message-kind :command
 :message-name "booking/create"
 :payload {...}
 :subject {:user-id "u-alice"}
 :source {:channel :websocket
          :transport :ws
          :session-id "..."
          :remote-addr "..."
          :origin "http://localhost:3006"}
 :correlation-id "..."
 :received-at 1710000000}
```

Важно:

1. приложение не публикует от своего имени доменные события `booking/*`;
2. приложение публикует только app-owned ingress event;
3. доменная интерпретация остается в модуле.

## 7. Что важно не делать

1. не передавать `lcmm-ws` в модульные `deps`;
2. не регистрировать websocket-export из `module/init!`;
3. не добавлять промежуточные websocket-specific registries или adapters в канонический happy path;
4. не смешивать app-owned ingress contract и доменные события модуля.

## 8. Минимальный checklist

Перед запуском websocket-контура проверьте:

1. `hub` создан;
2. `transport-state` создан;
3. handshake policy задана;
4. `Origin` policy задана;
5. auth и `lcmm-guard` integration подключены;
6. есть app-level subscription на нужные доменные события;
7. browser-facing message contract определен;
8. если есть incoming business flow, определен `:app/external-message-received`.
