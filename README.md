# app-crowdfunding

**OEM D2C 製品の先行販売・市場検証・資金調達を扱うクラウドファンディングの
appview。** `etzhayyim/root` の `60-apps/etzhayyim-project-crowdfunding` からの
抽出物で、**2026-08-18 に appview を TypeScript/Svelte から ClojureScript へ
移行した**（ADR-0001）。数字はすべて `scripts/verify-docs-claims.cljs` が tree
から再計算して検査する。

**この repo が持つのは公開面だけである。** campaign / pledge / fee / payout の
ドメイン実装は 2026-07-27 に `kotoba-lang/crowdfunding` と cloud-itonami の
4 actor へ移っており（`README.edn` の `:superseded-by`）、この appview は
**AT Protocol の面として唯一 supersede されずに残ったもの**である。

## deploy されるものは、いま読んでいるソースである

```
src/crowdfunding/appview/route.cljc    判断（どの handler が答えるか）  ← 純 .cljc、テスト対象
src/crowdfunding/appview/view.cljc     ページ（jp-go-dds の hiccup）    ← 純 .cljc、テスト対象
src/crowdfunding/appview/worker.cljs   Request/Response に触る唯一の層
        ↓ shadow-cljs :target :esm
dist/worker.js                         ← wrangler.jsonc の "main" が指すもの
```

移行前の `main` は `svelte/.svelte-kit/cloudflare/_worker.js` を指していた。
**その `.svelte-kit` ディレクトリは tree に存在しない**（実測: `ls` で
`No such file or directory`）。`assets` binding も同じく存在しないディレクトリ
`./svelte/.svelte-kit/cloudflare/client` を指していたので撤去した。

## 公開ルート

| METHOD | PATH | 何をするか |
|---|---|---|
| GET | `/` | この appview の説明ページ |
| GET | `/health` | 生存確認。deploy された面が答えることを外から確かめられる |
| POST | `/xrpc/:nsid` | XRPC を MCP router へ中継する |
| OPTIONS | `/xrpc/*` | CORS preflight |

**この表の出所は `crowdfunding.appview.route/routes` で、ページもそこから描く。**
移行前のページは `routeCount` と `vars` を literal で持っていた（2026-08-16 に
superproject の fixer が `routeCount 0->1, vars 0->9` と書き込んで一度直したが、
生成器が build に繋がっていないので次の `wrangler.jsonc` 編集でまた古くなる形
だった）。いまは route 表を渡す側が持ち、ページは描くだけなので、両者がずれる
余地が無い。

`/health` は移行前に**無かった**経路で、移し替えではなく**追加**である。
`kotodama.jsonld` の `triggers.http.routes` が `/health` を宣言しており、
deploy された面が答えることを外から確かめる手段が他に無いので足した。

## 移行前後 — 言語の内訳

| | 移行前 | 移行後 |
|---|---|---|
| appview の TypeScript | 2 本（`+server.ts` / `vite.config.ts`）+ Svelte 1 本 + 設定 4 本 | **0** |
| appview の正本言語（`.cljs`/`.cljc`） | 0 | **4**（`route.cljc` `view.cljc` `worker.cljs` `route_test.cljc`） |
| `kotoba/` の TypeScript | 8 本 / 10 ファイル / 26,131 バイト | **8 本 / 10 ファイル / 26,131 バイト（無変更）** |

**`kotoba/` は移行の対象ではないので、撤去していない。**

## `kotoba/` を消さなかった理由（測って決めた）

`kotoba/` は 789 行の TypeScript ドメイン実装（campaign / pledge / settlement /
tithe / types + vitest）で、**appview とは無関係**である。判定に使った 3 つの
測定:

| 問い | 測定 | 結果 |
|---|---|---|
| どれかの bundle に入るか | `grep -rn kotoba appview/` | **0 件**。appview はこれを一度も参照しない |
| 移行が置き換えるものから参照されるか | 撤去した 7 ファイルからの import | **無し** |
| 依存は解決するか | `git fetch <url> <sha>` で 2 つの pin | **両方 `type=commit`** |

したがって dead code ではない。**「TypeScript を全部消す」という読み方でこれを
消すのは、移行ではなく破壊である。** `scripts/verify-docs-claims.cljs` が
ファイル数・`.ts` 数・バイト総数を pin するので、黙って増えることも減ることも
できない。

なお `cd kotoba && npm install` は **通らない**（`npm error EALLOWSCRIPTS —
--allow-scripts is not allowed in project-scoped installs`、npm 11.x が git 依存の
入れ子 install を拒否する）。これは npm の方針であって依存の不在ではない ——
上の 3 行目のとおり、pin された 2 つの SHA は git では両方とも実在する commit と
して取得できる。だから vitest スイートは**走らせられない**（未検証のまま）。

## ページが出す値・出さない値

env の**キー名**は出すが、値は出さない —— **中継先を除いて**。
`AGENTGATEWAY_MCP_ROUTER_URL` の値だけは、どこへ中継するかを運用者が見る
必要があるので意図的に表示する。

smoke はこれを**2 つの独立した印**で見る: 別の var に置いた sentinel が
出ていないこと、そして中継先の値が出ていること。片方だけだと「全部隠す」実装も
「全部出す」実装も通ってしまう。

**この 2 本が独立であることは実測で確かめた。** 値を漏らす mutation を当てると
sentinel 側だけが赤くなり、中継先側は**緑のまま**だった —— 漏れた値の中に
中継先 URL が含まれていたためである。2 つを同時に当てていたら、後者は
「隠しても緑・漏らしても緑」で一度も落ちない検査に見えたはずで、
**mutation を 1 つずつ当てる規律がそのまま効いた。**

## デザインシステムの検査は 2 本ある

`dads-table` が在ることを 1 本で見る形は**落ちない検査**である —— それは view が
出力する markup であって、CSS が 1 バイトも入っていないページにも現れる。
実測（このページ、2026-08-18。`(rc/inline "jp_go_dds/dds.css")` を `""` にして
ビルドし直した bundle と比較）:

| 探す文字列 | CSS 込み | CSS 無し |
|---|---|---|
| `dads-table` | 74 | **6**（0 にならない） |
| `--color-primitive-blue` | 45 | **0** |

だから 2 本に割った。**component を使ったか**（`class="dads-table"`）と、
**stylesheet が実際に入ったか**（`--color-primitive-blue`）は別の主張である。
CSS を外してビルドし直すと後者だけが赤くなることを確認済み。

design-quality のスコアはこの区別をしない。CLI 自身が
`axes scored: 10 ... NOT scored: input-zoom, contrast` と出力し
`A pass says nothing about an axis that was not applied.` と書く。
**「デザインシステムが実際に入っている」と言えるのはこの smoke の 2 本目だけ。**

## UI

基盤は `kotoba-lang/jp-go-digital-design-system`（デジタル庁デザインシステム）。
色・寸法は `--hig-*` トークン契約だけで書き、raw hex も px フォントサイズも
置かない。app 固有 CSS は 3 行。CSS は外部リクエストゼロの方針どおり
`shadow.resource/inline` で bundle に焼く。

決定論的 audit（`kotoba-lang/design-quality`）で **100.00 / 100**（gate 95）——
既定の 10 軸でも、`--extra-axes` を付けた 12 軸でも 100.00。

## 呼び先が 1 つも解決しない（移行では直らない）

| ホスト | 役割 | DNS（2026-08-18 実測） |
|---|---|---|
| `cf0und1n.etzhayyim.com` | 公開ホスト（wrangler の唯一の route） | **NXDOMAIN** |
| `crowdfunding.etzhayyim.com` | `CLAUDE.md` が名乗る URL | **NXDOMAIN**、かつどの wrangler 設定にも無い |
| `mcp.etzhayyim.com` | `/xrpc/:nsid` の中継先 | **NXDOMAIN** |

deploy 先も中継先も、いま存在しない（`etzhayyim.com` 自体は解決する）。
`/xrpc/` は到達できなければ **502 を返す** —— 成功と同じ形で隠さない。

## 由来（custody）

`migration.edn` は出所を `etzhayyim/root` の tree `6b299d99` と宣言する。移行後:

- 継承した 4 ファイル（7,069 バイト: `NOTICE` / `README.edn` / `migration.edn` /
  `kotodama.jsonld`）は**いまも 1 バイトも変わっていない**（sha256 を検証器に固定）
- `kotoba/` の 10 ファイルも無変更（ファイル数・`.ts` 数・バイト数を検証器に固定）
- `wrangler.jsonc` は**意図的に変更**した（`main` の付け替え、存在しない
  SvelteKit client を指す `assets` の撤去、`compatibility_flags` の撤去、
  `APP_FRAMEWORK` を `sveltekit-edge-bff` → `cljs-esm-worker`）
- `CLAUDE.md` と `MIGRATION-TODO.md` も**意図的に変更**した（前者は appview の
  runtime を書いていなかったので追記、後者は「appview の去就を決める」という
  open item を移行が答えたので更新）。どちらも byte 一致集合から外し、**内容で
  検査する** —— 意図的な変更と勝手な変更を区別するため
- TypeScript/Svelte の 7 ファイルは**移行で撤去**した。検証器はその 7 パスを
  名指しで「不在であること」を検査する —— byte 合計は「Svelte が消えた」と
  言えない

## 移行で変えなかったもの（黙って変えていない）

- **多段パス `/xrpc/a/b` は移行前と同じく転送する。** 移行前の SvelteKit route は
  rest parameter `[...path]` で受け、空文字だけを 400 にしていた。1 セグメントに
  絞ると失敗の起きる場所と応答が変わる —— **それは移行ではなく方針変更**なので
  しない。実測（`.invalid` の router URL に対して。実 DNS に依存しない）:

  ```
  POST /xrpc/com.x.y  → 502 {"error":"MCP router unreachable", …}
  POST /xrpc/a/b      → 502 {"error":"MCP router unreachable", …}   ← 同一
  POST /xrpc/         → 400 {"error":"Missing XRPC method"}          ← 空だけ
  ```

- **受け取ったリクエストヘッダは、そのまま上流へ運ぶ。** 移行前の `+server.ts` は
  `new Headers(event.request.headers)` から `host` だけを落として転送していた。
  `authorization` を落とせば認証つきの XRPC が黙って壊れるし、`cookie` を
  third-party へ運ぶのを止めるのは**それ自体が方針の決定**である。どちらも
  移行の commit に紛れ込ませない。**`cookie` の転送は見直す価値があるが、
  それは別の決定として起票する。** `x-etzhayyim-bff` の値だけは実態に合わせて
  `sveltekit-edge-bff` → `cljs-worker` にした（前者はもう真ではない）。

- **`rules`（CompiledWasm）は残した。** SvelteKit の残骸ではなく kotodama
  component の module rule である（`kotodama.jsonld` が `component.path
  "component.wasm"` を宣言）。tree に `.wasm` は 1 つも無いので現状 inert だが、
  撤去は component についての決定であって移行ではない。

## 残っている欠陥（移行では直っていない）

1. **`CLAUDE.md` が名乗る URL `crowdfunding.etzhayyim.com` を、何も serve して
   いない。** 宣言されている唯一のアドレスは `cf0und1n.etzhayyim.com` である。
   移行はこれを直さない（どちらも NXDOMAIN なので、直す先が無い）。
2. **`MIGRATION-TODO.md` の substrate-boundary チェックは未実施のまま。** 移行が
   変えたのは言語であって substrate posture ではない。
3. **`kotoba/` の vitest スイート（143 行 / 10 test）は走らせられない**（上記
   `npm install` の EALLOWSCRIPTS）。移行はこれを直さない。
4. **appview は新しい capability（`kotoba-lang/crowdfunding`）に繋がっていない。**
   移行前と同じく MCP router へ中継するだけである。繋ぐのは別の決定。

## 検証

```bash
npx --yes nbb scripts/verify-docs-claims.cljs .    # <dir> は先頭に置く
```

exit 0 = 全一致 / 1 = 食い違い / **2 = 判定できなかった**（0 と区別する）。
テストとビルドは `docs/operator-quickstart.md`。
