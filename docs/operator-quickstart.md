# Operator quickstart — app-crowdfunding

This one is unusual in its family: the domain code is real, verifiable and careful, and
the two things worth checking before trusting the repository are about **naming and
serving**, not about the arithmetic.

Steps marked ✅ were run on 2026-08-16. §5 says what was not walked.

---

## 1. ✅ The tithe arithmetic holds, and it can be verified with nothing installed

`kotoba/src/tithe.ts` computes the 10% Public-Fund split. Its own header states the
contract:

> The 10% tithe to the Public Fund is an immutable constitutional constant
> (ADR-2605192100 §2). On-chain this is enforced atomically by TitheRouter.sol; these
> pure helpers compute the same split client-side so the pledge payment record matches
> what the contract will execute.
> … Integer division floors the tithe; the remainder accrues to the net side, so
> tithe + net === gross exactly with no rounding leak.

That is four checkable claims, and the module imports nothing — so it can be exercised
directly, without the SDK and without `npm install` (which does not work here, §5):

```bash
cat > /tmp/tithe-check.mts <<'EOF'
import { splitTithe, parseMicros, TITHE_PERMILLE } from "<abs path>/kotoba/src/tithe.ts";
let pass = 0, fail = 0;
const ok = (c: boolean, m: string) => { c ? pass++ : (fail++, console.log("  FAIL " + m)); };
ok(TITHE_PERMILLE === 100n, "100 permille");
for (const g of [0n, 1n, 9n, 10n, 11n, 999n, 1000n, 1000001n, 123456789n, 10n ** 18n]) {
  const s = splitTithe(g);
  ok(s.tithe + s.net === s.gross, `tithe+net===gross for ${g}`);
  ok(s.tithe === (g * 100n) / 1000n, `tithe floors for ${g}`);
}
ok(splitTithe(9n).tithe === 0n, "9 micros yields 0 tithe (floored, no leak)");
try { splitTithe(-1n); ok(false, "negative must throw"); }
catch (e) { ok(e instanceof RangeError, "negative throws RangeError"); }
for (const bad of ["1.5", "-1", "1e6", "", " 1", "0x10"]) {
  try { parseMicros(bad); ok(false, `must reject ${bad}`); }
  catch (e) { ok(e instanceof TypeError, `rejects ${JSON.stringify(bad)}`); }
}
console.log(`  ${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
EOF
node --experimental-strip-types /tmp/tithe-check.mts
#   40 passed, 0 failed
```

All four claims hold, including the one that matters most for money: `tithe + net`
equals `gross` exactly at every value tried, from 0 to 10^18 micros, and 9 micros yields
a tithe of 0 rather than 1 — floored, so nothing is created by rounding.

**The check was proved load-bearing before being written down.** On a copy of the module,
changing the floor to a round (`+ 500n` before the divide) fails 7 assertions, and making
`parseMicros` accept anything fails 6; the unmodified module passes 40 of 40. A check
that cannot fail is not evidence.

Two further facts about how value actually moves, from `kotoba/src/settlement.ts`:

> Per ADR-2605172100, this is the only place value transfer is initiated, and it goes
> through the SDK — never a direct viem/USDC call from app code.

and it wraps the SDK's `donate()`, which "routes the USDC transfer through TitheRouter
(10% Public-Fund auto-split for donations)". So `tithe.ts` is a *mirror* of what the
contract does, kept so the recorded pledge matches the executed transfer — which is
exactly why its floor behaviour has to match, and now demonstrably does.

## 2. ⚠ The URL in `CLAUDE.md` is not declared anywhere

`CLAUDE.md` opens with:

> **URL**: `https://crowdfunding.etzhayyim.com`

Nothing serves that name, as far as this workspace can say:

```bash
grep -rl 'crowdfunding\.etzhayyim\.com' orgs/*/*/appview/*/wrangler.jsonc orgs/*/*/wrangler.jsonc
#   (nothing)

python3 -c "import json,io;print([r['pattern'] for r in json.load(io.open('appview/etzhayyim-wasm-crowdfunding-cf0und1n/wrangler.jsonc'))['routes']])"
#   ['cf0und1n.etzhayyim.com/*']
```

and the workspace's surface index has **zero rows** whose host contains
`crowdfunding`, against one row for `cf0und1n.etzhayyim.com`. So the documented URL is
aspirational or stale, and the only declared address is the nanoid one. Anyone told to
"check the crowdfunding site" needs `cf0und1n.etzhayyim.com`.

The single surface row is also attributed to `orgs/etzhayyim/com-etzhayyim-app-crowdfunding`
— the **pre-rename** path. That repository is not in `manifest/west.yml`; this one is.
The index predates the org move, so a reader tracing the host back lands on an orphan.

## 3. ✅ There is no undeployed facade here — unlike the siblings

Several migrated appviews in this family keep a large `src/app.ts` that `wrangler.jsonc`
does not deploy (`cloud-itonami/app-cowork` has an 882-line Microsoft Graph
implementation in exactly that position). **This repository has no `src/app.ts` at all:**

```bash
ls appview/etzhayyim-wasm-crowdfunding-cf0und1n/
#   kotodama.jsonld  svelte  wrangler.jsonc
```

So the 60-line generic handler at `svelte/src/routes/xrpc/[...path]/+server.ts` — which
forwards any NSID to `AGENTGATEWAY_MCP_ROUTER_URL` as a JSON-RPC `tools/call` — is the
only handler, and there is no second, more capable file to mistake for it. The domain
code in `kotoba/` (646 lines across campaign, pledge, settlement, tithe and types) is
consumed by whatever serves those MCP tools, not by this worker.

That is a cleaner arrangement than the siblings', and worth saying so: the absence here
is the good case.

## 4. ✅ The landing page said it had no routes and no vars — fixed by script

The embedded summary in `appview/*/svelte/src/routes/+page.svelte` claimed
`routeCount: 0, routes: [], vars: []` while rendering "No public route is declared next
to this app surface" at the address wrangler declares. It now carries the real values,
written by the superproject's fixer rather than by hand:

```bash
nbb --classpath ".:scripts/nbb_compat" scripts/fix-appview-page-summary.cljs \
  --root <root> --only orgs/cloud-itonami/app-crowdfunding --write
#   WROTE orgs/cloud-itonami/app-crowdfunding -- routeCount 0->1, vars 0->9,
#         relativePath -> appview/etzhayyim-wasm-crowdfunding-cf0und1n/svelte/src/routes/+page.svelte
```

Note `routeCount 1`, where every other repository in the family has 2 — this app has no
human-readable alias route, which is the same fact as §2 seen from the config side.

There is still no generator wired into a build step, so a future `wrangler.jsonc` edit
will not update the page: rerun the fixer, or `verify-appview-page-summary.cljs` will
list it again.

## 5. ⚠ NOT WALKED: the vitest suite

`kotoba/` has 10 tests in 143 lines over 646 lines of source. They do not install:

```bash
cd kotoba && npm install
#   npm error code EALLOWSCRIPTS
#   npm error --allow-scripts is not allowed in project-scoped installs.
```

Both dependencies are git URLs (`@etzhayyim/sdk`, `@etzhayyim/sdk-mock`) whose
preparation runs a nested install that npm 11.16 refuses. So the suite is not claimed to
pass — and note that §1 is **not** a substitute for it: §1 exercised one module of five,
chosen because it imports nothing. `campaign.ts`, `pledge.ts` and `settlement.ts` all
import from `./types.js` or the SDK and were not run.

## 6. What the maturity instrument sees ✅

```
· orgs/cloud-itonami/app-crowdfunding  own=0.049  axis-docs=0bp → +2500bp
    ⚠ README が .md ではないので docs の README 成分は 0（README.edn 等が 1 件）
    ⚠ taxonomy に :repo/kind の行が無い → :default の重みで採点されている
```

Both are about the instrument: `README.edn` declares `:canonical-metadata :edn`, and
there is no row in `manifest/repo-taxonomy.edn`, so this `own` is computed against a
guessed weight profile (ADR-2608052000). `axis-substrate` also reads 0 while `kotoba/`
holds 646 lines, because the counter looks only at a top-level `src/`.
