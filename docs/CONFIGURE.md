# configure — руководство для разработчика

Репозиторий: `github.com/algebrain/lcmm-configure`

Эта инструкция описывает, как использовать библиотеку `configure` в коде приложения и модуля.

Главная мысль этого документа такая:

1. модуль может иметь собственный конфигурационный контракт и собственный запуск;
2. приложение может читать общий конфиг и передавать модулю только его часть;
3. эти два режима не противоречат друг другу.

Если нужен общий порядок сборки приложения, читайте также [`APP_COMPOSITION.md`](./APP_COMPOSITION.md).
Если речь идет о настройках хранилища и передаче ресурсов, читайте также [`DATABASE.md`](./DATABASE.md).
Если нужен общий разговор о границах модуля, читайте также [`MODULE.md`](./MODULE.md).

## 1. Что делает библиотека

Библиотека `configure`:

1. читает TOML-конфиг из файла;
2. накладывает поверх него переменные окружения;
3. проверяет обязательные ключи;
4. при необходимости приводит строковые значения из окружения к нужным типам;
5. умеет безопасно выводить конфиг с маскированием секретов.

## 2. Подключение

```clojure
(require '[configure.core :as cfg])
```

## 3. Основной вызов

```clojure
(def loaded
  (cfg/load-config
   {:module-name "users"
    :config "./users_config.toml"
    :allow-relative? true
    :env-only? false
    :allowed-keys #{"db.url" "db.user" "db.port" "debug"}
    :required #{"db.url" "db.user"}
    :types {"db.port" :int "debug" :bool}
    :logger (fn [level data]
              (println level data))}))

(:config loaded)
```

### Параметры `load-config`

- `:module-name` — имя модуля или приложения. Оно нужно для сопоставления переменных окружения.
- `:config` — явный путь к TOML-файлу. Если он указан, файл должен существовать.
- `:allow-relative?` — разрешать ли относительные пути для `:config`.
- `:env-only?` — если `true`, автоматический поиск файлов по умолчанию отключается.
- `:allowed-keys` — набор разрешенных dot-ключей из окружения. Это ограничение относится только к переменным окружения. Ключи из TOML-файла оно не отсекает.
- `:required` — набор обязательных ключей.
- `:types` — карта типов для строковых значений из окружения (`:int`, `:bool`, `:csv`).
- `:logger` — функция для логирования служебных сведений о загрузке.

### Что возвращает `load-config`

`load-config` возвращает карту вида:

```clojure
{:config <map>
 :meta <map>}
```

`meta` содержит:

- `:file` — путь к использованному файлу или `nil`;
- `:source` — `:explicit`, `:module-default`, `:global-default`, `:none`;
- `:env-keys` — список ключей, пришедших из окружения.

Если `:config` не указан и `:env-only?` не включен, библиотека ищет файл по такому порядку:

1. `<module-name>_config.toml` в текущем каталоге;
2. `config.toml` в текущем каталоге.

## 4. Два допустимых режима

В LCMM полезно различать два режима работы с конфигурацией.

### 4.1 Модуль запускается сам по себе

В этом режиме модуль сам читает свой конфиг.
Это нормально, если модуль живет отдельно или проверяется отдельно от большого приложения.

```clojure
(ns my.module.main
  (:require [configure.core :as cfg]
            [my.module.core :as module]))

(defn -main []
  (let [{:keys [config]} (cfg/load-config
                          {:module-name "my-module"
                           :config "./my_module.toml"
                           :allow-relative? true
                           :env-only? false})]
    (module/init! {:bus bus
                   :router router
                   :logger logger
                   :config config})))
```

Здесь модуль сам определяет:

1. какие ключи ему нужны;
2. какие из них обязательны;
3. какие режимы работы он поддерживает.

### 4.2 Модуль запускается внутри приложения

В этом режиме общий конфиг читает приложение, а затем передает модулю только нужную часть.

```clojure
(let [{:keys [config]} (load-app-config logger)]
  (module/init! {:bus bus
                 :router router
                 :logger logger
                 :config (module-config config :my-module)
                 :db (module-db-resource config)}))
```

Это удобно для приложения, потому что:

1. общий конфиг читается один раз;
2. приложение владеет общей картиной;
3. модуль получает только те настройки, которые ему действительно нужны.

Важно:
передача `:config` через `init!` не отменяет того, что модуль может иметь и отдельный запуск.

## 5. Чего делать не стоит

Плохая идея — передавать модулю весь конфиг приложения целиком.

```clojure
(module/init! {:config app-config})
```

Почему это плохо:

1. модуль начинает видеть лишнее;
2. его граница размывается;
3. становится трудно понять, какие ключи действительно относятся к модулю.

Правильнее передавать модулю только его собственный фрагмент.

```clojure
(module/init! {:config {"storage.mode" "external-managed"
                        "storage.backend" "sqlite"
                        "storage.allow-self-managed" false
                        "storage.sqlite.path" "./var/my-module.db"}})
```

## 6. Как приложение читает общий конфиг один раз

Для приложения с несколькими модулями обычный путь такой:

1. один раз прочитать общий конфиг;
2. получить одну карту значений;
3. дальше разбирать уже ее, а не читать файлы повторно.

Пример, похожий на `reference-app`:

```clojure
(ns my.app.config
  (:require [configure.core :as cfg]))

(defn load-app-config [logger]
  (cfg/load-config
   {:module-name "reference-app"
    :config "./resources/reference_app.toml"
    :allow-relative? true
    :env-only? false
    :allowed-keys #{"http.port"
                    "http.expose_correlation_headers"
                    "guard.mode"
                    "guard.rate_limit"
                    "guard.rate_window_sec"
                    "guard.ban_ttl_sec"
                    "accounts.db_path"
                    "catalog.db_path"
                    "booking.db_path"
                    "notify.db_path"
                    "audit.db_path"}
    :logger logger}))
```

## 7. Как из общего конфига выделить модульный фрагмент

Здесь проходит важная граница.
Приложение помогает сборке, но не должно подменять собой конфигурационный контракт модуля.

Пример:

```clojure
(defn module-storage-config [app-config module-id]
  {"storage.mode" "external-managed"
   "storage.backend" "sqlite"
   "storage.allow-self-managed" false
   "storage.sqlite.path" (get app-config (str (name module-id) ".db_path"))})
```

И пример использования:

```clojure
(accounts/init! {:bus event-bus
                 :router app-router
                 :logger logger
                 :config (module-storage-config app-config :accounts)
                 :db (accounts-db-resource app-config)})
```

Смысл здесь такой:

1. приложение не передает модулю весь свой конфиг;
2. приложение не придумывает модулю новый внутренний смысл настроек;
3. приложение лишь подготавливает значения в той форме, которую модуль уже умеет понимать.

## 8. Применение типов

Типы применяются только к строкам, пришедшим из окружения:

- `:int` — целое число;
- `:bool` — `true` или `false`;
- `:csv` — список через запятую.

## 9. Безопасный вывод конфига

```clojure
(cfg/dump-config (:config loaded))
```

`dump-config` скрывает значения по имени ключа.
По умолчанию маскируются ключи, в имени которых встречаются сегменты вроде `password`, `secret`, `token`, `apikey`, `api_key`.

Важно понимать границы этого механизма:

1. он рассчитан на безопасный вывод уже плоской карты конфигурации;
2. он принимает решение по имени ключа;
3. это полезная защита от случайной утечки в логах, но не отдельная универсальная система очистки любых произвольных структур.

Можно передать свой предикат для определения секретных ключей:

```clojure
(cfg/dump-config (:config loaded)
                 {:secret-key? (fn [k]
                                 (re-find #"(?i)password|token" (str k)))})
```

## 10. Ошибки

- Если `:module-name` пустой, будет выброшено исключение.
- Если `:config` задан, но файл не найден, будет выброшено исключение.
- Если `:allow-relative? false` и путь относительный, будет выброшено исключение.
- Если отсутствуют обязательные ключи из `:required`, будет выброшено исключение.

## 11. Практические рекомендации

1. Если модуль запускается отдельно, пусть он явно читает свой конфиг и проверяет свой набор ключей.
2. Если модуль запускается внутри приложения, приложению лучше читать общий конфиг один раз и передавать модулю только его часть.
3. Не передавайте модулю весь конфиг приложения без разбора.
4. Никогда не логируйте сырой конфиг, используйте `dump-config`.
5. Для общей сборки приложения держите рядом `CONFIGURE.md`, `APP_COMPOSITION.md` и `DATABASE.md`.
