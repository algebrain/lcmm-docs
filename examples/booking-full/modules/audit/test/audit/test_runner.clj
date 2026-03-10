(ns audit.test-runner
  (:require [clojure.test :as t]
            [audit.core-test]))

(defn -main []
  (let [{:keys [fail error]} (t/run-tests 'audit.core-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
