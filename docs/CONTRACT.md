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
5. Фиксирует контрактную границу для provider-based sync-read через `read_providers`.

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

В LCMM у модуля может быть до трех разных межмодульных границ:

1. HTTP
2. events
3. `read_providers`

`read_providers` не являются narrative-дополнением.
Если модуль использует `read-provider registry`, этот блок является такой же
нормальной контрактной поверхностью, как HTTP и события.

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

read_providers:
  # Контракт sync-read провайдеров модуля
  provides: []
  requires: []

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
- `read_providers.provides: []`
- `read_providers.requires: []`
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
8. Для sync-read, если используется `read-provider registry`, заполнен блок
   `read_providers` (минимум `provides` и/или `requires`).

## 15. Non-goals v1

1. Автогенерация полной реализации модуля из контракта.
2. Формальная верификация бизнес-логики.
3. Полная стандартизация всех возможных операционных политик.

## 16. Контракт sync-read: `read_providers`

Блок `read_providers` описывает контрактные зависимости для
`read-provider registry` и используется только для межмодульного чтения без
побочных эффектов.

Этот блок нужно воспринимать не как пояснение "на полях", а как полноценную
контрактную поверхность модуля, наравне с `http` и `events`.

Практическая роль этого блока:

1. `REGISTRY.md` описывает программный интерфейс и порядок инициализации.
2. `read_providers` в контракте фиксирует, что именно потребитель может передать
   на вход и что он вправе ожидать на выходе.
3. `provider_id`, форма входа и форма результата должны буквально совпадать
   между кодом и YAML-контрактом.

Структура:

```yaml
read_providers:
  provides:
    - provider_id: "users/get-user-by-id"
      summary: "Получить пользователя по id"
      input:
        type: map
        required: [user-id]
        properties:
          user-id: {type: string}
      output:
        type: map-or-nil
        description: "Пользователь или nil, если пользователь не найден"
      errors: ["invalid-arg", "unavailable"]
      error_behavior:
        mode: "mixed" # return | throw | mixed
        return_error_shape:
          type: map
          required: [code]
          properties:
            code: {type: keyword}
            message: {type: string}
            retryable?: {type: boolean}
        throw_types: ["clojure.lang.ExceptionInfo"]
  requires:
    - provider_id: "users/get-user-by-id"
      summary: "Проверить пользователя перед выполнением операции"
      input:
        type: map
        required: [user-id]
        properties:
          user-id: {type: string}
      expected_output:
        type: map-or-nil
        description: "Пользователь или nil"
```

### 16.1 Поля `provides`: зачем нужны и как применять

| Поле | Зачем | Когда указывать | Когда не указывать | Допустимые значения (v1) |
|---|---|---|---|---|
| `provider_id` | Стабильный идентификатор провайдера | Всегда | Никогда | `domain/action` (например `users/get-user-by-id`) |
| `summary` | Кратко объясняет смысл провайдера | Всегда | Никогда | Непустая строка |
| `input` | Описывает аргумент вызова провайдера | Всегда | Никогда | Встроенное описание структуры входа |
| `output` | Описывает обычный успешный результат | Всегда | Никогда | Встроенное описание результата; `nil` допустим только если это часть контракта |
| `errors` | Перечисляет ожидаемые коды ошибок | Всегда | Никогда | Массив кодов ошибок (см. 16.3) |
| `error_behavior.mode` | Фиксирует способ сигнализации ошибок | Всегда | Никогда | `return` / `throw` / `mixed` |
| `error_behavior.return_error_shape` | Описывает форму возвращаемой ошибки | Обязательно для `return` и `mixed` | Для `throw` | Встроенное описание error-map |
| `error_behavior.throw_types` | Перечисляет допустимые исключения | Обязательно для `throw` и `mixed` | Для `return` | Массив строк с типами исключений |

Важно:

1. `input` и `output` описывают именно вызов provider-функции, а не HTTP-запрос
   и не payload события.
2. Контракты сообщений из OpenAPI и AsyncAPI для этого блока не применяются.
3. `provider_id` в коде и в YAML должен совпадать буквально, без "почти того же"
   смысла и без переименования на одной стороне.

Канонический шаблон `provides`:

```yaml
read_providers:
  provides:
    - provider_id: "accounts/get-user-by-id"
      summary: "Вернуть пользователя по user-id"
      input:
        type: map
        required: [user-id]
        properties:
          user-id: {type: string}
      output:
        type: map-or-nil
        description: "Пользователь или nil, если пользователь не найден"
      errors: ["invalid-arg", "unavailable", "internal"]
      error_behavior:
        mode: "mixed"
        return_error_shape:
          type: map
          required: [code]
          properties:
            code: {type: keyword}
            message: {type: string}
            retryable?: {type: boolean}
        throw_types: ["clojure.lang.ExceptionInfo"]
```

### 16.2 Допустимые формы результата

При описании `read_providers` важно явно различать не только вход, но и виды
результата.

Обычная практическая картина выглядит так:

1. обычное значение:
   провайдер вернул данные, которые потребитель и ожидал получить;
2. `nil`:
   это допустимый бизнес-результат, например объект не найден;
3. error-map:
   это ожидаемая прикладная ошибка, например `:invalid-arg`;
4. исключение:
   это сбой или отказ, который не должен маскироваться под обычный результат.

Если `nil` допустим, это должно быть прямо отражено в `output` или
`expected_output`.
Если error-map возвращается значением, это должно быть видно из
`error_behavior.return_error_shape`.
Если исключения разрешены контрактом, это должно быть видно из
`error_behavior.throw_types`.

Короткий пример смешанного поведения:

```clojure
;; валидный вызов, данные найдены
{:id "u-alice" :login "alice"}

;; валидный вызов, но запись не найдена
nil

;; ожидаемая прикладная ошибка
{:code :invalid-arg
 :message "user-id must be non-empty string"
 :retryable? false}

;; сбой зависимости
(throw (ex-info "Provider unavailable" {:reason :unavailable}))
```

### 16.3 Коды ошибок provider (`errors`)

Минимально рекомендуется использовать коды:

- `invalid-arg` - вход невалиден, повтор без изменения входа бессмысленен.
- `not-found` - сущность по ключу не найдена, если проект предпочитает код ошибки вместо `nil`.
- `forbidden` - доступ к данным запрещен политикой безопасности.
- `timeout` - источник не успел ответить за ожидаемое время.
- `unavailable` - источник временно недоступен.
- `internal` - внутренняя ошибка provider.

Проект может вводить дополнительные доменные коды, но базовые коды должны
сохранять одинаковую семантику между модулями.

### 16.4 Контракт поведения provider при ошибках и сбоях

В контракте обязательно фиксируется `error_behavior.mode`.
Спецификация допускает все три режима и не навязывает один универсальный подход.

1. `return`:
   провайдер возвращает ошибку значением согласно `return_error_shape`.
2. `throw`:
   провайдер сигнализирует ошибку через исключения из `throw_types`.
3. `mixed`:
   часть ошибок возвращается значением, а часть передается через исключения.

Практическая рекомендация:

1. если ошибка ожидаема и полезна потребителю как часть обычного сценария,
   возвращайте ее значением;
2. если ошибка означает сбой зависимости или потерю обычных гарантий, передавайте
   ее через исключение;
3. если используется `mixed`, из контракта должно быть ясно, какие ошибки
   возвращаются значением, а какие могут быть выброшены.

Короткий пример режима `mixed`:

```clojure
;; ошибка уровня аргументов
{:code :invalid-arg
 :message "user-id must be string"
 :retryable? false}

;; отсутствие данных как нормальный бизнес-результат
nil

;; отказ внешней зависимости
(throw (ex-info "Provider unavailable" {:reason :unavailable}))
```

### 16.5 Правила для `requires`

1. `requires` по смыслу описывают обязательные внешние зависимости.
2. `provider_id` в `requires` обязателен.
3. Если обязательный provider отсутствует, приложение должно завершить
   startup-check ошибкой через `assert-requirements!`.

Канонический шаблон `requires`:

```yaml
read_providers:
  requires:
    - provider_id: "accounts/get-user-by-id"
      summary: "Проверить пользователя перед созданием бронирования"
      input:
        type: map
        required: [user-id]
        properties:
          user-id: {type: string}
      expected_output:
        type: map-or-nil
        description: "Пользователь или nil, если пользователь не найден"
```

Рекомендуется:

1. указывать `summary`, чтобы назначение зависимости было понятно без чтения
   кода;
2. указывать `input`, чтобы потребитель не додумывал форму вызова;
3. указывать `expected_output`, чтобы было ясно, считается ли `nil` допустимым
   результатом;
4. при важной зависимости кратко отражать ожидаемую семантику ошибок в
   `summary`, `narrative.dependencies` или в соседнем пояснении.

### 16.6 Полный опорный пример: владелец и потребитель

Ниже пара примеров уровня `reference-app`.
Они показывают не абстрактную схему, а типичный рабочий случай:
модуль-владелец описывает провайдер, а модуль-потребитель фиксирует ожидания по
тому же `provider_id`.

```yaml
# accounts contract
read_providers:
  provides:
    - provider_id: "accounts/get-user-by-id"
      summary: "Вернуть пользователя по user-id"
      input:
        type: map
        required: [user-id]
        properties:
          user-id: {type: string}
      output:
        type: map-or-nil
        description: "Пользователь или nil"
      errors: ["invalid-arg", "unavailable", "internal"]
      error_behavior:
        mode: "mixed"
        return_error_shape:
          type: map
          required: [code]
          properties:
            code: {type: keyword}
            message: {type: string}
            retryable?: {type: boolean}
        throw_types: ["clojure.lang.ExceptionInfo"]
```

```yaml
# booking contract
read_providers:
  requires:
    - provider_id: "accounts/get-user-by-id"
      summary: "Проверить пользователя перед созданием бронирования"
      input:
        type: map
        required: [user-id]
        properties:
          user-id: {type: string}
      expected_output:
        type: map-or-nil
        description: "Пользователь или nil"
```

Отдельно важно:

1. `accounts/get-user-by-id` в коде модуля-владельца и в контракте потребителя
   должен совпадать буквально;
2. если провайдер начал возвращать новую карту ошибки или перестал допускать `nil`,
   код и контракт должны обновляться вместе;
3. если код уровня приложения вызывает провайдер напрямую, например через
   `:accounts/get-user-by-login`, этот провайдер тоже должен быть описан в
   `provides`, а не оставаться "скрытым" знанием из кода.

## 17. Контракт и доставка в браузер

Если приложение доставляет модульные события в браузер через веб-сокет, это тоже
опирается на контракт модуля.

Практические правила:

1. приложение может переводить модульное событие в более удобную браузерную форму;
2. приложение не должно придумывать новый предметный смысл, которого модуль сам явно не объявлял;
3. если для браузерной доставки нужен новый смысловой сигнал, модуль должен объявить его через событие или иной явный контракт;
4. если приложению приходится выводить новый предметный смысл из внутренних деталей модуля, это признак слабого контракта, а не нормальная роль app-level кода.

Коротко:

контракт должен быть достаточным для независимой разработки модуля и приложения,
в том числе если приложение потом решит доставлять модульные события в браузер.

Соседний документ по app-level transport:
[WEBSOCKET](./WEBSOCKET.md).
