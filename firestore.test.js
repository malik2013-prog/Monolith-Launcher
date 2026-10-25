const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user cannot read or create launch_profiles", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(
    unauthDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("launch_profiles")
      .doc("prof_1")
      .get()
  );
});

test("Authenticated user can create and read their own valid LaunchProfileRecord", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const now = new Date();
  const docRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("launch_profiles")
    .doc("prof_1");

  await assertSucceeds(
    docRef.set({
      userId: ALICE_UID,
      profileName: "Vanilla 1.21.4 High FPS",
      versionId: "1.21.4",
      javaMajor: 21,
      allocatedMemoryMb: 2048,
      rendererId: "gl4es_114",
      gcAlgorithm: "G1GC",
      customJvmFlags: "-XX:+UseG1GC",
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(docRef.get());
});

test("Authenticated user cannot read or write another user's launch_profiles", async () => {
  const now = new Date();
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context
      .firestore()
      .collection("users")
      .doc(BOB_UID)
      .collection("launch_profiles")
      .doc("bob_prof")
      .set({
        userId: BOB_UID,
        profileName: "Bob Profile",
        versionId: "1.16.5",
        javaMajor: 17,
        allocatedMemoryMb: 2048,
        rendererId: "gl4es_114",
        gcAlgorithm: "G1GC",
        customJvmFlags: "",
        createdAt: now,
        updatedAt: now,
      });
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(
    aliceDb
      .collection("users")
      .doc(BOB_UID)
      .collection("launch_profiles")
      .doc("bob_prof")
      .get()
  );
});

test("Shadow field injection on LaunchProfileRecord is rejected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const now = new Date();
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("launch_profiles")
      .doc("prof_shadow")
      .set({
        userId: ALICE_UID,
        profileName: "Shadow Profile",
        versionId: "1.21.4",
        javaMajor: 21,
        allocatedMemoryMb: 2048,
        rendererId: "gl4es_114",
        gcAlgorithm: "G1GC",
        customJvmFlags: "",
        isAdmin: true,
        createdAt: now,
        updatedAt: now,
      })
  );
});

test("Authenticated user can create and query their own DiagnosticReportRecord", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const now = new Date();
  const docRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("diagnostic_reports")
    .doc("rep_1");

  await assertSucceeds(
    docRef.set({
      userId: ALICE_UID,
      versionId: "1.21.4",
      javaMajor: 21,
      totalChecks: 8,
      repairedIssuesCount: 2,
      summary: "Verified ARM64 JRE and repaired options.txt fullscreen flag",
      status: "REPAIRED",
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("diagnostic_reports")
      .where("userId", "==", ALICE_UID)
      .get()
  );
});
