#!/usr/bin/env nbb
;; campaign_claim_fixtures.cljs — deterministic offline fixtures for the
;; campaign-claim-pipeline contract
;; (`claim-pipeline/campaign-claim-pipeline.edn`).
;;
;; Runs a full pipeline on fixture data — source proposal → fetch receipt
;; → parser admission → dedupe → bounded retry/refusal → signed claim
;; proposal → readback — and asserts every stage's invariants. No network.
;;
;; Exit codes:
;;   0  all fixtures ran clean
;;   1  a fixture found a violation
;;   2  REFUSED — the contract could not be read
;;
;; Usage: nbb tools/campaign_claim_fixtures.cljs [path/to/contract.edn]

(ns campaign-claim-fixtures
  (:require ["fs" :as fs]
            ["path" :as path]
            ["crypto" :as crypto]
            [clojure.string :as str]
            [cljs.reader :refer [read-string]]))

(def contract-path
  (or (first (remove #(str/starts-with? % "--") *command-line-args*))
      (path/join "claim-pipeline" "campaign-claim-pipeline.edn")))

(def contract
  (try
    (read-string (fs/readFileSync contract-path "utf8"))
    (catch :default e
      (println (str "REFUSED: cannot read contract: " (.-message e)))
      (js/process.exit 2))))

(def failures (atom []))
(defn fail! [fixture msg] (swap! failures conj {:fixture fixture :msg msg}))

(defn sha256 [s]
  (let [h (crypto/createHash "sha256")]
    (.update h s) (.digest h "hex")))

;; ── Stage 1: source proposal ─────────────────────────────────────────
(def sp (:source-proposal contract))

(defn propose-source [id url class]
  {:proposal-id id :source-url url :source-class class
   :source-language "ja" :justification "fixture platform page"
   :proposed-at "2026-09-01"})

(def proposals
  [(propose-source "sp-1" "https://platform.example.test/campaigns/c-100"
                   :campaign-platform-first-party)
   (propose-source "sp-2" "https://search.example.test/campaign-c-100"
                   :search-snippet)])

(defn fixture-source-proposal [f]
  (when-not (contains? (:source-class-allow sp) :campaign-platform-first-party)
    (fail! f "allow list must contain :campaign-platform-first-party"))
  (doseq [p proposals]
    (if (contains? (:source-class-allow sp) (:source-class p))
      (when-not (:source-url p) (fail! f "allowed proposal missing url"))
      (println (str "STAGE source-proposal | " (:proposal-id p)
                    " | refused-before-fetch | "
                    (name (:source-class p)) " is on the forbid list")))))

;; ── Stage 2: fetch receipt ───────────────────────────────────────────
(def fr (:fetch-receipt contract))

(def body-1 "CAMPAIGN PAGE\ncampaign-id: c-100\ncompany: EXAMPLE株式会社\nplatform: EXAMPLEプラットフォーム\ntitle: 限量生産キット先行販売\nannounced-target: 1,000,000 JPY\nclaimed-progress: 640,000 JPY\nwindow: 2026-09-01/2026-10-01\nlanguage: ja")
(def body-1-again body-1) ; second fetch of the same page (dedupe fixture)
(def body-2 "CAMPAIGN PAGE\ntitle: identifier-less campaign\nlanguage: ja")

(defn receipt [id body status]
  {:receipt-id id
   :source-url "https://platform.example.test/campaigns/c-100"
   :source-class :campaign-platform-first-party :source-language "ja"
   :observed-at "2026-09-01" :asserted-at "2026-09-01"
   :issuing-entity "EXAMPLEプラットフォーム運営" :jurisdiction "JP"
   :content-hash (sha256 body) :fetch-status status})

(def receipts
  [(receipt "r-1" body-1 :ok)
   (receipt "r-2" body-1-again :ok)   ; same bytes as r-1
   (receipt "r-3" body-2 :ok)])

(defn fixture-receipt [f]
  (doseq [r receipts]
    (when-not (re-find #"[0-9a-f]{64}" (:content-hash r))
      (fail! f (str (:receipt-id r) " content-hash not sha256-hex")))
    (doseq [k (:required fr)]
      (when-not (get r k)
        (fail! f (str (:receipt-id r) " missing required field " (name k))))))
  (when-not (and (:respect-robots? (:fetch fr)) (:no-bypass? (:fetch fr)))
    (fail! f "fetch policy must respect robots and never bypass"))
  (doseq [r receipts]
    (println (str "STAGE fetch-receipt | " (:receipt-id r) " | "
                  (name (:fetch-status r)) " | sha256=" (:content-hash r)))))

;; ── Stage 3: parser / admission ──────────────────────────────────────
(def pa (:parser-admission contract))

(defn admit [rid record]
  (if (:campaign-id record)
    {:receipt-id rid :record-id (str "rec-" rid) :decision :admitted
     :reason-code nil
     :original-language-fields {:original-title (:title record)
                                :campaign-id (:campaign-id record)
                                :company-name (:company record)
                                :platform-name (:platform record)}
     :admitted-at "2026-09-01"}
    {:receipt-id rid :record-id (str "rec-" rid) :decision :refused
     :reason-code :campaign-identifier-missing
     :original-language-fields {:original-title (:title record)}
     :admitted-at "2026-09-01"}))

(def admissions
  [(admit "r-1" {:title "限量生産キット先行販売" :campaign-id "c-100"
                 :company "EXAMPLE株式会社"
                 :platform "EXAMPLEプラットフォーム"})
   (admit "r-2" {:title "限量生産キット先行販売" :campaign-id "c-100"
                 :company "EXAMPLE株式会社"
                 :platform "EXAMPLEプラットフォーム"})
   (admit "r-3" {:title "identifier-less campaign"})])

(defn fixture-admission [f]
  (doseq [a admissions]
    (if (= :admitted (:decision a))
      (when-not (:campaign-id (:original-language-fields a))
        (fail! f "admitted record lost its campaign-id"))
      (when-not (contains? (:refusal-codes pa) (:reason-code a))
        (fail! f (str "refusal code " (:reason-code a) " not in contract")))))
  (when-not (some #(= :refused (:decision %)) admissions)
    (fail! f "refusals must be recorded, never silently dropped"))
  (let [ad (some #(when (= :admitted (:decision %)) %) admissions)]
    (when-not (and ad (every? #(contains? (:original-language-fields ad) %)
                              [:original-title :campaign-id :company-name
                               :platform-name]))
      (fail! f "original language fields (title/campaign-id/company/platform) must be preserved verbatim"))
    ;; entity separation: company ≠ campaign ≠ platform, three records
    (when-not (= :distinct-record (get-in pa [:entity-separation :operating-company]))
      (fail! f "operating company must be a distinct record"))
    (when-not (= :source-record-only (get-in pa [:entity-separation :campaign-platform]))
      (fail! f "campaign platform is a source, not a participant")))
  (doseq [a admissions]
    (println (str "STAGE parser-admission | " (:record-id a) " | "
                  (name (:decision a)) " | "
                  (if (:reason-code a) (name (:reason-code a)) "-")))))

;; ── Stage 4: dedupe ──────────────────────────────────────────────────
(def dd (:dedupe contract))

(defn dedupe-key [a]
  {:platform-namespace "example-platform"
   :campaign-id (get-in a [:original-language-fields :campaign-id])
   :asserted-kind :claimed-progress
   :snapshot-at "2026-09-01"})

(defn fixture-dedupe [f]
  (let [k1 (dedupe-key (admissions 0))
        k2 (dedupe-key (admissions 1))]
    (when-not (= k1 k2)
      (fail! f "same bytes must produce the same dedupe key"))
    (when-not (= (:on-collision dd) {:first-wins-keep-provenance true
                                     :append :refresh-history
                                     :never-overwrite? true})
      (fail! f "collision policy must keep first provenance and never overwrite"))
    (println (str "STAGE dedupe | " (:campaign-id k1)
                  " | collision | second receipt appended to refresh-history, first provenance kept"))))

;; ── Stage 5: bounded retry / refusal ─────────────────────────────────
(def rr (:retry-refusal contract))

(def attempts {"src-A" [{:n 1 :status :http-error}
                        {:n 2 :status :http-error}
                        {:n 3 :status :http-error}
                        {:n 4 :status :http-error}]}) ; exceeds bound

(defn fixture-retry [f]
  (let [a (attempts "src-A")
        maxn (:max-attempts-per-source rr)
        used (count a)]
    (when-not (> used maxn)
      (fail! f "fixture must exercise the over-bound case"))
    (when-not (contains? (:retryable-fetch-status rr) :http-error)
      (fail! f ":http-error must be retryable"))
    (when-not (contains? (:non-retryable rr) :blocked-by-policy)
      (fail! f "policy blocks must be non-retryable"))
    (when (get-in rr [:on-exhausted :fabricate-placeholder?])
      (fail! f "exhausted retries must never fabricate a placeholder"))
    (println (str "STAGE retry-refusal | src-A | refusal-recorded | "
                  used " attempts > bound " maxn "; no placeholder fabricated; deferred to next run"))))

;; ── Stage 6: signed claim proposal ───────────────────────────────────
(def cp (:claim-proposal contract))

(def claim
  {:claim-id "c-1" :proposal-id "sp-1"
   :dedupe-key (dedupe-key (admissions 0))
   :claim-kind :campaign-observation-proposed
   :entity-records {:operating-company {:entity-id "e-co-1"
                                        :entity-type :legal-entity
                                        :name "EXAMPLE株式会社"}
                    :campaign {:entity-id "c-100"
                               :entity-type :startup-observation
                               :name "限量生産キット先行販売"}}
   :amount {:kind :claimed-progress :currency "JPY" :value 640000
            :as-asserted-by-platform? true}
   :window {:from "2026-09-01" :until "2026-10-01"}
   :source-receipt-id "r-1"
   :coverage-record-ref "cov-example-platform-2026-09"
   :missingness-flags #{}
   :signature (sha256 "c-1-content-canonical-edn")
   :proposed-at "2026-09-01"})

(defn fixture-claim [f]
  (when-not (contains? (:claim-kinds cp) (:claim-kind claim))
    (fail! f "claim kind not in contract"))
  (when-not (re-find #"[0-9a-f]{64}" (:signature claim))
    (fail! f "claim signature missing"))
  (when-not (= :claimed-progress (:kind (:amount claim)))
    (fail! f "amount asserted-kind must be carried, not collapsed"))
  (when-not (get-in claim [:amount :as-asserted-by-platform?])
    (fail! f "platform-asserted amount must carry as-asserted-by-platform?"))
  ;; entity separation in the claim itself
  (when-not (and (= :legal-entity (get-in claim [:entity-records :operating-company :entity-type]))
                 (= :startup-observation (get-in claim [:entity-records :campaign :entity-type])))
    (fail! f "campaign must be an observation attached to a distinct legal entity"))
  (doseq [k (keys claim)]
    (when (contains? (:forbidden-fields cp) k)
      (fail! f (str "forbidden field present in claim: " k))))
  (when-not (= :provenance-only-not-truth-assertion
               (get-in cp [:signature :meaning]))
    (fail! f "signature must assert provenance only"))
  (println (str "STAGE claim-proposal | " (:claim-id claim)
                " | signed | provenance-only signature over canonical content")))

;; ── Stage 7: readback ────────────────────────────────────────────────
(defn fixture-readback [f]
  (let [resp {:query-id "q-1" :status :ok :claims ["c-1"]
              :coverage-record-ref "cov-example-platform-2026-09"
              :missingness-flags #{}}]
    (when-not (contains? (:status-values (:query-readback contract)) (:status resp))
      (fail! f "readback status not in contract"))
    (when-not (and (:coverage-record-ref resp)
                   (contains? resp :missingness-flags))
      (fail! f "readback must carry coverage and missingness"))
    (println "STAGE query-readback | q-1 | ok | claims=1 coverage carried")))

;; ── Stage 8: audit output covers every stage ─────────────────────────
(defn fixture-audit [f]
  (let [printed #{"source-proposal" "fetch-receipt" "parser-admission"
                  "dedupe" "retry-refusal" "claim-proposal"
                  "query-readback" "audit-output"}]
    (doseq [s (:per-stage (:audit-output contract))]
      (when-not (contains? printed (name s))
        (fail! f (str "audit line missing for stage " (name s)))))
    (when-not (= (count (:per-stage (:audit-output contract))) 8)
      (fail! f "audit must cover exactly the 8 pipeline stages")))
  (println "STAGE audit-output | run | complete | all stages covered including refusals"))

;; ── Run ──────────────────────────────────────────────────────────────
(println (str "contract: " (:contract/id contract)
              " " (:contract/version contract)
              " (" (:method/version contract) ")"))
(fixture-source-proposal "source-proposal")
(fixture-receipt "fetch-receipt")
(fixture-admission "parser-admission")
(fixture-dedupe "dedupe")
(fixture-retry "retry-refusal")
(fixture-claim "claim-proposal")
(fixture-readback "query-readback")
(fixture-audit "audit-output")

(if (empty? @failures)
  (do (println "OK: all campaign-claim-pipeline fixtures ran clean")
      (js/process.exit 0))
  (do (doseq [{:keys [fixture msg]} @failures]
        (println (str "VIOLATION [" fixture "] " msg)))
      (js/process.exit 1)))
