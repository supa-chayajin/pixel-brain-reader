# How to build, verify and ship a signed release

Problem: produce a release-signed, R8-minified APK of PixelBrainReader, install it
on the daily-driver device without losing data, and publish it — without ever
letting the debug-keystore fallback slip through.

## Prerequisites

- All gates green: `./gradlew :app:testDebugUnitTest :app:lintDebug` (lint must
  report 0 errors / 0 warnings; the policy for the remaining hints lives in
  `app/lint.xml`).
- `CHANGELOG.md` `[Unreleased]` promoted to a version heading; `versionCode` /
  `versionName` bumped in `app/build.gradle.kts` (versionName is the single
  source of truth, surfaced via `BuildConfig.VERSION_NAME`).
- Release commit tagged locally (`git tag -a vX.Y.Z`). **Do not push yet** —
  the tag only goes to origin after the signature is verified.

## 1. Dogfood a release candidate first (no signing needed)

While `local.properties` has no `storeFile`/`storePassword`/`keyAlias`/
`keyPassword`, `assembleRelease` falls back to the **debug** keystore
(`app/build.gradle.kts`, `signingConfigs` block). A debug-signed release APK has
the same signature as the installed debug build, so:

```bash
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk   # in-place, no data loss
```

This is the only way to catch **release-only** bugs (R8 keep-rule gaps) on the
real device before signing. v10.0.0's frozen-widgets bug (stripped WorkManager
`InputMerger` constructor) was caught exactly here.

## 2. Produce the signed APK

Build it in **Android Studio** (Build → Generate Signed App Bundle / APK → APK →
release), using `waffle-commons/keystore.jks` — the credentials are remembered by
Android Studio. (The Gradle path via `local.properties` signing keys works too,
but Android Studio is the flow actually used.)

## 3. Verify the signature BEFORE touching the device

```bash
$ANDROID_HOME/build-tools/<latest>/apksigner verify --print-certs <signed>.apk
```

The certificate SHA-256 must start `3962f21e` (CN=PixelBrainReader). If it
starts `df99db49` (CN=Android Debug), the debug fallback happened — stop.

## 4. Archive the R8 mapping

Save `app/build/outputs/mapping/release/mapping.txt` (from the SAME build)
alongside the release — it is the only way to deobfuscate future crash logs
from `CrashActivity`. Attach it to the GitHub release in step 7.

## 5. One-time signature migration (debug-signed → release-signed install)

Android refuses to update across different signatures, so the first
release-signed install forces an uninstall. Before it:

1. In the running app, trigger a full sync and wait for success.
2. Spot-check on GitHub: today's daily note, `99_Private/*.md.enc` and the
   gamification JSON are at HEAD (everything vault-backed survives; private
   notes are password-encrypted, not Keystore-bound).
3. Accepted losses: Room-only AI chat history, stored tokens (GitHub re-login,
   Google re-consent), DataStore preferences, widget placements.

```bash
adb uninstall cloud.wafflecommons.pixelbrainreader
adb install <signed>.apk
```

Subsequent releases update in place — but the next `installDebug` would force
another uninstall (see the explanation page).

## 6. Smoke checklist (dependency-first order)

1. GitHub login (OAuth Device Flow; PAT entry reachable)
2. Vault clone completes; sync round-trips
3. Private vault note decrypts (password)
4. Daily board rehydrates today's note
5. Google sign-in (CredentialManager — historically the #1 release-only risk)
6. Web import of a URL (flexmark keep rules)
7. RAG search returns results; source chips open the cited note
8. Nano chat answers in French
9. Health Connect reads; gamification synergy applies
10. Re-place all widgets; verify they re-render after a mood log / habit toggle
    (WorkManager-driven Glance sessions)
11. A reminder notification fires (WorkManager, KEEP-on-startup policy)
12. Fenced code highlights (kotlin/json/yaml/bash/markdown)

## 7. Publish (only after smoke passes)

```bash
git push origin main --follow-tags
gh release create vX.Y.Z <signed>.apk mapping.txt --title "vX.Y.Z" --notes "<changelog section>"
```

Abort path: `adb uninstall` → `./gradlew installDebug` → in-app re-clone; fix,
rebuild, re-verify; move the local tag with `git tag -f` (it was never pushed).
