(ns booking-full.schema-registry)

(defn make-schema-registry []
  {:booking/create-requested {"1.0" [:map [:slot-id :string] [:user-id :string]]}
   :booking/created {"1.0" [:map [:booking-id :string] [:slot-id :string] [:user-id :string]]}
   :booking/rejected {"1.0" [:map [:slot-id :string] [:user-id :string] [:reason :string]]}
   :notify/booking-created {"1.0" [:map [:booking-id :string] [:message :string]]}})
