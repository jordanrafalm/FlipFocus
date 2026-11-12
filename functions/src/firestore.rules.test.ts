import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
  RulesTestEnvironment,
} from "@firebase/rules-unit-testing";
import { readFileSync } from "fs";
import { setDoc, doc, getDoc, deleteDoc } from "firebase/firestore";

/**
 * Firestore rules tests against the local emulator (never a real project). Requires the
 * Firestore emulator, downloaded/started automatically by `firebase emulators:exec`; see
 * functions/README.md for how to run this file. Skipped automatically (not run by `npm test`)
 * when the emulator host env var isn't set, so a plain `npm test` never needs the emulator.
 */
const emulatorHost = process.env.FIRESTORE_EMULATOR_HOST;
const describeIfEmulator = emulatorHost ? describe : describe.skip;

describeIfEmulator("firestore.rules", () => {
  let testEnv: RulesTestEnvironment;

  beforeAll(async () => {
    testEnv = await initializeTestEnvironment({
      projectId: "demo-flipfocus-rules-test",
      firestore: { rules: readFileSync("../firestore.rules", "utf8") },
    });
  });

  afterAll(async () => {
    await testEnv.cleanup();
  });

  afterEach(async () => {
    await testEnv.clearFirestore();
  });

  it("lets a company member create their own completion and read it back", async () => {
    const uid = "user-1";
    const context = testEnv.authenticatedContext(uid);
    await testEnv.withSecurityRulesDisabled(async (admin) => {
      await setDoc(doc(admin.firestore(), "users", uid), { companyId: "acme" });
    });
    const completionRef = doc(context.firestore(), "companies/acme/completions/user-1_session-1");
    await assertSucceeds(setDoc(completionRef, { uid, week: "2026-09-21", minutes: 25, epochMillis: Date.now() }));
    await assertSucceeds(getDoc(completionRef));
  });

  it("never lets a coworker read another employee's completion, by get() or list()", async () => {
    const owner = testEnv.authenticatedContext("user-1");
    const coworker = testEnv.authenticatedContext("user-2");
    await testEnv.withSecurityRulesDisabled(async (admin) => {
      await setDoc(doc(admin.firestore(), "users", "user-1"), { companyId: "acme" });
      await setDoc(doc(admin.firestore(), "users", "user-2"), { companyId: "acme" });
      await setDoc(doc(admin.firestore(), "companies/acme/completions/user-1_session-1"), {
        uid: "user-1", week: "2026-09-21", minutes: 25, epochMillis: Date.now(),
      });
    });
    await assertFails(getDoc(doc(coworker.firestore(), "companies/acme/completions/user-1_session-1")));
    const { getDocs, query, collection, where } = await import("firebase/firestore");
    await assertFails(getDocs(query(collection(coworker.firestore(), "companies/acme/completions"), where("uid", "==", "user-1"))));
    await assertSucceeds(getDocs(query(collection(owner.firestore(), "companies/acme/completions"), where("uid", "==", "user-1"))));
  });

  it("rejects a completion for a different company than the caller's own", async () => {
    const uid = "user-1";
    const context = testEnv.authenticatedContext(uid);
    await testEnv.withSecurityRulesDisabled(async (admin) => {
      await setDoc(doc(admin.firestore(), "users", uid), { companyId: "acme" });
    });
    const completionRef = doc(context.firestore(), "companies/other-co/completions/user-1_session-1");
    await assertFails(setDoc(completionRef, { uid, week: "2026-09-21", minutes: 25, epochMillis: Date.now() }));
  });

  it("rejects out-of-range minutes and mismatched-owner completion ids", async () => {
    const uid = "user-1";
    const context = testEnv.authenticatedContext(uid);
    await testEnv.withSecurityRulesDisabled(async (admin) => {
      await setDoc(doc(admin.firestore(), "users", uid), { companyId: "acme" });
    });
    await assertFails(
      setDoc(doc(context.firestore(), "companies/acme/completions/user-1_session-1"), {
        uid, week: "2026-09-21", minutes: 999, epochMillis: Date.now(),
      }),
    );
    await assertFails(
      setDoc(doc(context.firestore(), "companies/acme/completions/other-user_session-1"), {
        uid, week: "2026-09-21", minutes: 25, epochMillis: Date.now(),
      }),
    );
  });

  it("never allows a completion to be overwritten or deleted (immutability)", async () => {
    const uid = "user-1";
    const context = testEnv.authenticatedContext(uid);
    const completionRef = doc(context.firestore(), "companies/acme/completions/user-1_session-1");
    await testEnv.withSecurityRulesDisabled(async (admin) => {
      await setDoc(doc(admin.firestore(), "users", uid), { companyId: "acme" });
      await setDoc(doc(admin.firestore(), "companies/acme/completions/user-1_session-1"), {
        uid, week: "2026-09-21", minutes: 25, epochMillis: Date.now(),
      });
    });
    await assertFails(setDoc(completionRef, { uid, week: "2026-09-21", minutes: 30, epochMillis: Date.now() }));
    await assertFails(deleteDoc(completionRef));
  });

  it("keeps stats readable but write-protected, and the private aggregation subtree fully hidden", async () => {
    const context = testEnv.authenticatedContext("user-1");
    await testEnv.withSecurityRulesDisabled(async (admin) => {
      await setDoc(doc(admin.firestore(), "companies/acme/stats/allTime"), { minutesTotal: 100, sessionsTotal: 4, contributorCount: 3 });
      await setDoc(doc(admin.firestore(), "companies/acme/private/agg/dailyBuckets/2026-09-28"), { minutesTotal: 10 });
    });
    await assertSucceeds(getDoc(doc(context.firestore(), "companies/acme/stats/allTime")));
    await assertFails(setDoc(doc(context.firestore(), "companies/acme/stats/allTime"), { minutesTotal: 999 }));
    await assertFails(getDoc(doc(context.firestore(), "companies/acme/private/agg/dailyBuckets/2026-09-28")));
  });
});
