# Publishing to Google Play

The app now builds as `com.z12.mobileapp`, matching the existing Play listing. What follows is
everything between here and a live release.

## Two blockers only you can clear

### 1. The original upload keystore

An update to an existing listing **must be signed with the same upload key as the live
release**. A new key is rejected — there is no way around this from the code side.

- If you have it: copy `android/keystore.properties.example` to `android/keystore.properties`,
  fill in the path and passwords. Both are gitignored, along with `*.jks` / `*.keystore`.
- If it's lost: Play Console → Setup → App signing → **Request upload key reset**. This only
  works if the app is enrolled in Play App Signing. If it isn't, the listing cannot be updated
  at all and you'd need a new one under a different package name.

### 2. Confirm the package name and versionCode

I inferred `com.z12.mobileapp` from the second Android client in your Firebase project. Verify
it against Play Console → the live app → App information, because **a listing's package name
can never change**. If it differs, tell me and I'll redo the rename.

`versionCode` is currently **2**. It must be strictly greater than the live release's — read
that from Play Console → Release → Production, and bump `android/app/build.gradle` if needed.
Play rejects the upload outright if it's too low, so the failure is loud, not silent.

## Build the release

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
cd android && ./gradlew clean :app:bundleRelease
# → android/app/build/outputs/bundle/release/app-release.aab
```

With `keystore.properties` present the AAB is signed and uploadable. Without it the build still
succeeds but produces an **unsigned** bundle — useful for checking it compiles, useless to Play.
Confirm before uploading:

```bash
unzip -l android/app/build/outputs/bundle/release/app-release.aab | grep -E "META-INF/.*\.(RSA|SF)"
```

## Store listing assets

Everything in `store/` is generated from `Z12Logo_Black.jpg` and the running app:

| Asset | File | Play requirement |
|---|---|---|
| App icon | `store/play-icon-512.png` | 512×512, 32-bit PNG ✓ |
| Feature graphic | `store/play-feature-graphic-1024x500.png` | 1024×500, no alpha ✓ |
| Phone screenshots | `store/screenshots/01-home.png` … `04-races.png` | 1080×2400, ≥2 required ✓ |

Screenshots were captured from the app on an emulator with the status bar in demo mode
(fixed 9:30 clock, full battery) so they look clean and reproducible. Regenerate with the
commands in `TESTING.md` if the site's design changes.

The feature graphic uses the site's own palette — `#1E1E22` ground, `#FEB959` mark — sampled
from the live page rather than guessed. Note the app icon is deliberately the **black-on-white**
mark you asked for, while the site and feature graphic are yellow-on-dark; if you'd rather the
launcher icon matched the site, say so and I'll regenerate it.

## Data safety and privacy

Play's Data safety form is mandatory and must match what the app actually does:

- **Privacy policy URL**: `https://www.z12challenge.com/privacy` (the route exists in the site).
- **Data collected**: the app sends the device's **FCM token** to your backend, stored at
  `users/{uid}/devices/{token}` and tied to the signed-in account. Declare it under
  *Device or other IDs → Device or other IDs*, collected, linked to the user, used for
  *App functionality* (notifications). It is not shared with third parties and not used for
  advertising or tracking.
- Everything else — name, email, payments — is collected by the **website** inside the WebView,
  so it still counts as collected by the app. Declare *Personal info* (name, email address) and,
  because Stripe checkout runs in the WebView, *Financial info → Purchase history*.
- **Account deletion**: Play requires apps with accounts to offer a deletion route, including a
  web URL. Confirm the site has one before you submit.

## Policy risk worth knowing

Play's **Minimum Functionality / spam** policy explicitly targets apps that are only a website
in a WebView. This app is exactly that shape, so rejection is a genuine possibility. What is
already in its favour:

- Real native functionality beyond the web view: push notifications, with deep-linking into the
  right page on tap.
- An offline fallback screen (`server.errorPath`) instead of a browser error.
- It's a first-party app for a site you own, not a wrapper around someone else's content.

If it is rejected on those grounds, the usual fix is to add more native capability — a home
screen widget, share targets, or offline caching of results.

## Compliance status

| Requirement | Status |
|---|---|
| App Bundle (.aab), not APK | ✓ |
| `targetSdk` 36 (Play minimum is 35) | ✓ |
| 16 KB page size — verified `p_align=16384` on all four ABIs | ✓ |
| Not debuggable in release | ✓ |
| Package matches the live listing | ⚠ verify — see blocker 2 |
| Signed with the original upload key | ✗ needs your keystore |
| Data safety form | ✗ console only |
| Content rating questionnaire | ✗ console only |

`minifyEnabled` is deliberately left off: Capacitor resolves plugin classes by reflection, and
there is almost no app Java to shrink.
