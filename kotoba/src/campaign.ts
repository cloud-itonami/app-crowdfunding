/**
 * crowdfunding kotoba — campaign tier.
 *
 * Campaigns on AT PDS records (no RW). createCampaign / getCampaign /
 * listCampaigns. Raised total + backerCount are maintained by settlePledge
 * (pledge.ts) as on-chain pledges settle.
 */

import type { Etzhayyim } from "@etzhayyim/sdk";
import {
  CAMPAIGN_COLLECTION,
  campaignDid,
  campaignRkey,
  type CampaignRecord,
  type CampaignView,
  type CreateCampaignInput,
  type CreateCampaignOutput,
  type GetCampaignInput,
  type GetCampaignOutput,
  type ListCampaignsInput,
  type ListCampaignsOutput,
} from "./types.js";
import { parseMicros } from "./tithe.js";

/** Create a campaign (idempotent on campaignId, status=active). */
export async function createCampaign(
  e: Etzhayyim,
  input: CreateCampaignInput
): Promise<CreateCampaignOutput> {
  if (!input.campaignId || !input.creatorDid || !input.title) {
    return { status: "rejected", error: "missingRequiredFields" };
  }
  try {
    if (parseMicros(input.goalMicros) <= 0n) {
      return { status: "rejected", error: "goalMustBePositive" };
    }
    for (const r of input.rewards ?? []) parseMicros(r.minPledgeMicros);
  } catch {
    return { status: "rejected", error: "invalidMicros" };
  }

  const rkey = campaignRkey(input.campaignId);
  const existing = await e
    .read<CampaignRecord>({ collection: CAMPAIGN_COLLECTION, rkey })
    .catch(() => ({ records: [] }));
  if (existing.records[0]?.value) {
    return {
      status: "alreadyExists",
      campaignUri: existing.records[0].uri,
      did: existing.records[0].value.did,
      campaignId: input.campaignId,
    };
  }

  const did = campaignDid(input.campaignId);
  const record: CampaignRecord = {
    did,
    campaignId: input.campaignId,
    creatorDid: input.creatorDid,
    title: input.title,
    summary: input.summary,
    goalMicros: input.goalMicros,
    raisedMicros: "0",
    backerCount: 0,
    rewards: input.rewards ?? [],
    deadline: input.deadline,
    status: "active",
    createdAt: new Date().toISOString(),
  };
  const receipt = await e.write({
    collection: CAMPAIGN_COLLECTION,
    record: record as unknown as Record<string, unknown>,
    rkey,
  });
  return { status: "created", campaignUri: receipt.uri, did, campaignId: input.campaignId };
}

export async function getCampaign(
  e: Etzhayyim,
  input: GetCampaignInput
): Promise<GetCampaignOutput> {
  if (!input.campaignId) return { error: "invalidCampaignId" };
  const resp = await e
    .read<CampaignRecord>({
      collection: CAMPAIGN_COLLECTION,
      rkey: campaignRkey(input.campaignId),
    })
    .catch(() => ({ records: [] }));
  const r = resp.records[0];
  if (!r) return { error: "notFound" };
  return { campaign: { ...r.value, campaignUri: r.uri } };
}

export async function listCampaigns(
  e: Etzhayyim,
  input: ListCampaignsInput = {}
): Promise<ListCampaignsOutput> {
  const limit = Math.min(input.limit ?? 50, 200);
  const resp = await e.read<CampaignRecord>({
    collection: CAMPAIGN_COLLECTION,
    cursor: input.cursor,
    limit,
  });
  const items: CampaignView[] = resp.records
    .filter((r) => {
      const v = r.value;
      if (input.status && v.status !== input.status) return false;
      if (input.creatorDid && v.creatorDid !== input.creatorDid) return false;
      return true;
    })
    .map((r) => ({ ...r.value, campaignUri: r.uri }));
  return { items, cursor: resp.cursor, total: items.length };
}
