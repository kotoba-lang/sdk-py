;; llm.cljc — EVO-X2 LiteLLM client (cljc port of llm.py, ADR clj/bb repo rule). Provides
;; `translate` + `chat` with exponential-backoff retry, routed to the Murakumo fleet LiteLLM
;; proxy (DEFAULT-PREFERRED per Rider v3.3 §2(i) / ADR-2606172359). `httpx.AsyncClient` is
;; replaced by `babashka.http-client` (no new dependency).
;;
;; Config: ETZHAYYIM_LLM_URL (default http://levi.local:4000), ETZHAYYIM_LLM_KEY,
;;         ETZHAYYIM_LLM_MODEL (default gemma-4-e4b-it).
;;
;; Testability: HTTP goes through the dynamic var `*request*` ([url headers body-str] →
;; {:status :body}, may throw); the retry sleep goes through `*sleep-fn*` (rebind to a
;; no-op in tests to avoid real backoff). Defaults use babashka.http-client + Thread/sleep.
(ns etzhayyim-sdk.llm
  (:require [clojure.string :as str]
            [etzhayyim-sdk.errors :as err]
            #?(:clj [cheshire.core :as json])
            #?(:clj [babashka.http-client :as http])))

;; ─── Config ──────────────────────────────────────────────────────────

(defn- env [k] (not-empty #?(:clj (System/getenv k) :cljs nil)))

(defn llm-url   [] (str/replace (or (env "ETZHAYYIM_LLM_URL") "http://levi.local:4000") #"/+$" ""))
(defn llm-key   [] (or (env "ETZHAYYIM_LLM_KEY") ""))
(defn llm-model [] (or (env "ETZHAYYIM_LLM_MODEL") "gemma-4-e4b-it"))

(def ^:private max-retries 3)
(def ^:dynamic *retry-delay-ms* 2000)

(defn- default-sleep [ms] #?(:clj (Thread/sleep (long ms)) :cljs nil))
(def ^:dynamic *sleep-fn* default-sleep)

(defn- default-request
  "POST *body-str* (JSON) to *url* with *headers*, return {:status :body} without throwing on
  4xx/5xx. Throws on transport failure (wrapped upstream as a network error)."
  [url headers body-str]
  #?(:clj (http/post url {:headers (merge {"content-type" "application/json"} headers)
                          :body body-str
                          :throw false})
     :cljs (throw (ex-info "default-request unavailable on cljs; bind *request*" {:url url}))))

(def ^:dynamic *request*
  "Pluggable POST transport (testability hook). nil → bb-native default-request."
  nil)

(defn- subs200 [s] (let [s (str s)] (subs s 0 (min 200 (count s)))))

(defn- auth-headers []
  (let [k (llm-key)] (if (seq k) {"authorization" (str "Bearer " k)} {})))

(defn- chat-completions
  "POST /v1/chat/completions with exponential-backoff retry (network + 5xx retried up to
  max-retries). 401 → ::llm-auth-error, 429 → ::llm-rate-limit-error, 5xx →
  ::llm-server-error (after retries), other 4xx → ::llm-error, network → ::llm-network-error
  (after retries). Returns the assistant message text, stripped."
  [messages {:keys [model max-tokens temperature] :or {max-tokens 512 temperature 0.3}}]
  (let [url (str (llm-url) "/v1/chat/completions")
        payload {"model" (or model (llm-model))
                 "messages" messages
                 "max_tokens" max-tokens
                 "temperature" temperature}
        body-str #?(:clj (json/generate-string payload) :cljs (throw (ex-info "cljs json" {})))]
    (loop [attempt 0
           last-err nil]
      (if (>= attempt max-retries)
        (throw (or last-err (err/ex ::err/llm-error "LLM call failed after retries (unknown reason)" {})))
        (let [retry? (< attempt (dec max-retries))
              backoff #(*sleep-fn* (* *retry-delay-ms* (inc attempt)))
              resp (try ((or *request* default-request) url (auth-headers) body-str)
                        (catch #?(:clj Exception :cljs :default) e
                          {::network e}))]
          (cond
            ;; transport-level failure → retry, else surface ::llm-network-error
            (::network resp)
            (let [ne (err/ex ::err/llm-network-error
                             (str "network error calling LLM: " (ex-message (::network resp))) {})]
              (if retry? (do (backoff) (recur (inc attempt) ne)) (throw ne)))

            (= 401 (:status resp))
            (throw (err/ex ::err/llm-auth-error (str "LLM auth failed (HTTP 401): " (subs200 (:body resp))) {}))

            (= 429 (:status resp))
            (throw (err/ex ::err/llm-rate-limit-error (str "LLM rate limited (HTTP 429): " (subs200 (:body resp))) {}))

            (>= (:status resp) 500)
            (let [se (err/ex ::err/llm-server-error (str "LLM error HTTP " (:status resp) ": " (subs200 (:body resp))) {})]
              (if retry? (do (backoff) (recur (inc attempt) se)) (throw se)))

            (>= (:status resp) 400)
            (throw (err/ex ::err/llm-error (str "LLM error HTTP " (:status resp) ": " (subs200 (:body resp))) {}))

            :else
            (let [data (try #?(:clj (json/parse-string (:body resp) true) :cljs (throw (ex-info "cljs json" {})))
                            (catch #?(:clj Exception :cljs :default) e
                              (throw (err/ex ::err/llm-error (str "LLM response not valid JSON: " (ex-message e)) {}))))
                  choices (or (:choices data) [])]
              (if (empty? choices)
                (throw (err/ex ::err/llm-error (str "LLM returned no choices: " data) {}))
                (str/trim (str (get-in (first choices) [:message :content] "")))))))))))

;; ─── Public API ──────────────────────────────────────────────────────

(defn translate
  "Translate *source-text* to *target-lang* via EVO-X2 LiteLLM. opts: :source-lang (default
  \"\", auto-detect when blank), :model, :max-tokens (default 1024). Empty source → \"\".
  Temperature 0.1 (python parity). Per ADR-2605215000 §1 routes to the Murakumo fleet proxy."
  [source-text target-lang & {:keys [source-lang model max-tokens]
                              :or {source-lang "" max-tokens 1024}}]
  (if (str/blank? source-text)
    ""
    (let [lang-hint (if (seq source-lang) (str "from " source-lang " ") "")
          system-prompt (str "You are a precise translation assistant for the etzhayyim religious-corp "
                             "social platform. Translate the given text accurately. "
                             "Output ONLY the translated text — no explanations, no markdown, no prefixes.")
          user-prompt (str "Translate the following text " lang-hint "to " target-lang ":\n\n" source-text)]
      (chat-completions [{"role" "system" "content" system-prompt}
                         {"role" "user" "content" user-prompt}]
                        {:model model :max-tokens max-tokens :temperature 0.1}))))

(defn chat
  "Single-turn chat via EVO-X2 LiteLLM. opts: :context (default \"\", prepended), :model,
  :max-tokens (default 512), :temperature (default 0.7). Returns the assistant reply text."
  [prompt & {:keys [context model max-tokens temperature]
             :or {context "" max-tokens 512 temperature 0.7}}]
  (let [user-content (if (seq context) (str/trim (str context "\n\n" prompt)) prompt)]
    (chat-completions [{"role" "user" "content" user-content}]
                      {:model model :max-tokens max-tokens :temperature temperature})))
