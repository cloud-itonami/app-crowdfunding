# Migration TODO

**Status**: ✅ SUPERSEDED — 2026-07-27. The domain moved to
`kotoba-lang/crowdfunding` + four `cloud-itonami` actors
([ADR-2607268500](https://github.com/com-junkawasaki/root/blob/main/90-docs/adr/2607268500-crowdfunding-itonami-capability-and-actors.edn)).
This repo is kept for history and for its AT Protocol appview; **do not
extend the TypeScript domain here.**

## What happened to this code

The TRANSFORM codemod this file waited for since 2026-05-21 was never run,
and running it would have been the wrong move: the 646 lines of TypeScript
in `kotoba/src/` violate the workspace runtime priority (`kotoba wasm` >
`clojurewasm` > ClojureScript > nbb; no new raw TS), and the domain they
encoded was four concepts wide — campaign, pledge, settlement, tithe.

That domain has been reimplemented, in portable `.cljc`, at the scale the
rewards-crowdfunding category actually operates at:

| Was here | Is now |
|---|---|
| `types.ts` campaign/pledge records | `crowdfunding.campaign`, `crowdfunding.pledge`, `crowdfunding.reward` |
| `campaign.ts` create/get/list | `crowdfunding.campaign` + the `cloud-itonami-crowdfunding-campaign` actor |
| `pledge.ts` `createPledge` / `settlePledge` | `crowdfunding.pledge` and `crowdfunding.collection`, split across two actors — because a pledge is an *authorization* and charging it is a separate governed act |
| `tithe.ts` `splitTithe` (10%, floored, remainder to net) | `crowdfunding.fee` `:tithe-bps`, default 0 |
| `settlement.ts` `donateSettlementExecutor` | the rail-adapter seam, outside the actor; `crowdfunding.payout` computes and never transfers |

Plus everything that was missing: reward tier scarcity and add-ons,
per-destination shipping, the deadline collection window with retries and
drop-off, fee schedules, creator payout with holdbacks, launch review,
auditable discovery, and fulfilment accountability.

## The tithe survived, as an option

The 10% Public-Fund tithe was a constitutional constant of *this*
deployment (ADR-2605192100 §2). A general crowdfunding protocol must not
hard-code one operator's covenant — and that operator must not lose it.

So it is a fee-schedule option, defaulting to zero:

```clojure
(fee/fee-schedule {:platform-bps 0 :processing-bps 0 :tithe-bps 1000})
```

`crowdfunding`'s `fee_test.cljc` pins parity with `splitTithe` explicitly:
10% floored, remainder to the net side, `tithe + net = gross` exactly, for
every amount tested. If that ever drifts a test fails, rather than a
covenant quietly changing.

## What still needs doing in THIS repo

- [ ] Decide the fate of `appview/etzhayyim-wasm-crowdfunding-cf0und1n`
      (Svelte + XRPC). It is the only part not superseded — the itonami
      actors have no AT Protocol surface. Either point it at the new
      capability or retire it deliberately.
- [ ] The substrate-boundary checklist below is **moot for the domain
      code** (that no longer lives here) but still applies to the appview
      if it is kept.

## Original substrate-boundary checks (historical, appview only)

- [ ] Replace any `@atproto/api`, `viem`, raw IPFS client, `@noble/ciphers`,
      `@signalapp/libsignal-client` imports with `@etzhayyim/sdk`.
- [ ] Verify identity flow uses did:web:etzhayyim.com + did:plc + WebAuthn
      passkey + Adherent SBT. Remove server-issued JWTs without DID binding.
- [ ] Audit against Charter Rider v2.0 §2(a)-(h).

The 2026-05-21 codemod scan found none of Stripe / RisingWave / Kysely /
Prisma / Drizzle / GA4 / Meta Pixel / direct `@atproto/api` / direct `viem`
imports. The TRANSFORM classification came from the app's domain pattern,
not from detected violations.

## Reference

- Successor design: ADR-2607268500 (`com-junkawasaki/root`)
- Library: <https://github.com/kotoba-lang/crowdfunding>
- Actors: `cloud-itonami/cloud-itonami-crowdfunding-{campaign,pledge,collection,payout}`
- Constitution wave ADRs: ADR-2605192100 / 2605192115 / 2605192130 / 2605192200
