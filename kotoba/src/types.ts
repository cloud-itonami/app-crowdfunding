/**
 * crowdfunding kotoba — record types.
 *
 * Tier-2 function-split per ADR-2606011400 (on-chain-only). On-chain
 * crowdfunding: campaigns + pledges on AT PDS records, pledges settle on-chain
 * (USDC + ERC-4337 + TitheRouter 10% Public-Fund split). No Stripe, no RW.
 * ADR-2605172000 kotoba.
 *
 * Amounts are USDC base units (micros) as decimal STRINGS — AT Lexicon has no
 * float and bigint is not JSON-serializable.
 *
 * Identity hierarchy:
 *   did:web:crowdfunding.etzhayyim.com                          — controller
 *   did:web:crowdfunding.etzhayyim.com:campaign:{campaignId}    — a campaign
 */

export const CF_DID_PREFIX = "did:web:crowdfunding.etzhayyim.com:" as const;

export const CAMPAIGN_COLLECTION = "com.etzhayyim.apps.crowdfunding.campaign";
export const PLEDGE_COLLECTION = "com.etzhayyim.apps.crowdfunding.pledge";
export const PAYMENT_COLLECTION = "com.etzhayyim.apps.crowdfunding.payment";

export type CampaignStatus =
  | "draft"
  | "active"
  | "funded"
  | "failed"
  | "cancelled";

/**
 * Allowed on-chain pledge purpose. A pledge to an etzhayyim campaign is a
 * `donation` (the titheable purpose — 10% Public Fund auto-split). Reward-bearing
 * pledges between Adherents use `internal-purchase` (SBT↔SBT carve-out).
 */
export type PledgePurpose = "donation" | "internal-purchase";

export type PledgeStatus = "pending" | "funded" | "refunded";

export interface Reward {
  rewardId: string;
  title: string;
  /** Minimum pledge to claim, USDC micros as string. */
  minPledgeMicros: string;
  /** Optional claim cap. */
  limit?: number;
  claimed?: number;
}

export interface CampaignRecord {
  did: string;
  campaignId: string;
  creatorDid: string;
  title: string;
  summary?: string;
  /** Funding goal, USDC micros as string. */
  goalMicros: string;
  /** Gross pledged so far (toward goal), USDC micros as string. */
  raisedMicros: string;
  backerCount: number;
  rewards: Reward[];
  /** ISO deadline. */
  deadline?: string;
  status: CampaignStatus;
  createdAt: string;
}

export interface CampaignView extends CampaignRecord {
  campaignUri: string;
}

export interface CreateCampaignInput {
  campaignId: string;
  creatorDid: string;
  title: string;
  goalMicros: string;
  summary?: string;
  rewards?: Reward[];
  deadline?: string;
}

export interface CreateCampaignOutput {
  status: "created" | "alreadyExists" | "rejected";
  campaignUri?: string;
  did?: string;
  campaignId?: string;
  error?: string;
}

export interface GetCampaignInput {
  campaignId: string;
}

export interface GetCampaignOutput {
  campaign?: CampaignView;
  error?: string;
}

export interface ListCampaignsInput {
  status?: CampaignStatus;
  creatorDid?: string;
  limit?: number;
  cursor?: string;
}

export interface ListCampaignsOutput {
  items: CampaignView[];
  cursor?: string;
  total: number;
}

// ─── Pledge tier ────────────────────────────────────────────────────

export interface PledgeRecord {
  did: string;
  pledgeId: string;
  campaignId: string;
  backerDid: string;
  rewardId?: string;
  /** Pledge amount, USDC micros as string. */
  amountMicros: string;
  purpose: PledgePurpose;
  status: PledgeStatus;
  txHash?: string;
  createdAt: string;
}

export interface PledgeView extends PledgeRecord {
  pledgeUri: string;
}

export interface CreatePledgeInput {
  pledgeId: string;
  campaignId: string;
  backerDid: string;
  amountMicros: string;
  rewardId?: string;
  purpose?: PledgePurpose;
}

export interface CreatePledgeOutput {
  status: "created" | "alreadyExists" | "rejected" | "campaignNotFound";
  pledgeUri?: string;
  did?: string;
  pledgeId?: string;
  error?: string;
}

export interface GetPledgeInput {
  pledgeId: string;
}

export interface GetPledgeOutput {
  pledge?: PledgeView;
  error?: string;
}

// ─── On-chain settlement ────────────────────────────────────────────

export interface PaymentRecord {
  pledgeId: string;
  campaignId: string;
  backerDid: string;
  purpose: PledgePurpose;
  grossMicros: string;
  /** 10% tithe to the Public Fund (donations), USDC micros as string. */
  titheMicros: string;
  netMicros: string;
  txHash?: string;
  settledAt: string;
}

/**
 * Injected on-chain settlement executor. Real deployments wrap
 * `@etzhayyim/sdk/donate` `donate({ to, amountUsdc, purpose })`; tests inject a
 * fake. The only value-transfer seam (ADR-2605172100).
 */
export interface SettlementExecutor {
  (opts: {
    to: string;
    amountMicros: bigint;
    purpose: PledgePurpose;
    memo?: string;
    forUri?: string;
  }): Promise<{ txHash: string }>;
}

export interface SettlePledgeInput {
  pledgeId: string;
  /** Campaign payout address (Base L2). */
  to: string;
  memo?: string;
}

export interface SettlePledgeOutput {
  status: "settled" | "rejected" | "notFound" | "alreadyFunded";
  paymentUri?: string;
  txHash?: string;
  titheMicros?: string;
  netMicros?: string;
  campaignStatus?: CampaignStatus;
  error?: string;
}

// ─── Helpers ────────────────────────────────────────────────────────

export function campaignDid(campaignId: string): string {
  return `${CF_DID_PREFIX}campaign:${campaignId.toLowerCase()}`;
}

export function campaignRkey(campaignId: string): string {
  return `campaign-${campaignId.toLowerCase()}`;
}

export function pledgeDid(pledgeId: string): string {
  return `${CF_DID_PREFIX}pledge:${pledgeId.toLowerCase()}`;
}

export function pledgeRkey(pledgeId: string): string {
  return `pledge-${pledgeId.toLowerCase()}`;
}

export function paymentRkey(pledgeId: string): string {
  return `payment-${pledgeId.toLowerCase()}`;
}
