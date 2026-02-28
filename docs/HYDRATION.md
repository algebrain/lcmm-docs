# Спецификация LCMM Lazy Hydration (HYDRATION)

Версия: `1.0-draft`

## 1. Назначение

Этот документ описывает единый протокол ленивой гидрации данных между модулями LCMM.

Протокол нужен для сценария, когда модулю-потребителю нужен объект из смежного модуля,
но локальная копия отсутствует или устарела.

## 2. Что решает протокол

1. Устраняет зависимость от прямого доступа к БД смежного модуля.
2. Позволяет модулю восстанавливаться после пропущенных событий.
3. Снижает риск бесконечных повторов при несуществующих ID.
4. Делает поведение consumer/source предсказуемым для человека и ИИ-агента.

## 3. Термины

- `Source of truth`: модуль-владелец сущности.
- `Consumer`: модуль, которому нужна сущность для своей логики.
- `Hydration`: получение данных сущности по `entity_id` через событийный обмен.
- `Negative cache`: локальная отметка "сущности нет" с TTL.

## 4. Когда применять hydration

Hydration ОБЯЗАТЕЛЬНО применяется, если одновременно выполняются условия:
1. модуль не владеет сущностью;
2. локальная копия отсутствует или явно непригодна;
3. операция требует подтверждения существования сущности или ее атрибутов.

Hydration НЕ нужен, если:
- модуль владеет сущностью сам;
- операция не зависит от данных этой сущности;
- уже есть валидная локальная копия.

## 5. Уровни гидрации

1. `existence`
- Проверяется только факт существования/валидности `entity_id`.

2. `partial`
- Получается ограниченный набор полей (`fields`).

3. `full`
- Получается полный профиль сущности, который source готов отдавать по контракту.

## 6. Канонические события

В протоколе используются события:
- `entity/unknown`
- `entity/updated`
- `entity/not-found`

## 7. Контракты payload

### 7.1 `entity/unknown`

```yaml
event: "entity/unknown"
payload:
  type: object
  required: [entity_type, entity_id, requester_module, hydration_mode]
  properties:
    entity_type: {type: string}
    entity_id: {type: string}
    requester_module: {type: string}
    hydration_mode: {type: string, enum: [existence, partial, full]}
    fields:
      type: array
      items: {type: string}
```

### 7.2 `entity/updated`

```yaml
event: "entity/updated"
payload:
  type: object
  required: [entity_type, entity_id, source_module, data]
  properties:
    entity_type: {type: string}
    entity_id: {type: string}
    source_module: {type: string}
    data: {type: object}
```

### 7.3 `entity/not-found`

```yaml
event: "entity/not-found"
payload:
  type: object
  required: [entity_type, entity_id, source_module, reason]
  properties:
    entity_type: {type: string}
    entity_id: {type: string}
    source_module: {type: string}
    reason: {type: string, enum: [deleted_or_never_existed, inaccessible]}
```

## 8. Алгоритм consumer

1. Проверить локальные данные по `entity_id`.
2. Проверить `negative cache` по `(entity_type, entity_id)`.
3. Если в negative cache есть активная запись -> завершить с ошибкой "not found".
4. Если данных нет:
- опубликовать `entity/unknown`;
- ждать до `wait_timeout_ms`;
- повторно проверить локальные данные.
5. Если пришел `entity/updated` -> сохранить нужные поля и продолжить.
6. Если пришел `entity/not-found` -> записать negative cache и завершить "not found".
7. Если таймаут -> применить `failure_mode`.
8. Опционально: consumer МОЖЕТ запустить deferred hydration (не ждать в текущем запросе) и вернуть ответ "данные еще не готовы".

### 8.1 Механизм ожидания ответа в текущем запросе (waiter bridge)

Чтобы HTTP-запрос мог дождаться результата hydration, consumer обычно использует
локальный реестр ожиданий (waiters) и мост между подписками шины и этим реестром.

Идея:
1. HTTP-обработчик регистрирует waiter по ключу запроса гидрации.
2. Публикует `entity/unknown`.
3. Ждет waiter с таймаутом.
4. Подписчики `entity/updated` / `entity/not-found` находят waiter и завершают его.

Ниже Clojure-псевдокод (концептуальный, не привязан к конкретной реализации):

```clojure
(ns my.consumer.hydration
  (:require [clojure.core.async :as async]))

(defonce waiters* (atom {}))
;; key -> promise-chan

(defn hydration-key [{:keys [entity-type entity-id correlation-id]}]
  [entity-type entity-id correlation-id])

(defn register-waiter! [k]
  (let [ch (async/promise-chan)]
    (swap! waiters* assoc k ch)
    ch))

(defn ensure-correlation-id [opts]
  (update opts :correlation-id #(or % (str (java.util.UUID/randomUUID)))))

(defn complete-waiter! [k result]
  (when-let [ch (get @waiters* k)]
    (async/put! ch result)
    (swap! waiters* dissoc k)))

(defn await-hydration! [bus req opts]
  (let [opts* (ensure-correlation-id opts)
        k (hydration-key {:entity-type (:entity-type req)
                          :entity-id (:entity-id req)
                          :correlation-id (:correlation-id opts*)})
        ch (register-waiter! k)]
    (try
      (bus/publish bus :entity/unknown req opts*)
      (let [[v port] (async/alts!! [ch (async/timeout (:wait-timeout-ms req))])]
        (if (= port ch)
          v
          {:status :timeout}))
      (finally
        ;; cleanup обязателен, чтобы не накапливать waiters
        (swap! waiters* dissoc k)))))

;; bridge from bus -> waiter
(defn on-entity-updated [_bus envelope]
  (let [p (:payload envelope)
        k (hydration-key {:entity-type (:entity-type p)
                          :entity-id (:entity-id p)
                          :correlation-id (:correlation-id envelope)})]
    (complete-waiter! k {:status :updated :data (:data p)})))

(defn on-entity-not-found [_bus envelope]
  (let [p (:payload envelope)
        k (hydration-key {:entity-type (:entity-type p)
                          :entity-id (:entity-id p)
                          :correlation-id (:correlation-id envelope)})]
    (complete-waiter! k {:status :not-found :reason (:reason p)})))
```

Обязательные практики для этого механизма:
1. Cleanup waiter в `finally`.
2. Защита от дублей: первый ответ завершает waiter, остальные игнорируются.
3. Ключ waiter РЕКОМЕНДУЕТСЯ строить с `correlation-id` опубликованного `entity/unknown`, чтобы не смешивать параллельные запросы.
4. Consumer МОЖЕТ выбрать non-blocking режим (deferred hydration): не ждать в текущем запросе и вернуть `202 Accepted` с признаком "данные еще не готовы".

## 9. Алгоритм source of truth

1. Получить `entity/unknown`.
2. Найти сущность в своей БД.
3. Если сущность найдена:
- опубликовать `entity/updated` (с полями по `hydration_mode`).
4. Если сущность не найдена:
- опубликовать `entity/not-found`.
5. Повторный одинаковый запрос ОБЯЗАТЕЛЬНО обрабатывается идемпотентно.

## 10. Anti-storm и negative cache

### 10.1 Дефолты

- `wait_timeout_ms`: `500`
- `negative_cache_ttl_ms`: `300000` (5 минут)
- `in_flight_window_ms`: `5000`
- `max_retries`: `1`
- `retry_backoff_ms`: `200`

### 10.2 Обязательные методики anti-storm

1. `Negative cache` ОБЯЗАТЕЛЬНО: после `entity/not-found` consumer сохраняет запись "не найдено" с TTL (`negative_cache_ttl_ms`).
2. `Request collapse` ОБЯЗАТЕЛЬНО: когда гидрация по `(entity_type, entity_id)` уже началась, повторный `entity/unknown` не отправляется, пока активен in-flight период (`in_flight_window_ms`).

Для простых приложений обе методики ДОПУСКАЕТСЯ реализовывать прямо в БД consumer (таблица negative-cache и таблица/флаг in-flight).

### 10.3 Опциональная методика

1. `Deferred hydration` (опционально): consumer МОЖЕТ не блокировать текущий запрос, а вернуть "данные еще не готовы" и завершить гидрацию асинхронно.

## 11. Стратегии отказа

Поддерживаются:
- `fail-fast` (базовая)
- `defer`
- `degrade`

### 11.1 Рекомендуемая стратегия по умолчанию

`fail-fast`:
- если данных нет после `wait_timeout_ms`, операция завершается временной ошибкой;
- вызывающая сторона решает повтор.

`defer` и `degrade` применяются только если это явно задано контрактом модуля.

## 12. Наблюдаемость

Consumer и source РЕКОМЕНДУЕТСЯ логировать:
- `entity_type`, `entity_id`
- `hydration_mode`
- `requester_module` / `source_module`
- `correlation_id`
- `wait_timeout_ms`
- `negative_cache_hit` (true/false)
- `result` (`updated|not-found|timeout`)

## 13. Обязательные тест-сценарии

1. Успех гидрации: `unknown -> updated`.
2. Отсутствие сущности: `unknown -> not-found -> negative cache`.
3. Таймаут гидрации: `unknown -> timeout -> fail-fast`.
4. Anti-storm: multiple requests по одному ID не порождают шторм `unknown`.
5. Повтор после TTL: negative cache истек, запрос снова отправляется.

## 14. Non-goals

1. Описание внутренней структуры БД consumer/source.
2. Описание конкретного middleware HTTP-слоя.
3. Описание UI/UX-поведения клиента при retry.
