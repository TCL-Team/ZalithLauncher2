"use strict";

const crypto = require("node:crypto");
const { initializeApp } = require("firebase-admin/app");
const { getAuth } = require("firebase-admin/auth");
const { FieldValue, Timestamp, getFirestore } = require("firebase-admin/firestore");
const { getStorage } = require("firebase-admin/storage");
const { onRequest } = require("firebase-functions/v2/https");

initializeApp();

const auth = getAuth();
const db = getFirestore();
const MAX_PACK_BYTES = 512 * 1024 * 1024;
const MAX_LINKS_PER_UID_PER_DAY = 10;
const PENDING_TTL_MS = 60 * 60 * 1000;
const ID_PATTERN = /^[A-Za-z0-9_-]{32}$/;
const NAME_MAX_LENGTH = 64;
const DESCRIPTION_MAX_LENGTH = 500;
const PACK_CONTENT_TYPE = "application/octet-stream";

class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

function firebaseConfig() {
  try {
    return JSON.parse(process.env.FIREBASE_CONFIG || "{}");
  } catch (_) {
    return {};
  }
}

function projectId() {
  const id = process.env.GCLOUD_PROJECT || firebaseConfig().projectId;
  if (!id || !/^[a-z][a-z0-9-]{4,28}[a-z0-9]$/.test(id)) {
    throw new HttpError(503, "Firebase project configuration is incomplete.");
  }
  return id;
}

function storageBucket() {
  const name = process.env.PLAYLINK_STORAGE_BUCKET || firebaseConfig().storageBucket;
  if (!name || !/^[a-z0-9][a-z0-9._-]*$/.test(name)) {
    throw new HttpError(503, "Firebase Storage is not configured for PlayLink.");
  }
  return getStorage().bucket(name);
}

function cleanText(value, maxLength, required = false) {
  if (typeof value !== "string") {
    if (required) throw new HttpError(400, "A required PlayLink field is missing.");
    return "";
  }
  const result = value.replace(/[\u0000-\u001f\u007f]/g, " ").trim().slice(0, maxLength);
  if (required && !result) throw new HttpError(400, "A required PlayLink field is empty.");
  return result;
}

function validId(value) {
  return typeof value === "string" && ID_PATTERN.test(value);
}

function bodyObject(req) {
  if (!req.body || typeof req.body !== "object" || Array.isArray(req.body)) {
    throw new HttpError(400, "A JSON request body is required.");
  }
  return req.body;
}

async function authenticatedUid(req) {
  const header = req.headers.authorization || "";
  const match = /^Bearer\s+(.+)$/i.exec(header);
  if (!match) throw new HttpError(401, "Sign-in is required to create a PlayLink.");
  try {
    const decoded = await auth.verifyIdToken(match[1], true);
    if (decoded.firebase?.sign_in_provider !== "anonymous") {
      throw new HttpError(403, "PlayLink currently accepts anonymous launcher sessions only.");
    }
    return decoded.uid;
  } catch (error) {
    if (error instanceof HttpError) throw error;
    throw new HttpError(401, "Your launcher session expired. Please retry the action.");
  }
}

async function enforceDailyQuota(uid) {
  const dayKey = new Date().toISOString().slice(0, 10);
  const quotaRef = db.collection("playLinkQuotas").doc(uid);
  await db.runTransaction(async (transaction) => {
    const snapshot = await transaction.get(quotaRef);
    const currentCount = snapshot.exists && snapshot.get("dayKey") === dayKey
      ? Number(snapshot.get("count") || 0)
      : 0;
    if (currentCount >= MAX_LINKS_PER_UID_PER_DAY) {
      throw new HttpError(429, "Daily PlayLink creation limit reached. Try again tomorrow.");
    }
    transaction.set(quotaRef, {
      dayKey,
      count: currentCount + 1,
      updatedAt: FieldValue.serverTimestamp(),
    }, { merge: true });
  });
}

function validatedMetadata(body) {
  const packName = cleanText(body.packName, NAME_MAX_LENGTH, true);
  const description = cleanText(body.description, DESCRIPTION_MAX_LENGTH);
  const minecraftVersion = cleanText(body.minecraftVersion, 64, true);
  const loaderName = cleanText(body.loaderName, 64) || "Vanilla";
  const loaderVersion = cleanText(body.loaderVersion, 64);
  const fileSizeBytes = Number(body.fileSizeBytes);
  const modCount = Number(body.modCount);

  if (!Number.isSafeInteger(fileSizeBytes) || fileSizeBytes < 1 || fileSizeBytes > MAX_PACK_BYTES) {
    throw new HttpError(413, "PlayLink packs must be between 1 byte and 512 MB.");
  }
  if (!Number.isSafeInteger(modCount) || modCount < 0 || modCount > 20000) {
    throw new HttpError(400, "The mod count is invalid.");
  }
  return { packName, description, minecraftVersion, loaderName, loaderVersion, fileSizeBytes, modCount };
}

function pathOf(req) {
  const rawPath = req.path || new URL(req.url, "https://invalid.local").pathname;
  return rawPath.replace(/^\/api(?=\/)/, "").replace(/\/+$/, "") || "/";
}

function sendError(res, error) {
  const status = error instanceof HttpError ? error.status : 500;
  const message = error instanceof HttpError
    ? error.message
    : "The PlayLink service encountered an error. Please try again later.";
  if (status === 500) console.error("PlayLink request failed", error);
  res.status(status).set("Cache-Control", "no-store").json({ error: { message } });
}

function downloadUrl(bucket, objectPath, token) {
  return `https://firebasestorage.googleapis.com/v0/b/${encodeURIComponent(bucket.name)}` +
    `/o/${encodeURIComponent(objectPath)}?alt=media&token=${encodeURIComponent(token)}`;
}

async function createUploadTicket(req, res) {
  const uid = await authenticatedUid(req);
  const metadata = validatedMetadata(bodyObject(req));
  await enforceDailyQuota(uid);

  const id = crypto.randomBytes(24).toString("base64url");
  const objectPath = `playlinks/${id}/pack.mrpack`;
  const downloadToken = crypto.randomUUID();
  const expiresAt = Timestamp.fromMillis(Date.now() + PENDING_TTL_MS);
  await db.collection("playLinkPending").doc(id).create({
    ownerUid: uid,
    objectPath,
    downloadToken,
    createdAt: FieldValue.serverTimestamp(),
    expiresAt,
    ...metadata,
  });

  res.status(201).set("Cache-Control", "no-store").json({
    id,
    objectPath,
    expiresAt: expiresAt.toDate().toISOString(),
  });
}

async function finalizeUpload(req, res) {
  const uid = await authenticatedUid(req);
  const id = bodyObject(req).id;
  if (!validId(id)) throw new HttpError(400, "The PlayLink ID is invalid.");

  const pendingRef = db.collection("playLinkPending").doc(id);
  const pendingSnapshot = await pendingRef.get();
  if (!pendingSnapshot.exists) throw new HttpError(404, "The pending PlayLink upload was not found.");
  const pending = pendingSnapshot.data();
  if (pending.ownerUid !== uid) throw new HttpError(403, "This PlayLink upload belongs to another launcher session.");
  if (!pending.expiresAt || pending.expiresAt.toMillis() <= Date.now()) {
    throw new HttpError(410, "The PlayLink upload expired. Please create it again.");
  }

  const bucket = storageBucket();
  const object = bucket.file(pending.objectPath);
  let objectMetadata;
  try {
    [objectMetadata] = await object.getMetadata();
  } catch (_) {
    throw new HttpError(409, "Upload the PlayLink pack before finalizing the link.");
  }
  const uploadedBytes = Number(objectMetadata.size || 0);
  if (uploadedBytes !== pending.fileSizeBytes || uploadedBytes < 1 || uploadedBytes > MAX_PACK_BYTES) {
    await object.delete({ ignoreNotFound: true }).catch(() => {});
    throw new HttpError(400, "The uploaded pack size did not match the PlayLink request.");
  }

  await object.setMetadata({
    contentType: PACK_CONTENT_TYPE,
    metadata: { firebaseStorageDownloadTokens: pending.downloadToken },
  });

  const linkRef = db.collection("playLinks").doc(id);
  await db.runTransaction(async (transaction) => {
    const currentPending = await transaction.get(pendingRef);
    const existingLink = await transaction.get(linkRef);
    if (!currentPending.exists || existingLink.exists) {
      throw new HttpError(409, "This PlayLink upload has already been finalized.");
    }
    transaction.create(linkRef, {
      status: "published",
      ownerUid: uid,
      objectPath: pending.objectPath,
      downloadToken: pending.downloadToken,
      packName: pending.packName,
      description: pending.description,
      minecraftVersion: pending.minecraftVersion,
      loaderName: pending.loaderName,
      loaderVersion: pending.loaderVersion,
      fileSizeBytes: uploadedBytes,
      modCount: pending.modCount,
      createdAt: FieldValue.serverTimestamp(),
    });
    transaction.delete(pendingRef);
  });

  const shareUrl = `https://${projectId()}.web.app/play/${id}`;
  res.status(201).set("Cache-Control", "no-store").json({ id, shareUrl });
}

async function resolveLink(id, res) {
  if (!validId(id)) throw new HttpError(400, "The PlayLink code is invalid.");
  const snapshot = await db.collection("playLinks").doc(id).get();
  if (!snapshot.exists || snapshot.get("status") !== "published") {
    throw new HttpError(404, "This PlayLink was not found or is no longer available.");
  }
  const record = snapshot.data();
  const bucket = storageBucket();
  const [exists] = await bucket.file(record.objectPath).exists();
  if (!exists) throw new HttpError(410, "The pack for this PlayLink is no longer available.");

  res.status(200).set("Cache-Control", "no-store").json({
    id,
    packName: record.packName,
    description: record.description,
    minecraftVersion: record.minecraftVersion,
    loaderName: record.loaderName,
    loaderVersion: record.loaderVersion,
    fileSizeBytes: record.fileSizeBytes,
    modCount: record.modCount,
    createdAt: record.createdAt?.toDate?.().toISOString() || null,
    downloadUrl: downloadUrl(bucket, record.objectPath, record.downloadToken),
  });
}

exports.playLinkApi = onRequest({
  region: process.env.PLAYLINK_REGION || "us-central1",
  cors: true,
  timeoutSeconds: 120,
  memory: "512MiB",
  maxInstances: 5,
}, async (req, res) => {
  try {
    const path = pathOf(req);
    if (req.method === "POST" && path === "/playlink/upload-ticket") {
      await createUploadTicket(req, res);
      return;
    }
    if (req.method === "POST" && path === "/playlink/finalize") {
      await finalizeUpload(req, res);
      return;
    }
    const resolveMatch = /^\/playlink\/([A-Za-z0-9_-]{32})$/.exec(path);
    if (req.method === "GET" && resolveMatch) {
      await resolveLink(resolveMatch[1], res);
      return;
    }
    throw new HttpError(404, "PlayLink endpoint not found.");
  } catch (error) {
    sendError(res, error);
  }
});

exports.cleanupExpiredPlayLinkUploads = require("./cleanup").cleanupExpiredPlayLinkUploads;
