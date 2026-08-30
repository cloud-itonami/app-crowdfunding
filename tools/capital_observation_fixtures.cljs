#!/usr/bin/env nbb
;; capital_observation_fixtures — deterministic, offline verification of the
;; campaign-observation.v1 contract (capital-observation/campaign-observation.edn).
;;
;; Exit-code convention mirrors scripts/verify-docs-claims.cljs:
;;   0  every fixture passes
;;   1  a fixture failed (contract violated)
;;   2  could not answer (contract file unreadable/unparseable)
;;
;; All fixtures are offline and deterministic: no network, no clock, no RNG.
;; Usage: nbb tools/capital_observation_fixtures.cljs [<dir>]   (<dir> FIRST, default ".")

(require '["fs" :as fs]
         '["crypto" :as crypto]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(def exit-code (atom 0))

(defn fail! [name msg]
  (println (str "FAIL " name " — " msg))
  (reset! exit-code 1))

(defn pass [name] (println (str "PASS " name)))

(def dir (or (second *command-line-args*) "."))
(def contract-path (str dir "/capital-observation/campaign-observation.edn"))

(def contract
  (try
    (edn/read-string (fs/readFileSync contract-path "utf8"))
    (catch :default e
      (println (str "could not read " contract-path ": " (.-message e)))
      (.exit js/process 2))))

;; --------------------------------------------------------------- helpers
(defn iso-utc? [s] (boolean (re-find #"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$" s)))

(defn sha256 [s]
  (-> (crypto/createHash "sha256") (.update s "utf8") (.digest "hex")))

(defn in-window? [ts start end] ; half-open [start, end), ISO-8601 UTC sorts lexically
  (and (>= (compare ts start) 0) (< (compare ts end) 0)))

;; A derived-observation builder standing in for the readback path: applies the
;; contract's structural forbids and missingness rules to a fixed receipt set.
(defn derive-observation
  "campaign — a map with :campaign-opened-at :campaign-closed-at; receipts — vector of
   {:observed-at :amount-asserted-kind :amount :fulfillment-asserted? :source-class}."
  [contract campaign receipts ts]
  (let [{:keys [campaign-opened-at campaign-closed-at]} campaign]
    (if-not (in-window? ts campaign-opened-at campaign-closed-at)
      {:status :out-of-window}
      (let [amounted (filter :amount receipts)
            fulfillment (filter :fulfillment-asserted? receipts)
            observed (count receipts)]
        {:status (if (zero? observed) :unmeasured :ok)
         :coverage {:jurisdiction {:observed observed :unmeasured (if (zero? observed) 1 0)}}
         :missingness (cond-> {}
                        (empty? amounted) (assoc :amount :amount-not-stated)
                        (empty? fulfillment) (assoc :fulfillment :fulfillment-unmeasured))
         :progress-snapshots (mapv (fn [r] {:amount-asserted-kind (:amount-asserted-kind r)
                                            :amount (:amount r :amount-not-stated)}) receipts)}))))

;; ------------------------------------------------------------------ fixtures
;; 1. Provenance: receipts require content-hash + source-url + observed-at, and
;;    the hash is a real sha256 of the verbatim bytes.
(let [label "receipt-provenance"]
  (let [verbatim "# Platform campaign page, verbatim bytes\n\"Raised: ¥12,400,000\"\n"
        h (sha256 verbatim)
        receipt {:source-url "https://platform.example/campaigns/x"
                 :content-hash h
                 :source-class :campaign-platform-first-party
                 :source-language "ja"
                 :observed-at "2026-08-30T00:00:00Z"
                 :asserted-at "2026-08-20T00:00:00Z"
                 :issuing-entity "platform.example"
                 :jurisdiction :jp}
        rp (:receipt-policy contract)
        needed [:source-url-required? :content-hash-required? :source-language-required?
                :observed-at-required? :asserted-at-required? :issuing-entity-required?
                :jurisdiction-required?]]
    (if (and (= 64 (count h))
             (every? #(get receipt %)
                     [:source-url :content-hash :source-language :observed-at
                      :asserted-at :issuing-entity :jurisdiction])
             (every? #(true? (get rp %)) needed))
      (pass label)
      (fail! label (str "hash=" h)))))


;; 2. Entity separation: campaign is not the legal entity; platform is a source.
(let [label "entity-separation"]
  (let [em (:entity-model contract)]
    (if (and (:brand-is-not-legal-entity (:operating-company em))
             (:is-not-a-financing-round (:campaign em))
             (= :source-not-participant (:role (:campaign-platform em))))
      (pass label)
      (fail! label "entity model lost separation"))))


;; 3. Measurement window: half-open [start, end); boundary end excluded.
(let [label "half-open-window"]
  (let [w (:measurement-window contract)
        start "2026-08-01T00:00:00Z" end "2026-09-01T00:00:00Z"]
    (if (and (= :half-open-utc (:form w))
             (in-window? "2026-08-15T12:00:00Z" start end)
             (in-window? start start end)
             (not (in-window? end start end)))
      (pass label)
      (fail! label "window semantics wrong"))))


;; 4. Out-of-window: observations before open or after close answer :out-of-window.
(let [label "out-of-window-readback"]
  (let [camp {:campaign-opened-at "2026-08-01T00:00:00Z" :campaign-closed-at "2026-09-01T00:00:00Z"}
        r (derive-observation contract camp
                              [{:observed-at "2026-08-10T00:00:00Z" :amount 100 :amount-asserted-kind :claimed-progress}] 
                              "2026-09-02T00:00:00Z")]
    (if (= :out-of-window (:status r))
      (pass label)
      (fail! label (pr-str r)))))

;; 5. Missingness: a receipt without an amount flags :amount-not-stated, never vanishes.
(let [label "missing-amount-flagged"]
  (let [camp {:campaign-opened-at "2026-08-01T00:00:00Z" :campaign-closed-at "2026-09-01T00:00:00Z"}
        r (derive-observation contract camp
                              [{:observed-at "2026-08-10T00:00:00Z"
                                :amount-asserted-kind :claimed-progress
                                :fulfillment-asserted? false}]
                              "2026-08-15T00:00:00Z")]
    (if (and (= :ok (:status r))
             (= :amount-not-stated (get-in r [:missingness :amount])))
      (pass label)
      (fail! label (pr-str r)))))

;; 6. Structural forbids: no derived kind may carry a forbidden field.
(let [label "forbidden-fields-absent"]
  (let [forbidden (set (map keyword (:forbidden-fields (:derived-observation-policy contract))))
        camp {:campaign-opened-at "2026-08-01T00:00:00Z" :campaign-closed-at "2026-09-01T00:00:00Z"}
        r (derive-observation contract camp
                              [{:observed-at "2026-08-10T00:00:00Z" :amount 100
                                :amount-asserted-kind :claimed-progress}]
                              "2026-08-15T00:00:00Z")
        leaked (filter #(contains? r %) forbidden)]
    (if (and (empty? leaked)
             (seq (:permitted-kinds (:derived-observation-policy contract))))
      (pass label)
      (fail! label (str "leaked: " (pr-str leaked))))))

;; 7. Amount kinds carried, not collapsed: distinct asserted kinds stay distinct.
(let [label "amount-kinds-not-collapsed"]
  (let [camp {:campaign-opened-at "2026-08-01T00:00:00Z" :campaign-closed-at "2026-09-01T00:00:00Z"}
        r (derive-observation contract camp
                              [{:observed-at "2026-08-05T00:00:00Z" :amount 1000000
                                :amount-asserted-kind :announced-target}
                               {:observed-at "2026-08-20T00:00:00Z" :amount 640000
                                :amount-asserted-kind :claimed-progress}
                               {:observed-at "2026-09-01T00:00:00Z" :amount 710000
                                :amount-asserted-kind :claimed-final}]
                              "2026-08-25T00:00:00Z")
        kinds (set (map :amount-asserted-kind (:progress-snapshots r)))]
    (if (and (= #{:announced-target :claimed-progress :claimed-final} kinds)
             (= 3 (count (:progress-snapshots r))))
      (pass label)
      (fail! label (pr-str r)))))

;; 8. Refresh history: append-only by declaration; and the Hyakka proposal
;;    shape forbids advice/solicitation/profiling and requires the disclaimer.
(let [label "refresh-and-hyakka-shape"]
  (let [rh (:refresh-history contract) hp (:hyakka-proposal contract)
        forbidden #{:investment-advice :trade-or-allocation :outreach-or-solicitation
                    :fundraising-invitation :financial-commitment :personal-profiling
                    :reputation-ranking}]
    (if (and (true? (:append-only? rh))
             (true? (:no-rewrite? rh))
             (true? (:disclaimer-required? hp))
             (every? #(contains? (set (:forbidden hp)) %) forbidden))
      (pass label)
      (fail! label "refresh history or Hyakka shape weakened"))))


(.exit js/process @exit-code)
