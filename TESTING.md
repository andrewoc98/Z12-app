# Z12 Challenge app — local testing

The app is a wrapper: the WebView loads `https://www.z12challenge.com/` directly
(`capacitor.config.json` → `server.url`), so **none of our own JavaScript runs inside it**.
Push registration is therefore done in native code — `MainActivity.java` on Android,
`AppDelegate.swift` on iOS. The website (`Z12Website2.0`) additionally reports the device's
token against the signed-in user via `src/app/shared/lib/nativePush.ts`, so pushes can be
addressed to specific people.

`www/index.html` is now the offline fallback page (`server.errorPath`), shown when the site
can't be reached.

App icons and splash screens are generated from `Z12Logo_Black.jpg` into `assets/` and then
into both platforms:

```bash
npx @capacitor/assets generate --android --ios \
  --iconBackgroundColor '#ffffff' --iconBackgroundColorDark '#ffffff' \
  --splashBackgroundColor '#ffffff' --splashBackgroundColorDark '#ffffff'
```

That tool rewrites `mipmap-anydpi-v26/ic_launcher*.xml` with an inset background drawable,
which leaves transparent corners under some launcher masks — after regenerating, re-point the
`<background>` at `@color/ic_launcher_background` as it is now.

## Android — full end-to-end test

Firebase is already wired up: `android/app/google-services.json` registers
`com.z12challenge.app` against project `z12-website-prod`.

### 1. Build and install

Android Studio needs its own JDK (system Java is 25; Gradle 8.14 wants ≤ 21):

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
```

Start a device (the `glycofuel_test` AVD already exists — a Google **Play** or **Google APIs**
image is required, FCM does not work on a plain AOSP image):

```bash
emulator -avd glycofuel_test &
adb wait-for-device
```

Then build and install:

```bash
npx cap sync android
cd android && ./gradlew installDebug && cd ..
adb shell am start -n com.z12challenge.app/.MainActivity
```

Or open it in the IDE instead: `npx cap open android`, then Run.

### 2. Confirm the device token

```bash
adb logcat -s Z12Push
```

Expect, within a second or two of launch:

```
I Z12Push : Push registration token: fXy...:APA91b...
```

Copy that token. Android 13+ shows a notification permission prompt on first launch — accept
it, or the notification won't be displayed (the token is still issued either way).

If you see `Push registration error` instead, the emulator image has no Google Play services.

### 3. Send a test push

You need a service-account key for `z12-website-prod` (Firebase console → Project settings →
Service accounts → Generate new private key). If your website tooling already exports
`GOOGLE_APPLICATION_CREDENTIALS`, reuse that.

```bash
export GOOGLE_APPLICATION_CREDENTIALS="/path/to/serviceAccountKey.json"
node scripts/send-notification.js "<TOKEN FROM STEP 2>" "Test" "Hello from Z12" "https://www.z12challenge.com/updates"
```

Background the app first (press Home) — with the app in the foreground FCM hands the message
to the app rather than the system tray, so nothing appears on screen.

Expect: a notification on the device; tapping it opens the app and navigates the WebView to
the `url` you passed. Only `https://*.z12challenge.com` URLs are followed — anything else is
logged and ignored, so a push can't be used to load arbitrary content into the app.

### Known harmless log lines

- `Capacitor/Console: Uncaught TypeError: Cannot read properties of undefined (reading
  'triggerEvent')` — the native bridge fires an app-state event before the injected bridge
  script has initialised on the remote page. Pre-existing wrapper behaviour; the site loads
  fine after it.
- `Capacitor: Unable to find a Capacitor plugin to handle permission requestCode ... 4711` —
  that's our own `POST_NOTIFICATIONS` request, which the bridge doesn't own.

## Event notifications

Two Cloud Functions in `Z12website-functions/functions/src/eventNotifications.ts`:

| Function | Fires | Message |
|---|---|---|
| `onEventCreate` | a document is created in `events/` with `status: "open"` and `closeAt` in the future | "Registration for {name} is now available — sign up!" |
| `eventClosingReminder` | hourly schedule, for events whose `closeAt` is inside the next 24h | "Registration for {name} closes in 24 hours..." |
| `registerDevice` / `unregisterDevice` | callables; the website calls them on sign-in / sign-out | — |

Both deep-link to `https://www.z12challenge.com/events/{eventId}`, which the tap handler
accepts. `eventClosingReminder` stamps `closingReminderSentAt` on the event so it only ever
reminds once, and skips events created inside their own final 24 hours (those already got the
"registration open" push an hour earlier).

The two use **different addressing**, on purpose:

- `onEventCreate` broadcasts to the FCM topic `events`. Every install subscribes on launch
  (`MainActivity.subscribeToEventsTopic`, confirmed by `I Z12Push : Subscribed to topic:
  events` in logcat), signed in or not — a new event should reach everybody.
- `eventClosingReminder` sends **device by device**, because a topic can't exclude anyone.
  It collects `rowerUids` from every boat in `events/{eventId}/boats`, then sends to every
  registered device whose owner isn't in that set. Nobody gets chased to sign up for an
  event they're already racing.

Device targeting depends on the token store: `users/{uid}/devices/{token}`, written only by
the `registerDevice` callable (Firestore rules deny client writes). The website calls it from
`AuthProvider` on every auth state change, so **a device only receives the closing reminder
once its user has signed in on the app at least once.** Signed-out installs still get the
"registration open" broadcast.

### Testing them

Deploy to dev first:

```bash
cd ../Z12website-functions/functions
npm run build
firebase use z12-website        # NOT prod
firebase deploy --only functions:onEventCreate,functions:eventClosingReminder
```

Then create an event in the dev site and watch `firebase functions:log`. Note the app's
`google-services.json` points at **z12-website-prod**, so a dev-project function won't reach
your emulator — to test the whole path end to end you either deploy to prod or temporarily
send by hand:

```bash
# same credentials as the single-device test above
node scripts/send-notification.js --topic events "Registration open" "Registration for Greenwich Z12 Challenge is now available — sign up!" "https://www.z12challenge.com/events/abc123"
```

`eventClosingReminder` runs on a schedule, so to exercise it without waiting, set an event's
`closeAt` to ~12 hours out, clear `closingReminderSentAt`, and run it from the console
(Cloud Scheduler → Force run).

### Verifying the exclusion

1. Sign in on the app and confirm a device doc appears at `users/{yourUid}/devices/{token}`.
2. Set an event's `closeAt` to ~12 hours out and clear `closingReminderSentAt`.
3. Force-run `eventClosingReminder` (Cloud Scheduler → Force run) and read the log line:
   `Reminder sent for event <id> to N device(s), skipping M registered rower(s)`.
4. Join a boat for that event, clear `closingReminderSentAt` again, re-run — your device
   should now be in the skipped count and no notification should arrive.

Club-level filtering (only notify members of the event's `clubId`) is still not built; the
device doc carries the uid, so it would be a matter of reading each user's `clubMemberships`.

## iOS: what is still outstanding

`AppDelegate.swift` requests permission, registers with APNs, forwards the token to
`@capacitor/push-notifications`, and logs the APNs token. That is as far as this repo can go
unaided — **iOS push cannot be tested yet**, because:

0. **No topic subscription.** `MainActivity` subscribes to `events` via `FirebaseMessaging`;
   iOS has no equivalent until the Firebase SDK is added (point 2), so iOS installs receive
   no event announcements even once APNs works.
1. **No `GoogleService-Info.plist`.** Add an iOS app (bundle ID `com.z12challenge.app`) to the
   `z12-website-prod` Firebase project, download the plist, and drag it into `ios/App/App/` in
   Xcode with "Copy items if needed" ticked. It's already in `.gitignore`.
2. **No Firebase iOS SDK.** `@capacitor/push-notifications` on iOS returns a raw *APNs* token,
   which `firebase-admin` cannot address. To send via FCM you must add `firebase-ios-sdk`
   (FirebaseMessaging) to the Xcode project — File → Add Package Dependencies — and swap the
   APNs token for an FCM one. Note `ios/App/CapApp-SPM/Package.swift` is regenerated by
   `npx cap sync`, so add the package through the Xcode project, not that file.
3. **Push Notifications capability** must be enabled on the App target (Signing & Capabilities
   → + Capability → Push Notifications, and Background Modes → Remote notifications).
4. **A paid Apple Developer account** for the APNs auth key, which is uploaded to Firebase
   (Project settings → Cloud Messaging → APNs Authentication Key), plus a **physical device**
   — the simulator can't receive real pushes.

## Sending the token to a backend

Nothing stores the token yet. `MainActivity.TOKEN_ENDPOINT` is an empty string; set it to a
real endpoint (and mirror it in `www/push.js`'s `TOKEN_ENDPOINT`) once one exists. A callable
Cloud Function in `Z12website-functions` writing to a `deviceTokens` collection would be the
natural home.
