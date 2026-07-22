# Why release builds are a different animal: signatures, R8, and the migration

## Two signatures, one package name

Debug and release builds share the applicationId (`cloud.wafflecommons.pixelbrainreader`
— the debug build type deliberately has no `applicationIdSuffix`, so both can never
coexist). Android identifies an app's owner by its signing certificate: the debug
keystore (`CN=Android Debug`, SHA-256 `df99db49…`) and the release keystore
(`waffle-commons/keystore.jks`, `CN=PixelBrainReader`, SHA-256 `3962f21e…`) are
different owners, so switching between them forces a full uninstall — with the loss
of everything that is not vault-backed (Room-only chat history, encrypted token
store, DataStore preferences, widget placements).

This cuts both ways: once the daily driver runs the release-signed build, a future
`./gradlew installDebug` would itself force an uninstall. The working policy after
v10.0.0 is: **the device daily-drives release-signed builds; debug builds live on
the emulator** (or accept another migration).

The signing config in `app/build.gradle.kts` reads the four `storeFile`/
`storePassword`/`keyAlias`/`keyPassword` keys from `local.properties` and *silently
falls back to the debug keystore* when any is missing. That fallback is a feature,
not a bug (see below) — but it is why `apksigner verify --print-certs` is a
mandatory gate before any signed APK touches the device.

## The debug-signed release candidate trick

Because the fallback produces a **debug-signed but fully R8-minified** APK, a
release candidate can be dogfooded *in place* over the debug install: same
signature → `adb install -r` → no uninstall, no data loss. This converts the most
dangerous class of Android bug — code that only breaks after minification — from a
release-day surprise into a mid-week test. Keep the signing keys **out of**
`local.properties` until the actual signing step so `assembleRelease` stays in
RC mode by default.

## Why minification keeps finding new victims

R8 removes and renames everything it cannot prove reachable. Reflection breaks that
proof. This codebase's release-only failures have all been the same shape:

- **Gson field mapping** (OpenMeteo weather DTOs, then `DailyHealthMetrics` and the
  gamification `Attribute` enum): fields renamed → parsing silently yields nulls —
  and where the JSON is *persisted*, renamed enum constants also corrupt stored
  data across builds.
- **flexmark's html→markdown converter** (web import): resolves extensions and
  DataKey holders reflectively.
- **WorkManager's `InputMerger`** (the v10.0.0 frozen-widgets bug): WorkManager
  instantiates `OverwritingInputMerger` reflectively by class name with a no-arg
  constructor. Jetpack Glance renders every widget through WorkManager-driven
  session jobs, so one stripped constructor silently killed all widget re-renders
  — the snapshot pipeline (Room → `widget_snapshot.json` via kotlinx.serialization,
  which is compile-time-safe) kept working, making the freeze look like a data bug
  when it was an R8 one. Glance `ActionCallback`s (widget taps) follow the same
  reflective pattern and are kept alongside.

The heuristic that falls out: **any library that names classes in strings —
WorkManager factories, Gson/TypeToken, ServiceLoader (JGit), JNI (TFLite/DJL) —
needs an explicit keep rule, and its keep rule needs an on-device release smoke
test.** kotlinx.serialization needs none: its serializers are generated at compile
time, which is why the vault-JSON repositories and the widget snapshot survived
minification untouched.

## Where the rules live

`app/proguard-rules.pro` is load-bearing and organized by library, each section
carrying the failure mode it prevents. Deliberate lint-level ignores (pinned
toolchain advisories, third-party jar warnings) are documented in `app/lint.xml`
instead — policy belongs next to the tool that enforces it.
