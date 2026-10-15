# PlayLink cloud service

This folder contains the Firebase Functions, Firestore/Storage rules, and Firebase Hosting page used by the launcher PlayLink UI. It uses an opaque random 192-bit link ID, Firestore metadata, and a `.mrpack` file in Firebase Storage. Share URLs use Firebase Hosting's project hostname (`https://<project-id>.web.app/play/<id>`); no custom domain, Firebase Dynamic Links, Minecraft server, or server address is used.

## Firebase project setup

A Firebase project is required before real links can be created or resolved. No project ID or API key is hard-coded here.

1. Create/select a Firebase project and enable **Authentication → Sign-in method → Anonymous**.
2. Create a **Cloud Firestore** database and a **Cloud Storage** bucket.
3. From Project settings, copy the project ID, the Web API key, and the exact Storage bucket name into `ZalithLauncher/src/main/assets/playlink-config.json`:

   ```json
   {
     "projectId": "YOUR_FIREBASE_PROJECT_ID",
     "apiKey": "YOUR_FIREBASE_WEB_API_KEY",
     "storageBucket": "YOUR_FIREBASE_STORAGE_BUCKET",
     "region": "us-central1"
   }
   ```

   The Web API key is a public Firebase client identifier; do not put a service-account key or Admin credential in the Android app. Keep the API enabled only for the Firebase services needed by this project.
4. Copy `playlink-backend/functions/.env.example` to `playlink-backend/functions/.env.<project-id>` and set the project's exact Storage bucket name. The example is ignored from Git so the project-specific file stays local.
5. Install Node.js 22 and the Firebase CLI, authenticate, and select the project:

   ```sh
   npm install --global firebase-tools
   firebase login
   firebase use --add
   npm --prefix playlink-backend/functions install
   firebase deploy --only functions,firestore:rules,storage,hosting
   ```

   Cloud Functions deployment may require a Firebase billing plan. Use the same region in `firebase.json`, the function options, `.env.<project-id>`, and the Android config if you change the default `us-central1`.
6. The scheduled `cleanupExpiredPlayLinkUploads` function removes expired upload tickets and any incomplete Storage objects daily. The service rejects expired tickets immediately; Firestore TTL may also be enabled for `playLinkPending.expiresAt` as a cleanup backstop.
7. Rebuild the Android app after filling the config asset. With the checked-in empty template, the launcher shows a clear setup-required message instead of generating a fake link.

## What the flow does

- The launcher creates an anonymous Firebase Auth session, packages a portable Modrinth `.mrpack` from the selected profile folders, and requests an upload ticket. This filtering cannot audit arbitrary mod configuration contents, so users should review sensitive settings before sharing.
- The ticket is bound to that anonymous UID and expires after one hour. Storage rules permit only that session to create its single file, with a 512 MB maximum.
- After the upload succeeds, the function verifies the object and publishes the Firestore record. The share URL is returned only after that finalization succeeds.
- Resolving a link previews the pack metadata and returns its Firebase Storage download-token URL. The launcher asks the user to confirm before downloading and hands the file to the existing modpack importer.
- The default package includes `mods`, `config`, `defaultconfigs`, `resourcepacks`, `shaderpacks`, `kubejs`, and `scripts`. It deliberately excludes worlds/saves, server lists, launcher profiles/accounts, options, logs, and launcher data.
- Create requests are limited to ten per anonymous UID per day. Firestore client access is denied; uploads are owner-bound through Storage rules. Link IDs are generated on the server using cryptographic randomness.

Links are bearer links: anyone with the full random URL can view the metadata and download that pack. Do not publish a link containing content you do not want to share.
