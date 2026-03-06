(ns reference-app.audit.test-runner
  (:require [clojure.test :as t]
            [reference-app.audit.core-test]))

(defn -main []
  (let [{:keys [fail error]} (t/run-tests 'reference-app.audit.core-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
