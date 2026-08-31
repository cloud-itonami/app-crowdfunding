# claim-pipeline — campaign-claim-pipeline.v1

この directory は、`capital-observation/campaign-observation.v1`（PR #2）が定義する
観測 **schema 層** の上に、schema が規定していない **pipeline 層** を追加する:

source proposal → fetch receipt → parser admission → dedupe →
bounded retry/refusal → signed Hyakka claim proposal → readback → audit。

## これは何か / 何ではないか

- **何か**: app-crowdfunding が Hyakka（network-awai/app-hyakka）へ
  クラウドファンディング campaign の観測 claim を **提案** するための
  実行可能な bounded 契約。claim は署名付き proposal であり、
  署名は provenance の証明であって真実の主張ではない。公開の判定は
  Hyakka governance が行う。
- **何ではないか**: campaign を financing round・cash received・ownership・
  performance として扱わない（campaign-observation.v1 の entity 分離をそのまま
  引き継ぐ）。rank / score / valuation / suitability 等の導出値は
  構造的に禁じられる。

## pipeline の要点

- **forbid class は fetch 前に拒否**（search-snippet, scraped-directory,
  inferred-ownership 等）。
- receipt は verbatim bytes + sha256（PR #2 の receipt shape を無変更で再利用）。
- admission は全レコードについて明示的に admit / refuse（refusal code 付き）。
  黙って落とすレコードは無い。原文言語・識別子は verbatim 保存。
- dedupe key は `[:platform-namespace :campaign-id :asserted-kind
  :snapshot-at]` の決定論的キー。衝突時は first-wins で provenance を保持し
  refresh-history に追記（never overwrite）。
- retry は source あたり 3 回・指数 backoff。使い切ったら **記録された拒否** —
  placeholder の捏造は禁止。
- platform 主張の金額は `:amount-asserted-kind`（announced-target /
  claimed-progress / claimed-final）を保持し、単一の "raised" に潰さない。

## 検証

```bash
nbb tools/campaign_claim_fixtures.cljs   # exit 0 = 全 fixture pass
```

offline・決定論的（ネットワークアクセス無し）。exit 1 = 違反検出、
exit 2 = contract 読取失敗（REFUSED）。
