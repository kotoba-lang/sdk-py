#!/usr/bin/env bb
;; etzhayyim-sdk — bb-native test runner (Clojure / babashka; no shell). ADR-2606072802 /
;; root CLAUDE.md §"Operational code = clj/bb". Supersedes any run_tests.sh (new actors ship
;; run_tests.clj). Runs the cljc suites that exercise the httpx→babashka.http-client port.
;;
;;   bb 20-actors/etzhayyim-sdk-py/run_tests.clj      ; run from anywhere
;;
;; Classpath root = this file's sibling `src/` (ns etzhayyim-sdk.<m> → src/etzhayyim_sdk/<m>.cljc).
(require '[babashka.classpath :as cp]
         '[babashka.fs :as fs]
         '[clojure.test :as t])

(cp/add-classpath (str (fs/file (fs/parent (fs/absolutize *file*)) "src")))

(def suites
  '[etzhayyim-sdk.test-metrics
    etzhayyim-sdk.test-mst-projector])

(apply require suites)

(let [{:keys [fail error]} (apply t/run-tests suites)]
  (if (zero? (+ fail error))
    (println "── etzhayyim-sdk: ALL suites green ──")
    (do (println "── etzhayyim-sdk: FAILURES above ──")
        (System/exit 1))))
