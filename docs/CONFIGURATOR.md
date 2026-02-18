# CONFIGURATOR

Библиотека конфигурирования для модулей LCMM. Предоставляет единый порядок источников (TOML + env), предсказуемое переопределение и строгую валидацию обязательных ключей.

## Основные правила

- Конфиг читается из TOML‑файла (явный `:config` или локальные дефолты для dev/test).
- Переменные окружения всегда применяются поверх файла по ключам.
- В проде допустимы два режима: env‑only или явный путь через `:config` (без относительных путей).
- TOML может содержать секции, итоговый конфиг плоский (ключи `"db.url"`).
- Имена env: `MODULE__SECTION__KEY`.
- Пустые env не переопределяют файл.

## Структура

- `configurator.core` — публичный API.
- `configurator.toml` — чтение TOML.
- `configurator.env` — маппинг env → ключи.
- `configurator.keys` — утилиты для ключей и маскирование секретов.

## Пример использования

```clojure
(require '[configurator.core :as cfg])

(def config
  (cfg/load-config
   {:module-name "users"
    :config "./users_config.toml"
    :allow-relative? true
    :required #{"db.url" "db.user"}
    :types {"db.port" :int "debug" :bool}
    :logger (fn [lvl data] (println lvl data))}))

;; получить итоговую карту
(:config config)

;; безопасный вывод
(cfg/dump-config (:config config))
```

## Имена переменных окружения

Пример:

- `USERS__DB__URL` → `"db.url"`
- `USERS__DB__PORT` → `"db.port"`

## Типы

Поддерживаемые типы для env‑значений:

- `:int` — целое число
- `:bool` — `true` или `false`
- `:csv` — список через запятую

## Логи

При загрузке конфигурации логируется:
- выбранный файл
- источник (`:explicit`, `:module-default`, `:global-default`, `:none`)
- ключи, пришедшие из env

## Примечание

Это стартовая версия. При выносе в отдельный проект можно добавить тесты и расширить поддержку типов.
