(ns app.startup-modes-test
  (:require [app.main :as main]
            [clojure.test :refer [deftest is testing]]))

(deftest startup-cli-args-test
  (testing "Startup flags support reset and continue"
    (is (= {:mode :reset :port nil}
           (main/parse-cli-args [])))
    (is (= {:mode :reset :port nil}
           (main/parse-cli-args ["--reset"])))
    (is (= {:mode :continue :port nil}
           (main/parse-cli-args ["--continue"])))
    (is (= {:mode :reset :port nil}
           (main/parse-cli-args ["--" "--reset"])))
    (is (= {:mode :continue :port 3010}
           (main/parse-cli-args ["--continue" "--port=3010"])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unknown argument"
                          (main/parse-cli-args ["--wat"])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Invalid port value"
                          (main/parse-cli-args ["--port=abc"])))))

(deftest reset-and-continue-db-preparation-test
  (testing "Reset removes SQLite files while continue preserves them"
    (let [tmp-dir (.toFile (java.nio.file.Files/createTempDirectory "lcmm-example2-startup-" (make-array java.nio.file.attribute.FileAttribute 0)))
          db-path (str (.getAbsolutePath tmp-dir) java.io.File/separator "example2.db")
          journal-path (str db-path "-journal")
          wal-path (str db-path "-wal")
          shm-path (str db-path "-shm")
          db-file (java.io.File. db-path)
          journal-file (java.io.File. journal-path)
          wal-file (java.io.File. wal-path)
          shm-file (java.io.File. shm-path)]
      (try
        (spit db-file "db")
        (spit journal-file "journal")
        (spit wal-file "wal")
        (spit shm-file "shm")
        (main/prepare-db! db-path :continue)
        (is (.exists db-file))
        (is (.exists journal-file))
        (is (.exists wal-file))
        (is (.exists shm-file))
        (main/prepare-db! db-path :reset)
        (is (not (.exists db-file)))
        (is (not (.exists journal-file)))
        (is (not (.exists wal-file)))
        (is (not (.exists shm-file)))
        (finally
          (when (.exists db-file)
            (.delete db-file))
          (when (.exists journal-file)
            (.delete journal-file))
          (when (.exists wal-file)
            (.delete wal-file))
          (when (.exists shm-file)
            (.delete shm-file))
          (when (and tmp-dir (.exists ^java.io.File tmp-dir))
            (.delete ^java.io.File tmp-dir)))))))

(deftest port-check-happens-before-reset-db-cleanup-test
  (testing "Busy port aborts startup before reset removes SQLite files"
    (let [tmp-dir (.toFile (java.nio.file.Files/createTempDirectory "lcmm-example2-port-" (make-array java.nio.file.attribute.FileAttribute 0)))
          db-path (str (.getAbsolutePath tmp-dir) java.io.File/separator "example2.db")
          journal-path (str db-path "-journal")
          db-file (java.io.File. db-path)
          journal-file (java.io.File. journal-path)
          socket (java.net.ServerSocket. 0)
          port (.getLocalPort socket)]
      (try
        (spit db-file "db")
        (spit journal-file "journal")
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Port is already in use"
                              (main/validate-startup! db-path :reset port)))
        (is (.exists db-file))
        (is (.exists journal-file))
        (finally
          (.close socket)
          (when (.exists db-file)
            (.delete db-file))
          (when (.exists journal-file)
            (.delete journal-file))
          (when (and tmp-dir (.exists ^java.io.File tmp-dir))
            (.delete ^java.io.File tmp-dir)))))))
