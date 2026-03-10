(ns catalog.test-runner
  (:require [clojure.test :as t]
            [catalog.core-test]))

(defn -main []
  (let [{:keys [fail error]} (t/run-tests 'catalog.core-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
