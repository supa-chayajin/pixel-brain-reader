# Changelog

All notable changes to Pixel Brain Reader are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

_No unreleased changes._

## [9.1.0] — 2026-07-17

Navigation, settings, and launcher-icon polish for the pre-dogfooding build,
plus the "living" weather icons and the redesigned launcher chip that landed
after 9.0.0.

### Added

- **Stats as a Life-OS hub.** The Life Stats screen now has top-bar shortcuts to
  Habits and Chores, and Habits & Chores each show a Stats shortcut. Navigation
  is back-stack aware: opening Habits/Chores from Stats (or Stats from either)
  returns to the exact screen you came from instead of stacking new copies —
  handy on the folded display where those tabs aren't in the navbar.
- **"Living" weather icons.** Colourful custom vector illustrations (sun,
  partly-cloudy, rain, snow, storm, fog, unknown, offline) replace the flat
  Material glyphs, rendered untinted with a subtle bob + breathe animation.

### Changed

- **Launcher icon redesigned & re-padded.** New duotone "pixel-brain chip"
  (cyan→magenta processor die + pins with a centred brain) on a solid white
  background, with a matching Android-13 themed monochrome layer and status-bar
  notification icon. Inner padding increased (foreground **and** monochrome now
  scale to 0.75) so the pins clear Android 16/17's tighter adaptive masks,
  including the 7-sided "cookie" mask.
- **Settings reorganized.** "Ordre de la barre de navigation" and "Effets
  sonores" moved out of *Life OS Automations* into a dedicated **Interface &
  Sound** section; *Knowledge Vault (RAG)* now sits beside *Intelligence* (both
  are the on-device brain). Section order tidied.
- Version bumped to **9.1.0** (`versionCode` 910).

### Fixed

- File-list **"No results"** empty state now uses a crossed-out magnifier
  (`SearchOff`) instead of a plain search glyph.
- Cleared two Android Lint errors: `SettingsScreen` resolves its host Activity
  via a `ContextWrapper` walk, and `MoodCalendar` reads the locale from
  `LocalConfiguration`. `lintDebug` is green.

## [9.0.0] — 2026-07-17

Stability, data-safety and security hardening pass (full code review), plus a
reader-mode web importer and GitHub OAuth Device Flow login.

### Added

- **"Login with GitHub" (OAuth Device Flow).** New `GitHubDeviceAuthService` /
  `GitHubDeviceAuthRepository`; the login screen shows a user code, opens the
  browser to approve, and drops the resulting token into the same secure store
  the PAT flow uses. Requires an OAuth App client id in `local.properties`
  (`githubOauthClientId`); hidden otherwise. PAT login is preserved.
- **Reader-mode web importer.** `ImportWorker` now strips chrome/ads/nav,
  scores the DOM for the densest article body, rewrites image/link URLs to
  absolute, extracts Open-Graph metadata (title/author/date/site/hero image),
  and emits clean Obsidian-flavoured markdown with source + AI-summary callouts.
- **Cold-start data recovery.** `DailyDashboardRepository.rehydrateTodayFromDiskIfEmpty()`
  rebuilds today's board (dashboard/timeline/tasks/scraps/gratitude) from the
  last burned markdown after a destructive Room migration; extracted
  `DailyMarkdownParser` (the inverse of `MarkdownBurner`) with round-trip tests.
- **Import URL SSRF guard.** `ImportUrlValidator` (unit-tested) + a user
  confirmation dialog gate the public `pixelbrain://import` deep link.
- **Tests.** New JVM suites: `SyncOrchestratorTest`, `DailyMarkdownParserTest`,
  `ImportUrlValidatorTest`, `SafeFileProviderTest`.

### Fixed

- **Data loss on schema bump (CRITICAL).** Today's board / scraps / gratitude
  are now continuously burned to disk (start of every sync cycle + on app
  background) and the nightly burn runs **23:50 daily, unconditional** (was
  23:00 gated on device-idle/battery, which could defer for days).
- **Silent sync stall.** A rebase conflict now stops the cycle with a visible
  `SyncState.Error` instead of aborting, failing the push, and reporting Success.
  A failed push likewise surfaces an error. Conflict backups now capture the
  clean local file (post-abort) instead of merge-marker garbage.
- **Structured concurrency.** `CancellationException` is rethrown across the
  sync cycle and `LocalAiManager` instead of being swallowed into an error toast.
- **Sync serialization.** `SyncOrchestrator` and `FileRepository.syncRepository`
  now share a `GitSyncCoordinator` lock so explicit saves and the foreground
  cycle can't interleave; `JGitProvider.setRemote` runs under the git mutex.
- **Folder-insight overwrite.** Viewing an AI folder summary can no longer save
  itself over the previously-open note.
- **Unsaved-edit flush.** The background auto-save flush now reads live state
  (`rememberUpdatedState`) instead of a stale value captured at file-open.
- **Release logging leak.** OkHttp `BODY` logging (which included GPS query
  params) is gated behind `BuildConfig.DEBUG`.
- **Crash loop.** `GlobalExceptionHandler` bails to the platform handler after
  repeated rapid crashes instead of relaunching the crash UI's restart loop.
- **Worker retries.** `ImportWorker` retries transient failures (capped);
  `DailyExportWorker` no longer retries permanent failures forever.
- **Atomic writes.** `SafeFileProvider.atomicWrite` uses `Files.move(ATOMIC_MOVE)`
  (no delete-then-rename crash window).

### Changed

- Version bumped to **9.0.0** (`versionCode` 900).
- Heavy file-list sorting moved off the main thread (`MainViewModel`); the chat
  history query respects `WhileSubscribed`; Mood-repo import no longer holds a
  Room transaction across the filesystem walk.

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
