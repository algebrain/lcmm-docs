# GUARD_API: строгий справочник публичного API

Версия: `v1 (draft)`
Репозиторий: `https://github.com/algebrain/lcmm-guard`

Этот документ задает строгий интерфейсный контракт `lcmm-guard`.
Используйте его как источник истины для сигнатур, аргументов, формата возврата и семантики решений.
Это именно справочник, а не точка первого знакомства с практической интеграцией.
Если вам нужен сначала не справочник, а короткий пример того, как guard
встраивается в обычное приложение, начните с [`GUARD.md`](./GUARD.md).

## 1. Общие соглашения

1. Временные значения в guard-логике (`:now`, detector `:ts`, `expires-at-sec`) передаются в `epoch-seconds`.
2. Обработка IP в guard-path только literal-only (без DNS-резолва hostname).
3. Поле security event `:event/ts` формируется в миллисекундах Unix time (`long-ms`).
4. Возвращаемые map считаются data-контрактами: ветвление в коде делайте по ключам результата, а не по внутренним деталям реализации.

## 2. Core API

## 2.1 `lcmm-guard.core/make-guard`

Зачем:
1. Собирает единый экземпляр guard из настроек и зависимостей (ban/rate-limit/detector/mode-policy).

Когда применять:
1. Один раз при инициализации приложения, до старта обработки HTTP-трафика.

Сигнатура:

```clojure
(make-guard {:ip-config ...
             :ban-store ...
             :rate-limiter ...
             :detector ...
             :mode-policy ...})
```

Обязательные аргументы:
1. `:ip-config` map (`:trust-xff?`, `:trusted-proxies`).
2. `:ban-store` экземпляр из `lcmm-guard.ban-store/make-ban-store`.
3. `:rate-limiter` экземпляр из `lcmm-guard.rate-limiter/make-rate-limiter`.
4. `:detector` экземпляр из `lcmm-guard.detector/make-detector`.
5. `:mode-policy` map с `:mode` = `:fail-open | :fail-closed`.

Возврат:
1. Экземпляр guard (opaque для вызывающего кода; передается в `evaluate-request!`, `unban-ip!`, `reset-ip-state!`, `unban-and-reset-ip!`).

Примечание:
1. `:ip-config` нормализуется внутри через `prepare-ip-config`.

## 2.2 `lcmm-guard.core/evaluate-request!`

Зачем:
1. Выполняет основную проверку запроса: IP resolution, ban-check, rate-limit, detector, деградация по mode-policy.

Когда применять:
1. На app-level перед бизнес-обработчиком каждого входящего запроса.
2. Дополнительно на security-событиях (`:kind`) для накопления сигналов detector.

Сигнатура:

```clojure
(evaluate-request! guard-instance
                   {:request ring-request
                    :now epoch-seconds
                    :kind :validation-failed|:auth-failed|:suspicious|nil
                    :endpoint string?
                    :code keyword?|string?|nil
                    :correlation-id string?|nil})
```

Обязательные аргументы:
1. `guard-instance` из `make-guard`.
2. `:request` (Ring-подобный map, должен содержать `:remote-addr`; headers опциональны).
3. `:now` в секундах.

Необязательные аргументы:
1. `:kind` (`nil` означает, что событие в detector не записывается).
2. `:endpoint` (рекомендуется при передаче `:kind`).
3. `:code` (опциональный доменный reason code).
4. `:correlation-id` (опциональный tracing id).

Форма возврата:

```clojure
{:action :allow|:rate-limited|:banned|:degraded-allow|:degraded-block
 :ip "canonical-ip"|nil
 :events [{:event/kind keyword
           :event/ts long-ms
           :event/payload map} ...]}
```

Семантика `:action`:
1. `:allow` — запрос можно передавать в бизнес-обработчик.
2. `:rate-limited` — запрос должен быть отклонен (`429` в типичной политике).
3. `:banned` — запрос должен быть отклонен (`429` или `403` по политике приложения).
4. `:degraded-allow` — защита в деградации, но запрос пропускается (`fail-open`).
5. `:degraded-block` — защита в деградации, запрос блокируется (`fail-closed`).

Примечание по событиям:
1. Warning resolver-а `:proxy-config-empty` может появиться в результате `evaluate-request!` как событие `:guard/proxy-misconfig` в `:events`.

Минимальный пример:

```clojure
(guard/evaluate-request! g
                         {:request {:remote-addr "203.0.113.10" :headers {}}
                          :now 1700000000
                          :correlation-id "req-1"})
```

## 2.3 `lcmm-guard.core/unban-ip!`

Зачем:
1. Выполняет операционное снятие бана для конкретного IP.

Когда применять:
1. В admin/ops потоке (ручной unban, сценарии поддержки, автоматические playbook-ы).

Сигнатура:

```clojure
(unban-ip! guard-instance
           {:ip "canonical-or-literal-ip"
            :reason keyword?|string?|nil
            :now epoch-seconds|nil
            :correlation-id string?|nil})
```

Обязательные аргументы:
1. `guard-instance` из `make-guard`.
2. `:ip` целевой IP для unban.

Необязательные аргументы:
1. `:reason` (для audit/logging).
2. `:now` (используется в payload трассировки).
3. `:correlation-id`.

Форма возврата (успех):

```clojure
{:ok? true
 :ip "..."
 :events [{:event/kind :guard/unbanned ...}]}
```

Форма возврата (деградация):

```clojure
{:ok? false
 :ip "..."
 :action :degraded-allow|:degraded-block
 :events [{:event/kind :guard/degraded ...}]}
```

Пример:

```clojure
(guard/unban-ip! g {:ip "203.0.113.10"
                    :reason :manual
                    :correlation-id "admin-42"})
```

Примечание:
1. `:ip` может быть передан как в каноническом виде, так и в другой корректной literal-форме; guard сам нормализует IPv4/IPv6 перед `unban`.

## 2.4 `lcmm-guard.core/reset-ip-state!`

Зачем:
1. Очищает накопленный rate-limit и detector state для конкретного IP.

Когда применять:
1. В ops/admin потоке, если нужно снять накопленные counters без изменения ban state.
2. После ручной диагностики или отладки ложных срабатываний.

Сигнатура:

```clojure
(reset-ip-state! guard-instance
                 {:ip "canonical-or-literal-ip"
                  :reason keyword?|string?|nil
                  :now epoch-seconds|nil
                  :correlation-id string?|nil})
```

Форма возврата (успех):

```clojure
{:ok? true
 :ip "..."
 :events [{:event/kind :guard/state-reset ...}]}
```

Форма возврата (деградация):

```clojure
{:ok? false
 :ip "..."
 :action :degraded-allow|:degraded-block
 :events [{:event/kind :guard/degraded ...}]}
```

Примечание:
1. Для очистки counters backend counter-store должен поддерживать admin-операцию удаления по префиксу ключа.
2. `:ip` нормализуется так же, как в обычном request-path, поэтому для IPv6 можно передавать любую корректную literal-форму адреса.

## 2.5 `lcmm-guard.core/unban-and-reset-ip!`

Зачем:
1. Выполняет типовой ops-сценарий: снять ban и сразу очистить накопленный state для IP.

Когда применять:
1. В admin/ops endpoint-ах ручного восстановления доступа.

Сигнатура:

```clojure
(unban-and-reset-ip! guard-instance
                     {:ip "canonical-or-literal-ip"
                      :reason keyword?|string?|nil
                      :now epoch-seconds|nil
                      :correlation-id string?|nil})
```

Примечание:
1. `:ip` нормализуется перед обоими шагами операции, поэтому сценарий корректно работает и для IPv6, даже если admin передал адрес не в том же текстовом виде, в каком он накопился в counters/ban-store.
2. В поле результата `:ip` возвращается нормализованный адрес.

Форма возврата (успех):

```clojure
{:ok? true
 :ip "..."
 :events [{:event/kind :guard/unbanned ...}
          {:event/kind :guard/state-reset ...}]}
```

Форма возврата (деградация):

```clojure
{:ok? false
 :ip "..."
 :action :degraded-allow|:degraded-block
 :events [...]}
```

## 2.6 `lcmm-guard.ring`

Этот namespace задает optional Ring adapter поверх `core` API.
Он не заменяет `lcmm-guard.core`, а только убирает повторяющийся app-level plumbing.

### `wrap-guard`

Сигнатура:

```clojure
(wrap-guard handler
            guard-instance
            {:now-fn fn?
             :preprocess-request fn?
             :request->guard-opts fn?
             :action->response fn?
             :on-result fn?})
```

Семантика:
1. `:preprocess-request` вызывается до `evaluate-request!`;
2. `:request->guard-opts` собирает аргументы для `evaluate-request!`;
3. `:action->response` строит short-circuit response или возвращает `nil`;
4. `:on-result` вызывается на всех типах результата.

Практическое правило:
1. если приложение использует `:preprocess-request` для нормализации loopback/localhost адресов, тот же preprocessing надо применять и в auth-failure path;
2. не держите в приложении отдельный локальный список loopback alias-ов, если уже используете библиотечный helper для того же поведения.

### `preprocess-loopback-remote-addr`

Сигнатура:

```clojure
(preprocess-loopback-remote-addr ring-request)
```

Назначение:
1. нормализует loopback textual aliases в request `:remote-addr` к одному значению для guard integration path;
2. нужен для того, чтобы `127.0.0.1`, `::1`, `0:0:0:0:0:0:0:1` и `::ffff:127.0.0.1` не расходились по разным ключам guard state;
3. это helper integration-layer, а не замена общей IP canonicalization в `ip-resolver`.

### `default-request->guard-opts`

Возвращает:

```clojure
{:request request
 :correlation-id (:lcmm/correlation-id request)}
```

### `default-action->response`

Типовые соответствия:
1. `:allow -> nil`
2. `:degraded-allow -> nil`
3. `:rate-limited -> {:status 429 ...}`
4. `:banned -> {:status 429 ...}`
5. `:degraded-block -> {:status 503 ...}`

### `report-auth-failure!`

Сигнатура:

```clojure
(report-auth-failure! guard-instance
                      {:request ring-request
                       :endpoint string
                       :code keyword?|string?|nil
                       :now epoch-seconds
                       :request->guard-opts fn?})
```

Назначение:
1. helper для сценария, где auth уже не прошел и нужно передать `:auth-failed` в guard;
2. сам helper не строит success/fallback auth response и не заменяет auth flow приложения.
3. helper не применяет `:preprocess-request` автоматически; если приложению нужна та же preprocessing-политика, что и в `wrap-guard`, оно должно передать уже подготовленный `request`.
4. типичный пример: если `wrap-guard` использует `preprocess-loopback-remote-addr`, перед `report-auth-failure!` нужно передавать request после этого же helper.

## 3. IP resolver API

## 3.1 `lcmm-guard.ip-resolver/prepare-ip-config`

Зачем:
1. Нормализует и очищает `ip-config` перед рабочим использованием.

Когда применять:
1. Обычно вызывается из `make-guard` автоматически.
2. Явно вызывать имеет смысл при unit/contract тестах resolver-а.

Сигнатура:

```clojure
(prepare-ip-config {:trust-xff? bool
                    :trusted-proxies coll-of-ip-literals})
```

Возврат:
1. Нормализованный config map с каноническим set trusted proxy.

Примечание:
1. Некорректные записи `trusted-proxies` отбрасываются.

## 3.2 `lcmm-guard.ip-resolver/resolve-client-ip`

Зачем:
1. Определяет канонический клиентский IP из `remote-addr` и `x-forwarded-for` с учетом trusted proxy policy.

Когда применять:
1. Обычно используется внутри `evaluate-request!`.
2. Явно вызывать полезно для тестов и диагностики прокси-конфигурации.

Сигнатура:

```clojure
(resolve-client-ip ip-config ring-request)
```

Обязательные аргументы:
1. `ip-config` (предпочтительно результат `prepare-ip-config`).
2. `ring-request` с `:remote-addr` и опциональным header `x-forwarded-for`.

Форма возврата:

```clojure
{:ip "canonical-ip"|nil
 :source :remote-addr|:xff|:unresolved
 :warnings [:proxy-config-empty ...]}
```

Семантика warnings:
1. `:proxy-config-empty` появляется, когда `trust-xff? = true`, а trusted proxy set пуст.
2. В guard flow этот warning отражается как событие `:guard/proxy-misconfig` в результате `evaluate-request!`.

Семантика выбора IP:
1. Цепочка XFF используется только если запрос пришел от trusted proxy.
2. Клиентский IP выбирается справа налево с отбрасыванием trusted hop-ов.
3. Если валидного IP в цепочке нет, используется канонический `:remote-addr`, иначе результат `:unresolved`.

## 4. Ban store API

## 4.1 `lcmm-guard.ban-store/make-ban-store`

Зачем:
1. Создает ban-store с TTL-хранилищем, allow-list и настройками долговечности записи.

Когда применять:
1. При сборке зависимостей guard на старте приложения.

Сигнатура:

```clojure
(make-ban-store {:ttl-store ttl-store
                 :allow-list #{...}
                 :default-ban-ttl-sec 900
                 :flush-on-ban? false})
```

Обязательные аргументы:
1. `:ttl-store`, реализующий протокол `TtlStore`.

Необязательные аргументы + defaults:
1. `:allow-list` default `#{}`.
2. `:default-ban-ttl-sec` default `900`.
3. `:flush-on-ban?` default `false`.

## 4.2 `ban!`

Зачем:
1. Ставит бан для IP (если IP не в allow-list) и возвращает результат операции.

Когда применять:
1. Обычно вызывается внутри `evaluate-request!` после срабатывания detector-а.
2. Можно вызывать напрямую в специальных ops-сценариях.

Сигнатура:

```clojure
(ban! ban-store ip reason now-sec)
(ban! ban-store ip reason now-sec {:ttl-sec n})
```

Возврат (IP в allow-list):

```clojure
{:banned? false :allow-listed? true :ip "..."}
```

Возврат (бан установлен):

```clojure
{:banned? true
 :allow-listed? false
 :ban {:ip "..." :reason ... :created-at ... :until ...}}
```

## 4.3 `banned?`

Зачем:
1. Проверяет, активен ли бан для IP на момент `now-sec`.

Когда применять:
1. Обычно вызывается внутри `evaluate-request!` в начале decision flow.

Сигнатура:

```clojure
(banned? ban-store ip now-sec)
```

Возврат:

```clojure
{:banned? true|false
 :ban map|nil}
```

## 4.4 `unban!`

Зачем:
1. Удаляет бан-запись по IP в ban-store.

Когда применять:
1. Внутри `unban-ip!`.
2. В тестах или низкоуровневых административных сценариях.

Сигнатура:

```clojure
(unban! ban-store ip)
```

Возврат:

```clojure
{:unbanned? true :ip "..."}
```

## 5. Rate limiter API

## 5.1 `make-rate-limiter`

Зачем:
1. Создает limiter с окном и лимитом запросов.

Когда применять:
1. При инициализации guard-зависимостей на старте приложения.

Сигнатура:

```clojure
(make-rate-limiter {:counter-store counter-store
                    :limit 60
                    :window-sec 60})
```

Обязательные аргументы:
1. `:counter-store`, реализующий `CounterStore`.

Необязательные аргументы + defaults:
1. `:limit` default `60`.
2. `:window-sec` default `60`.

## 5.2 `allow?`

Зачем:
1. Проверяет, укладывается ли ключ в текущую квоту fixed-window.

Когда применять:
1. Обычно вызывается внутри `evaluate-request!` для ключа IP.
2. Можно использовать отдельно для API-квот вне guard-core.

Сигнатура:

```clojure
(allow? limiter {:key any-hashable
                 :now epoch-seconds})
```

Возврат:

```clojure
{:allowed? true|false
 :limit long
 :remaining long
 :reset-at epoch-seconds}
```

## 6. Detector API

## 6.1 `make-detector`

Зачем:
1. Создает detector для накопления security-сигналов в скользящем окне.

Когда применять:
1. При инициализации guard-зависимостей.

Сигнатура:

```clojure
(make-detector {:counter-store counter-store
                :thresholds {:validation-failed 20 :auth-failed 20 :suspicious 20}
                :window-sec 300
                :bucket-sec 10})
```

Обязательные аргументы:
1. `:counter-store`, реализующий `CounterStore`.

Необязательные аргументы + defaults:
1. `:thresholds` default `{validation/auth/suspicious -> 20}`.
2. `:window-sec` default `300`.
3. `:bucket-sec` default `10`.

## 6.2 `record-event!`

Зачем:
1. Регистрирует security-событие и сообщает, достигнут ли порог trigger-а.

Когда применять:
1. Обычно вызывается внутри `evaluate-request!`, когда передан `:kind`.
2. Можно применять отдельно для доменных anti-abuse сценариев.

Сигнатура:

```clojure
(record-event! detector
               {:kind :validation-failed|:auth-failed|:suspicious
                :ip "canonical-ip"
                :endpoint string|nil
                :code any|nil
                :ts epoch-seconds})
```

Возврат:

```clojure
{:kind keyword
 :count long
 :threshold long
 :window-sec long
 :triggered? boolean}
```

## 7. Mode policy API

## 7.1 `lcmm-guard.mode-policy/with-mode`

Зачем:
1. Применяет fail-open/fail-closed политику к операциям, которые могут бросать исключение.

Когда применять:
1. Внутри guard-кода вокруг backend-операций.
2. Можно использовать как общий паттерн деградации в смежных security-модулях.

Сигнатура:

```clojure
(with-mode {:mode :fail-open|:fail-closed} thunk)
```

Возврат (успех):

```clojure
{:ok? true :value any}
```

Возврат (обработанное исключение):

```clojure
{:ok? false :action :degraded-allow|:degraded-block :error ex}
```

Примечание:
1. Перехватывается `Exception`; фатальные `Error` не подавляются.

## 8. Backend protocol reference

## 8.1 `CounterStore`

Зачем:
1. Интерфейс для счетчиков по бакетам времени.

Когда применять:
1. При реализации собственного backend-а, который поддерживает detector/rate-limiter.

```clojure
(incr-bucket! this k bucket)        ; => long
(buckets-snapshot this k)           ; => {bucket count}
(prune-before-bucket! this k min-bucket) ; => nil (return value ignored)
```

Семантика:
1. `incr-bucket!` должен быть атомарным.
2. `buckets-snapshot` должен возвращать бакеты только для заданного ключа.
3. `prune-before-bucket!` удаляет бакеты `< min-bucket` для ключа.

## 8.2 `TtlStore`

Зачем:
1. Интерфейс для хранения значений с абсолютным временем истечения.

Когда применять:
1. При реализации backend-а для ban-store и других TTL-сценариев.

```clojure
(put-ttl! this k value expires-at-sec) ; => value
(get-live this k now-sec)              ; => value|nil
(delete-key! this k)                   ; => nil (return value ignored)
```

## 8.3 `DurableStore` (опционально)

Зачем:
1. Интерфейс принудительного flush/shutdown для backend-ов с durability.

Когда применять:
1. Если backend поддерживает persistence и фоновые задачи (например, periodic flush).

```clojure
(flush! this)
(shutdown! this)
```

Используется in-memory persistence и опциональными durability hooks.

## 9. Краткий чеклист интеграции

1. Соберите guard через `make-guard`.
2. Вызывайте `evaluate-request!` до бизнес-обработчика.
3. Маппите `:action` в HTTP-ответ на уровне приложения.
4. Передавайте `:events` в logging/monitoring.
5. Используйте `unban-ip!` для явного операционного потока снятия бана.
