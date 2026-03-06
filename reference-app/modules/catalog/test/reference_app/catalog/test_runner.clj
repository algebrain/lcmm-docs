(ns reference-app.catalog.test-runner
  (:require [clojure.test :as t]
            [reference-app.catalog.core-test]))

(defn -main []
  (let [{:keys [fail error]} (t/run-tests 'reference-app.catalog.core-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
