import { companyMembershipDelta, lastNUtcDateKeys, mergeDailyBuckets, utcDateKey } from "./index";

describe("utcDateKey", () => {
  it("formats an epoch millis timestamp as a UTC calendar day", () => {
    expect(utcDateKey(Date.UTC(2026, 8, 28, 23, 59))).toBe("2026-09-28");
  });
});

describe("lastNUtcDateKeys", () => {
  it("returns today first, then the previous days, UTC-based", () => {
    const now = Date.UTC(2026, 8, 28, 12, 0);
    expect(lastNUtcDateKeys(now, 3)).toEqual(["2026-09-28", "2026-09-27", "2026-09-26"]);
  });
});

describe("mergeDailyBuckets", () => {
  it("sums minutes/sessions and counts distinct contributors without exposing their uids", () => {
    const result = mergeDailyBuckets([
      { minutesTotal: 30, sessionsTotal: 2, contributorUids: ["a", "b"] },
      { minutesTotal: 15, sessionsTotal: 1, contributorUids: ["b", "c"] },
      undefined,
    ]);
    expect(result).toEqual({ minutesTotal: 45, sessionsTotal: 3, contributorCount: 3 });
  });

  it("returns zeros when there is no data in the window yet", () => {
    expect(mergeDailyBuckets([undefined, undefined])).toEqual({ minutesTotal: 0, sessionsTotal: 0, contributorCount: 0 });
  });
});

describe("companyMembershipDelta", () => {
  it("reports a join when a fresh user doc sets a companyId", () => {
    expect(companyMembershipDelta(undefined, { companyId: "acme" })).toEqual({ joined: "acme" });
  });

  it("reports a leave when a user doc is deleted", () => {
    expect(companyMembershipDelta({ companyId: "acme" }, undefined)).toEqual({ left: "acme" });
  });

  it("reports both a leave and a join when switching companies", () => {
    expect(companyMembershipDelta({ companyId: "acme" }, { companyId: "other-co" })).toEqual({ left: "acme", joined: "other-co" });
  });

  it("reports a leave only when clearing companyId", () => {
    expect(companyMembershipDelta({ companyId: "acme" }, {})).toEqual({ left: "acme" });
  });

  it("reports nothing when companyId is unchanged", () => {
    expect(companyMembershipDelta({ companyId: "acme" }, { companyId: "acme" })).toEqual({});
    expect(companyMembershipDelta(undefined, undefined)).toEqual({});
    expect(companyMembershipDelta({}, {})).toEqual({});
  });
});
