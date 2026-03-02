# GUARD_BACKEND: как реализовать backend для `lcmm-guard`

Версия: `v1 (draft)`
Репозиторий: `https://github.com/algebrain/lcmm-guard`

## 1. Зачем этот документ

Этот документ описывает, как добавить собственный backend хранения для `lcmm-guard`.
После прочтения вы должны уметь:

1. реализовать backend через протоколы `CounterStore` и `TtlStore`;
2. подключить его в `detector`, `rate-limiter`, `ban-store`, `core/evaluate-request!`;
3. проверить корректность поведения тестами.

## 2. Куда backend подключается в архитектуре

`lcmm-guard` разделен на компоненты.
Backend используется только в двух местах:

1. `CounterStore`:
- нужен `detector` (окна событий);
- нужен `rate-limiter` (квоты в окнах).

2. `TtlStore`:
- нужен `ban-store` (бан с TTL).

Остальные части (`ip-resolver`, `mode-policy`, `security-events`, `core`) backend-независимы.

## 3. Контракты протоколов (обязательно)

Файл: `src/lcmm_guard/backend/protocols.clj`

```clojure
(defprotocol CounterStore
  (incr-bucket! [this k bucket])
  (buckets-snapshot [this k])
  (prune-before-bucket! [this k min-bucket]))

(defprotocol TtlStore
  (put-ttl! [this k value expires-at-sec])
  (get-live [this k now-sec])
  (delete-key! [this k]))
```


### 3.1 Опциональный протокол durability

Файл: `src/lcmm_guard/backend/protocols.clj`

```clojure
(defprotocol DurableStore
  (flush! [this])
  (shutdown! [this]))
```

Использование:
1. `flush!` — принудительный flush snapshot на диск;
2. `shutdown!` — остановка фонового flush-loop и финальный flush.

Это опциональный протокол: backend может его не реализовывать.

## 4. Семантика методов (критично)

### 4.1 `CounterStore/incr-bucket!`

Вход:
1. `k` — логический ключ (обычно вектор, например `[:detector :auth-failed ip endpoint]`);
2. `bucket` — номер временного бакета (`long`).

Требования:
1. инкремент должен быть атомарным;
2. метод возвращает новое значение счетчика в бакете;
3. конкурентные вызовы не должны терять инкременты.

### 4.2 `CounterStore/buckets-snapshot`

Возвращает map вида:

```clojure
{bucket-1 count-1
 bucket-2 count-2}
```

Требования:
1. возвращать только бакеты для данного `k`;
2. если данных нет — `{}`.

### 4.3 `CounterStore/prune-before-bucket!`

Удаляет старые бакеты (< `min-bucket`) для ключа `k`.

Требования:
1. не удалять бакеты `>= min-bucket`;
2. можно быть idempotent;
3. возвращаемое значение не используется (можно `nil`).

### 4.4 `TtlStore/put-ttl!`

Сохраняет значение и абсолютное время истечения `expires-at-sec`.

Требования:
1. перезапись по ключу должна быть корректной;
2. `expires-at-sec` трактуется как epoch-seconds;
3. возвращать записанное значение (по текущей практике in-memory backend).

### 4.5 `TtlStore/get-live`

Возвращает значение только если оно еще не истекло на момент `now-sec`.

Требования:
1. если истекло — возвращать `nil`;
2. если отсутствует — возвращать `nil`;
3. ленивое удаление просроченных ключей допустимо.

### 4.6 `TtlStore/delete-key!`

Удаляет ключ.

Требования:
1. операция должна быть безопасной, даже если ключа нет;
2. возвращаемое значение не используется (можно `nil`).

## 5. Минимальная реализация backend (каркас)

Ниже шаблон backend-а на atom (идея как у `in-memory`).

```clojure
(ns my.guard.backend
  (:require [lcmm-guard.backend.protocols :as p]))

(defn make-counter-store []
  (let [state (atom {})]
    (reify p/CounterStore
      (incr-bucket! [_ k bucket]
        (let [composite-k [k bucket]]
          (get (swap! state update composite-k (fnil inc 0)) composite-k)))

      (buckets-snapshot [_ k]
        (reduce-kv (fn [acc [stored-k bucket] cnt]
                     (if (= stored-k k)
                       (assoc acc bucket cnt)
                       acc))
                   {}
                   @state))

      (prune-before-bucket! [_ k min-bucket]
        (swap! state
               (fn [m]
                 (reduce-kv (fn [acc [stored-k bucket :as ck] cnt]
                              (if (and (= stored-k k) (< bucket min-bucket))
                                acc
                                (assoc acc ck cnt)))
                            {}
                            m)))
        nil))))

(defn make-ttl-store []
  (let [state (atom {})]
    (reify p/TtlStore
      (put-ttl! [_ k value expires-at-sec]
        (swap! state assoc k {:value value :expires-at expires-at-sec})
        value)

      (get-live [_ k now-sec]
        (let [{:keys [value expires-at]} (get @state k)]
          (when (and value (<= now-sec expires-at))
            value)))

      (delete-key! [_ k]
        (swap! state dissoc k)
        nil))))
```

## 6. Подключение собственного backend в guard

```clojure
(ns myapp.security
  (:require [lcmm-guard.ban-store :as ban-store]
            [lcmm-guard.core :as guard]
            [lcmm-guard.detector :as detector]
            [lcmm-guard.rate-limiter :as rate-limiter]
            [my.guard.backend :as my-backend]))

(defn make-guard []
  (let [counter (my-backend/make-counter-store)
        ttl     (my-backend/make-ttl-store)]
    (guard/make-guard
     {:ip-config {:trust-xff? true
                  :trusted-proxies #{"10.0.0.1"}}
      :ban-store (ban-store/make-ban-store
                  {:ttl-store ttl
                   :allow-list #{"127.0.0.1"}
                   :default-ban-ttl-sec 900})
      :rate-limiter (rate-limiter/make-rate-limiter
                     {:counter-store counter
                      :limit 60
                      :window-sec 60})
      :detector (detector/make-detector
                 {:counter-store counter
                  :thresholds {:validation-failed 20
                               :auth-failed 20
                               :suspicious 20}
                  :window-sec 300
                  :bucket-sec 10})
      :mode-policy {:mode :fail-open}})))
```

## 7. Схема ключей (рекомендовано)

Чтобы backend проще отлаживался, придерживайтесь читаемой схемы ключей:

1. detector key: `[:detector kind ip endpoint]`
2. rate-limit key: `[:rate-limit ip]`
3. ban key: `[:ban ip]`

Это уже соответствует текущей реализации `lcmm-guard`.

## 8. Временные единицы (частая ошибка)

`lcmm-guard` использует секунды (`epoch-seconds`):

1. `now` в `evaluate-request!` — в секундах;
2. `event :ts` в detector — в секундах;
3. `expires-at-sec` в TTL store — в секундах.

Если передавать миллисекунды, ban/rate-limit/detector будут вести себя неверно.

## 9. Обязательные тесты backend-а

Рекомендуется создать contract-тесты, которые гоняются на каждом backend.

### 9.1 Тесты для `CounterStore`

1. `incr-bucket!` увеличивает и возвращает новое значение.
2. Разные `bucket` считаются отдельно.
3. Разные `k` изолированы.
4. `buckets-snapshot` возвращает только ключ `k`.
5. `prune-before-bucket!` удаляет только старые бакеты.

### 9.2 Тесты для `TtlStore`

1. `put-ttl!` + `get-live` до истечения -> значение доступно.
2. После истечения -> `nil`.
3. Перезапись ключа обновляет value/TTL.
4. `delete-key!` удаляет значение.

### 9.3 Интеграционные тесты через `core/evaluate-request!`

1. rate-limit: `:allow -> :rate-limited` при превышении.
2. detector+ban: после порога `:auth-failed` действие `:banned`.
3. allow-list: IP из allow-list не банится.
4. при ошибках backend поведение соответствует `mode-policy`.

## 10. Пример contract-теста (шаблон)

```clojure
(ns my.guard.backend-contract-test
  (:require [clojure.test :refer [deftest is testing]]
            [lcmm-guard.backend.protocols :as p]
            [my.guard.backend :as sut]))

(deftest counter-store-contract-test
  (let [store (sut/make-counter-store)]
    (testing "increment"
      (is (= 1 (p/incr-bucket! store :k 1)))
      (is (= 2 (p/incr-bucket! store :k 1))))
    (testing "snapshot"
      (is (= {1 2} (p/buckets-snapshot store :k))))
    (testing "prune"
      (p/incr-bucket! store :k 2)
      (p/prune-before-bucket! store :k 2)
      (is (= {2 1} (p/buckets-snapshot store :k))))))

(deftest ttl-store-contract-test
  (let [store (sut/make-ttl-store)]
    (p/put-ttl! store :a {:x 1} 100)
    (is (= {:x 1} (p/get-live store :a 100)))
    (is (nil? (p/get-live store :a 101)))
    (p/put-ttl! store :a {:x 2} 200)
    (is (= {:x 2} (p/get-live store :a 150)))
    (p/delete-key! store :a)
    (is (nil? (p/get-live store :a 150)))))
```

## 11. Реализация persistent backend (SQLite/Redis): практические заметки

### 11.1 SQLite

1. Используйте отдельные таблицы для counters и ttl-keys.
2. Для counters нужен key+bucket и атомарный upsert (`count = count + 1`).
3. Для TTL храните `expires_at_sec` и фильтруйте по `now_sec`.
4. Индексы:
- counters: `(logical_key, bucket)`
- ttl: `(logical_key)`, `(expires_at_sec)`

### 11.2 Redis

1. Для counters используйте `INCR` по ключу вида `guard:cnt:<hash(k)>:<bucket>`.
2. Для TTL ключей используйте `SET` + `EXAT`/`EXPIRE`.
3. Для snapshot нужен префиксный доступ или дополнительный индекс bucket-ов.
4. Важно: учесть clock skew и единые секунды времени.

## 12. Durable persistence для in-memory backend

`in-memory` backend поддерживает persistence-конфиг:

```clojure
{:persistence {:enabled? true
               :path "/tmp/lcmm-guard-counter.edn"
               :interval-sec 5
               :on-corrupt :reset
               :flush-on-write? false}}
```

И интеграционный флаг в ban-store:
1. `:flush-on-ban? true` вызывает `flush!` на ttl-store после `ban!` (если store реализует `DurableStore`).

## 12. Ошибки backend и `mode-policy`

Если backend может бросать исключения:

1. не глотайте исключения внутри backend без необходимости;
2. пусть ошибка поднимется в `core`, где ее обработает `mode-policy/with-mode`;
3. в `:fail-open` это даст `:degraded-allow`,
4. в `:fail-closed` это даст `:degraded-block`.

Так сохраняется единое поведение защиты.

## 13. Definition of Done для нового backend

Backend считается готовым, когда:

1. реализованы оба протокола (`CounterStore`, `TtlStore`) или нужное подмножество для вашего сценария;
2. пройдены unit contract-тесты;
3. пройдены интеграционные тесты через `evaluate-request!`;
4. документированы ограничения backend (durability, latency, failover);
5. описаны настройки (pool, timeout, retry) и безопасные дефолты.

## 14. Краткая памятка

1. Храните время в секундах.
2. Держите операции инкремента атомарными.
3. Не ломайте форму ключей и контракт map-результатов.
4. Проверяйте backend на интеграционных сценариях guard, а не только unit-ами.
5. Для production добавляйте мониторинг деградации по событиям `:guard/degraded`.


## 15. Literal-only IP policy

Hostname в security-path не поддерживается:
1. backend и интеграция guard должны работать с canonical IP literal;
2. DNS-resolve client IP запрещен в guard-path.


## 16. Наблюдаемость persistence ошибок

Для in-memory persistence используйте callback:

```clojure
{:persistence {:enabled? true
               :path "..."
               :interval-sec 5
               :on-corrupt :reset
               :on-persistence-error (fn [{:keys [store stage path error mode]}] ... )}}
```

Рекомендуется отправлять эти события в ваш security logger/alerting.
