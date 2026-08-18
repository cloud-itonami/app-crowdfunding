# operator-quickstart — app-crowdfunding

**この repo で今日実際にできることを、踏める形で上から書く。** 所要 5 分。
Cloudflare のアカウントは要らない（deploy だけが要る。§7）。

出力はすべて 2026-08-18 に実際に walk した結果である。**§6 は walk していない**
ものを、その理由と一緒に書く。

> この文書は 2026-08-18 の cljs 移行（ADR-0001）で全面的に書き直した。旧版は
> Svelte 版の appview と `kotoba/` の tithe 算術について書いていた。tithe の
> 検証（旧 §1）は**いまも有効**で、`kotoba/` は移行で 1 バイトも変えていない
> —— 手順は下の §6 に畳んである。

## 0. 前提

| 要るもの | 確認 | この walk で使った版 |
|---|---|---|
| git | `git --version` | 2.51.0 |
| nbb | `npx --yes nbb --version` | v1.4.208 |
| node | `node --version` | v26.3.0 |
| clojure | `clojure --version` | ビルド時のみ |

## 1. 取得して、書いてあることが本当か検査する

```bash
git clone git@github.com:cloud-itonami/app-crowdfunding.git
cd app-crowdfunding
REPO=$PWD
npx --yes nbb scripts/verify-docs-claims.cljs .
```

末尾が `OK` なら README の数値・存在・不在は tree と一致している。
**exit 2（UNDETERMINED）は 0 ではない** —— tree を読み切れなかったという別の
答えで、「検査して問題なし」と混ぜない。

この検査には移行の不変条件が入っている: Svelte/TypeScript が appview に戻って
いないこと（撤去した 7 パスの不在 + appview 配下の `.ts` 総数）、`wrangler.jsonc`
の `main` が shadow の出力先を指していること、`:warnings-as-errors` が
`:compiler-options` に在ること（**EDN として parse して確かめる**）、ページが
route 表から描かれていること、そして **`kotoba/` が 10 ファイル / 26,131 バイト
のままであること**。

## 2. テストを走らせる（ビルド不要・ブラウザ不要）

判断（`route.cljc`）と描画（`view.cljc`）は純 `.cljc` なので、nbb だけで回る。

```bash
K=~/github/com-junkawasaki/orgs/kotoba-lang
CP="src:test:$K/jp-go-digital-design-system/src:$K/html/src:$K/css/src"
d=$(mktemp -d)
cat > "$d/run.cljs" <<'EOF'
(require '[cljs.test :refer [run-tests]] 'crowdfunding.appview.route-test)
(run-tests 'crowdfunding.appview.route-test)
EOF
npx --yes nbb --classpath "$CP" "$d/run.cljs"
```

実際の出力:

```
Testing crowdfunding.appview.route-test

Ran 6 tests containing 25 assertions.
0 failures, 0 errors.
```

何を固定しているか: `/xrpc/` は**空の nsid だけ** 400 にする（`/xrpc/a/b` は
移行前の rest parameter と同じく転送する。1 セグメントに絞るのは移行ではなく
方針変更）、MCP router の URL 解決（空白だけの設定は未設定として扱う）、
`result` / `structuredContent` の剥がし方、そして**ページが route 表から
描かれること**（固定値を焼いていたら落ちる）。

## 3. ページを描画して採点する

```bash
K=~/github/com-junkawasaki/orgs/kotoba-lang
CP="src:$K/jp-go-digital-design-system/src:$K/html/src:$K/css/src"
cat > "$d/render.cljs" <<'EOF'
(require '["node:fs" :as fs]
         '[crowdfunding.appview.view :as view]
         '[crowdfunding.appview.route :as route])
(let [css (.readFileSync fs (str (.-DDS js/process.env) "/resources/jp_go_dds/dds.css") "utf8")]
  (.writeFileSync fs (.-OUT js/process.env)
    (view/render {:css css :routes route/routes
                  :vars [:AGENTGATEWAY_MCP_ROUTER_URL :APP_CAPABILITIES :APP_DESCRIPTION
                         :APP_DISPLAY_NAME :APP_EMBED_URL :APP_FRAMEWORK :APP_NANOID
                         :APP_PERFORMER_TYPE :APP_UI_TYPE]
                  :mcp-url "https://mcp.etzhayyim.com/xrpc/com.etzhayyim.mcp.message"}))
  (println "ok"))
EOF
DDS="$K/jp-go-digital-design-system" OUT="$d/page.html" \
  npx --yes nbb --classpath "$CP" "$d/render.cljs"

cd $K/design-quality && npx --yes nbb -m design-quality.cli score "$d/page.html" --min 95
```

実際の出力（末尾）:

```
  100.00  /…/page.html
aggregate: 100.00
axes scored: 10 (viewport, safe-area, dynamic-viewport, tap-targets, focus-visible,
                 reduced-motion, overflow-guard, color-scheme, responsive, semantics)
NOT scored: input-zoom, contrast — pass --extra-axes to include the optional ones
A pass says nothing about an axis that was not applied.
gate: aggregate 100.00 >= min 95.00 -> PASS
```

**CLI 自身が「10 軸しか当てていない」と言っている行を読むこと。**
`--extra-axes` を付けた 12 軸でも 100.00 / PASS だった（こちらも実測）。

ただし **design-quality の 100.00 は「デザインシステムが入っている」ことを
証明しない**。それを言えるのは §5 の smoke の 2 本目だけである。

## 4. bundle をビルドする

**高負荷ビルドは同時 1 本に制限されている**（superproject `CLAUDE.md` の
resource governor）。直接叩かず、必ず guard 経由で:

```bash
cd "$REPO"
node ~/github/com-junkawasaki/scripts/resource-guard.mjs run build -- \
  npx --yes shadow-cljs release worker
ls -la dist/worker.js
```

lock を他セッションが持っていると **exit 2** で拒否される。**迂回しない** ——
それはエラーではなく順番待ちである（この walk の日は 5 回・13 回と待った）。

実際の出力（末尾）:

```
[:worker] Build completed. (55 files, 12 compiled, 0 warnings, 17.90s)
-rw-r--r--  1 … 246094 … dist/worker.js
sha256 = cdf9df9422f0f13a3d0e8c664399d6c68c900d27f91ad4fbf9ee6a14250c4072
```

### 壊れた var はビルドを **落とす**（実測）

`shadow-cljs.edn` の `:compiler-options` に `:warnings-as-errors true` を入れて
ある。入れる前は、存在しない var を参照しても shadow は **WARNING** を出して
**exit 0** し、最初のリクエストで `Cannot read properties of undefined` を投げる
bundle を書く ——「ビルドが通った」は検査ではない（**落ちようがない**）。

この repo で実際に落として確かめた。`worker.cljs` の `route/dispatch` を、
存在しない `route/dispatch-nonexistent` に改名して再ビルドする:

```
------ ERROR -------------------------------------------------------------------
 File: src/crowdfunding/appview/worker.cljs:130:45
Use of undeclared Var crowdfunding.appview.route/dispatch-nonexistent
{:warning :undeclared-var, …, :shadow.build.compiler/warning-as-error true}
```

| | exit | `dist/worker.js` sha256 | bytes |
|---|---|---|---|
| 改名前 | **0** | `cdf9df94…250c4072` | 246094 |
| 改名後 | **1** | `cdf9df94…250c4072`（**不変**） | 246094 |
| 戻して再ビルド | **0** | `cdf9df94…250c4072` | 246094 |

**落ちたビルドは bundle を出荷しない** —— sha256 が 1 バイトも動いていないことが
それを言っている。

キーは `:build-options` ではなく **`:compiler-options`** に置く。shadow が読むのは
`[:compiler-options :warnings-as-errors]` で、置き場所を間違えると**黙って無視
される** —— この option が防ぐはずの失敗そのものになる。検証器はこれを
**EDN として parse して**確かめる（grep ではできない: 誤配置も、それを説明する
`shadow-cljs.edn` のコメント自身も、同じ文字列を含む）。

## 5. ビルドした成果物を実際に叩く

ここが deploy されるものに触る唯一の検査である。

```bash
cd "$REPO" && npx --yes nbb scripts/smoke-worker.cljs dist/worker.js
```

```
PASS	default export has fetch	expected=true	actual=true
PASS	GET / status	expected=200	actual=200
…
PASS	page hides other var values	expected=false	actual=false
PASS	page shows the relay target it uses	expected=true	actual=true
PASS	page uses the design system components	expected=true	actual=true
PASS	page carries the stylesheet itself	expected=true	actual=true
…
PASS	wrong method names what is allowed	expected="GET"	actual="GET"
OK	the built bundle answers as the route table says
```

17 項目すべて PASS。**bundle が無ければ exit 2**（「判定できなかった」であって
合格ではない）。

### なぜ検査が 2 本ずつ在るのか

**デザインシステム。** `dads-table` が在ることを 1 本で見る形は落ちない。
`(rc/inline "jp_go_dds/dds.css")` を `""` にしてビルドし直した bundle と比較:

| 探す文字列 | CSS 込み | CSS 無し |
|---|---|---|
| `dads-table` | 74 | **6**（0 にならない） |
| `--color-primitive-blue` | 45 | **0** |

`class="dads-table"` は「view がライブラリを呼んだ」、`--color-primitive-blue` は
「stylesheet が実際に入った」—— 別の主張なので別の検査にする。CSS を外すと
**後者だけ**が赤くなることを確認済み。

**env の値。** 「値を出さない」を 1 本で見ると、「全部隠す」実装も「全部出す」
実装も通る。だから印を 2 つ使う: 出てはいけない sentinel（`SENTINEL-cf0-7b21e4`）
と、出ていなければならない中継先（`.invalid` の URL なので実 DNS に依存しない）。
値を漏らす mutation を当てると sentinel 側だけが赤くなり、中継先側は**緑のまま**
だった —— 漏れた値の中に中継先 URL が含まれていたためである。**2 つを同時に
当てていたら、後者は一度も落ちない検査に見えた。**

## 5.5 Workers ランタイム（workerd）で動かす

Node で import する smoke より強い検査。実際の workerd で起こす。

```bash
cd "$REPO/appview/etzhayyim-wasm-crowdfunding-cf0und1n"
npx --yes wrangler@latest dev --local --port 8791 --ip 127.0.0.1
# 別シェルで
curl -s -o /dev/null -w '%{http_code} %{content_type}\n' http://127.0.0.1:8791/
curl -s http://127.0.0.1:8791/health
```

実際の出力:

```
200 text/html; charset=utf-8
{"ok":true,"app":"crowdfunding","runtime":"cljs","routes":["/","/health","/xrpc/:nsid"]}
```

全 route を叩いた結果: `GET /` 200（82,377 バイト、`dads-table` 74 /
`--color-primitive-blue` 45）· `GET /health` 200 · `POST /xrpc/` 400 ·
`OPTIONS /xrpc/x` 204 · `GET /nope` 404 · `POST /health` 405。

`compatibility_flags`（`nodejs_compat` / `nodejs_als`）は SvelteKit の
adapter-cloudflare 由来で、この bundle には要らない。**撤去は憶測ではなく
この実測の後に行った。**

多段パスが移行前と同じ扱いであることも、`.invalid` の router URL に対して
確かめた（実 DNS に依存しない）:

```
POST /xrpc/com.x.y  → 502 {"error":"MCP router unreachable","url":"https://mcp.example.invalid/xrpc/probe"}
POST /xrpc/a/b      → 502 {"error":"MCP router unreachable","url":"https://mcp.example.invalid/xrpc/probe"}
POST /xrpc/         → 400 {"error":"Missing XRPC method"}
```

## 6. `kotoba/` — 移行で触っていない TypeScript

`kotoba/`（10 ファイル / 789 行 / 26,131 バイト）は appview ではない。cljs 移行は
これを 1 バイトも変えていない。理由と判定は README の「`kotoba/` を消さなかった
理由」にある。

### ✅ tithe の算術は、何もインストールせずに検証できる

`kotoba/src/tithe.ts` は何も import しないので、SDK も `npm install` も無しに
直接動かせる:

```bash
cat > "$d/tithe-check.mts" <<'EOF'
import { splitTithe, parseMicros, TITHE_PERMILLE } from "<abs path>/kotoba/src/tithe.ts";
let pass = 0, fail = 0;
const ok = (c: boolean, m: string) => { c ? pass++ : (fail++, console.log("  FAIL " + m)); };
ok(TITHE_PERMILLE === 100n, "100 permille");
for (const g of [0n, 1n, 9n, 10n, 11n, 999n, 1000n, 1000001n, 123456789n, 10n ** 18n]) {
  const s = splitTithe(g);
  ok(s.tithe + s.net === s.gross, `tithe+net===gross for ${g}`);
  ok(s.tithe === (g * 100n) / 1000n, `tithe floors for ${g}`);
}
console.log(`  ${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
EOF
node --experimental-strip-types "$d/tithe-check.mts"
#   40 passed, 0 failed        (2026-08-16 の walk。移行はこのファイルを変えていない)
```

### ⚠ NOT WALKED: vitest スイート

`kotoba/` の 10 test / 143 行は**インストールできない**（2026-08-18 に再実測）:

```
npm error code 1
npm error git dep preparation failed
npm error   code EALLOWSCRIPTS
npm error   --allow-scripts is not allowed in project-scoped installs.
```

依存 2 つはどちらも git URL で、その preparation が走らせる入れ子 install を
npm 11.x が拒否する。**これは npm の方針であって依存の不在ではない** ——
pin された SHA は git では両方とも実在する commit として取得できる:

```bash
git fetch https://github.com/etzhayyim/com-etzhayyim-sdk.git \
  12314a0cc5ac2feb49dd9789d5c002398acb6988 && git cat-file -t FETCH_HEAD
#   commit
```

なので**スイートが通るとは主張しない**。上の tithe check は 5 モジュール中
1 本を見ているだけで、代わりにはならない。

## 7. deploy

```bash
cd "$REPO/appview/etzhayyim-wasm-crowdfunding-cf0und1n"
npx wrangler deploy
```

**ただし route が指すホストは解決しない**（2026-08-18 実測、`dig +short` が
いずれも空）:

| ホスト | 結果 |
|---|---|
| `cf0und1n.etzhayyim.com`（唯一の declared route） | NXDOMAIN |
| `crowdfunding.etzhayyim.com`（`CLAUDE.md` が名乗る URL） | NXDOMAIN。どの wrangler 設定にも無い |
| `mcp.etzhayyim.com`（中継先） | NXDOMAIN |

deploy が成功しても誰も到達できない。`/xrpc/` の中継先も同様なので、到達できた
としても中継は **502 を返す**（成功と同じ形で隠さない）。

superproject の deploy guard は `origin/main` を含む checkout からの deploy しか
許さない点も併せて注意。**この移行では deploy していない。**

## 8. ここに無いもの

- **新しい capability への接続。** この appview は移行前と同じく MCP router へ
  中継するだけで、`kotoba-lang/crowdfunding` には繋がっていない。繋ぐのは別の決定
- `MIGRATION-TODO.md` の substrate-boundary レビュー（移行が変えたのは言語で
  あって substrate posture ではない）
- ドメイン実装そのもの（`kotoba-lang/crowdfunding` と cloud-itonami の 4 actor にある）
