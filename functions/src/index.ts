/**
 * DEFERRED / UNUSED on the current (Spark-plan) app: `firebase.json` no longer declares a
 * `functions` codebase and `firestore.rules` no longer has writable `stats`/`private`
 * subcollections for this to populate, so nothing calls or reads this code today. Company-wide
 * aggregate stats are disabled client-side until this is deliberately redeployed on a paid
 * (Blaze) project with matching firebase.json/rules changes.
 *
 * Cloud Functions that would fold private per-user company completions (written directly by the
 * client — see FirebaseSocialRepository.submitCompanyCompletion) into public rollups at
 * companies/{companyId}/stats/{allTime,last7Days}. Firestore rules would need to make the
 * `stats` and `private` subcollections client-unwritable again so these documents only ever
 * change here, and never leak the contributing UIDs — only aggregate counts.
 *
 * Layout under companies/{companyId}/private/agg:
 *  - processed/{completionId}     idempotency marker so at-least-once trigger delivery is safe
 *  - contributorsAllTime/{uid}    one marker doc per distinct all-time contributor
 *  - dailyBuckets/{yyyy-mm-dd}    UTC daily rollup used to compute the rolling 7-day window
 *
 * Do not deploy this from an agent session; run `npm run build` to typecheck and `npm test`
 * for the pure-logic unit tests. Deployment is a manual, explicit operator action
 * (`firebase deploy --only functions`) after reviewing billing/plan requirements (Blaze).
 */
import { initializeApp, getApps } from "firebase-admin/app";
import { getFirestore, FieldValue } from "firebase-admin/firestore";
import { onDocumentCreated, onDocumentWritten } from "firebase-functions/v2/firestore";
import { onSchedule } from "firebase-functions/v2/scheduler";
import * as logger from "firebase-functions/logger";

if (getApps().length === 0) initializeApp();

const MIN_MINUTES = 1;
const MAX_MINUTES = 180;
const ROLLING_WINDOW_DAYS = 7;

function db() {
  return getFirestore();
}

/** UTC calendar-day bucket key for an epoch-millis timestamp, e.g. "2026-09-28". */
export function utcDateKey(epochMillis: number): string {
  return new Date(epochMillis).toISOString().slice(0, 10);
}

/** The [ROLLING_WINDOW_DAYS] UTC day keys ending today (today first), used for the rolling window. */
export function lastNUtcDateKeys(nowEpochMillis: number, days: number = ROLLING_WINDOW_DAYS): string[] {
  const keys: string[] = [];
  for (let i = 0; i < days; i++) keys.push(utcDateKey(nowEpochMillis - i * 86_400_000));
  return keys;
}

interface CompletionDoc {
  uid?: string;
  minutes?: number;
  epochMillis?: number;
}

/**
 * Merges daily buckets into a single rollup. Contributor UIDs are only ever used to compute
 * `contributorCount`; the returned value never carries the UIDs themselves.
 */
export function mergeDailyBuckets(
  buckets: Array<{ minutesTotal?: number; sessionsTotal?: number; contributorUids?: string[] } | undefined>,
): { minutesTotal: number; sessionsTotal: number; contributorCount: number } {
  let minutesTotal = 0;
  let sessionsTotal = 0;
  const contributors = new Set<string>();
  for (const bucket of buckets) {
    if (!bucket) continue;
    minutesTotal += bucket.minutesTotal ?? 0;
    sessionsTotal += bucket.sessionsTotal ?? 0;
    for (const uid of bucket.contributorUids ?? []) contributors.add(uid);
  }
  return { minutesTotal, sessionsTotal, contributorCount: contributors.size };
}

/**
 * Fires once per newly created company completion. Folds it into the all-time stats rollup and
 * a UTC daily bucket (used by [recomputeLast7DaysStats]). Idempotent via a per-completion marker
 * doc checked and written inside the same transaction, so Cloud Functions' at-least-once retry
 * delivery can never double-count a completion.
 */
export const onCompanyCompletionCreated = onDocumentCreated(
  "companies/{companyId}/completions/{completionId}",
  async (event) => {
    const snapshot = event.data;
    if (!snapshot) return;
    const { companyId, completionId } = event.params as { companyId: string; completionId: string };
    const data = snapshot.data() as CompletionDoc;
    const uid = data.uid;
    const minutes = data.minutes;
    const epochMillis = typeof data.epochMillis === "number" ? data.epochMillis : Date.now();
    if (!uid || typeof minutes !== "number" || minutes < MIN_MINUTES || minutes > MAX_MINUTES) {
      logger.error(`Skipping malformed completion companies/${companyId}/completions/${completionId}`);
      return;
    }
    if (!completionId.startsWith(`${uid}_`)) {
      logger.error(`Skipping completion with unexpected id companies/${companyId}/completions/${completionId}`);
      return;
    }

    const firestore = db();
    const companyRef = firestore.collection("companies").doc(companyId);
    const aggRef = companyRef.collection("private").doc("agg");
    const processedRef = aggRef.collection("processed").doc(completionId);
    const contributorRef = aggRef.collection("contributorsAllTime").doc(uid);
    const statsAllTimeRef = companyRef.collection("stats").doc("allTime");
    const dailyBucketRef = aggRef.collection("dailyBuckets").doc(utcDateKey(epochMillis));

    await firestore.runTransaction(async (tx) => {
      const [processedSnap, contributorSnap] = await Promise.all([tx.get(processedRef), tx.get(contributorRef)]);
      if (processedSnap.exists) return; // Already folded into stats; retried delivery is a safe no-op.

      tx.set(processedRef, { uid, minutes, processedAt: FieldValue.serverTimestamp() });
      tx.set(contributorRef, { uid, since: FieldValue.serverTimestamp() }, { merge: true });
      tx.set(
        statsAllTimeRef,
        {
          minutesTotal: FieldValue.increment(minutes),
          sessionsTotal: FieldValue.increment(1),
          contributorCount: FieldValue.increment(contributorSnap.exists ? 0 : 1),
        },
        { merge: true },
      );
      tx.set(
        dailyBucketRef,
        {
          minutesTotal: FieldValue.increment(minutes),
          sessionsTotal: FieldValue.increment(1),
          contributorUids: FieldValue.arrayUnion(uid),
        },
        { merge: true },
      );
    });
  },
);

async function recomputeCompanyLast7Days(
  companyRef: FirebaseFirestore.DocumentReference,
  dayKeys: string[],
): Promise<void> {
  const aggRef = companyRef.collection("private").doc("agg");
  const snapshots = await Promise.all(dayKeys.map((day) => aggRef.collection("dailyBuckets").doc(day).get()));
  const rollup = mergeDailyBuckets(snapshots.map((snap) => (snap.exists ? snap.data() : undefined)));
  await companyRef.collection("stats").doc("last7Days").set(rollup, { merge: true });
}

/**
 * Recomputes the rolling 7-day rollup for every company from its UTC daily buckets. A scheduled
 * function (rather than doing this per-write) keeps the window correct as days roll off without
 * needing to touch every historical completion on every write.
 *
 * Iterating all companies here is simplest for the current catalog size; if the company count
 * grows large enough for this to become slow/costly, page through `listDocuments()` in batches.
 */
export const recomputeLast7DaysStats = onSchedule({ schedule: "every 60 minutes", timeZone: "Etc/UTC" }, async () => {
  const firestore = db();
  const dayKeys = lastNUtcDateKeys(Date.now());
  const companyRefs = await firestore.collection("companies").listDocuments();
  for (const companyRef of companyRefs) {
    await recomputeCompanyLast7Days(companyRef, dayKeys);
  }
});

interface UserDoc {
  companyId?: string;
}

/**
 * Pure diff of a users/{uid} write: which company (if any) the user left and/or joined. Returns
 * an empty object when companyId didn't change (including a doc create/delete with no company).
 */
export function companyMembershipDelta(
  before: UserDoc | undefined,
  after: UserDoc | undefined,
): { left?: string; joined?: string } {
  const beforeCompanyId = before?.companyId;
  const afterCompanyId = after?.companyId;
  if (beforeCompanyId === afterCompanyId) return {};
  const result: { left?: string; joined?: string } = {};
  if (beforeCompanyId) result.left = beforeCompanyId;
  if (afterCompanyId) result.joined = afterCompanyId;
  return result;
}

/**
 * Maintains companies/{companyId}.employeeCount — the only public, function-derived headcount —
 * from private users/{uid}.companyId writes, so clients never need to read the owner-private
 * /users collection to show a company's employee count. Each half of the delta (leave/join) is
 * idempotency-guarded by the triggering event's id, so Cloud Functions' at-least-once retries
 * can never double-count a single membership change.
 */
export const onUserCompanyChanged = onDocumentWritten("users/{uid}", async (event) => {
  const before = event.data?.before?.exists ? (event.data.before.data() as UserDoc) : undefined;
  const after = event.data?.after?.exists ? (event.data.after.data() as UserDoc) : undefined;
  const delta = companyMembershipDelta(before, after);
  if (!delta.left && !delta.joined) return;

  const firestore = db();
  const eventId = event.id;

  async function applyDelta(companyId: string, amount: number, marker: string): Promise<void> {
    const companyRef = firestore.collection("companies").doc(companyId);
    const processedRef = companyRef.collection("private").doc("agg").collection("processedUserChanges").doc(marker);
    await firestore.runTransaction(async (tx) => {
      const processedSnap = await tx.get(processedRef);
      if (processedSnap.exists) return; // Already applied; retried delivery is a safe no-op.
      tx.set(processedRef, { processedAt: FieldValue.serverTimestamp() });
      tx.set(companyRef, { employeeCount: FieldValue.increment(amount) }, { merge: true });
    });
  }

  if (delta.left) await applyDelta(delta.left, -1, `${eventId}_leave`);
  if (delta.joined) await applyDelta(delta.joined, 1, `${eventId}_join`);
});
