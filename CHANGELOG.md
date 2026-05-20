# Changelog

All notable changes to Pixel Brain Reader are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

_No unreleased changes._

## [7.0.0] — 2026-05-20

Security-hardened release with R8 minification, an upgraded native vector
search engine, persistent chat history, and a Google integration narrowed to
import-only.

### Added

- **Persistent chat history.** New `ChatRepository`, `ChatDao`, and
  `ChatMessageEntity` back a durable conversation log; `ChatViewModel` rehydrates
  from Room and `LocalAiManager` performs augmented inference over prior turns.
- **Manual RAG indexing control.** Settings → Intelligence now exposes a
  user-triggered re-index action wired through `SettingsViewModel` and
  `IndexingWorker`; the worker also runs automatically at application startup
  and after every successful `SyncOrchestrator` cycle.
- **Encrypted private notes.** `PrivateNoteRepository` and `NoteRepository`
  gain encrypted-content paths; `VaultDiscoveryRepository` keeps the file index
  aligned with the new encryption flag on `EmbeddingEntity` / `FileDao`.
- **Upgraded vector search engine.** `VectorSearchEngine` is rewritten with a
  native component (`app/src/main/cpp/CMakeLists.txt`, `stub.cpp`) and a second
  bundled model asset (`assets/sentences_encoder.tflite` alongside
  `universal_sentence_encoder.tflite`). Indexing throughput on Pixel 9 Pro Fold
  is materially improved; ProGuard rules updated to keep the native bridge.
- **Splash screen + async login resolution.** `MainActivity` resolves the
  saved-token / login state off the main thread behind a new `SplashScreen`
  composable, eliminating a cold-start frame stall.
- **Release signing + process-aware init.** `app/build.gradle.kts` ships a
  release signing config; `PixelBrainApplication` short-circuits initialisation
  for the `:crash` process so the crash UI never re-enters Hilt graphs.
- **Unified AI persona / French prompts.** `LocalAiManager` and `ChatViewModel`
  share a single persona definition with localised French prompt scaffolding.
- **Semantic theme tokens.** New `ui/theme/SemanticColors.kt` plus tuned
  `Theme.kt` palette; multiple surfaces (chat, daily, gamification, homeos,
  journal, lifeos, lifestats, login, mood) migrated to the semantic tokens.

### Changed

- **R8 minification + resource shrinking are now enabled for release.**
  `app/proguard-rules.pro` was expanded with comprehensive keep rules for Hilt,
  Room, kotlinx-serialization, JGit, MediaPipe, Generative AI, and the new
  native vector engine; release builds also strip logs automatically.
- **`CryptoManager` is suspend-only and CPU-bound work runs on
  `Dispatchers.Default`** instead of blocking callers.
- **Google sync is import-only.** `CalendarSyncWorker` and `TaskSyncWorker`
  were removed; `GoogleCalendarRepository`, `GoogleTaskRepository`,
  `TaskRepository`, and `DailyDashboardRepository` were simplified to one-way
  read paths. Calendar/task entities (`DailyTaskEntity`, `TimelineEntryEntity`)
  and their DAOs shed the outbox/write surface accordingly.
- **`SyncOrchestrator` triggers indexing after a successful sync** and emits
  richer logging across `GoogleAuthRepository`, `GoogleCalendarRepository`,
  and `GoogleTaskRepository`.
- **Chore sync hardened.** `ChoreRepository` gains conflict-aware merge logic;
  `ChoreDao` and `HomeRoomDao` expose the queries it needs.
- **Health metrics refactor.** `HealthMetricsRepository`, `HealthConnectManager`,
  and `SyncHealthDataUseCase` were restructured around the migrated Room
  schema; `IndexingWorker` moved from `data/ai/` to `data/workers/`.
- **Background sync waits for device idle.** `WorkManager` constraints on the
  remaining periodic workers now require `setRequiresDeviceIdle(true)`.
- **Crash UI restricts technical stack traces to debug builds.**
  `CrashActivity` only renders raw stack frames when `BuildConfig.DEBUG` is
  true; release builds show the user-facing message.
- **Gemini Nano lifecycle remains fully decoupled from inference** (carried
  forward from the prior `Unreleased` block): `LocalAiManager.generateResponse`
  fast-fails with `NanoException.Unavailable` when the model is not `Ready`;
  Settings → Intelligence orchestrates download / progress / "Manage storage"
  and `GeminiRagManager.generateWithLocalEngine` routes through the same gate.
- **DailyNoteViewModel, LifeStatsViewModel, ChatViewModel, AutomateHabitsUseCase**
  refactored to consume the new vector engine + repository surfaces.

### Removed

- **`DailyBriefingWorker` and `HealthSyncWorker`** — superseded by
  `DailyExportWorker` + on-resume orchestration.
- **`CalendarSyncWorker` and `TaskSyncWorker`** — Google integration is
  import-only; outbox/write paths deleted from the Google repositories.
- **`allowBackup` and sensitive path/metadata logging** — `AndroidManifest.xml`
  disables auto-backup; repositories no longer log absolute vault paths.
- **`GenerativeModel.warmup()`** — first inference pays a one-time ~6 s load on
  Pixel 9 Pro Fold; subsequent calls run in ~2.7 s.

### Security

- App data backup is disabled (`allowBackup="false"`).
- Stack traces are gated behind `BuildConfig.DEBUG`.
- Release builds enable R8 minification, resource shrinking, log stripping,
  and ship a dedicated signing configuration.
- Sensitive file paths and metadata removed from production log statements.

## [6.1.0]

Baseline at the time this changelog was introduced. Historical changes prior to
this entry are preserved in the git log (`git log --oneline`).
