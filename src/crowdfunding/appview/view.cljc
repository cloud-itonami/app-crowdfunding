(ns crowdfunding.appview.view
  "この appview の説明ページ。純 hiccup。

  基盤は `jp-go-dds`（デジタル庁デザインシステム）—— superproject の skill
  `kotoba-uiux` が定める新規 UI の base。色・寸法は `--hig-*` トークン契約で
  書き、raw hex も px フォントサイズも置かない。

  **表示する事実は引数で受け取る。ページの中に焼かない。**
  これは装飾の都合ではなく、docs/adr/0001 が記録した欠陥そのものへの答えで
  ある —— 移行前のページは `routeCount` と `vars` を literal で持っており、
  隣の wrangler.jsonc が実際に何を宣言しているかを知らなかった（2026-08-16 に
  superproject の fixer が `routeCount 0->1, vars 0->9` と書き込んで一度直した
  が、生成器が build に繋がっていないので次の編集でまた古くなる形だった）。
  ここでは route 表と設定を渡す側が持ち、ページは描くだけなので、両者がずれる
  余地が無い。"
  (:require [jp-go-dds.core :as dds]
            [jp-go-dds.page :as page]
            [jp-go-dds.tokens :as tokens]
            [clojure.string :as str]))

(def app-css
  "app 固有の最小 CSS。`--hig-*` 契約だけを使う（bridge が DADS の上に再定義する）。
  DADS を base にした app の下には `shitsuke.hig` が居ないので、bridge が運んで
  いないトークンは何にも解決しない —— 使うのは運ばれている 71 個の中だけ。"
  (str/join
   "\n"
   [".cf-lede { color: var(--hig-color-secondary-label); max-width: 42rem; }"
    ".cf-note { color: var(--hig-color-secondary-label); font-size: var(--hig-text-footnote-font-size); }"
    ".cf-mono { font-family: var(--hig-font-mono); }"]))

(defn- route-rows [routes]
  (mapv (fn [r]
          [(str/upper-case (name (:route/method r)))
           [:span {:class "cf-mono"} (:route/path r)]
           (:route/doc r)])
        routes))

(defn body
  "opts:
   :routes    crowdfunding.appview.route/routes（この Worker が実際に答えるもの）
   :vars      wrangler が渡した env のキー（**キー名だけ**。値は出さない）
   :mcp-url   XRPC の中継先（route/mcp-router-url の戻り値）
   :built-at  bundle のビルド時刻（不明なら nil）"
  [{:keys [routes vars mcp-url built-at]}]
  (dds/container
   (dds/section
    {}
    (dds/heading 1 "Crowdfunding — OEM D2C の先行販売")
    [:p {:class "cf-lede"}
     "OEM D2C 製品の先行販売・市場検証・資金調達を扱う appview の公開面。"
     "campaign / pledge / fee / payout のドメインそのものは "
     [:span {:class "cf-mono"} "kotoba-lang/crowdfunding"]
     " と cloud-itonami の 4 つの actor にあり、ここには無い。"])

   (dds/section
    {:title "この面が答えるもの"}
    (dds/table {:caption "公開ルート"
                :headers ["METHOD" "PATH" "何をするか"]
                :rows (route-rows routes)})
    [:p {:class "cf-note"}
     "この表は Worker の route 表そのものから描いている。ページに焼いた値では"
     "ないので、実際に答えるものと表示がずれない。"])

   (dds/section
    {:title "実行時の設定"}
    (if (seq vars)
      [:div (into [:p] (interpose " "
                                  (map (fn [k] (dds/chip-label (name k))) vars)))
       [:p {:class "cf-note"}
        "キー名のみ。**ただし下の中継先だけは値そのもの**（"
        [:span {:class "cf-mono"} "AGENTGATEWAY_MCP_ROUTER_URL"]
        "）—— どこへ中継するかは運用者が見る必要があるので意図的に出している。"
        "それ以外の値は出さない。"]]
      [:p {:class "cf-note"} "env が渡されていない（ローカル描画）。"])
    [:p {:class "cf-note"} "XRPC の中継先: "
     [:span {:class "cf-mono"} mcp-url]])

   (dds/section
    {:title "現在地"}
    [:p {:class "cf-lede"}
     "この appview は TypeScript/Svelte から ClojureScript へ移行済み。"
     "deploy される bundle は、いま読んでいるソースからコンパイルされたもので"
     "ある（docs/adr/0001）。ドメイン実装は 2026-07-27 に "
     [:span {:class "cf-mono"} "kotoba-lang/crowdfunding"]
     " へ移っており（README.edn の :superseded-by）、移行の対象でなかった "
     [:span {:class "cf-mono"} "kotoba/"]
     " の TypeScript は撤去せずそのまま残してある。"]
    (when built-at
      [:p {:class "cf-note"} "bundle build: " built-at]))))

(defn render
  "完全な HTML 文書。`css` は呼び出し側が渡す（ライブラリは I/O を持たない）。"
  [{:keys [css] :as opts}]
  (page/->page
   {:title "Crowdfunding — OEM D2C の先行販売"
    :description "OEM D2C 製品の先行販売・市場検証・資金調達を扱う appview の公開面。"
    :lang "ja"
    :css css
    :app-css (str tokens/bridge-css "\n" app-css)}
   (body opts)))
