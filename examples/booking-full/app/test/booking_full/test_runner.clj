(ns booking-full.test-runner
  (:require [clojure.test :as t]
            [booking-full.browser-scenarios-test]
            [booking-full.integration-test]))

(defn -main []
  (let [{:keys [fail error]} (t/run-tests 'booking-full.integration-test
                                          'booking-full.browser-scenarios-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
