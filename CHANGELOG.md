# Changelog

All notable changes to Pixel Brain Reader are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed
- **Gemini Nano lifecycle is now fully decoupled from inference.** `LocalAiManager.generateResponse`
  no longer triggers an implicit download or warmup — it fast-fails with
  `NanoException.Unavailable` when the model is not `Ready`. The Settings screen
  is the sole orchestrator of the on-device model lifecycle.
- **Settings → Intelligence** now renders the model lifecycle inline under the
  "Cortex (On-Device)" option: Download (~1.5 GB) → progress bar → green
  checkmark + "Manage storage" icon-button that deep-links to Android AICore's
  app settings. The "Cortex (On-Device)" radio stays disabled until the model
  reports `Ready`; tapping it early shows a snackbar instead of switching the
  preference.
- `GeminiRagManager.generateWithLocalEngine` now routes through `LocalAiManager`
  so the Settings-gated contract also covers the RAG / `analyzeFolder` paths.

### Removed
- `GenerativeModel.warmup()` is no longer called. On Pixel devices it could hang
  indefinitely at the AICore service layer and wedge subsequent
  `generateContent()` calls. First inference now pays a one-time load cost
  (~6 s on Pixel 9 Pro Fold); subsequent calls run in ~2.7 s.

### Added
- `NanoState.NotDownloaded` — distinguishes "downloadable, waiting on user" from
  the device-`Unavailable` and transient-`Error` cases.
- `LocalAiManager.downloadModel()` and `openAicoreSettings()` — explicit
  user-driven entry points wired to the new Settings UI.
- Unit test guarding the lifecycle-decoupling contract: `generateResponse`
  fast-fails with `NanoException.Unavailable` when the manager is not `Ready`.

## [6.1.0]

Baseline at the time this changelog was introduced. Historical changes prior to
this entry are preserved in the git log (`git log --oneline`).
