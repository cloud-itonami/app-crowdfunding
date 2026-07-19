import { describe, it, expect, beforeEach } from "vitest";
import { MockEtzhayyim } from "@etzhayyim/sdk-mock";
import {
  createCampaign,
  getCampaign,
  listCampaigns,
  createPledge,
  getPledge,
  settlePledge,
  splitTithe,
  parseMicros,
  type SettlementExecutor,
} from "../src/index.js";

const fakeSettle: SettlementExecutor = async () => ({ txHash: "0xfeed" });
const PAYOUT = "0x3333333333333333333333333333333333333333";

describe("crowdfunding kotoba", () => {
  let e: any;
  beforeEach(() => {
    e = new MockEtzhayyim({ did: "did:web:crowdfunding.etzhayyim.com" });
  });

  describe("tithe", () => {
    it("splits 10% with no leak", () => {
      const s = splitTithe(parseMicros("100000000")); // 100 USDC
      expect(s.tithe).toBe(10_000_000n);
      expect(s.net).toBe(90_000_000n);
      expect(s.tithe + s.net).toBe(s.gross);
    });
  });

  describe("createCampaign", () => {
    const c = {
      campaignId: "CMP-1",
      creatorDid: "did:web:alice.etzhayyim.com",
      title: "Build a well",
      goalMicros: "300000000", // 300 USDC
    };
    it("creates active with zero raised", async () => {
      const r = await createCampaign(e, c);
      expect(r.status).toBe("created");
      const got = await getCampaign(e, { campaignId: "CMP-1" });
      expect(got.campaign?.status).toBe("active");
      expect(got.campaign?.raisedMicros).toBe("0");
      expect(got.campaign?.backerCount).toBe(0);
    });
    it("rejects non-positive goal", async () => {
      const r = await createCampaign(e, { ...c, goalMicros: "0" });
      expect(r.status).toBe("rejected");
    });
    it("is idempotent", async () => {
      await createCampaign(e, c);
      expect((await createCampaign(e, c)).status).toBe("alreadyExists");
    });
  });

  describe("pledge + on-chain settlement", () => {
    beforeEach(async () => {
      await createCampaign(e, {
        campaignId: "CMP-1",
        creatorDid: "did:web:alice.etzhayyim.com",
        title: "Build a well",
        goalMicros: "300000000",
      });
    });
    it("creates a pending pledge (default purpose donation)", async () => {
      const r = await createPledge(e, {
        pledgeId: "PL-1",
        campaignId: "CMP-1",
        backerDid: "did:web:bob.etzhayyim.com",
        amountMicros: "120000000",
      });
      expect(r.status).toBe("created");
      const got = await getPledge(e, { pledgeId: "PL-1" });
      expect(got.pledge?.status).toBe("pending");
      expect(got.pledge?.purpose).toBe("donation");
    });
    it("rejects pledge to a missing campaign", async () => {
      const r = await createPledge(e, {
        pledgeId: "PL-X",
        campaignId: "NOPE",
        backerDid: "did:web:bob.etzhayyim.com",
        amountMicros: "1000000",
      });
      expect(r.status).toBe("campaignNotFound");
    });
    it("settles on-chain: tithe split + pledge funded + campaign raised", async () => {
      await createPledge(e, {
        pledgeId: "PL-1",
        campaignId: "CMP-1",
        backerDid: "did:web:bob.etzhayyim.com",
        amountMicros: "120000000",
      });
      const s = await settlePledge(e, fakeSettle, { pledgeId: "PL-1", to: PAYOUT });
      expect(s.status).toBe("settled");
      expect(s.titheMicros).toBe("12000000"); // 10% of 120
      expect(s.netMicros).toBe("108000000");
      const pledge = await getPledge(e, { pledgeId: "PL-1" });
      expect(pledge.pledge?.status).toBe("funded");
      const camp = await getCampaign(e, { campaignId: "CMP-1" });
      expect(camp.campaign?.raisedMicros).toBe("120000000");
      expect(camp.campaign?.backerCount).toBe(1);
      expect(camp.campaign?.status).toBe("active"); // 120 < 300
    });
    it("flips campaign to funded when goal met", async () => {
      await createPledge(e, {
        pledgeId: "PL-BIG",
        campaignId: "CMP-1",
        backerDid: "did:web:carol.etzhayyim.com",
        amountMicros: "300000000",
      });
      const s = await settlePledge(e, fakeSettle, { pledgeId: "PL-BIG", to: PAYOUT });
      expect(s.campaignStatus).toBe("funded");
      const camp = await getCampaign(e, { campaignId: "CMP-1" });
      expect(camp.campaign?.status).toBe("funded");
    });
    it("does not double-settle", async () => {
      await createPledge(e, {
        pledgeId: "PL-1",
        campaignId: "CMP-1",
        backerDid: "did:web:bob.etzhayyim.com",
        amountMicros: "120000000",
      });
      await settlePledge(e, fakeSettle, { pledgeId: "PL-1", to: PAYOUT });
      const again = await settlePledge(e, fakeSettle, { pledgeId: "PL-1", to: PAYOUT });
      expect(again.status).toBe("alreadyFunded");
    });
  });

  describe("listCampaigns", () => {
    it("filters by status", async () => {
      await createCampaign(e, {
        campaignId: "CMP-1",
        creatorDid: "did:web:alice.etzhayyim.com",
        title: "A",
        goalMicros: "1000000",
      });
      const active = await listCampaigns(e, { status: "active" });
      expect(active.total).toBe(1);
    });
  });
});
