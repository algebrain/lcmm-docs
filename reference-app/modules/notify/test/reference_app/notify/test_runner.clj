(ns reference-app.notify.test-runner
  (:require [clojure.test :as t]
            [reference-app.notify.core-test]))

(defn -main []
  (let [{:keys [fail error]} (t/run-tests 'reference-app.notify.core-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
