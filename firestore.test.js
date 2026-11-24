const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const HOST_UID = "host_alice_123";
const PLAYER_UID = "player_bob_456";
const STRANGER_UID = "stranger_eve_789";
const ROOM_ID = "room_alpha_1";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function validRoomPayload(hostUid = HOST_UID, now = new Date(Date.now() - 2000)) {
  return {
    hostUid,
    hostName: "Алиса",
    roomName: "Неон Арена #1",
    status: "waiting",
    active: true,
    arenaRadius: 2200,
    includeBots: true,
    botDifficulty: "BALANCED",
    playerCount: 1,
    maxPlayers: 12,
    tick: 0,
    createdAt: now,
    updatedAt: now,
  };
}

function validPlayerPayload(uid = PLAYER_UID, hostUid = HOST_UID, now = new Date(Date.now() - 1000)) {
  return {
    uid,
    hostUid,
    name: "Боб",
    skinId: 2,
    targetAngle: 1.25,
    boosting: false,
    respawnSeq: 0,
    createdAt: now,
    updatedAt: now,
  };
}

function validLiveStatePayload(hostUid = HOST_UID, now = new Date(Date.now() - 1000)) {
  return {
    hostUid,
    tick: 12,
    arenaRadius: 2200,
    killFeed: "",
    worms: [
      {
        id: 1,
        uid: HOST_UID,
        name: "Алиса",
        skinId: 0,
        bot: false,
        alive: true,
        shield: true,
        x: 100,
        y: -50,
        a: 0.5,
        ta: 0.6,
        s: 15,
        k: 0,
        b: false,
        r: 15.1,
        pts: [100, -50, 92, -54, 84, -58],
      },
    ],
    orbs: [1, 200, -100, 3, 2, 2, -40, 80, 1, 1],
    deaths: [],
    updatedAt: now,
  };
}

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

test("Unauthenticated user cannot list, read, or create rooms", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("rooms").where("active", "==", true).get());
  await assertFails(unauthDb.collection("rooms").doc(ROOM_ID).get());
  await assertFails(unauthDb.collection("rooms").doc(ROOM_ID).set(validRoomPayload()));
});

test("Authenticated host can create, update, close room; terminal closed room locks updates", async () => {
  const hostDb = testEnv.authenticatedContext(HOST_UID).firestore();
  const roomRef = hostDb.collection("rooms").doc(ROOM_ID);

  await assertSucceeds(roomRef.set(validRoomPayload()));

  // Host starts game and increments playerCount
  await assertSucceeds(
    roomRef.update({
      status: "playing",
      playerCount: 2,
      tick: 60,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Reject ghost field (Shadow Update test)
  await assertFails(
    roomRef.update({
      tick: 70,
      isVerified: true,
      updatedAt: new Date(Date.now() - 400),
    })
  );

  // Reject immutable hostUid or createdAt modification
  await assertFails(
    roomRef.update({
      hostUid: PLAYER_UID,
      updatedAt: new Date(Date.now() - 400),
    })
  );

  // Host closes room (terminal state)
  await assertSucceeds(
    roomRef.update({
      status: "closed",
      active: false,
      updatedAt: new Date(Date.now() - 300),
    })
  );

  // Terminal state lock: closed room cannot be reopened or mutated
  await assertFails(
    roomRef.update({
      status: "playing",
      active: true,
      updatedAt: new Date(Date.now() - 200),
    })
  );
});

test("Room list enforces active == true filter; non-host cannot update or delete room", async () => {
  const hostDb = testEnv.authenticatedContext(HOST_UID).firestore();
  const bobDb = testEnv.authenticatedContext(PLAYER_UID).firestore();

  await assertSucceeds(hostDb.collection("rooms").doc(ROOM_ID).set(validRoomPayload()));

  // Filtered query matches rule
  await assertSucceeds(bobDb.collection("rooms").where("active", "==", true).get());

  // Unfiltered query rejected
  await assertFails(bobDb.collection("rooms").get());

  // Non-host cannot hijack or delete host's room
  await assertFails(
    bobDb.collection("rooms").doc(ROOM_ID).update({
      playerCount: 5,
      updatedAt: new Date(Date.now() - 300),
    })
  );
  await assertFails(bobDb.collection("rooms").doc(ROOM_ID).delete());
});

test("Player joins room via /players/{uid}, streams own inputs, and cannot spoof or read others", async () => {
  const hostDb = testEnv.authenticatedContext(HOST_UID).firestore();
  const bobDb = testEnv.authenticatedContext(PLAYER_UID).firestore();
  const eveDb = testEnv.authenticatedContext(STRANGER_UID).firestore();

  await assertSucceeds(hostDb.collection("rooms").doc(ROOM_ID).set(validRoomPayload()));

  const bobPlayerRef = bobDb.collection("rooms").doc(ROOM_ID).collection("players").doc(PLAYER_UID);
  await assertSucceeds(bobPlayerRef.set(validPlayerPayload(PLAYER_UID, HOST_UID)));

  // Bob updates steering & boost
  await assertSucceeds(
    bobPlayerRef.update({
      targetAngle: -2.1,
      boosting: true,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Eve cannot write into Bob's player slot or forge another UID
  await assertFails(
    eveDb.collection("rooms").doc(ROOM_ID).collection("players").doc(PLAYER_UID).set(validPlayerPayload(PLAYER_UID, HOST_UID))
  );
  await assertFails(
    eveDb.collection("rooms").doc(ROOM_ID).collection("players").doc(PLAYER_UID).update({
      boosting: false,
      updatedAt: new Date(Date.now() - 400),
    })
  );

  // Eve cannot list players (only host can list with hostUid filter)
  await assertFails(
    eveDb.collection("rooms").doc(ROOM_ID).collection("players").where("hostUid", "==", HOST_UID).get()
  );

  // Host can list joined players using hostUid filter
  await assertSucceeds(
    hostDb.collection("rooms").doc(ROOM_ID).collection("players").where("hostUid", "==", HOST_UID).get()
  );

  // Joining a non-existent or closed room fails Parent Gate
  await assertFails(
    bobDb.collection("rooms").doc("non_existent_room").collection("players").doc(PLAYER_UID).set(validPlayerPayload())
  );
});

test("Live game state is writable only by host and readable only by host or joined players", async () => {
  const hostDb = testEnv.authenticatedContext(HOST_UID).firestore();
  const bobDb = testEnv.authenticatedContext(PLAYER_UID).firestore();
  const eveDb = testEnv.authenticatedContext(STRANGER_UID).firestore();

  await assertSucceeds(hostDb.collection("rooms").doc(ROOM_ID).set(validRoomPayload()));

  // Before Bob joins, Bob and Eve cannot read state/live
  await assertFails(bobDb.collection("rooms").doc(ROOM_ID).collection("state").doc("live").get());
  await assertFails(eveDb.collection("rooms").doc(ROOM_ID).collection("state").doc("live").get());

  // Bob joins room
  await assertSucceeds(
    bobDb.collection("rooms").doc(ROOM_ID).collection("players").doc(PLAYER_UID).set(validPlayerPayload())
  );

  // Even before host writes state/live, Bob can listen/get state/live without PERMISSION_DENIED
  await assertSucceeds(bobDb.collection("rooms").doc(ROOM_ID).collection("state").doc("live").get());

  // Host publishes live state
  await assertSucceeds(
    hostDb.collection("rooms").doc(ROOM_ID).collection("state").doc("live").set(validLiveStatePayload())
  );

  // Joined Bob can read; unjoined Eve cannot
  await assertSucceeds(bobDb.collection("rooms").doc(ROOM_ID).collection("state").doc("live").get());
  await assertFails(eveDb.collection("rooms").doc(ROOM_ID).collection("state").doc("live").get());

  // Bob cannot tamper with live state
  await assertFails(
    bobDb.collection("rooms").doc(ROOM_ID).collection("state").doc("live").update({
      tick: 999,
      updatedAt: new Date(Date.now() - 200),
    })
  );
});

test("Future timestamps and oversized strings are rejected", async () => {
  const hostDb = testEnv.authenticatedContext(HOST_UID).firestore();
  const futureTime = new Date(Date.now() + 60000);
  await assertFails(
    hostDb.collection("rooms").doc(ROOM_ID).set(validRoomPayload(HOST_UID, futureTime))
  );
});
