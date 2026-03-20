# WEBSOCKET: app-level websocket в LCMM

Версия: `1.1-draft`

Этот документ фиксирует рабочую модель websocket в LCMM.

Главный принцип:

1. websocket является transport уровня приложения;
2. модуль не использует `lcmm-ws` напрямую;
3. модуль публикует и потребляет события шины;
4. приложение принимает внешние websocket-сообщения и доставляет websocket-push в браузер.

## 1. Когда websocket нужен

Для малого и среднего проекта websocket нужен тогда, когда приложению действительно нужен push в браузер.

Базовый сценарий первой фазы:

1. начальное состояние загружается по HTTP;
2. websocket доставляет новые события;
3. после reconnect клиент при необходимости перечитывает состояние по HTTP.

Это означает:

1. websocket не заменяет HTTP API;
2. экран не должен зависеть только от постоянного сокет-соединения;
3. потеря соединения не должна делать поведение приложения непредсказуемым.

## 2. Что принадлежит приложению

Приложение владеет:

1. websocket endpoint;
2. handshake policy;
3. `Origin` policy;
4. auth и transport-level authz;
5. хранением соединений и подписок;
6. browser-facing формой сообщений;
7. topic routing;
8. доставкой сообщений подписчикам;
9. app-owned ingress event для входящих внешних сообщений.

## 3. Что принадлежит модулю

Модуль владеет:

1. доменными событиями;
2. доменной логикой;
3. доменной интерпретацией входящих фактов;
4. своими HTTP и bus contract-ами;
5. своими read-provider contract-ами.

Модуль не владеет:

1. websocket endpoint;
2. browser-facing topic model;
3. browser-facing message shape;
4. raw websocket input contract.

## 4. Исходящий поток в браузер

Каноническая модель:

1. модуль публикует доменное событие в `event-bus`;
2. app-level websocket слой подписывается на это событие;
3. приложение решает, надо ли доставлять его в браузер;
4. приложение строит browser-facing message;
5. приложение отправляет его через websocket transport.

Пример:

```clojure
(bus/publish bus
             :booking/created
             {:booking-id booking-id
              :slot-id slot-id
              :user-id user-id}
             {:module :booking})
```

Далее приложение само решает, что из этого события уйдет в websocket topic вроде `[:user "u-alice"]`.

## 5. Входящий поток из браузера

Если websocket используется не только для push, но и для входящих сообщений, приложение не должно публиковать доменные события от имени модуля.

Каноническая модель:

1. браузер присылает websocket message;
2. приложение проверяет transport-level правила;
3. приложение нормализует message;
4. приложение публикует единое app-owned ingress event в шину;
5. модуль при необходимости подписывается на это ingress event и сам решает, какова его доменная интерпретация.

Зафиксированное ingress event:

1. `:app/external-message-received`

Пример app-owned payload:

```clojure
{:message-kind :command
 :message-name "booking/create"
 :payload {:slot-id "slot-09-00"}
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

1. это app-owned contract;
2. это не raw websocket frame;
3. это не доменное событие модуля;
4. модуль может читать этот contract, если подписывается на ingress event.

## 6. Минимальный scope первой фазы

Первая фаза включает:

1. `subscribe`;
2. `unsubscribe`;
3. `ping`;
4. server push от приложения к браузеру.

Первая фаза сознательно не включает:

1. произвольный command API по websocket как основной путь системы;
2. durable delivery;
3. offline queue;
4. распределенное хранение соединений.

## 7. Browser-facing contract

Внутреннее модульное событие и browser-facing message не обязаны совпадать один к одному.

Приложение может:

1. убрать лишние поля;
2. перевести имена в удобную внешнюю форму;
3. добавить технические поля вроде `correlationId`.

Приложение не должно:

1. менять доменный смысл события;
2. придумывать новый предметный статус, которого модуль не объявлял.

Минимальные сообщения первой фазы:

```json
{"type":"subscribed","topic":["user","u-alice"]}
{"type":"unsubscribed","topic":["user","u-alice"]}
{"type":"pong"}
{"type":"error","code":"subscription_rejected","message":"Subscription rejected"}
{"type":"event","event":"booking/created","topic":["user","u-alice"],"payload":{"bookingId":"...","userId":"u-alice","slotId":"slot-09-00"},"correlationId":"..."}
```

## 8. Безопасность

Минимальный baseline:

1. auth на handshake уровне приложения;
2. проверка `Origin`;
3. отсутствие секретов в URL/query string;
4. authorization на каждый `subscribe`;
5. единый безопасный отказ без лишних деталей;
6. reconnect проходит ту же проверку заново;
7. raw payload не логируется по умолчанию.

## 9. Что важно не делать

1. не давать модулю зависимость от `lcmm-ws`;
2. не публиковать доменные события модуля от имени `app`;
3. не превращать raw websocket payload в модульный contract;
4. не смешивать transport decisions и доменную интерпретацию.

## 10. Связь с контрактами

Если модуль подписывается на `:app/external-message-received`, relevant app-level bus contract должен быть доступен разработчику модуля.

Это не заменяет contract-ы модулей. Это добавляет app-owned ingress contract как отдельную контрактную поверхность системы.
