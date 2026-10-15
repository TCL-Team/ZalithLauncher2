"use strict";

const { getFirestore, Timestamp } = require("firebase-admin/firestore");
const { getStorage } = require("firebase-admin/storage");
const { onSchedule } = require("firebase-functions/v2/scheduler");

function configuredBucket() {
  let firebaseConfig = {};
  try {
    firebaseConfig = JSON.parse(process.env.FIREBASE_CONFIG || "{}");
  } catch (_) {
    // The task will fail clearly below if project configuration is absent.
  }
  const bucketName = process.env.PLAYLINK_STORAGE_BUCKET || firebaseConfig.storageBucket;
  if (!bucketName) throw new Error("PlayLink Storage bucket is not configured.");
  return getStorage().bucket(bucketName);
}

exports.cleanupExpiredPlayLinkUploads = onSchedule({
  schedule: "every 24 hours",
  timeZone: "Etc/UTC",
  region: process.env.PLAYLINK_REGION || "us-central1",
  maxInstances: 1,
}, async () => {
  const db = getFirestore();
  const bucket = configuredBucket();
  const expired = await db.collection("playLinkPending")
    .where("expiresAt", "<=", Timestamp.now())
    .limit(200)
    .get();

  let removed = 0;
  for (const document of expired.docs) {
    const objectPath = document.get("objectPath");
    try {
      if (typeof objectPath === "string" && objectPath.startsWith("playlinks/")) {
        await bucket.file(objectPath).delete({ ignoreNotFound: true });
      }
      await document.ref.delete();
      removed += 1;
    } catch (error) {
      console.error("Could not clean up expired PlayLink upload", document.id, error);
    }
  }
  console.info(`Removed ${removed} expired PlayLink upload(s).`);
});
