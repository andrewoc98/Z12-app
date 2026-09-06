# Plan: Z12 Challenge — Website Wrapper App with Push Notifications

## Goal
Wrap https://www.z12challenge.com/ (owned by the user) into installable
Android/iOS apps using Capacitor, with Firebase Cloud Messaging push
notifications, reusing the user's existing Firebase project.

## Current state (already done by the user manually)
- [x] Step 1: Project scaffolded — `npm init`, Capacitor deps installed,
  `npx cap init "Z12 Challenge" "com.z12challenge.app" --web-dir www` run
- [x] Step 2: `capacitor.config.json` and `www/index.html` in place
- [x] Step 3: (merged into step 1 above in prior instructions)
- [x] Step 4: `npx cap add android` and `npx cap add ios` run — `android/`
  and `ios/` directories exist

Verify this state first before doing anything else — do not re-run `cap
init` or `cap add` if these directories already exist, as that can
overwrite user config. If `android/` or `ios/` are missing, run:
```bash
npx cap add android
npx cap add ios
```

## Remaining work

### Task 1 — Confirm project structure
1. Run `cat capacitor.config.json` and confirm `appId` matches
   `com.z12challenge.app` (or whatever the user actually set — read it,
   don't assume).
2. Run `cat android/app/build.gradle | grep applicationId` and confirm it
   matches the `appId` above. If they differ, flag this to the user before
   proceeding — Firebase app registration must match this exact ID.
3. If targeting iOS, run `cat ios/App/App.xcodeproj/project.pbxproj | grep PRODUCT_BUNDLE_IDENTIFIER` and confirm the same ID.

### Task 2 — Firebase config files
The user has an existing Firebase project (used for their website) and
needs to register the native apps in it manually via the Firebase console
(this cannot be automated — it requires their Firebase login). Prompt the
user to:
- Add an Android app to their existing Firebase project with package name
  matching Task 1, download `google-services.json`.
- (If doing iOS) Add an iOS app with bundle ID matching Task 1, download
  `GoogleService-Info.plist`.

Once the user provides these files (they'll place them somewhere findable,
e.g. project root or paste contents):
1. Move `google-services.json` into `android/app/google-services.json`.
2. If iOS: move `GoogleService-Info.plist` into `ios/App/App/` and confirm
   it's referenced in `ios/App/App.xcodeproj/project.pbxproj` (Xcode
   project file) as a resource. If not auto-linked, note that the user
   needs to add it via Xcode UI (drag into the App folder with "Copy items
   if needed" checked) since this part is not reliably scriptable from
   the CLI.

### Task 3 — Android Gradle wiring for google-services.json
1. Open `android/build.gradle` (project-level) and ensure this is in the
   `dependencies` block under `buildscript`:
   ```gradle
   classpath 'com.google.gms:google-services:4.4.2'
   ```
2. Open `android/app/build.gradle` (app-level) and add at the very bottom:
   ```gradle
   apply plugin: 'com.google.gms.google-services'
   ```
3. Confirm `android/app/build.gradle` has the Firebase messaging dependency
   available. `@capacitor/push-notifications` handles this internally via
   its own `build.gradle`, so no extra dependency line should be needed —
   just verify the plugin is listed after running `npx cap sync android`.

### Task 4 — Create the push notification wiring file
Create `src/push.js` (or `www/push.js` if not using a bundler) with this
content:

```javascript
import { PushNotifications } from '@capacitor/push-notifications';

export async function initPush() {
  let permStatus = await PushNotifications.checkPermissions();
  if (permStatus.receive === 'prompt') {
    permStatus = await PushNotifications.requestPermissions();
  }
  if (permStatus.receive !== 'granted') {
    console.warn('Push permission not granted');
    return;
  }

  await PushNotifications.register();

  PushNotifications.addListener('registration', (token) => {
    console.log('Push registration token:', token.value);
    fetch('https://www.z12challenge.com/api/register-device', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ token: token.value, platform: 'capacitor' }),
    }).catch((e) => console.error('Failed to send token to backend', e));
  });

  PushNotifications.addListener('registrationError', (err) => {
    console.error('Push registration error:', err);
  });

  PushNotifications.addListener('pushNotificationReceived', (notification) => {
    console.log('Push received in foreground:', notification);
  });

  PushNotifications.addListener('pushNotificationActionPerformed', (action) => {
    const url = action.notification.data?.url;
    if (url) {
      window.location.href = url;
    }
  });
}
```

Ask the user for the real endpoint that should receive device tokens
(placeholder above is `https://www.z12challenge.com/api/register-device`
— replace with whatever their backend actually exposes, or flag that this
endpoint needs to be built if it doesn't exist yet).

### Task 5 — Trigger `initPush()` on launch
Since `capacitor.config.json` has `server.url` pointing at the live
website, `initPush()` needs to run once the app's WebView is ready. Two
options — pick based on what's simplest given the project setup found in
Task 1:

**Option A (preferred, simplest):** Add a `App` plugin listener in a small
bootstrap script that Capacitor loads before navigating to `server.url`.
Check whether `www/index.html` currently just shows a fallback screen —
if so, add a `<script type="module">` there that imports and calls
`initPush()` before or during the redirect/load sequence.

**Option B:** If Option A proves unreliable (Capacitor's injected bridge
timing with `server.url` can vary by platform), fall back to calling
`PushNotifications.register()` from native code instead:
- Android: in `android/app/src/main/java/.../MainActivity.java`, inside
  `onCreate()`, after `super.onCreate()`.
- iOS: in `ios/App/App/AppDelegate.swift`, inside
  `application(_:didFinishLaunchingWithOptions:)`.

Try Option A first. If it doesn't fire reliably in testing (Task 7), fall
back to Option B and document which one was used.

### Task 6 — Sync native projects
```bash
npx cap sync
```
Run this after every change to `capacitor.config.json`, `package.json`
dependencies, or added native files.

### Task 7 — Build and smoke-test
1. Android:
   ```bash
   npx cap open android
   ```
   Then build/run from Android Studio on an emulator or device. Confirm:
    - App launches and loads the live site.
    - Logcat shows a push registration token being logged (filter for
      "Push registration token").
2. iOS (if applicable): same via `npx cap open ios`, run from Xcode,
   check the Xcode console for the token log.

### Task 8 — Test sending a push
1. Create `scripts/send-notification.js`:
   ```javascript
   const admin = require('firebase-admin');
   admin.initializeApp({ credential: admin.credential.applicationDefault() });

   async function sendPush(deviceToken, title, body, url) {
     const message = {
       token: deviceToken,
       notification: { title, body },
       data: { url: url || '' },
       android: { priority: 'high' },
       apns: { payload: { aps: { sound: 'default' } } },
     };
     const response = await admin.messaging().send(message);
     console.log('Sent notification:', response);
   }

   const [,, token, title, body, url] = process.argv;
   sendPush(token, title || 'Z12 Challenge', body || 'Test push', url).catch(console.error);
   ```
2. `npm install firebase-admin`
3. Ask the user for their existing Firebase project's service account key
   (Project Settings → Service Accounts → Generate new private key), or
   confirm they already have `GOOGLE_APPLICATION_CREDENTIALS` set from
   website-side tooling.
4. Run:
   ```bash
   export GOOGLE_APPLICATION_CREDENTIALS="/path/to/serviceAccountKey.json"
   node scripts/send-notification.js "<TOKEN FROM STEP 7 LOG>" "Test" "Hello from Z12" "https://www.z12challenge.com/updates"
   ```
5. Confirm the notification appears on the test device.

## Do NOT do without explicit user confirmation
- Do not create a new Firebase project — the user wants to reuse their
  existing one.
- Do not commit `google-services.json`, `GoogleService-Info.plist`, or any
  service account key to version control. Add them to `.gitignore` if a
  git repo is present.
- Do not attempt to submit to the Play Store / App Store — that requires
  the user's developer account credentials and manual review of store
  listing content.

## Definition of done
- [ ] `google-services.json` / `GoogleService-Info.plist` in place and
  wired into Gradle/Xcode
- [ ] `push.js` created and `initPush()` fires on launch
- [ ] Device token appears in logs on a real build
- [ ] Test push sent via `scripts/send-notification.js` successfully
  received on device
- [ ] `.gitignore` updated to exclude Firebase credential files