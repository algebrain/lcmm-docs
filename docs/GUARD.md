# GUARD: документация по библиотеке `lcmm-guard`

Версия: `v1 (draft)`
Репозиторий: `https://github.com/algebrain/lcmm-guard`

## 1. Что это за библиотека

`lcmm-guard` — это слой app-level безопасности для малого и среднего HTTP API.
Он решает задачи, которые обычно остаются "между" библиотеками:

1. детект атакующих паттернов по IP в окне времени;
2. временный ban IP по TTL;
3. rate limit per IP;
4. политика `trusted proxy` при извлечении IP;
5. единая деградация `fail-open`/`fail-closed` при сбое backend;
6. структурированные security-события для логов/мониторинга.

Важно: `lcmm-guard` не заменяет ваш HTTP/router слой, auth middleware и validation middleware.
Он принимает уже нормализованные факты и возвращает действие (`:allow`, `:rate-limited`, `:banned`, `:degraded-*`).

## 2. Когда использовать

Используйте `lcmm-guard`, если вам нужен единый security-policy слой поверх Ring/Reitit:

1. хотите ban + rate-limit + detector в одном API;
2. хотите одинаковую fail-policy для всех защитных компонентов;
3. хотите детальные security events в одном формате.

## 3. Быстрый старт (минимальная рабочая интеграция)

Ниже код, после которого guard уже можно использовать в приложении.

### 3.1 Подключение в `deps.edn`

Пока библиотека не опубликована в Clojars, подключайте из GitHub:

```clojure
{:deps
 {algebrain/lcmm-guard
  {:git/url "https://github.com/algebrain/lcmm-guard"
   :git/sha "<PIN_COMMIT_SHA>"}}}
```

Рекомендуется всегда фиксировать конкретный `:git/sha`.

### 3.2 Создание guard-инстанса

```clojure
(ns myapp.security
  (:require [lcmm-guard.backend.in-memory :as backend]
            [lcmm-guard.ban-store :as ban-store]
            [lcmm-guard.core :as guard]
            [lcmm-guard.detector :as detector]
            [lcmm-guard.rate-limiter :as rate-limiter]))

(defn make-guard []
  (let [counter-store (backend/make-counter-store)
        ttl-store (backend/make-ttl-store)]
    (guard/make-guard
     {:ip-config {:trust-xff? true
                  :trusted-proxies #{"10.0.0.1" "10.0.0.2"}}
      :ban-store (ban-store/make-ban-store
                  {:ttl-store ttl-store
                   :allow-list #{"127.0.0.1"}
                   :default-ban-ttl-sec 900})
      :rate-limiter (rate-limiter/make-rate-limiter
                     {:counter-store counter-store
                      :limit 60
                      :window-sec 60})
      :detector (detector/make-detector
                 {:counter-store counter-store
                  :thresholds {:validation-failed 20
                               :auth-failed 20
                               :suspicious 20}
                  :window-sec 300
                  :bucket-sec 10})
      :mode-policy {:mode :fail-open}})))
```

### 3.3 Применение результата guard к HTTP-ответу

```clojure
(ns myapp.security.middleware
  (:require [lcmm-guard.core :as guard]))

(defn guard-action->response
  [{:keys [action ip events]}]
  (case action
    :allow nil
    :rate-limited {:status 429
                   :body {:code "rate_limited"
                          :message "Too many requests"
                          :ip ip}}
    :banned {:status 429
             :body {:code "ip_banned"
                    :message "IP temporarily banned"
                    :ip ip}}
    :degraded-allow nil
    :degraded-block {:status 503
                     :body {:code "guard_unavailable"
                            :message "Security guard unavailable"}}
    {:status 500
     :body {:code "unknown_guard_action"
            :action (str action)}}))

(defn wrap-guard
  [handler guard-instance]
  (fn [req]
    (let [result (guard/evaluate-request! guard-instance
                                          {:request req
                                           :now (quot (System/currentTimeMillis) 1000)
                                           :correlation-id (get-in req [:headers "x-correlation-id"])})
          short-circuit (guard-action->response result)]
      ;; В реальном приложении отправьте result/events в ваш logger.
      (if short-circuit
        short-circuit
        (handler req)))))
```

На этом этапе у вас уже есть:
1. извлечение IP с trusted proxy политикой;
2. проверка active ban;
3. rate limit;
4. единая fail-policy на сбои storage;
5. security events в `result :events`.

## 4. Интеграция с фактами безопасности (validation/auth/suspicious)

`evaluate-request!` может принимать `:kind`.
Это нужно, чтобы детектор видел ошибки валидации/авторизации и подозрительные повторы.

Поддерживаемые виды (по текущему API и дефолтным threshold):
1. `:validation-failed`
2. `:auth-failed`
3. `:suspicious`

Пример: вы поймали auth failure и передали событие в guard.

```clojure
(defn on-auth-failed [guard-instance req]
  (guard/evaluate-request! guard-instance
                           {:request req
                            :kind :auth-failed
                            :endpoint (:uri req)
                            :code :invalid-credentials
                            :now (quot (System/currentTimeMillis) 1000)
                            :correlation-id (get-in req [:headers "x-correlation-id"])}))
```

Если порог превышен, guard вернет `:banned` и сформирует security-события.

## 5. Контракт `evaluate-request!`

Сигнатура:

```clojure
(lcmm-guard.core/evaluate-request! guard-instance
                                   {:request ring-request
                                    :kind :validation-failed|:auth-failed|:suspicious|nil
                                    :endpoint "/api/..." ; optional
                                    :code :some-reason ; optional
                                    :now epoch-seconds
                                    :correlation-id "..."})

(lcmm-guard.core/unban-ip! guard-instance
                           {:ip "203.0.113.10"
                            :reason :manual
                            :now epoch-seconds
                            :correlation-id "..."})
```

Возврат:

```clojure
{:action :allow|:rate-limited|:banned|:degraded-allow|:degraded-block
 :ip "198.51.100.10" | nil
 :events [{:event/kind ... :event/ts ... :event/payload ...} ...]}
```

### 5.1 Порядок принятия решения внутри guard

1. `ip-resolver`: определить клиентский IP.
2. `ban-store`: если IP уже в бане -> `:banned`.
3. `rate-limiter`: если превышен лимит -> `:rate-limited`.
4. если `:kind` не передан -> `:allow`.
5. если `:kind` передан -> `detector` считает окно/порог.
6. при trigger -> `ban-store/ban!` и итог `:banned` (или `:allow` для allow-list).
7. backend ошибки оборачиваются через `mode-policy`.

## 6. Политика отказа (`mode-policy`)

Настройка:

```clojure
{:mode-policy {:mode :fail-open}}
;; или
{:mode-policy {:mode :fail-closed}}
```

Поведение:
1. `:fail-open` -> при сбое backend guard возвращает `:degraded-allow`.
2. `:fail-closed` -> при сбое backend guard возвращает `:degraded-block`.

Рекомендация для небольшого проекта: стартовать с `:fail-open` + обязательный алерт по `:guard/degraded` событиям.

## 7. IP-resolver и trusted proxy

`lcmm-guard` принимает только IP literal (IPv4/IPv6) в security-path. Hostname не используется и не резолвится через DNS.

Функция:

```clojure
(lcmm-guard.ip-resolver/resolve-client-ip
 {:trusted-proxies #{"10.0.0.1"}
  :trust-xff? true}
 ring-request)
```

Правила:
1. `X-Forwarded-For` используется только если `remote-addr` входит в `trusted-proxies`.
2. если proxy не trusted, используется `:remote-addr`.
3. если IP не распознан, guard вернет `:guard/ip-unresolved` и действие по `mode-policy`:
- `:degraded-allow` для `:fail-open`;
- `:degraded-block` для `:fail-closed`.

## 8. Ban store

Функции:

```clojure
(ban-store/ban! ban-store ip reason now-sec)
(ban-store/banned? ban-store ip now-sec)
(ban-store/unban! ban-store ip)
```

Пример:

```clojure
(let [ttl-store (backend/make-ttl-store)
      bs (ban-store/make-ban-store {:ttl-store ttl-store
                                    :allow-list #{"127.0.0.1"}
                                    :default-ban-ttl-sec 900})]
  (ban-store/ban! bs "203.0.113.10" :auth-failed 1700000000)
  ;; => {:banned? true ...}

  (ban-store/banned? bs "203.0.113.10" 1700000100)
  ;; => {:banned? true ...}

  (ban-store/unban! bs "203.0.113.10"))
```

## 9. Rate limiter

Функция:

```clojure
(rate-limiter/allow? limiter {:key ip :now epoch-sec})
```

Возврат:

```clojure
{:allowed? true|false
 :limit 60
 :remaining 59
 :reset-at 1700000060}
```

В `v1` используется fixed-window алгоритм.

## 10. Detector

Функция:

```clojure
(detector/record-event! detector
                        {:kind :auth-failed
                         :ip "203.0.113.10"
                         :endpoint "/api/login"
                         :code :invalid-credentials
                         :ts 1700000000})
```

Возврат:

```clojure
{:kind :auth-failed
 :count 7
 :threshold 20
 :window-sec 300
 :triggered? false}
```

Пороги по умолчанию:
1. `:validation-failed -> 20`
2. `:auth-failed -> 20`
3. `:suspicious -> 20`

## 11. Security events и redaction

Все события в `:events` формируются через `lcmm-guard.security-events/make-event`.
Чувствительные ключи редактируются автоматически (`password`, `token`, `secret`, `api-key`, `authorization`, `cookie`).

Пример:

```clojure
(require '[lcmm-guard.security-events :as ev])

(ev/make-event :guard/test
               {:authorization "Bearer abc"
                :password "qwerty"
                :ip "203.0.113.10"})
;; => {:event/kind :guard/test
;;     :event/ts ...
;;     :event/payload {:authorization "***"
;;                     :password "***"
;;                     :ip "203.0.113.10"}}
```

## 12. Рекомендуемый порядок middleware в приложении

Рекомендуемый порядок для Ring/Reitit:
1. `correlation-id` middleware;
2. базовые HTTP/security headers;
3. body limits/timeout/concurrency limits;
4. валидация endpoint данных;
5. auth middleware;
6. интеграция с `lcmm-guard`;
7. бизнес-handler.

Почему так:
1. guard использует уже нормализованные факты (после валидации/auth);
2. события guard получают `correlation-id`;
3. бизнес-логика не запускается для `:banned`/`:rate-limited`.

## 13. Полный пример интеграции с auth/validation событиями

```clojure
(ns myapp.http
  (:require [lcmm-guard.core :as guard]))

(defn apply-guard [guard-instance req maybe-kind maybe-code]
  (let [result (guard/evaluate-request! guard-instance
                                        {:request req
                                         :kind maybe-kind
                                         :endpoint (:uri req)
                                         :code maybe-code
                                         :now (quot (System/currentTimeMillis) 1000)
                                         :correlation-id (get-in req [:headers "x-correlation-id"])} )]
    (case (:action result)
      :allow {:allowed? true :result result}
      :degraded-allow {:allowed? true :result result}
      :rate-limited {:allowed? false
                     :response {:status 429
                                :body {:code "rate_limited"}}}
      :banned {:allowed? false
               :response {:status 429
                          :body {:code "ip_banned"}}}
      :degraded-block {:allowed? false
                       :response {:status 503
                                  :body {:code "guard_unavailable"}}})))
```

Сценарии использования:
1. перед входом в handler вызывайте `apply-guard` с `maybe-kind=nil`;
2. при `validation`/`auth` ошибках дополнительно вызывайте guard с `:kind`;
3. security events из `result` отправляйте в ваш logger.

## 14. Backend-ы

Сейчас в библиотеке реализован in-memory backend:

1. `lcmm-guard.backend.in-memory/make-counter-store`
2. `lcmm-guard.backend.in-memory/make-ttl-store`

Оба backend-а поддерживают:
1. лимиты cardinality (`:max-entries`);
2. опциональный persistence режим (`:persistence`) со snapshot/restore;
3. `on-corrupt` политику (`:reset`/`:fail`).

Пример конфигурации persistence:

```clojure
(backend/make-ttl-store
 {:max-entries 10000
  :persistence {:enabled? true
                :path "C:/var/lcmm-guard/ttl-snapshot.edn"
                :interval-sec 5
                :on-corrupt :reset
                :flush-on-write? false}})
```

Если persistence выключен, состояние живет только в памяти процесса и сбрасывается после рестарта.

## 15. Частые ошибки интеграции

1. Передают `:now` в миллисекундах, а не в секундах.
2. Слепо доверяют `X-Forwarded-For` без `trusted-proxies`.
3. Не логируют `result :events` и теряют наблюдаемость.
4. Не обрабатывают `:degraded-*` действия.
5. Не передают `:kind` на validation/auth ошибках, из-за чего detector не учится.

## 16. Минимальный production checklist

1. Настроены `trusted-proxies` и `trust-xff?`.
2. Выбран mode-policy (`:fail-open` или `:fail-closed`) и есть алерт на деградацию.
3. Настроены пороги detector и TTL ban под вашу нагрузку.
4. Есть явное отображение `:action -> HTTP response`.
5. Security events сохраняются в централизованный лог.
6. Проверены сценарии: burst, auth-bruteforce, validation-spam, storage-failure.

## 17. Что читать дальше

1. Архитектурный контекст: [ARCH](./ARCH.md)
2. Общий security baseline: [SECURITY](./SECURITY.md)
3. App-level меры: [SECURE_APP](./SECURE_APP.md)
4. Module-level меры: [SECURE_MODULE](./SECURE_MODULE.md)



## 18. Persistence observability

Для `in-memory` persistence поддержан callback деградации:

```clojure
{:persistence {:enabled? true
               :path "..."
               :interval-sec 5
               :on-corrupt :reset
               :on-persistence-error (fn [event] ... )}}
```

`event` содержит минимум: `:store`, `:stage`, `:path`, `:error`, `:mode`.
Это позволяет поднимать alert при деградации snapshot/restore.
