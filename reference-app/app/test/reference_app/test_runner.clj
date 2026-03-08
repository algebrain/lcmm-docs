(ns reference-app.test-runner
  (:require [clojure.test :as t]
            [reference-app.browser-scenarios-test]
            [reference-app.integration-test]))

(defn -main []
  (let [{:keys [fail error]} (t/run-tests 'reference-app.integration-test
                                         'reference-app.browser-scenarios-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
