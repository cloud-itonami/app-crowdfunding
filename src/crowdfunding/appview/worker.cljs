(ns crowdfunding.appview.worker
  "Cloudflare Worker の入口。**この repo で唯一 Request/Response に触る層。**

  ここには判断を置かない —— どのハンドラが答えるかは
  `crowdfunding.appview.route/dispatch` が決め、ページの中身は
  `crowdfunding.appview.view` が組む。どちらも `.cljc` なので、ブラウザも
  ビルドも無しにテストできる。

  wrangler.jsonc の `main` は `dist/worker.js` を指し、それはこの名前空間を
  コンパイルしたものである。移行前は SvelteKit のビルド出力
  （`svelte/.svelte-kit/cloudflare/_worker.js`）を指していて、そのディレクトリ
  は tree に存在しなかった（docs/adr/0001）。

  `aget` を使うのは `:advanced-optimization` 下で env のキーが潰れないため
  （先例 `listingops.edge.worker` と同じ約束）。"
  (:require [crowdfunding.appview.route :as route]
            [crowdfunding.appview.view :as view]
            [shadow.resource :as rc]
            [clojure.string :as str]))

(def ^:private dds-css
  "DADS の CSS はビルド時に bundle へ焼く。外部リクエストゼロが design system
  の方針で、Worker から resource を読む経路も無い。"
  (rc/inline "jp_go_dds/dds.css"))

(defn- ->response [body {:keys [status content-type cache extra]}]
  (js/Response.
   body
   #js {:status status
        :headers (clj->js (merge {"content-type" content-type
                                  "cache-control" (or cache "no-store")}
                                 extra))}))

(defn- json [body status]
  (->response (js/JSON.stringify (clj->js body))
              {:status status :content-type "application/json; charset=utf-8"}))

(defn- env->map
  "env の **キーだけ** を keyword で拾う。値はページにも応答にも出さない
  （中継先だけは route/mcp-router-url を通して意図的に出す）。"
  [env]
  (if env
    (into {} (map (fn [k] [(keyword k) (aget env k)])) (js/Object.keys env))
    {}))

(defn- cors-headers []
  {"access-control-allow-origin" "*"
   "access-control-allow-methods" "POST,OPTIONS"
   "access-control-allow-headers" "content-type,authorization"
   "access-control-max-age" "86400"})

(defn- upstream-headers
  "移行前の `+server.ts` と同じ形で上流ヘッダを組む。

      const headers = new Headers(event.request.headers);
      headers.delete('host');
      headers.set('content-type', 'application/json');
      headers.set('x-etzhayyim-bff', …);
      headers.set('x-etzhayyim-xrpc-method', nsid);

  **受け取ったヘッダをそのまま持っていくのを、移行では変えていない。**
  `authorization` を落とせば認証つきの XRPC 呼び出しが黙って壊れるし、
  `cookie` を落とす/残すのはどちらも方針の決定であって移行ではない
  （route.cljc の多段パスと同じ理由 —— 絞るなら別の決定として記録する）。
  `x-etzhayyim-bff` の値だけは実態に合わせて `cljs-worker` にした。
  移行前は `sveltekit-edge-bff` で、それはもう真ではない。"
  [req nsid]
  (let [h (js/Headers. (.-headers req))]
    (.delete h "host")
    (.set h "content-type" "application/json")
    (.set h "x-etzhayyim-bff" "cljs-worker")
    (.set h "x-etzhayyim-xrpc-method" nsid)
    h))

(defn- proxy-xrpc
  "XRPC を MCP router へ中継する。移行前に deploy されていた SvelteKit の
  route と同じ形（jsonrpc の封筒に包み、result/structuredContent を剥がす）。"
  [req env nsid]
  (let [url (route/mcp-router-url (env->map env))
        headers (upstream-headers req nsid)]
    (-> (.json req)
        (.catch (fn [_] #js {}))
        (.then
         (fn [input]
           (js/fetch url
                     #js {:method "POST"
                          :headers headers
                          :body (js/JSON.stringify
                                 #js {:jsonrpc "2.0"
                                      :id (.randomUUID js/crypto)
                                      :method "tools/call"
                                      :params #js {:name nsid :arguments input}})})))
        (.then (fn [resp]
                 (-> (.text resp)
                     (.then (fn [text]
                              (let [payload (try (when (seq text) (js/JSON.parse text))
                                                 (catch :default _ text))
                                    clj-payload (js->clj payload :keywordize-keys true)]
                                (if-not (.-ok resp)
                                  (json {:error "MCP router request failed"
                                         :upstream clj-payload}
                                        (.-status resp))
                                  (let [{:keys [ok? value error upstream]} (route/unwrap-mcp clj-payload)]
                                    (if ok?
                                      (json (or value {}) 200)
                                      (json {:error error :upstream upstream} 502))))))))))
        (.catch (fn [e]
                  ;; 到達できなかったことを 200 で隠さない。移行時点で
                  ;; mcp.etzhayyim.com は A レコードを返さないので、これは
                  ;; 想像上の経路ではなく今日の既定の結末である。
                  (json {:error "MCP router unreachable"
                         :detail (str (.-message e))
                         :url url}
                        502))))))

(defn- page-response [env]
  (->response
   (view/render {:css dds-css
                 :routes route/routes
                 :vars (sort (keys (env->map env)))
                 :mcp-url (route/mcp-router-url (env->map env))
                 :built-at nil})
   {:status 200
    :content-type "text/html; charset=utf-8"
    :cache "public, max-age=60"}))

(defn fetch-handler [req env _ctx]
  (let [url (js/URL. (.-url req))
        path (.-pathname url)
        {:keys [action nsid allow reason]} (route/dispatch (.-method req) path)]
    (case action
      :page   (page-response env)
      :health (json {:ok true :app "crowdfunding" :runtime "cljs"
                     :routes (mapv :route/path route/routes)}
                    200)
      :xrpc   (proxy-xrpc req env nsid)
      :cors-preflight (->response nil {:status 204 :content-type "text/plain"
                                       :extra (cors-headers)})
      :bad-request (json {:error reason} 400)
      :method-not-allowed (->response (js/JSON.stringify #js {:error "Method Not Allowed"})
                                      {:status 405
                                       :content-type "application/json; charset=utf-8"
                                       :extra {"allow" allow}})
      (json {:error "Not Found"
             :routes (mapv (fn [r] (str (str/upper-case (name (:route/method r)))
                                        " " (:route/path r)))
                           route/routes)}
            404))))

(def handler #js {:fetch fetch-handler})
