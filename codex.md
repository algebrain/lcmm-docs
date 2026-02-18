# Codex Context

## Цель проекта
Документация LCMM и примеры кода. Отдельные компоненты (например, configurator) выносятся в отдельные репозитории.

## Главное, что сделано
- Переписан `docs/SAGA.md`: сага — обычный модуль LCMM, запуск через события, без прямых вызовов. Убраны примеры «подчиненных» модулей.
- Добавлены правила БД и интеграционных тестов: `docs/DATABASE.md`, обновлён `docs/MODULE.md`.
- Добавлен раздел конфигурирования модулей в `docs/MODULE.md`: использовать `lcmm-configurator` и см. `docs/CONFIGURATOR.md`.
- Сделана библиотека `configurator/` (будет вынесена в `https://github.com/algebrain/lcmm-configurator`).
  - Java TOML парсер: `org.tomlj/tomlj`.
  - Тесты через kaocha, `configurator/test.bat` проходит.
  - `CONFIGURATOR.md` создан в корне `configurator/`.

## Текущее решение по конфигам
- Порядок: TOML (явный :config или dev/test defaults) + env override по ключам.
- В проде: env-only или явный :config (без относительных путей).
- Env формат: `MODULE__SECTION__KEY` (двойной underscore).
- Пустые env не переопределяют файл.
- Обязательная валидация ключей.

## Важно знать
- Пользователь планирует удалить папку `configurator/` из `lcmm-docs` и перенести в отдельный репозиторий.
- Документ `docs/CONFIGURATOR.md` будет храниться в `lcmm-docs`, а реализация — в отдельном репо.
- В `docs/MODULE.md` должен быть лишь ссылочный текст на `lcmm-configurator` и `./CONFIGURATOR.md`, без дубляжа правил.

## Следующие шаги
- Если нужно, синхронизировать содержимое `docs/CONFIGURATOR.md` с реальной библиотекой (после переноса).
- Проверить, что в `docs/MODULE.md` больше нет дублей правил конфигурирования (оставить только ссылку).
