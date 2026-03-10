(ns notify.test-runner
  (:require [clojure.test :as t]
            [notify.core-test]))

(defn -main []
  (let [{:keys [fail error]} (t/run-tests 'notify.core-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
