/**
 * crowdfunding kotoba — barrel.
 *
 * Tier-2 on-chain crowdfunding on the etzhayyim substrate (AT PDS records +
 * on-chain USDC settlement via TitheRouter). Per ADR-2606011400 (on-chain-only).
 * No Stripe, no RW.
 *
 *   campaign : createCampaign / getCampaign / listCampaigns
 *   pledge   : createPledge / getPledge / settlePledge
 *   tithe    : splitTithe / parseMicros
 *   settle   : donateSettlementExecutor (real on-chain adapter)
 */

export * from "./types.js";
export { splitTithe, parseMicros, TITHE_PERMILLE, type TitheSplit } from "./tithe.js";
export { createCampaign, getCampaign, listCampaigns } from "./campaign.js";
export { createPledge, getPledge, settlePledge } from "./pledge.js";
export { donateSettlementExecutor } from "./settlement.js";
