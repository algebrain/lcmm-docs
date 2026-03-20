# WS_MODULE_LEVEL: что остается за модулем при websocket в LCMM

Этот документ описывает новую module-level позицию после рефакторинга `lcmm-ws`.

## 1. Главная идея

Модуль не работает с `lcmm-ws` напрямую.

Это значит:

1. модуль не получает websocket transport;
2. модуль не получает raw channel;
3. модуль не регистрирует websocket-export через `lcmm-ws`;
4. модуль не владеет browser-facing topic model;
5. модуль не владеет browser-facing message shape.

## 2. Чем модуль владеет

Модуль владеет:

1. доменными событиями;
2. доменной бизнес-логикой;
3. своими HTTP contract-ами;
4. своими bus contract-ами;
5. своими read-provider contract-ами.

Если приложению нужно доставлять модульные события в браузер, приложение делает это на основе доменных событий модуля.

## 3. Что модулю не нужно знать

Модулю не нужно знать:

1. как приложение хранит websocket sessions;
2. как приложение делает transport auth;
3. как приложение кодирует browser-facing message;
4. как приложение выбирает websocket topic;
5. как приложение организует reconnect behavior.

## 4. Что происходит на исходящем пути

Исходящий путь остается таким:

1. модуль публикует доменное событие;
2. приложение подписывается на него;
3. приложение само решает, как и куда его доставить в браузер.

То есть модуль знает только смысл события, а не канал доставки.

## 5. Что происходит на входящем пути

Если websocket используется для входящих внешних сообщений:

1. приложение публикует app-owned ingress event `:app/external-message-received`;
2. модуль при необходимости подписывается на это ingress event;
3. модуль сам решает, относится ли сообщение к нему и какова его доменная интерпретация.

Из этого следует важное правило:

1. relevant app-level bus contract может быть нужен разработчику модуля;
2. но websocket-specific payload не становится прямым контрактом модуля;
3. модуль работает с app-owned ingress envelope, а не с raw websocket message.

## 6. Зафиксированный ingress contract, который может читать модуль

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

Это не доменный контракт модуля. Это app-owned ingress contract.

## 7. Чего модуль делать не должен

1. не требовать `lcmm-ws` в `deps.edn`;
2. не импортировать `lcmm-ws.*` namespaces;
3. не описывать browser-facing topics как часть своего module API;
4. не принимать raw websocket transport contract;
5. не рассчитывать, что приложение будет публиковать доменные события за модуль.

## 8. Практическое правило

Если модулю не хватает данных для корректной интерпретации `:app/external-message-received`, это означает не то, что модулю нужен `lcmm-ws`, а то, что app-owned ingress contract надо расширить или уточнить.

## 9. Что это дает

Такая модель дает:

1. чистую app/module границу;
2. отсутствие websocket-знания в модуле;
3. понятный transport ownership на стороне приложения;
4. возможность показывать разработчику модуля relevant app-level bus contract без протаскивания transport API в модуль.
