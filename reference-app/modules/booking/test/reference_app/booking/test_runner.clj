(ns reference-app.booking.test-runner
  (:require [clojure.test :as t]
            [reference-app.booking.core-test]))

(defn -main []
  (let [{:keys [fail error]} (t/run-tests 'reference-app.booking.core-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
