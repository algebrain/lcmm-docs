(ns accounts.test-runner
  (:require [clojure.test :as t]
            [accounts.core-test]))

(defn -main []
  (let [{:keys [fail error]} (t/run-tests 'accounts.core-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
