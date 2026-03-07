# APP_COMPOSITION: как собирать LCMM-приложение

Версия: `1.0-draft`

Этот документ объясняет, как собрать **app-level composition root** в LCMM.
Он нужен как практический guide для реального приложения, а не как полный reference по всем подсистемам.

Если вам нужны детали отдельных библиотек и API, переходите в профильные документы:

1. [MODULE](./MODULE.md)
2. [DATABASE](./DATABASE.md)
3. [REGISTRY](./REGISTRY.md)
4. [HTTP](./HTTP.md)
5. [OBSERVE_APP](./OBSERVE_APP.md)
6. [SECURE_APP](./SECURE_APP.md)
7. [GUARD](./GUARD.md)
8. [TRACE_FLOW](./TRACE_FLOW.md)

## 1. Что такое app-level composition root

В LCMM приложение (`app`) не реализует бизнес-логику модулей.
Его задача:

1. загрузить конфиг;
2. собрать shared infrastructure;
3. создать storage resources;
4. вызвать `init!` модулей;
5. выполнить startup checks;
6. собрать финальный HTTP handler.

Коротко:

1. **app owns infrastructure**
2. **modules own business logic**

Это главный принцип composition.

Для рабочего примера app-level assembly см. также `reference-app/app/src/reference_app/system.clj`.
Для сквозного walkthrough по correlation и causation см. также [`TRACE_FLOW.md`](./TRACE_FLOW.md).

## 2. Что именно обычно собирает `app`

На практике app-level composition root собирает:

1. `config`
2. `logger`
3. `schema-registry` для event-bus
4. `event-bus`
5. `router`
6. `read-provider registry`
7. `observe registry`
8. `guard`
9. `storage resources`
10. app-level routes (`/healthz`, `/readyz`, `/metrics`, ops/auth handlers)
11. middleware chain

В working `reference-app` это распределено по:

1. `reference-app/app/src/reference_app/config.clj`
2. `reference-app/app/src/reference_app/storage.clj`
3. `reference-app/app/src/reference_app/schema_registry.clj`
4. `reference-app/app/src/reference_app/security.clj`
5. `reference-app/app/src/reference_app/system.clj`

## 3. Канонический порядок сборки приложения

Рекомендуемый baseline order:

1. `load-config`
2. `make-logger`
3. `make-schema-registry`
4. `make-event-bus`
5. `make-router`
6. `make-read-provider-registry`
7. `make-guard` (если используется)
8. `make-observe-registry`
9. `make-storage-resources`
10. `init!` upstream modules
11. `init!` downstream modules
12. `assert-requirements!`
13. register app-level routes
14. `router/as-ring-handler`
15. wrap app-level middleware

Почему именно так:

1. модули должны получать уже созданные shared dependencies;
2. `read-provider registry` должен быть готов до `init!` модулей;
3. `assert-requirements!` нужно делать после инициализации модулей, но до старта HTTP;
4. middleware chain строится уже поверх готового Ring-handler.

## 4. Канонический startup skeleton

Ниже не production template и не copy-paste шаблон, а **опорная схема composition**.
Она показывает shape и порядок сборки, а не навязывает точные имена функций во всех приложениях.

Практические оговорки:

1. `load-config`, `make-logger`, `make-guard`, `make-*-db-resource` здесь placeholders.
2. Если у вас нет `guard` или отдельного `observe`-слоя, эти части просто выпадают из composition.
3. Если нужен полный working пример, ориентируйтесь на `reference-app`, а не на этот skeleton.

```clojure
(ns my.app.system
  (:require [event-bus :as bus]
            [lcmm.http.core :as http]
            [lcmm.observe :as obs]
            [lcmm.observe.http :as observe.http]
            [lcmm.read-provider-registry :as rpr]
            [lcmm.router :as router]))

(defn make-system []
  (let [config (load-config ...)
        logger (make-logger ...)
        schema-registry (make-schema-registry ...)
        event-bus (bus/make-bus
                   :schema-registry schema-registry
                   :logger logger
                   :log-payload :none)
        app-router (router/make-router)
        provider-registry (rpr/make-registry)
        guard-instance (make-guard config)
        observe-registry (obs/make-registry ...)
        accounts-db (make-accounts-db-resource config)
        booking-db (make-booking-db-resource config)]

    (accounts/init! {:bus event-bus
                     :router app-router
                     :logger logger
                     :read-provider-registry provider-registry
                     :config ...
                     :db accounts-db})

    (booking/init! {:bus event-bus
                    :router app-router
                    :logger logger
                    :read-provider-registry provider-registry
                    :observe-registry observe-registry
                    :config ...
                    :db booking-db})

    (rpr/assert-requirements! provider-registry)

    (install-security-routes! app-router provider-registry guard-instance logger)
    (router/add-route! app-router :get "/healthz" (http/health-handler {}) {:name ::healthz})
    (router/add-route! app-router :get "/readyz" (http/ready-handler {:checks [...]}) {:name ::readyz})
    (router/add-route! app-router :get "/metrics" (obs/metrics-handler observe-registry) {:name ::metrics})

    (let [raw-handler (router/as-ring-handler app-router)
          observed (observe.http/wrap-observe-http
                    raw-handler
                    {:registry observe-registry
                     :module :app
                     :route-fn (fn [req]
                                 (or (get-in req [:reitit.core/match :template])
                                     "unknown"))})
          guarded (wrap-guard observed guard-instance logger)
          app-handler (-> guarded
                          (http/wrap-correlation-context {:expose-headers? true})
                          (http/wrap-error-contract {}))]
      {:handler app-handler
       :config config
       :bus event-bus
       :router app-router
       :registry provider-registry})))
```

## 5. Какой shape `deps` давать модулю

Минимальный practical shape:

```clojure
{:bus event-bus
 :router app-router
 :logger logger
 :read-provider-registry provider-registry
 :observe-registry observe-registry
 :config module-config
 :db db-resource}
```

Не все ключи обязательны для каждого модуля.
Но этот shape достаточно хорошо покрывает реальный app composition.

Если нужен app-level контекст того, **кто** собирает этот `deps` map и **в каком порядке**, сначала читайте этот документ, а уже потом профильные API docs.

### 5.1 Что обычно обязательно

Для большинства LCMM-модулей:

1. `:bus`
2. `:router`
3. `:logger`

Если модуль использует provider-based sync-read:

1. `:read-provider-registry`

Если модуль работает с БД:

1. `:config`
2. `:db` или разрешённый self-managed fallback

Если модуль пишет метрики:

1. `:observe-registry`

### 5.2 Что модуль не должен собирать сам по умолчанию

Модуль не должен сам:

1. создавать global `event-bus`
2. создавать global `router`
3. создавать отдельный app-level `observe registry`
4. создавать guard
5. определять app-wide middleware order

Это зона composition root.

## 6. Resource injection и storage mode

LCMM рекомендует разделять:

1. **backend mode selection**
2. **resource injection**

### 6.1 Что выбирает app

Приложение через конфиг и composition root определяет:

1. какой backend использовать;
2. external-managed или self-managed режим;
3. какие конкретные resource instances передать в модули.
4. кто владеет жизненным циклом ресурса.

### 6.2 Что делает модуль

Модуль при старте:

1. читает storage policy из `:config`;
2. проверяет, передан ли внешний backend;
3. если backend передан и поддерживается, использует его;
4. если backend передан и не поддерживается, fail-fast;
5. если backend не передан, может поднять self-managed backend только если это явно разрешено.

Уточнения:

1. `storage.backend` выбирает backend для self-managed режима только когда внешний ресурс не передан через `deps`.
2. При `external-managed` жизненный цикл ресурса принадлежит корню композиции приложения.
3. При `self-managed` жизненный цикл ресурса принадлежит самому модулю.

Это правило делает startup предсказуемым.

Подробнее:
см. [DATABASE](./DATABASE.md).

## 7. Где проходит граница app vs module

### App owns

1. config loading
2. logger construction
3. event-bus creation
4. router creation
5. provider-registry creation
6. observe-registry creation
7. guard creation
8. storage resource creation
9. startup checks
10. middleware chain

### Module owns

1. route registration через переданный router
2. event subscriptions через переданный bus
3. provider registration или requirement declaration
4. свою бизнес-логику
5. свой storage layer поверх переданного resource
6. свои полезные module-level metrics

## 8. Канонический middleware order

Для малого/среднего backend-а рекомендуется такой порядок вызова
от внешнего слоя к внутреннему:

1. `wrap-error-contract`
2. `wrap-correlation-context`
3. app-level security/limits middleware
4. guard middleware
5. observability wrapper
6. business/router handler

### Почему такой порядок

1. error contract должен ловить исключения из внутренних слоёв;
2. correlation/request ids должны быть доступны и guard, и логам, и error responses;
3. guard должен работать до бизнес-логики;
4. observability должна измерять фактическую обработку запросов;
5. router/business handler остаётся внутренним слоем.

### Важная практическая оговорка

LCMM не требует, чтобы каждая система включала все слои сразу.
Можно вводить hardening поэтапно:

1. correlation + error contract + health/ready + metrics
2. затем guard и auth-related flows
3. затем более строгий security baseline

## 9. Где ставить `assert-requirements!`

Каноническое место:

1. после `init!` всех модулей;
2. до старта HTTP;
3. до перевода app в ready state.

Неправильно:

1. вызывать `assert-requirements!` до инициализации модулей;
2. запускать HTTP без проверки обязательных provider-ов;
3. рассчитывать, что missing provider “обнаружится потом”.

Подробнее:
см. [REGISTRY](./REGISTRY.md).

## 10. App-level routes, которые обычно живут в composition root

Типичные app-level endpoints:

1. `/healthz`
2. `/readyz`
3. `/metrics`
4. auth/ops endpoints, если они принадлежат приложению, а не бизнес-модулю

В `reference-app` app-level routes включают:

1. `/healthz`
2. `/readyz`
3. `/metrics`
4. `/auth/demo-login`
5. `/ops/guard/unban`

Это хороший пример того, что не всё HTTP обязано принадлежать отдельному бизнес-модулю.

## 11. Типичные ошибки

### 11.1 Модуль напрямую вызывает другой модуль

Неправильно:

1. `booking` вызывает функцию `notify/send!`

Правильно:

1. sync-read через provider registry;
2. side effects через event-bus.

### 11.2 App забывает `assert-requirements!`

Следствие:

1. приложение стартует в partially wired состоянии.

### 11.3 Модуль игнорирует переданный backend

Следствие:

1. composition root теряет контроль над инфраструктурой.

### 11.4 Модуль создаёт свой global registry/bus/router

Следствие:

1. ломается единая app assembly модель.

### 11.5 Middleware order собирается “по ощущениям”

Следствие:

1. пропадает `correlation-id` в ошибках;
2. guard работает не в том месте;
3. observability измеряет не то, что ожидалось.

## 12. Минимальный startup checklist

Перед запуском приложения проверьте:

1. config загружен;
2. schema registry собран;
3. bus/router/registries созданы;
4. storage resources созданы;
5. все модули успешно инициализированы;
6. `assert-requirements!` прошёл успешно;
7. `healthz`, `readyz`, `/metrics` зарегистрированы;
8. middleware chain собран;
9. только после этого стартует HTTP server.

## 13. Когда этот документ читать, а когда нет

Читайте `APP_COMPOSITION.md`, если:

1. вы собираете новое LCMM-приложение;
2. вы хотите понять app-level ownership;
3. вы проектируете composition root;
4. вы внедряете новый shared слой вроде `observe` или `guard`.

Не используйте этот документ как единственный источник истины, если вам нужен:

1. точный API event-bus;
2. точный API registry;
3. точный API guard;
4. детальная DB strategy.

Для этого переходите в профильные документы.

## 14. Связь с `reference-app`

Рабочий пример app composition можно смотреть в:

1. `reference-app/app/src/reference_app/system.clj`
2. `reference-app/app/src/reference_app/security.clj`
3. `reference-app/app/src/reference_app/storage.clj`
4. `reference-app/app/test/reference_app/integration_test.clj`

Этот документ должен оставаться согласованным с working `reference-app`.
