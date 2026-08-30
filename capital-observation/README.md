# capital-observation — campaign-observation.v1

この contract は、クラウドファンディング上の campaign（先行販売・市場検証・
資金調達シグナル）を **Hyakka に提案可能な監査可能な観測** として扱うための
bounded な契約である。`cloud-itonami/app-public-fund` の
`fund-close-observation.v1` と同じ原則（source receipts は verbatim bytes +
sha256、半開 UTC 窓、`missing-is-unmeasured`、append-only refresh history、
読み返しは必ず coverage + missingness を伴う）を、campaign の面に適用する。

## この契約が観測するもの

- campaign の存在、開始/進行/終了の **主張**（platform 自身のページにある通り）
- 金額は **主張の種類ごとに分離して運ぶ**（announced target / claimed
  progress / claimed final を決して折りたたまない）。欠けている金額は
  `:amount-not-stated` で旗が立ち、黙って消えない
- operating company / campaign / platform の **実体分離**
  （brand ≠ legal entity、platform は source であって participant ではない）

## この契約が観測しないもの（構造的に禁止）

rank、score、centrality、NAV、valuation（推定含む）、ownership、board
control、performance、success rate、investment suitability。これらの field
を派生観測が含んだ瞬間に fixture が fail する。

## この契約の epistemic boundary（抜粋）

- `:announced-target-is-not-cash-received` — 宣言額は入金ではない
- `:campaign-pledge-total-is-not-cash-in-bank` — 総 pledge 額は銀行残高ではない
- `:campaign-is-a-time-bounded-observation-not-a-permanent-class`
- `:missing-is-unmeasured` — 無いデータは 0 でも「無事象」でもない
- `:worldwide-is-a-coverage-goal-not-a-completeness-claim`

## 安全境界

投資助言なし、取引/配分なし、outreach/solicitation なし、資金約束なし、
個人の profiling なし。Hyakka への提案は監査可能な質問・観測・方法論の
メモに限り、disclaimer を必ず伴う。外部ソースは untrusted — robots /
認証 / WAF / CAPTCHA / licensing を尊重し、bypass は禁止。

## 検証（オフライン・決定論的・8 fixture）

```bash
nbb tools/capital_observation_fixtures.cljs .
```

exit 0 = 全 fixture pass、1 = 契約違反を検出、2 = 契約ファイルを読めない。
既存の `nbb scripts/verify-docs-claims.cljs .` と併用する（本 contract は
README や quickstart の数値には触れない）。
