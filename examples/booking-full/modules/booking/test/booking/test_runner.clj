(ns booking.test-runner
  (:require [clojure.test :as t]
            [booking.core-test]))

(defn -main []
  (let [{:keys [fail error]} (t/run-tests 'booking.core-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
