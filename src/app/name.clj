(ns app.name
  (:require [lcmm.router :as router]
            [event-bus :as bus]
            [malli.core :as m]
            [malli.registry :as mr]))

;; Схема для валидации payload события :name/changed
(def NameChangedSchema
  [:map
   [:name :string]])

(defn handle-set-name
  "HTTP handler to set the name and publish an event."
  [bus logger request]
  (try
    ;; Получаем query-параметр (wrap-params уже добавил :query-params на уровне приложения)
    (let [query-params (:query-params request)
          name-val (get query-params "value" "World")]
      (logger :info {:component ::name, :event :name-set, :value name-val, :query-params query-params})
      (bus/publish bus :name/changed {:name name-val})
      {:status 200
       :headers {"Content-Type" "text/plain"}
       :body (str "Name set to: " name-val)})
    (catch Exception e
      (logger :error {:component ::name, :event :name-set-error, :exception e})
      {:status 500
       :headers {"Content-Type" "text/plain"}
       :body "Internal server error"})))

(defn init!
  "Initializes the 'name' module."
  [{:keys [bus router logger]}]
  ;; Подписка с валидацией схемы
  (bus/subscribe bus
                 :name/changed
                 (fn [bus envelope]
                   ;; Обработчик события с правильной сигнатурой [bus envelope]
                   (logger :info {:component ::name, :event :name-changed-handled, :payload (:payload envelope)})
                   ;; Пример публикации производного события с сохранением причинности
                   (bus/publish bus
                                :name/audit
                                {:action "name-changed" :name (get-in envelope [:payload :name])}
                                {:parent-envelope envelope}))
                 {:schema NameChangedSchema
                  :meta ::name-changed-handler})

  (logger :info {:component ::name, :event :module-initialized, :schema NameChangedSchema})

  (router/add-route! router
                     :get "/name"
                     (partial handle-set-name bus logger)
                     {:name ::set-name}))
