# Документация: Работа с БД в модулях

Этот документ описывает стандартный подход к работе с базой данных в LCMM. Цель: один и тот же модуль и один и тот же набор тестов должны работать и с `DataScript`, и с основной БД (например, PostgreSQL), переключаясь одной настройкой.

## 1. Правила

*   Реализация для `DataScript` пишется первой и считается эталоном.
*   Реализация для основной БД обязательна для модулей, которым нужна БД.
*   Бизнес-логика модуля не знает о типе БД. Доступ к данным идет через абстракцию.
*   Тесты всегда интеграционные, без моков.
*   Один набор тестов запускается и на `DataScript`, и на основной БД, меняется только конфиг.
*   Если основной БД нет, тесты запускаются на `DataScript`. Это корректная базовая реализация.

## 2. Слой абстракции без глубоких папок

Чтобы сохранить компактную структуру, абстракцию и обе реализации можно держать **в одном namespace**. Например, в `my-module.db`. Это избегает вложенных папок и сохраняет код обозримым.

**Идея:** определить контракт (протокол) и две функции-конструктора, которые создают реализацию `DB` как map функций.

```clojure
(ns my-module.db
  (:require [datascript.core :as d]
            [clojure.java.jdbc :as jdbc]))

;; Контракт хранилища
(defprotocol Store
  (get-user [this user-id])
  (put-user! [this user]))

;; Реализация DataScript
(defn make-ds-store [{:keys [conn]}]
  (reify Store
    (get-user [_ user-id]
      (let [db (d/db conn)]
        (first (d/q '[:find (pull ?e [*]) .
                       :in $ ?id
                       :where [?e :user/id ?id]]
                    db user-id))))
    (put-user! [_ user]
      (d/transact! conn [user])
      user)))

;; Реализация PostgreSQL (примерно)
(defn make-pg-store [{:keys [datasource]}]
  (reify Store
    (get-user [_ user-id]
      (first (jdbc/query datasource
                         ["select * from users where id = ?" user-id])))
    (put-user! [_ user]
      (jdbc/execute! datasource
                     ["insert into users (id, name) values (?, ?)"
                      (:id user) (:name user)])
      user)))
```

**Почему так:** модуль получает `store` как зависимость и работает с ним через протокол `Store`. Это сохраняет один уровень вложенности и не распыляет код по папкам.

## 3. Подключение в модуле

В `init!` модуль получает `store` как зависимость. Конкретную реализацию выбирает приложение при сборке зависимостей.

```clojure
(defn init!
  [{:keys [bus router logger store]}]
  ;; store реализует протокол Store
  ...)
```

## 4. Выбор реализации через одну настройку

Пример конфигурации:

*   `:db/engine :datascript`
*   `:db/engine :postgres`

Пример сборки зависимостей:

```clojure
(defn make-store [config]
  (case (:db/engine config)
    :datascript (db/make-ds-store {:conn (:ds/conn config)})
    :postgres   (db/make-pg-store {:datasource (:pg/ds config)})))
```

## 5. Тесты только интеграционные

Один и тот же набор тестов должен запускаться на двух реализациях. Разница только в конфиге.

Пример схемы тестов:

```clojure
(defn with-store [config f]
  (let [store (make-store config)]
    (f store)))

(deftest user-store-test
  (with-store test-config
    (fn [store]
      (is (nil? (get-user store 1)))
      (put-user! store {:id 1 :name "Alice"})
      (is (= "Alice" (:name (get-user store 1)))))))
```

Рекомендация для CI:
*   В обычном CI прогонять тесты на `DataScript`.
*   На nightly или отдельном job прогонять те же тесты на основной БД.
