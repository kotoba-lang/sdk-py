;; test_mst_projector.cljc — cljc port of tests/test_mst_projector.py (clojure.test). The
;; httpx.MockTransport injection becomes a `(binding [mp/*request* stub] …)` — the stub is
;; an [url body-str] → {:status :body} fn (or throws to simulate a transport failure), the
;; bb-native analogue of the python MockTransport handler. No real network calls.
;; Per ADR-2605215500 §5 M5 milestone.
(ns etzhayyim-sdk.test-mst-projector
  (:require [clojure.test :refer [deftest is]]
            [cheshire.core :as json]
            [etzhayyim-sdk.errors :as err]
            [etzhayyim-sdk.mst-projector :as mp]))

;; ─── Transport stubs (≈ python _json_handler / _error_handler / _capturing_handler) ───

(defn- json-handler
  "Stub that always responds with *status* and JSON-encoded *body*."
  [status body]
  (fn [_url _body-str] {:status status :body (json/generate-string (or body {}))}))

(defn- error-handler
  "Stub that throws on every request (≈ httpx.ConnectError)."
  []
  (fn [_url _body-str] (throw (ex-info "connection refused" {}))))

(defn- capturing-handler
  "Stub returning *responses* (each {:status :body-map}) in order, recording request urls/
  bodies into the supplied atoms. Records the parsed request body (string keys, wire form)."
  [responses {:keys [urls bodies]}]
  (let [i (atom 0)]
    (fn [url body-str]
      (when urls (swap! urls conj url))
      (when bodies (swap! bodies conj (json/parse-string body-str)))
      (let [resp (nth responses (min @i (dec (count responses))))]
        (swap! i inc)
        {:status (:status resp 200)
         :body (json/generate-string (:body resp {}))}))))

;; ─── query_by_collection ─────────────────────────────────────────────

(deftest test-query-by-collection-happy
  (let [server {:records [{:did "did:plc:aaa" :rkey "r1"} {:did "did:plc:bbb" :rkey "r2"}]
                :cursor "2"}]
    (binding [mp/*request* (json-handler 200 server)]
      (let [result (mp/query-by-collection "com.etzhayyim.test.record" :limit 10)]
        (is (= 2 (count (:records result))))
        (is (= "2" (:cursor result)))
        (is (= "did:plc:aaa" (:did (first (:records result)))))))))

(deftest test-query-by-collection-no-cursor
  (binding [mp/*request* (json-handler 200 {:records [] :cursor nil})]
    (let [result (mp/query-by-collection "com.etzhayyim.test.record")]
      (is (= [] (:records result)))
      (is (nil? (:cursor result))))))

;; ─── query_by_did ────────────────────────────────────────────────────

(deftest test-query-by-did-with-filter
  (let [bodies (atom [])
        server {:records [{:did "did:plc:alice" :rkey "r1"}] :cursor nil}]
    (binding [mp/*request* (capturing-handler [{:status 200 :body server}] {:bodies bodies})]
      (let [result (mp/query-by-did "did:plc:alice"
                                    :collection "com.etzhayyim.test.record" :limit 20)]
        (is (= 1 (count (:records result))))
        (is (= "did:plc:alice" (:did (first (:records result)))))
        ;; request payload carries collection / did / limit (string wire keys)
        (is (= "com.etzhayyim.test.record" (get (first @bodies) "collection")))
        (is (= "did:plc:alice" (get (first @bodies) "did")))
        (is (= 20 (get (first @bodies) "limit")))))))

(deftest test-query-by-did-without-collection
  (let [bodies (atom [])]
    (binding [mp/*request* (capturing-handler [{:status 200 :body {:records [] :cursor nil}}]
                                              {:bodies bodies})]
      (mp/query-by-did "did:plc:bob")
      (is (not (contains? (first @bodies) "collection")))
      (is (= "did:plc:bob" (get (first @bodies) "did"))))))

;; ─── query_by_field ──────────────────────────────────────────────────

(deftest test-query-by-field-happy
  (let [bodies (atom [])
        server {:records [{:did "did:plc:a" :rkey "r1" :status "published"}
                          {:did "did:plc:b" :rkey "r2" :status "published"}]}]
    (binding [mp/*request* (capturing-handler [{:status 200 :body server}] {:bodies bodies})]
      (let [result (mp/query-by-field "com.etzhayyim.test.record" "status" "published" :limit 5)]
        (is (= 2 (count (:records result))))
        ;; wire format uses camelCase
        (is (= "status" (get (first @bodies) "fieldName")))
        (is (= "published" (get (first @bodies) "fieldValue")))
        (is (= 5 (get (first @bodies) "limit")))))))

;; ─── count_by_collection ─────────────────────────────────────────────

(deftest test-count-by-collection-happy
  (binding [mp/*request* (json-handler 200 {:count 42 :asOf "2026-05-21T00:00:00Z"})]
    (let [result (mp/count-by-collection "com.etzhayyim.test.record")]
      (is (= 42 (:count result)))
      (is (= "2026-05-21T00:00:00Z" (:asOf result))))))

;; ─── Error handling ──────────────────────────────────────────────────

(deftest test-network-error-raises
  (binding [mp/*request* (error-handler)]
    (try
      (mp/query-by-collection "com.etzhayyim.test.record")
      (is false "expected network error")
      (catch clojure.lang.ExceptionInfo e
        (is (err/sdk-error? e ::err/mst-projector-network-error))
        (is (re-find #"network error" (ex-message e)))))))

(deftest test-server-error-500
  (binding [mp/*request* (json-handler 500 {:error "InternalError" :message "oops"})]
    (try
      (mp/query-by-collection "com.etzhayyim.test.record")
      (is false "expected server error")
      (catch clojure.lang.ExceptionInfo e
        (is (err/sdk-error? e ::err/mst-projector-server-error))
        (is (re-find #"HTTP 500" (ex-message e)))))))

(deftest test-400-error
  (binding [mp/*request* (json-handler 400 {:error "InvalidRequest" :message "collection required"})]
    (try
      (mp/query-by-collection "")
      (is false "expected client error")
      (catch clojure.lang.ExceptionInfo e
        ;; an SDK error, but NOT a server (5xx) error
        (is (err/sdk-error? e ::err/mst-projector-error))
        (is (not (err/sdk-error? e ::err/mst-projector-server-error)))))))

;; ─── Base URL env var ────────────────────────────────────────────────

(deftest test-base-url-env-var-override
  (let [urls (atom [])]
    ;; base-url reads the env each call → redef to the override (no real env mutation needed)
    (with-redefs [mp/base-url (constantly "http://my-projector.local:9999")]
      (binding [mp/*request* (capturing-handler [{:status 200 :body {:records [] :cursor nil}}]
                                                {:urls urls})]
        (mp/query-by-collection "com.etzhayyim.test.record")))
    (is (= 1 (count @urls)))
    (is (clojure.string/starts-with? (first @urls) "http://my-projector.local:9999/"))))
