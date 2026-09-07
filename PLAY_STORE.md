# Publishing to Google Play

The app now builds as `com.z12.mobileapp`, matching the existing Play listing. What follows is
everything between here and a live release.

## Two blockers only you can clear

### 1. The original upload keystore

An update to an existing listing **must be signed with the same upload key as the live
release**. A new key is rejected — there is no way around this from the code side.

- If you have it: copy `android/keystore.properties.example` to `android/keystore.properties`,
  fill in the path and passwords. Both are gitignored, along with `*.jks` / `*.keystore`.
- **The app is already in closed testing, which means a bundle has already been signed and
  uploaded with this key.** Whoever did that upload has it, or had it — that's the fastest
  lead, much faster than hunting the filesystem.
- If it's lost: Play Console → Setup → App signing → **Request upload key reset**. This only
  works if the app is enrolled in Play App Signing. If it isn't, the listing cannot be updated
  at all and you'd need a new one under a different package name.

### 2. Confirm the package name and versionCode

I inferred `com.z12.mobileapp` from the second Android client in your Firebase project. Verify
it against Play Console → the live app → App information, because **a listing's package name
can never change**. If it differs, tell me and I'll redo the rename.

`versionCode` is currently **2**. It must be strictly greater than **the highest versionCode
ever uploaded to any track** — not just production. The app is currently in closed testing, so
check Play Console → Release → **Releases overview**, which lists every track (internal,
closed, open, production) and the version in each. Take the highest, add one, and set it in
`android/app/build.gradle`. Play rejects a duplicate or lower one outright, so the failure is
loud rather than silent.

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

Everything in `store/` is generated from `Z12Challenge.jpg` and the running app:

| Asset | File | Play requirement |
|---|---|---|
| App icon | `store/play-icon-512.png` | 512×512, 32-bit PNG ✓ |
| Feature graphic | `store/play-feature-graphic-1024x500.png` | 1024×500, no alpha ✓ |
| Phone screenshots | `store/screenshots/01-home.png` … `04-races.png` | 1080×2400, ≥2 required ✓ |

Screenshots were captured from the app on an emulator with the status bar in demo mode
(fixed 9:30 clock, full battery) so they look clean and reproducible. Regenerate with the
commands in `TESTING.md` if the site's design changes.

All of it comes from the `Z12Challenge.jpg` lockup — `#191D23` ground, `#F6AC42` mark, both
sampled from the file rather than guessed — so the icon, splash, store graphics and the site
itself are one palette.

The launcher and store icons use the **Z12 mark alone**, not the full lockup: "CHALLENGE" sits
at about a fifth of the artwork's height, which is legible on the splash and feature graphic
but turns to mush at a 48dp launcher icon. The splash and feature graphic use the full lockup.
To use the full lockup everywhere instead, change `MARK` to `LOCKUP` in the asset generation
and re-run `@capacitor/assets`.

## Changing the store listing name

The name on the phone (`app_name` in `strings.xml`, `CFBundleDisplayName` on iOS) and the name
in the Play listing are separate fields. Both currently read "Z12 Challenge"; the phone one is
in this repo, the store one is not.

To change the store name: Play Console → your app → **Grow → Main store listing → App name**
(30 characters max), then Save. It applies to the listing testers and users see, needs no new
bundle, and does not touch `applicationId`. Store listing edits go through review, so expect a
short delay before it shows.

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
