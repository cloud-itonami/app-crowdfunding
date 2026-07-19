# crowdfunding.etzhayyim.com — AI Agent-Driven Crowdfunding Platform (D2C OEM)

**URL**: `https://crowdfunding.etzhayyim.com`

## Architecture

AI Agent が運営するクラウドファンディングプラットフォーム。okaimono.etzhayyim.com (D2C EC) の上流に位置し、OEM 製品の先行販売・市場検証・資金調達を担う。プロジェクト作成→支援募集→目標達成→okaimono.etzhayyim.com で量産・出荷の一気通貫パイプライン。

## Product Concept

| 原則 | 詳細 |
|---|---|
| **OEM D2C 専用** | okaimono.etzhayyim.com 同様、自社ブランド OEM 製品のみ。外部マーケットプレイス仕入・転売禁止 |
| **All-or-Nothing + Flex** | 目標未達 = 全額返金 (All-or-Nothing) or 目標なし受付 (Flex) の2モード |
| **AI Agent 自律運営** | プロジェクト作成・リワード設計・進捗報告・支援者コミュニケーション・工場発注を AI Agent が実行 |
| **okaimono 連携** | 目標達成 → okaimono.etzhayyim.com catalog 自動登録 → CTO 製造 → 出荷 |
| **kakin/credits 決済** | kakin.etzhayyim.com (billing) + credits.etzhayyim.com (credit ledger) + stripe.etzhayyim.com (Stripe Issuing) 統合 |

## Campaign Lifecycle

```
1. Draft
   → AI Agent がプロジェクト企画 (BOM 分析、価格設定、リワード設計)
   → プロジェクトページ生成 (画像・動画・スペック・FAQ)

2. Review
   → compliance チェック (product-safety, 景表法, 特商法)
   → moderator.etzhayyim.com 承認

3. Live
   → 支援募集開始 (期間: 30/60/90 days)
   → リアルタイム進捗 (支援額・支援者数・達成率)
   → stretch goal 自動追加 (AI 判定)
   → 支援者向け update 投稿 (AppBskyFeedPost)

4. Funded (All-or-Nothing: 目標達成時 / Flex: 期間終了時)
   → kakin.etzhayyim.com で支援者決済確定
   → okaimono.etzhayyim.com に catalog 自動登録 (fulfillment_mode: "bto")
   → tsukuru.etzhayyim.com に OEM 製造発注

5. Failed (All-or-Nothing: 目標未達時)
   → 全額返金処理 (kakin.etzhayyim.com → stripe.etzhayyim.com refund)
   → プロジェクト archive

6. Fulfillment
   → okaimono.etzhayyim.com fulfillment pipeline で出荷
   → 支援者へ tracking 通知
   → 完了報告 (AppBskyFeedPost)
```

## Domain Model

### Campaign (プロジェクト)

| Field | Type | Description |
|---|---|---|
| `id` | string | campaign nanoid |
| `title` | string | プロジェクト名 |
| `description` | string | 詳細説明 (Markdown) |
| `creatorDid` | string | 作成者 DID (AI Agent or Human) |
| `goalAmount` | number | 目標金額 (JPY) |
| `currentAmount` | number | 現在の支援総額 |
| `backerCount` | number | 支援者数 |
| `mode` | enum | `allOrNothing` / `flex` |
| `status` | enum | `draft` / `review` / `live` / `funded` / `failed` / `fulfillment` / `completed` |
| `startAt` | string | 募集開始日時 (ISO 8601) |
| `endAt` | string | 募集終了日時 |
| `rewards` | Reward[] | リワード一覧 |
| `stretchGoals` | StretchGoal[] | stretch goal 一覧 |
| `productSkuId` | string | okaimono SKU ID (funded 後に紐付け) |

### Reward (リワード)

| Field | Type | Description |
|---|---|---|
| `id` | string | reward nanoid |
| `title` | string | リワード名 |
| `description` | string | 内容説明 |
| `price` | number | 支援金額 (JPY) |
| `limit` | number | 数量制限 (0 = 無制限) |
| `claimed` | number | 支援済み数 |
| `estimatedDelivery` | string | 配送予定 (YYYY-MM) |
| `ctoOptions` | string[] | CTO option IDs (keyboard 等の configurator 連携) |

### Pledge (支援)

| Field | Type | Description |
|---|---|---|
| `id` | string | pledge nanoid |
| `campaignId` | string | campaign ID |
| `rewardId` | string | reward ID |
| `backerDid` | string | 支援者 DID |
| `amount` | number | 支援金額 |
| `status` | enum | `pending` / `confirmed` / `refunded` |
| `paymentIntentId` | string | kakin.etzhayyim.com payment intent ID |

## Write-Only Derived Architecture

**Handler は write のみ。social post / cross-actor invoke (→tsukuru 製造発注) / backer notification は PDS commit pipeline の derive rule で自動導出。**

| Data | Storage | Reason |
|---|---|---|
| campaign, reward, campaignUpdate | Repo (public) | 公開カタログ・告知情報 |
| pledge | **Preferences (private)** | backerDid + 支援金額 = PII + 金融情報 |
| campaign status transition | Repo (public) | derive rule → auto social post + auto tsukuru invoke |

Derive rules: `kotodama.jsonld` `"derive"` section。設計: `90-docs/260407-write-only-derived-architecture-design.md`

### cross-actor (all via derive rules, no explicit invoke)

| Trigger | Target | Method | Condition |
|---|---|---|---|
| campaign `status: "fulfillment"` | tsukuru.etzhayyim.com | `createProductionOrder` | funded → 製造発注 |
| tsukuru `production_progress` | (social derive) | auto post | `productionOrderId: "cf_*"` |
| tsukuru `quality_inspection` pass | (social derive) | auto post | QC 完了通知 |

## Component

| Component | nanoid | 役割 |
|---|---|---|
| `etzhayyim-wasm-crowdfunding-cf0und1n` | `cf0und1n` | Campaign management + cross-actor product launch |

## Actor Composition

| Actor DID | Role |
|---|---|
| `did:web:crowdfunding.etzhayyim.com` | controller — platform management |
| `did:web:crowdfunding.etzhayyim.com:actor:campaignManager` | campaign lifecycle (draft→live→funded→fulfillment) |
| `did:web:crowdfunding.etzhayyim.com:actor:backerRelations` | 支援者コミュニケーション・update・FAQ |
| `did:web:crowdfunding.etzhayyim.com:actor:analyst` | 市場分析・価格設定・stretch goal 判定 |
| `did:web:crowdfunding.etzhayyim.com:actor:compliance` | 景表法・特商法・product-safety チェック |

## Domain WIT (Lexicon)

**AT Lexicon namespace**: `com.etzhayyim.apps.crowdfunding.*`

| WIT interface | Lexicon prefix | Record kinds |
|---|---|---|
| `campaign` | `com.etzhayyim.apps.crowdfunding.campaign` | campaign lifecycle |
| `reward` | `com.etzhayyim.apps.crowdfunding.reward` | reward definition |
| `pledge` | `com.etzhayyim.apps.crowdfunding.pledge` | backer pledge |
| `update` | `com.etzhayyim.apps.crowdfunding.campaignUpdate` | project update posts |
| `analytics` | `com.etzhayyim.apps.crowdfunding.analyticsEvent` | KPI events |

## Integration

| Service | 連携内容 |
|---|---|
| **okaimono.etzhayyim.com** | funded → catalog 自動登録 + fulfillment |
| **kakin.etzhayyim.com** | 決済 intent 作成・確定・返金 |
| **credits.etzhayyim.com** | Murakumo credit での支援 |
| **stripe.etzhayyim.com** | カード決済・返金処理 |
| **tsukuru.etzhayyim.com** | OEM 製造発注 (funded 後) |
| **moderator.etzhayyim.com** | campaign review 承認 |
| **keyboard.etzhayyim.com** | KB-SPLIT キーボードの crowdfunding campaign (first product) |

## Sales Channel

**crowdfunding.etzhayyim.com 自体が販売チャネル。** funded 後は okaimono.etzhayyim.com に移行。

- D2C 専売 (外部クラウドファンディングサイト不使用)
- OEM 製品のみ (外部仕入・転売禁止)
- 支援金は kakin.etzhayyim.com がエスクロー管理

## Contract

`contract-category: service-agreement` (クラウドファンディングプラットフォーム利用規約 + 特定商取引法 + 資金決済法)

## First Campaign: KB-SPLIT Keyboard

keyboard.etzhayyim.com の KB-SPLIT-60-FIDO を crowdfunding first product として投入。

| 項目 | 値 |
|---|---|
| **目標金額** | ¥5,000,000 (500万円 ≈ 200台 × ¥24,800) |
| **期間** | 60 days |
| **モード** | All-or-Nothing |
| **Early Bird** | ¥19,800 (先着50台, FIDO2込み, 20% OFF) |
| **Standard** | ¥24,800 (KB-SPLIT-60-FIDO) |
| **Premium** | ¥32,800 (CNC アルミフレーム + Cherry MX) |
| **Stretch ¥10M** | 全 backer に磁気リストレスト追加 |
| **Stretch ¥20M** | 75% layout モデル追加 |
