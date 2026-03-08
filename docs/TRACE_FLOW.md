# TRACE_FLOW: сквозная трассировка `HTTP -> Bus -> Reactive -> Audit`

Версия: `1.0-draft`

Этот документ показывает короткий практический разбор того, как в LCMM проходит
сквозная трассировка запроса:

1. HTTP-запрос входит в приложение;
2. появляется `correlation-id`;
3. HTTP-обработчик публикует корневое событие в `event-bus`;
4. реактивный обработчик публикует производное событие;
5. аудит и логи читают уже существующий контекст трассировки.

Если нужны точные API отдельных библиотек, переходите в профильные документы:

1. [HTTP](./HTTP.md)
2. [BUS](./BUS.md)
3. [ARCH](./ARCH.md)
4. [LOGGING](./LOGGING.md)
5. [APP_COMPOSITION](./APP_COMPOSITION.md)

Для сквозной трассировки это главный документ.
В `HTTP.md`, `BUS.md`, `LOGGING.md` и `ARCH.md` должны оставаться только короткие
ссылки на него, а не еще один полный пересказ той же истории.

## 1. Зачем нужен этот документ

В LCMM correlation и causation проходят через несколько слоев сразу:

1. HTTP middleware
2. `event-bus`
3. reactive handlers
4. audit
5. logs

По отдельности это описано в нескольких документах.
Задача этого текста - собрать одну короткую практическую цепочку.

Коротко:

1. `correlation-id` связывает весь flow в одну трассу;
2. `causation-path` показывает, какое сообщение породило какое;
3. вместе они делают отладку и audit цепочек предсказуемыми.

## 2. Короткая схема

Сквозной путь выглядит так:

1. HTTP request входит в app;
2. `wrap-correlation-context` принимает или создает `correlation-id`;
3. HTTP handler публикует root event через `http/->bus-publish-opts`;
4. `event-bus` кладет `correlation-id` и `causation-path` в envelope;
5. reactive handler получает envelope;
6. reactive handler публикует derived event с `:parent-envelope`;
7. derived event сохраняет тот же `correlation-id` и расширяет `causation-path`;
8. audit и logs могут показать всю цепочку.

## 3. Шаг 1: HTTP вход и correlation context

App-level точка входа выглядит так:

```clojure
(def app-handler
  (-> raw-handler
      (http/wrap-correlation-context {:expose-headers? true})
      (http/wrap-error-contract {})))
```

Практический смысл:

1. middleware получает входной `correlation-id` из запроса или генерирует новый;
2. добавляет `correlation-id` и `request-id` в request context;
3. может вернуть их в response headers;
4. это app-level точка входа для всей дальнейшей трассировки.

## 4. Шаг 2: HTTP handler публикует root event

HTTP handler не должен руками собирать metadata для шины.
Вместо этого correlation context переносится через `http/->bus-publish-opts`.

Опорный пример:

```clojure
(defn- booking-publish-opts [request]
  (http/->bus-publish-opts request {:module :booking}))

(bus/publish bus-instance
             :booking/create-requested
             {:slot-id slot-id :user-id user-id}
             (booking-publish-opts request))
```

Практический смысл:

1. `http/->bus-publish-opts` переносит correlation context из HTTP request в publish opts;
2. для root event именно HTTP request является источником correlation;
3. `:module` обязателен для causation discipline и cycle control.

## 5. Шаг 3: envelope внутри bus

После `publish` шина работает уже с envelope.

Важные поля envelope:

1. `:message-id`
2. `:correlation-id`
3. `:causation-path`
4. `:event-type`
5. `:module`
6. `:payload`

Опорный shape:

```clojure
{:message-id ...
 :correlation-id "..."
 :causation-path [[:booking :booking/create-requested]]
 :event-type :booking/create-requested
 :module :booking
 :payload {:slot-id "slot-09-00" :user-id "u-alice"}}
```

`correlation-id` связывает все события одного flow.
`causation-path` показывает последовательность пар `[module event-type]`.

## 6. Шаг 4: reactive handler и производное событие

Это ключевой момент всей цепочки.

Reactive handler получает входной envelope.
Если он публикует новое событие, он должен передать `:parent-envelope`.

Опорный пример:

```clojure
(bus/subscribe bus
               :booking/created
               (fn [bus-instance envelope]
                 (bus/publish bus-instance
                              :notify/booking-created
                              {:booking-id booking-id
                               :message message}
                              {:parent-envelope envelope
                               :module :notify})))
```

Практический смысл:

1. reactive handler получает входной envelope;
2. `:parent-envelope` сохраняет тот же `correlation-id`;
3. `causation-path` при этом расширяется;
4. без `:parent-envelope` цепочка становится рваной и плохо трассируемой.

## 7. Шаг 5: audit trail

Audit-модуль не придумывает correlation заново.
Он читает уже готовые поля из envelope.

Опорный пример:

```clojure
(db/create-audit-record! store
  {:event-type (event-type->string event-type)
   :correlation-id (:correlation-id envelope)
   :causation-path (pr-str (:causation-path envelope))
   :details (summarize-envelope envelope)})
```

Практический смысл:

1. audit использует уже существующий trace context;
2. благодаря этому можно увидеть всю цепочку от HTTP до reactive side effects;
3. audit не должен создавать свою отдельную correlation-схему.

## 8. Шаг 6: logging

Logs и audit не заменяют друг друга.

Опорный пример:

```clojure
(logger :info {:component ::booking
               :event :booking-created
               :booking-id booking-id
               :slot-id slot-id
               :user-id user-id})
```

Практические правила:

1. logs должны использовать тот же correlation context, что и остальная система;
2. module events, guard events и audit trail - это разные слои наблюдаемости;
3. модуль не должен строить свою отдельную trace-систему поверх LCMM.

## 9. Полный walkthrough на одном сценарии

Сценарий уровня `reference-app`:

1. браузер вызывает `/bookings/actions/create?slot-id=slot-09-00&user-id=u-alice`;
2. `wrap-correlation-context` принимает или создает `correlation-id`;
3. `booking` публикует `:booking/create-requested`;
4. `booking` публикует `:booking/created`;
5. `notify` получает `:booking/created` и публикует `:notify/booking-created` с `:parent-envelope`;
6. `audit` сохраняет записи по этим событиям;
7. у всех записей один `correlation-id`, а `causation-path` показывает рост цепочки.

## 10. Частые ошибки

1. Забыли `wrap-correlation-context` на app-level handler.
2. Публикуют событие из HTTP handler без `http/->bus-publish-opts`.
3. Reactive handler публикует derived event без `:parent-envelope`.
4. Пытаются логами заменить causation chain.
5. Audit пишет свои correlation values вместо чтения из envelope.

## 11. Что этот документ не заменяет

Этот документ не заменяет профильные docs:

1. за точный HTTP API отвечает [HTTP](./HTTP.md);
2. за точный API `event-bus` отвечает [BUS](./BUS.md);
3. за app-level composition отвечает [APP_COMPOSITION](./APP_COMPOSITION.md);
4. за logging policy отвечает [LOGGING](./LOGGING.md);
5. за архитектурную рамку отвечает [ARCH](./ARCH.md).
