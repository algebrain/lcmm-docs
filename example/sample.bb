#!/usr/bin/env bb
(require '[babashka.process :refer [process]])

(defn run! [cmd]
  (let [proc (process {:inherit true} cmd)
        {:keys [exit]} @proc]
    (when (not= 0 exit)
      (System/exit exit))))

(run! "clj -M:run-main")
