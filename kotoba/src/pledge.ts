/**
 * crowdfunding kotoba — pledge + on-chain settlement tier.
 *
 * createPledge writes a pledge (status pending). settlePledge performs on-chain
 * USDC settlement via an injected SettlementExecutor (real deployments wrap
 * `@etzhayyim/sdk/donate` → TitheRouter 10% Public-Fund split), writes a payment
 * record, marks the pledge funded, and updates the campaign's raised total +
 * backerCount (flipping it to `funded` once the goal is met).
 *
 * No Stripe, no RW. The only value-transfer seam is the injected executor
 * (ADR-2605172100: app code never calls viem/USDC directly).
 */

import type { Etzhayyim } from "@etzhayyim/sdk";
import {
  CAMPAIGN_COLLECTION,
  PAYMENT_COLLECTION,
  PLEDGE_COLLECTION,
  campaignRkey,
  pledgeDid,
  pledgeRkey,
  paymentRkey,
  type CampaignRecord,
  type CreatePledgeInput,
  type CreatePledgeOutput,
  type GetPledgeInput,
  type GetPledgeOutput,
  type PaymentRecord,
  type PledgeRecord,
  type SettlementExecutor,
  type SettlePledgeInput,
  type SettlePledgeOutput,
} from "./types.js";
import { parseMicros, splitTithe } from "./tithe.js";

async function readCampaign(
  e: Etzhayyim,
  campaignId: string
): Promise<{ value: CampaignRecord; uri: string } | null> {
  const resp = await e
    .read<CampaignRecord>({
      collection: CAMPAIGN_COLLECTION,
      rkey: campaignRkey(campaignId),
    })
    .catch(() => ({ records: [] }));
  const r = resp.records[0];
  return r ? { value: r.value, uri: r.uri } : null;
}

/** Create a pledge against an active campaign (idempotent on pledgeId). */
export async function createPledge(
  e: Etzhayyim,
  input: CreatePledgeInput
): Promise<CreatePledgeOutput> {
  if (!input.pledgeId || !input.campaignId || !input.backerDid) {
    return { status: "rejected", error: "missingRequiredFields" };
  }
  let amount: bigint;
  try {
    amount = parseMicros(input.amountMicros);
  } catch {
    return { status: "rejected", error: "invalidAmount" };
  }
  if (amount <= 0n) return { status: "rejected", error: "amountMustBePositive" };

  const campaign = await readCampaign(e, input.campaignId);
  if (!campaign) return { status: "campaignNotFound", error: "campaignNotFound" };
  if (campaign.value.status !== "active") {
    return { status: "rejected", error: `campaignNotActive:${campaign.value.status}` };
  }

  const rkey = pledgeRkey(input.pledgeId);
  const existing = await e
    .read<PledgeRecord>({ collection: PLEDGE_COLLECTION, rkey })
    .catch(() => ({ records: [] }));
  if (existing.records[0]?.value) {
    return {
      status: "alreadyExists",
      pledgeUri: existing.records[0].uri,
      did: existing.records[0].value.did,
      pledgeId: input.pledgeId,
    };
  }

  const did = pledgeDid(input.pledgeId);
  const record: PledgeRecord = {
    did,
    pledgeId: input.pledgeId,
    campaignId: input.campaignId,
    backerDid: input.backerDid,
    rewardId: input.rewardId,
    amountMicros: input.amountMicros,
    purpose: input.purpose ?? "donation",
    status: "pending",
    createdAt: new Date().toISOString(),
  };
  const receipt = await e.write({
    collection: PLEDGE_COLLECTION,
    record: record as unknown as Record<string, unknown>,
    rkey,
  });
  return { status: "created", pledgeUri: receipt.uri, did, pledgeId: input.pledgeId };
}

export async function getPledge(
  e: Etzhayyim,
  input: GetPledgeInput
): Promise<GetPledgeOutput> {
  if (!input.pledgeId) return { error: "invalidPledgeId" };
  const resp = await e
    .read<PledgeRecord>({
      collection: PLEDGE_COLLECTION,
      rkey: pledgeRkey(input.pledgeId),
    })
    .catch(() => ({ records: [] }));
  const r = resp.records[0];
  if (!r) return { error: "notFound" };
  return { pledge: { ...r.value, pledgeUri: r.uri } };
}

/**
 * Settle a pending pledge on-chain. Executes the USDC transfer through the
 * injected SettlementExecutor (donation → TitheRouter 10% split), writes a
 * payment record, marks the pledge funded, and updates the campaign raised
 * total + backerCount (→ `funded` when goal met).
 */
export async function settlePledge(
  e: Etzhayyim,
  settle: SettlementExecutor,
  input: SettlePledgeInput
): Promise<SettlePledgeOutput> {
  if (!input.pledgeId || !input.to) {
    return { status: "rejected", error: "missingRequiredFields" };
  }

  const presp = await e
    .read<PledgeRecord>({
      collection: PLEDGE_COLLECTION,
      rkey: pledgeRkey(input.pledgeId),
    })
    .catch(() => ({ records: [] }));
  const pledgeRec = presp.records[0];
  if (!pledgeRec?.value) return { status: "notFound", error: "pledgeNotFound" };
  const pledge = pledgeRec.value;
  if (pledge.status !== "pending") {
    return pledge.status === "funded"
      ? { status: "alreadyFunded", error: "pledgeAlreadyFunded" }
      : { status: "rejected", error: `pledgeNotPending:${pledge.status}` };
  }

  const campaign = await readCampaign(e, pledge.campaignId);
  if (!campaign) return { status: "notFound", error: "campaignNotFound" };

  const split = splitTithe(parseMicros(pledge.amountMicros));

  // Sole value-transfer seam. donation → TitheRouter 10% auto-split.
  const { txHash } = await settle({
    to: input.to,
    amountMicros: split.gross,
    purpose: pledge.purpose,
    memo: input.memo,
    forUri: pledgeRec.uri,
  });

  const payment: PaymentRecord = {
    pledgeId: pledge.pledgeId,
    campaignId: pledge.campaignId,
    backerDid: pledge.backerDid,
    purpose: pledge.purpose,
    grossMicros: split.gross.toString(),
    titheMicros: split.tithe.toString(),
    netMicros: split.net.toString(),
    txHash,
    settledAt: new Date().toISOString(),
  };
  const payReceipt = await e.write({
    collection: PAYMENT_COLLECTION,
    record: payment as unknown as Record<string, unknown>,
    rkey: paymentRkey(pledge.pledgeId),
  });

  // Mark pledge funded.
  await e.write({
    collection: PLEDGE_COLLECTION,
    record: { ...pledge, status: "funded", txHash } as unknown as Record<string, unknown>,
    rkey: pledgeRkey(pledge.pledgeId),
  });

  // Update campaign raised + backerCount; flip to funded if goal met.
  const raised = parseMicros(campaign.value.raisedMicros) + split.gross;
  const goal = parseMicros(campaign.value.goalMicros);
  const nextStatus =
    campaign.value.status === "active" && raised >= goal ? "funded" : campaign.value.status;
  const updatedCampaign: CampaignRecord = {
    ...campaign.value,
    raisedMicros: raised.toString(),
    backerCount: campaign.value.backerCount + 1,
    status: nextStatus,
  };
  await e.write({
    collection: CAMPAIGN_COLLECTION,
    record: updatedCampaign as unknown as Record<string, unknown>,
    rkey: campaignRkey(pledge.campaignId),
  });

  return {
    status: "settled",
    paymentUri: payReceipt.uri,
    txHash,
    titheMicros: payment.titheMicros,
    netMicros: payment.netMicros,
    campaignStatus: nextStatus,
  };
}
