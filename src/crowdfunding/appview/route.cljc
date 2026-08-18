(ns crowdfunding.appview.route
  "どのハンドラが要求に答えるか —— データとして持ち、純関数で決める。

  `.cljs` ではなく `.cljc` なのは意図的である。edge worker のうち検査する
  価値があるのは routing であり、ここならブラウザもビルドもネットワークも
  無しに検査できる。`crowdfunding.appview.worker` は Request/Response に触る
  唯一の名前空間で、このファイルが既に決めたことしかしない。

  ingress capability が qualify した時（`:native-aot`/`:wasm-aot` は今日いずれも
  pending —— ADR-2606290000）に **最初に `.kotoba` へ移るのもここ**である。
  route 表はスカラと文字列に対する決定であり、それはその移行を生き残る形その
  ものだから。

  ns が `crowdfunding.route` ではなく `crowdfunding.appview.route` なのは、
  `crowdfunding.{campaign,pledge,fee,payout,…}` を **kotoba-lang/crowdfunding**
  が既に所有しているためである（README.edn の :superseded-by）。この repo が
  持つのはその公開面（appview）だけなので、名前もそう言う。"
  (:require [clojure.string :as str]))

(def routes
  "公開されている面を、データとして。ランディングページは **これ** を描くので、
  実在する route とページが宣伝する route がずれる余地が無い ——
  docs/adr/0001 が記録した欠陥は、隣の wrangler.jsonc が route 1・var 9 を
  宣言している横でページが `routeCount: 0` を literal で持っていたことだった。"
  [{:route/path "/"           :route/method :get  :route/kind :page
    :route/doc "この appview の説明ページ"}
   {:route/path "/health"     :route/method :get  :route/kind :json
    :route/doc "生存確認。deploy された面が答えることを外から確かめられる"}
   {:route/path "/xrpc/:nsid" :route/method :post :route/kind :proxy
    :route/doc "XRPC を MCP router へ中継する"}])

(defn- xrpc-nsid
  "`/xrpc/<nsid>` の nsid。**空文字だけが nil**。

  多段パス（`/xrpc/a/b`）も通す。移行前の SvelteKit route は rest parameter
  `[...path]` で受けており、`a/b` をそのまま tool 名として転送していた —— この
  repo の `+server.ts` も `if (!nsid) return 400` だけで、それ以外は素通しだった。
  ここで 1 セグメントに絞ると挙動が変わる。NSID に `/` は現れないので上流で
  失敗するだけだが、**それは移行ではなく方針変更**であり、移行の commit に
  紛れ込ませるべきものではない。絞るなら別の決定として記録する。"
  [path]
  (when (str/starts-with? path "/xrpc/")
    (let [rest' (subs path (count "/xrpc/"))]
      (when (seq rest') rest'))))

(defn dispatch
  "method + path → 何をするか。Request も Response も知らない。

  返すのは `{:action …}` で、`:action` は
  `:page` / `:health` / `:xrpc` / `:cors-preflight` / `:not-found` /
  `:method-not-allowed` / `:bad-request` のいずれか。"
  [method path]
  (let [m (keyword (str/lower-case (or method "get")))
        p (or path "")]
    (cond
      (and (= m :options) (str/starts-with? p "/xrpc/"))
      {:action :cors-preflight}

      (str/starts-with? p "/xrpc/")
      (if (= m :post)
        (if-let [nsid (xrpc-nsid p)]
          {:action :xrpc :nsid nsid}
          {:action :bad-request :reason "Missing XRPC method"})
        {:action :method-not-allowed :allow "POST, OPTIONS"})

      (= p "/health") (if (= m :get)
                        {:action :health}
                        {:action :method-not-allowed :allow "GET"})
      (= p "/")       (if (= m :get)
                        {:action :page}
                        {:action :method-not-allowed :allow "GET"})
      :else {:action :not-found})))

(defn mcp-router-url
  "env の設定 → MCP router の URL。末尾スラッシュは落とす。

  移行前の `+server.ts` と同じ優先順位（`AGENTGATEWAY_MCP_ROUTER_URL` →
  `MCP_ROUTER_URL` → 既定値）で、空白だけの設定は未設定として扱うのも同じ。
  既定値をここに焼くのは、**どこへ行くのかを 1 箇所で読めるようにする**ため。"
  [{:keys [AGENTGATEWAY_MCP_ROUTER_URL MCP_ROUTER_URL]}]
  (let [pick (fn [s] (when (and (string? s) (seq (str/trim s))) (str/trim s)))]
    (-> (or (pick AGENTGATEWAY_MCP_ROUTER_URL)
            (pick MCP_ROUTER_URL)
            "https://mcp.etzhayyim.com/xrpc/com.etzhayyim.mcp.message")
        (str/replace #"/+$" ""))))

(defn unwrap-mcp
  "MCP router の応答から、呼び手に返す値を取り出す。

  `{:result {:structuredContent X}}` → X、`{:result X}` → X、それ以外は素通し。
  `{:error …}` は呼び出し側が 502 にするので、ここでは判定だけ返す。
  移行前の `+server.ts` の `payload.error` / `result.structuredContent` の
  剥がし方と同じ。"
  [payload]
  (cond
    (and (map? payload) (contains? payload :error))
    {:ok? false :error (get-in payload [:error :message] "MCP router returned an error")
     :upstream payload}

    (and (map? payload) (contains? payload :result))
    (let [r (:result payload)]
      {:ok? true :value (if (and (map? r) (contains? r :structuredContent))
                          (:structuredContent r)
                          r)})

    :else {:ok? true :value payload}))
