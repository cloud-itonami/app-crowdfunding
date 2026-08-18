#!/usr/bin/env nbb
;; verify-docs-claims — re-derive every number README.md and docs/operator-quickstart.md
;; state, from the tree itself, and fail when the tree and the prose disagree.
;;
;; Before the cljs migration this repo's load-bearing gap was that wrangler.jsonc's
;; `main` pointed at "svelte/.svelte-kit/cloudflare/_worker.js" -- a build output whose
;; directory does not exist anywhere in the tree. The claims below assert the CLOSURE,
;; and are written so the gap cannot quietly come back: the Svelte/TypeScript is
;; asserted ABSENT BY NAME, not merely absent from a byte total.
;;
;; Two things here differ from the sibling migrations, and both are deliberate:
;;
;;   1. This repo keeps `kotoba/` -- 10 files of TypeScript that are in no bundle,
;;      referenced by nothing the migration replaced, and whose git dependencies
;;      resolve. So the claim is NOT "zero TypeScript in the repo" (false) but
;;      "zero TypeScript in the appview" plus a PIN on kotoba/'s size, so it can
;;      neither grow nor silently shrink.
;;   2. :warnings-as-errors is checked by PARSING shadow-cljs.edn and reading
;;      [:builds :worker :compiler-options :warnings-as-errors]. Grepping cannot
;;      do this job: the misplacement it guards against (:build-options, which
;;      shadow ignores in silence) still contains the string, and so does the
;;      comment in shadow-cljs.edn that explains the trap.
;;
;; Usage:  nbb scripts/verify-docs-claims.cljs [<dir>]     (<dir> FIRST, default ".")
;; Exit:   0 every claim holds · 1 a claim is false · 2 could not answer

(require '["node:fs" :as fs]
         '["node:child_process" :as cp]
         '["node:crypto" :as crypto]
         '[clojure.string :as str]
         '[cljs.reader :as reader])

(def root (or (first (remove #(str/starts-with? % "--") *command-line-args*)) "."))
(def APP "appview/etzhayyim-wasm-crowdfunding-cf0und1n")

(def claims
  {:tracked-files 29
   :inherited-bytes 7069           ; the 4 inherited metadata files, still byte-identical
   :svelte-artifacts 0             ; no .svelte / svelte.config / svelte-dir file survives
   :sveltekit-compat-flags 0       ; nodejs_compat / nodejs_als were adapter-cloudflare's
   :appview-ts-files 0             ; the appview holds no TypeScript at all
   :production-canonical-files 4   ; route.cljc view.cljc worker.cljs route_test.cljc
   :kotoba-files 10                ; NOT migrated, NOT deleted -- pinned so it cannot drift
   :kotoba-ts-files 8
   :kotoba-bytes 26131
   :declared-vars 9
   :declared-routes 1
   :wrangler-main "../../dist/worker.js"
   :shadow-output-dir "dist"
   :shadow-export "crowdfunding.appview.worker/handler"})

;; Inherited files this repository still carries BYTE-IDENTICAL.
;; CLAUDE.md and MIGRATION-TODO.md deliberately left this set: the migration made
;; statements in both of them incomplete or false (MIGRATION-TODO asked for the
;; appview's fate to be decided; CLAUDE.md described the component without saying
;; what serves it). Both are checked by CONTENT below instead of by hash, which is
;; how an intended change is told apart from an unnoticed one.
(def preserved
  {"NOTICE" "bae68743feb911cbedcc745b136e444d3595854e4324f59e7cc9438ccda13d49"
   "README.edn" "debe3862e704250480915f5541209f276db90fe0b4845fa0a2a48242bb258e26"
   "migration.edn" "bd48c7c08a9d6701f2091339a1c9261fd3f5660e70c2c746596c2d00a1392d08"
   "appview/etzhayyim-wasm-crowdfunding-cf0und1n/kotodama.jsonld"
   "5bef26bdc2fd4d94715ab30e6729b068287f6e20a3715db9b62314e86a9aeee7"})

;; What the migration REMOVED, by name. A byte total cannot say "the Svelte is
;; gone"; this can, and it fails if any of it comes back.
(def removed-by-migration
  ["appview/etzhayyim-wasm-crowdfunding-cf0und1n/svelte/package.json"
   "appview/etzhayyim-wasm-crowdfunding-cf0und1n/svelte/src/app.html"
   "appview/etzhayyim-wasm-crowdfunding-cf0und1n/svelte/src/routes/+page.svelte"
   "appview/etzhayyim-wasm-crowdfunding-cf0und1n/svelte/src/routes/xrpc/[...path]/+server.ts"
   "appview/etzhayyim-wasm-crowdfunding-cf0und1n/svelte/svelte.config.js"
   "appview/etzhayyim-wasm-crowdfunding-cf0und1n/svelte/tsconfig.json"
   "appview/etzhayyim-wasm-crowdfunding-cf0und1n/svelte/vite.config.ts"])

(def undetermined (atom []))
(def failures (atom []))
(defn undet! [m] (swap! undetermined conj m))

(defn tracked-files []
  (try (->> (.execSync cp "git ls-files" #js {:cwd root :encoding "utf8"})
            str/split-lines (remove str/blank?) vec)
       (catch :default e (undet! (str "git ls-files failed: " (.-message e))) nil)))
(defn slurp* [rel] (try (.readFileSync fs (str root "/" rel) "utf8") (catch :default _ nil)))
(defn bytes-of [rel] (try (.-size (.statSync fs (str root "/" rel))) (catch :default _ nil)))
(defn sha256 [rel]
  (try (-> (.createHash crypto "sha256") (.update (.readFileSync fs (str root "/" rel))) (.digest "hex"))
       (catch :default _ nil)))
(defn strip-jsonc [s] (str/replace s #"(?m)^\s*//.*$" ""))

(defn check! [label expected actual]
  (let [ok (= expected actual)]
    (println (str (if ok "PASS" "FAIL") "\t" (name label)
                  "\texpected=" (pr-str expected) "\tactual=" (pr-str actual)))
    (when-not ok (swap! failures conj label))
    ok))

(let [files (tracked-files)]
  (when (nil? files) (println "UNDETERMINED\tcould not list tracked files") (js/process.exit 2))
  (println (str "SCANNED\t" (count files)))
  (when (zero? (count files)) (println "UNDETERMINED\tscanned 0 files") (js/process.exit 2))

  (let [sizes (into {} (map (juxt identity bytes-of)) files)]
    (when-let [bad (seq (keep (fn [[f s]] (when (nil? s) f)) sizes))]
      (undet! (str "tracked but unreadable: " (str/join ", " bad))))

    (check! :tracked-files (:tracked-files claims) (count files))
    (check! :inherited-bytes (:inherited-bytes claims)
            (reduce + 0 (keep #(get sizes %) (keys preserved))))
    (check! :preserved-files-unchanged []
            (vec (keep (fn [[f want]] (let [got (sha256 f)]
                                        (when-not (= want got) (str f " " (or got "MISSING")))))
                       preserved)))

    ;; the Svelte/TypeScript appview is gone, by name
    (check! :removed-by-migration-absent []
            (vec (filter #(some? (bytes-of %)) removed-by-migration)))

    ;; Svelte must not come back under ANY name -- a new .svelte file, a
    ;; svelte.config, or a svelte/ directory.
    (check! :svelte-artifacts (:svelte-artifacts claims)
            (count (filter #(or (str/ends-with? % ".svelte")
                                (str/includes? % "svelte.config")
                                (str/includes? % "/svelte/"))
                           files)))

    ;; The appview holds no TypeScript. Stated about the APPVIEW, not the repo:
    ;; kotoba/ is TypeScript, was not part of this migration, and is pinned below.
    (check! :appview-ts-files (:appview-ts-files claims)
            (count (filter #(and (str/starts-with? % (str APP "/")) (str/ends-with? % ".ts")) files)))

    ;; kotoba/ -- kept deliberately, pinned so it cannot drift in either direction.
    (let [k (filter #(str/starts-with? % "kotoba/") files)]
      (check! :kotoba-files (:kotoba-files claims) (count k))
      (check! :kotoba-ts-files (:kotoba-ts-files claims) (count (filter #(str/ends-with? % ".ts") k)))
      (check! :kotoba-bytes (:kotoba-bytes claims) (reduce + 0 (keep #(get sizes %) k))))

    (let [prod (remove #(or (str/starts-with? % "scripts/") (str/starts-with? % "kotoba/")) files)]
      (check! :production-canonical-files (:production-canonical-files claims)
              (count (filter #(re-find #"\.(cljs|cljc|clj|kotoba)$" %) prod))))

    ;; CLAUDE.md now says what actually serves this component
    (let [c (slurp* "CLAUDE.md")]
      (if (nil? c)
        (undet! "CLAUDE.md unreadable")
        (check! :claude-md-describes-cljs true
                (and (str/includes? c "shadow-cljs")
                     (str/includes? c "src/crowdfunding/appview/worker.cljs")
                     (not (str/includes? c "sveltekit-edge-bff"))))))

    ;; MIGRATION-TODO.md's open question about the appview's fate is answered, and
    ;; it says kotoba/ was not removed.
    (let [m (slurp* "MIGRATION-TODO.md")]
      (if (nil? m)
        (undet! "MIGRATION-TODO.md unreadable")
        (check! :migration-todo-records-the-decision true
                (and (str/includes? m "[x] **Decide the fate of")
                     (str/includes? m "`kotoba/` was NOT removed")))))

    ;; the deployed bundle is built from the source in this tree
    (let [w (some-> (slurp* (str APP "/wrangler.jsonc")) strip-jsonc)
          sh (slurp* "shadow-cljs.edn")]
      (if (or (nil? w) (nil? sh))
        (undet! "wrangler.jsonc or shadow-cljs.edn unreadable")
        (let [j (js->clj (.parse js/JSON w) :keywordize-keys false)
              edn (try (reader/read-string sh) (catch :default e (undet! (str "shadow-cljs.edn unparseable: " (.-message e))) nil))]
          (check! :wrangler-main (:wrangler-main claims) (get j "main"))
          (check! :declared-vars (:declared-vars claims) (count (get j "vars")))
          (check! :declared-routes (:declared-routes claims) (count (get j "routes")))
          ;; the old config served a SvelteKit client dir that never existed here
          (check! :no-stale-assets-binding true (nil? (get j "assets")))
          (check! :sveltekit-compat-flags (:sveltekit-compat-flags claims)
                  (count (filter #{"nodejs_compat" "nodejs_als"}
                                 (or (get j "compatibility_flags") []))))
          (check! :app-framework-is-not-sveltekit "cljs-esm-worker" (get-in j ["vars" "APP_FRAMEWORK"]))
          (when edn
            (let [b (get-in edn [:builds :worker])]
              (check! :shadow-output-dir (:shadow-output-dir claims) (:output-dir b))
              (check! :shadow-export (:shadow-export claims)
                      (str (get-in b [:modules :worker :exports 'default])))
              (check! :wrangler-main-is-the-shadow-bundle true
                      (str/includes? (or (get j "main") "")
                                     (str (:output-dir b) "/worker.js")))
              ;; PARSED, not grepped. Under :build-options shadow ignores it in
              ;; silence -- which is the very failure the option exists to prevent.
              (check! :warnings-as-errors-under-compiler-options true
                      (true? (get-in b [:compiler-options :warnings-as-errors])))
              (check! :warnings-as-errors-not-misplaced true
                      (nil? (get-in b [:build-options :warnings-as-errors]))))))))

    ;; The page renders the route TABLE rather than a baked count -- the defect
    ;; ADR-0001 records. Asserted structurally (the view takes :routes, the worker
    ;; passes the real table) and NOT by forbidding a substring: a check that a
    ;; docstring explaining the old defect can trip is a check about prose.
    (let [v (slurp* "src/crowdfunding/appview/view.cljc")
          w (slurp* "src/crowdfunding/appview/worker.cljs")]
      (if (or (nil? v) (nil? w))
        (undet! "view.cljc or worker.cljs unreadable")
        (check! :page-renders-route-table true
                (and (str/includes? v "[{:keys [routes vars mcp-url built-at]}]")
                     (str/includes? v "(route-rows routes)")
                     (str/includes? w ":routes route/routes")))))))

(let [u @undetermined f @failures]
  (when (seq u)
    (doseq [m u] (println (str "UNDETERMINED\t" m)))
    (println "Refusing to report a pass: the tree could not be read completely.")
    (js/process.exit 2))
  (if (seq f)
    (do (println (str "FAILED\t" (count f) " claim(s): " (str/join ", " (map name f)))) (js/process.exit 1))
    (do (println "OK\tevery claim in README.md and docs/operator-quickstart.md holds") (js/process.exit 0))))
