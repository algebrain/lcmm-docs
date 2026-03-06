(ns reference-app.accounts.test-runner
  (:require [clojure.test :as t]
            [reference-app.accounts.core-test]))

(defn -main []
  (let [{:keys [fail error]} (t/run-tests 'reference-app.accounts.core-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
