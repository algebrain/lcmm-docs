# Спецификация LCMM Module Contract (CONTRACT)

Версия спецификации: `1.0-draft`

## 1. Назначение

Этот документ задает правила, по которым пишутся **формальные YAML-контракты модулей LCMM**.

Ключевой сценарий:
- модуль разрабатывается в отдельном репозитории;
- автор модуля (человек или ИИ-агент) не видит код остального монолита;
- автор читает контракты смежных модулей и на их основе проектирует свой модуль и его контракт.

`CONTRACT.md` описывает **формат и семантику контрактов**, а не контракт конкретного модуля.

## 2. Что решает спецификация

1. Убирает неоднородные описания интерфейсов между модулями.
2. Позволяет понимать окружение без доступа к исходному коду.
3. Объединяет HTTP-границы и событийные границы в одном контракте.
4. Формализует поведенческие требования к обработчикам через `x-` поля.

## 3. Нормативные термины

- ОБЯЗАТЕЛЬНО: обязательно.
- РЕКОМЕНДУЕТСЯ: рекомендуется, но допустимы обоснованные исключения.
- ДОПУСТИМО: опционально.

## 4. Внешние спецификации и роль CONTRACT

Эта спецификация не дублирует и не заменяет OpenAPI/AsyncAPI.

Роль этого документа:
- задать LCMM-структуру YAML-контракта модуля;
- задать LCMM-поведенческие аннотации (`x-` поля);
- зафиксировать правила, важные именно для взаимодействия модулей в LCMM.

## 5. Каноническая структура YAML-контракта модуля

Каждый контракт ОБЯЗАТЕЛЬНО содержит следующие разделы верхнего уровня:

```yaml
contract_version: "1.0"
module:
  name: "billing" # это пример значения поля name
  owner: "team-billing" # это пример значения поля owner
  summary: "Краткая роль модуля" # это пример значения поля summary

http:
  # HTTP-контракт модуля (модель OpenAPI)
  operations: []

events:
  # Событийный контракт модуля (модель AsyncAPI)
  publishes: []
  subscribes: []

types:
  # Типы/схемы, используемые в HTTP и events (по OpenAPI/AsyncAPI)
  schemas: {}

behavior:
  defaults: {}

narrative:
  purpose: ""
  scenarios: []
  dependencies: []

compatibility:
  module_contract_version: "0.1.0" # это пример значения версии контракта модуля
  backwards_compatible_with: [] # это пример, поле можно опустить
```

Правило компактности:
- поля с пустыми коллекциями ДОПУСТИМО не указывать;
- в этом случае используется значение по умолчанию (см. раздел 10 и таблицу дефолтов).

## 6. Правила именования

1. Имя события ОБЯЗАТЕЛЬНО задается в формате `domain/action`.
Примеры: `booking/created`, `user/not-found`.

2. Технические имена полей формата контракта ОБЯЗАТЕЛЬНО задаются в `snake_case`.
Пользовательские ключи внутри доменных `payload` спецификация не ограничивает, если иное не оговорено отдельно.

3. Имена операций `operation_id` ОБЯЗАТЕЛЬНО должны быть стабильными и уникальными в пределах контракта.

## 7. Типы данных (v1)

В `v1` спецификация CONTRACT **не вводит собственную систему типов** и не фиксирует локальные ограничения по типам.
Для описания типов в YAML-контрактах используются внешние спецификации:

Справочные источники:
- OpenAPI Specification: https://spec.openapis.org/oas/latest.html
- AsyncAPI Specification: https://www.asyncapi.com/docs/reference/specification/latest

## 8. Контракт HTTP-операций

Этот раздел определяет только LCMM-надстройку над HTTP-описанием.
Семантика HTTP и формат схем берутся из OpenAPI (см. раздел 7).

Для каждой записи в `http.operations` контракт ОБЯЗАТЕЛЬНО фиксирует:
- `operation_id` — стабильный идентификатор операции внутри модуля;
- `method` и `path` — внешний HTTP-интерфейс;
- `summary` — краткое назначение операции;
- `request` и `responses` — структура входа/выхода по OpenAPI-модели;
- LCMM-поведение через `x-` поля (`x-emits`, `x-transactional`, `x-idempotent` и т.д.).

Важно:
- `operation_id` используется как опорный идентификатор для обсуждения контракта между модулями;
- поведение операции в LCMM описывается не только HTTP-блоком, но и `x-` аннотациями.

## 9. Контракт событий

Этот раздел определяет только LCMM-надстройку над событийным описанием.
Семантика сообщений и структура схем берутся из AsyncAPI (см. раздел 7).

### 9.1 Публикуемые события (`events.publishes`)

Для каждого публикуемого события контракт ОБЯЗАТЕЛЬНО фиксирует:
- `event` — имя события в формате `domain/action`;
- `summary` — краткое назначение;
- `payload` — структура сообщения по AsyncAPI-модели;
- `x-owner-module` — модуль-владелец события.

### 9.2 Подписки (`events.subscribes`)

Для каждой подписки контракт ОБЯЗАТЕЛЬНО фиксирует:
- `event` — входящее событие;
- `summary` — зачем подписка нужна;
- `payload` — ожидаемая структура входного сообщения.

Для подписки РЕКОМЕНДУЕТСЯ указывать поведенческие аннотации (`x-idempotent`, `x-timeout-ms`, `x-retry-policy` и т.д.) по уровню зрелости.

## 10. Каталог `x-` полей

Каталог разделен на уровни, чтобы модуль можно было начинать с простого минимума.

Общее правило:
- если `x-` поле не указано, используется его значение по умолчанию из таблицы ниже.
- если `x-` поле задано в `behavior.defaults` и одновременно в конкретной операции/подписке, значение в операции/подписке имеет приоритет.

### 10.1 Уровни

- Уровень 1: базовые поля (для старта и межмодульной читаемости).
- Уровень 2: рекомендуемые для production-like сценариев.
- Уровень 3: расширенные и опциональные.

### 10.2 Поля, значения по умолчанию и минимальная семантика

| Поле | Где указывать | Тип/значение | По умолчанию | Минимальная семантика |
|---|---|---|---|---|
| `x-idempotent` | HTTP operation, event subscription | `boolean` | `false` | Операция/обработчик допускает безопасный повтор одного и того же запроса/события. |
| `x-idempotency-key` | HTTP operation, event subscription | `string` (путь/выражение) | отсутствует | Откуда берется ключ дедупликации. ОБЯЗАТЕЛЬНО, если `x-idempotent=true`. |
| `x-transactional` | HTTP operation, event subscription | `none` / `local-db` / `transact` | `none` | `none`: без транзакционной обвязки; `local-db`: атомарно в локальной БД модуля; `transact`: использовать механизм `event-bus/transact`. |
| `x-emits` | HTTP operation, event subscription | `array<string>` | `[]` | Какие события публикуются этим обработчиком. Это не дублирование `events.publishes`: `events.publishes` = полный список событий модуля, `x-emits` = подмножество для конкретной операции/подписки. |
| `x-consumes` | module/event section, event subscription | `array<string>` | `[]` | Явное указание, какие входящие события ожидает обработчик/модуль. Если поле опущено у конкретной подписки, ДОПУСТИМО считать его равным `[event]` этой подписки. |
| `x-owner-module` | `events.publishes` item | `string` | отсутствует | Какой модуль является владельцем (source of truth) этого события. |
| `x-timeout-ms` | HTTP operation, event subscription | `integer` | `1000` | Таймаут выполнения обработчика. |
| `x-failure-mode` | HTTP operation, event subscription | `fail-fast` / `defer` / `degrade` | `fail-fast` | Стратегия при недоступности зависимостей/данных. |
| `x-retry-policy` | event subscription | object `{max_retries, backoff_ms, strategy}` | `{max_retries:0, backoff_ms:0, strategy:\"none\"}` | Политика повторов для обработчика событий. |
| `x-consistency` | HTTP operation, event flow | `strong` / `eventual` | `eventual` | Ожидаемая модель согласованности результата. |
| `x-side-effects` | HTTP operation, event subscription | `array<db_write|event_publish|external_io|none>` | `["none"]` | Декларация побочных эффектов. |
| `x-correlation-source` | HTTP operation, event subscription | `incoming-header` / `generated` / `parent-envelope` | `generated` | Как берется correlation-id. |
| `x-audit` | HTTP operation, event subscription | `boolean` | `false` | Нужно ли обязательно писать аудит по данной операции. |
| `x-auth` | HTTP operation | `none` / `api-key` / `jwt` / `internal` | `none` | Требуемый способ аутентификации. |
| `x-rate-limit` | HTTP operation | object `{limit, window_ms}` | отсутствует | Ограничение частоты запросов. |

### 10.3 Дефолты для опускаемых коллекций

Если поле не указано, подразумевается:
- `x-emits: []`
- `x-consumes: []`
- `events.publishes: []`
- `events.subscribes: []`
- `compatibility.backwards_compatible_with: []`
### 10.4 Минимальный стартовый профиль (чтобы не перегружать контракт)

Для первых версий модуля достаточно:
- явно указать `x-owner-module` для публикуемых событий;
- при необходимости идемпотентности указать `x-idempotent=true` и `x-idempotency-key`;
- при публикации событий указывать `x-emits`.

Остальные `x-` поля можно добавлять по мере взросления модуля.

### 10.5 Почему набор считается полным для v1

В сумме эти поля покрывают:
- надежность,
- причинность/наблюдаемость,
- побочные эффекты,
- retry/timeout/failure policy,
- безопасность и эксплуатационные ограничения.

Если в проекте появляется требование, не выражаемое этим каталогом, это входной критерий для `v2` расширения.

## 11. Narrative-блок (обязателен)

`narrative` нужен, потому что схемы и `x-` поля не передают всю бизнес-логику.

`narrative` ОБЯЗАТЕЛЬНО включает:
1. `purpose`: зачем модуль существует.
2. `scenarios`: ключевые сценарии поведения.
3. `dependencies`: какие смежные модули и контракты важны.

## 12. Совместимость и версионирование

1. `contract_version` — версия этой спецификации.
2. `compatibility.module_contract_version` — версия конкретного контракта модуля.
3. Изменения ОБЯЗАТЕЛЬНО классифицируются:
- backward-compatible,
- breaking.

Breaking-change примеры:
- удаление операции или события;
- удаление обязательного поля;
- изменение типа существующего поля на несовместимый.

## 13. Минимальный эталонный пример контракта (сокращенный, фокус на LCMM-части)

```yaml
contract_version: "1.0"
module:
  name: "booking"
  owner: "team-booking"
  summary: "Управление бронированием слотов"

http:
  operations:
    - operation_id: "create_booking"
      method: "GET"
      path: "/booking/create"
      summary: "Создать бронирование"
      x-idempotent: true
      x-idempotency-key: "query.slot + ':' + query.name"
      x-transactional: "local-db"
      x-emits: ["booking/created", "booking/rejected"]
      request:
        # schema по OpenAPI/AsyncAPI; здесь показан только placeholder
        query: {type: object}
      responses:
        "200": {description: "Бронь создана"}
        "409": {description: "Слот занят"}

    - operation_id: "get_booking"
      method: "GET"
      path: "/booking/get"
      summary: "Получить бронирование по id"
      # x-idempotent не указан: по умолчанию false
      x-transactional: "none"
      x-emits: []
      request:
        query: {type: object}
      responses:
        "200": {description: "Найдена"}
        "404": {description: "Не найдена"}

events:
  publishes:
    - event: "booking/created"
      summary: "Бронь успешно создана"
      x-owner-module: "booking"
      payload: {type: object}

    - event: "booking/rejected"
      summary: "Бронь отклонена"
      x-owner-module: "booking"
      payload: {type: object}

  subscribes:
    - event: "notify/booking-created"
      summary: "Подтверждение отправки уведомления"
      x-idempotent: true
      x-idempotency-key: "payload.booking_id"
      x-transactional: "none"
      x-consumes: ["notify/booking-created"]
      payload: {type: object}

types:
  schemas:
    # детали схем определяются по OpenAPI/AsyncAPI
    booking_id: {type: string}

behavior:
  defaults:
    x-timeout-ms: 1000
    x-failure-mode: "fail-fast"

narrative:
  purpose: "Модуль управляет созданием и чтением бронирований"
  scenarios:
    - "Клиент вызывает /booking/create"
    - "Модуль проверяет слот, записывает в БД и публикует booking/created"
    - "Если слот занят, модуль публикует booking/rejected"
  dependencies:
    - "notify module contract"
    - "audit module contract"

compatibility:
  module_contract_version: "0.1.0"
  backwards_compatible_with: []
```

## 14. Чек-лист качества контракта (для человека и ИИ-агента)

1. Все разделы верхнего уровня присутствуют.
2. У каждой HTTP-операции есть `operation_id`, `method`, `path`, `request`, `responses`.
3. Все события имеют `event`, `summary`, `payload`.
4. Для `x-` полей соблюдены обязательные зависимости и корректно применены значения по умолчанию.
5. `narrative` заполнен и отражает реальный сценарий работы модуля.
6. Имена событий соответствуют формату `domain/action`.
7. Breaking-изменения помечены в процессе версионирования.

## 15. Non-goals v1

1. Автогенерация полной реализации модуля из контракта.
2. Формальная верификация бизнес-логики.
3. Полная стандартизация всех возможных операционных политик.
