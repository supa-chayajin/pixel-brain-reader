# Changelog

All notable changes to Pixel Brain Reader are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed

- **Daily-note frontmatter no longer degrades over repeated burns.** The burner
  received the YAML *without* its `---` fences and appended it verbatim, so the second
  burn of a day emitted an unfenced block (breaking Obsidian properties) and the third
  reset it, silently dropping user-added keys. The burner now always re-fences.
  Reported by the new round-trip test harness.
- **AI source citations are now tappable.** The source chips under a RAG answer open
  the cited vault note (chat → file browser detail pane) instead of doing nothing —
  the last dead button in the app.
- **Rebase-conflict backups now actually happen for committed edits.** JGit reports a
  committed-vs-committed conflict as `STOPPED` with a `null` conflict list, so the
  documented "abort then back up the local files" flow silently backed up nothing
  (the data only survived inside the local commit). The provider now reads the
  conflicted paths from git status while the rebase is stopped and writes the
  timestamped backups after the abort — making the "your local changes were saved"
  sync message literally true. Also: `NOTHING_TO_COMMIT` now counts as a successful
  pull, and `UNCOMMITTED_CHANGES` surfaces an error instead of a bogus success.

- **Cancelled syncs no longer masquerade as sync failures.** Every git/repository/AI
  `catch (e: Exception)` that converted failures into results (JGitProvider's 10 op
  wrappers, Mood/Habit/VaultDiscovery repository parse-and-push paths, the Gemini Nano
  download stream) now rethrows `CancellationException` instead of swallowing it, so a
  cancelled coroutine unwinds cleanly rather than surfacing a bogus `SyncState.Error`
  or polluting logs.
- **Explicit save-and-sync now stops on a rebase conflict instead of pushing.**
  `FileRepository.syncRepository` treated a conflicted pull as non-fatal and relied on
  the subsequent push being rejected; it now stops immediately with the same
  actionable "Sync conflict: your local changes were saved" failure the foreground
  `SyncOrchestrator` cycle reports, and never attempts to push into diverged history.

- **Release builds no longer risk silently breaking web import, health synergy or the
  gamification stat mapping.** R8 keep rules added for the flexmark html→markdown
  converter (reflection-driven; used by the share-target / `pixelbrain://import`
  pipeline) and for the two Gson-reflected types living outside the kept packages
  (`DailyHealthMetrics`, the `Attribute` enum) — the same release-only failure class
  as the old OpenMeteo weather bug.
- **Release logcat no longer prints personal data.** The Google account e-mail and
  vault file paths were logged at levels that survive minification (`Log.i`/`w`/`e`);
  they are now debug-only (stripped by R8) or scrubbed, and the last `println` calls
  are gone.

- **Fenced code blocks are finally syntax-highlighted.** Ten languages (kotlin, java,
  javascript, json, yaml, bash, python, markup/HTML, markdown, clike) with the usual
  aliases (`kt`, `js`, `sh`, `yml`, `md`, `py`…), light/dark theme following the app
  surface. Grammars are vendored from Prism4j (Apache-2.0) plus a hand-written bash
  grammar; a manual locator avoids the kapt-only bundler, so the KSP-only toolchain
  is untouched. Unknown languages still render as plain text.

### Removed

- Dead code: `GenerativeModelStub` (unreferenced mock AI) and the unused
  `BiometricHelper` (the private vault drives `BiometricPrompt` directly).

### Changed

- **L'interface est désormais entièrement en français.** ~250 chaînes en dur
  (écrans, boîtes de dialogue, boutons, descriptions d'accessibilité, messages,
  libellés des widgets et raccourcis) unifiées en français pour s'accorder avec
  l'IA French-first — l'app parlait jusqu'ici un mélange anglais/français. Les
  identifiants techniques (routes, clés persistées, motifs de date, en-têtes
  markdown du coffre) sont intacts ; l'externalisation `stringResource` reste
  prévue pour une version ultérieure.
- `DailyBriefingRepository` restructured to drop all `!!` unwraps (smart-cast-friendly
  cache handling), rethrow cancellation, and log through `Log.d` instead of `println`;
  the dead "freshness" computation in `DailyDashboardRepository.getOrGenerateBriefing`
  was removed (the cache row is keyed by date — its presence is the policy).

### Added

- **Regression tests for the two highest-risk untested paths**: the daily-note
  burn→parse round trip (external Google-sync keys must survive — the "items triple on
  every sync" bug class, 25 tests) and the JGit rebase-conflict abort/backup recovery
  flow with a real JGit repo harness (6 tests).

## [9.4.0] — 2026-07-20

Two dogfooding follow-ups: on-device AI now answers in **French**, and Mood **activity
tags are user-managed** in a dedicated, vault-synced settings page.

### Added

- **Mood Tags settings page** (`Settings ▸ Life OS Automations ▸ Manage Mood Tags`). Add,
  remove and reorder the activity tags offered when logging a mood. This list is now the
  canonical source for the mood check-in sheet (previously a hardcoded, categorized list).
  It is stored as `10_Journal/data/config/mood_tags.json` in the vault, so it **syncs across
  devices via git** — seeded from the former defaults on first run, written + pushed on the
  first edit. Historical mood entries keep the tags they were saved with; curating the list
  only changes what is *offered* going forward. Backed by the new `MoodTagRepository`.

### Changed

- **All on-device AI output is now in French.** Chat, daily briefing/quote/weather advice, the
  RPG Oracle quest, folder insight (map-reduce), private-journal writing assist
  (improve / continue / inspire), Markdown beautify and the web-import summary all reply in
  French. Prompts were rewritten in French prose (the strongest steer for Gemini Nano) and the
  explicit "answer in French" directive is centralised in the new `data/ai/AiLanguage.kt`. The
  chat scaffold labels in `LocalAiManager` were translated too so the surrounding structural
  language reinforces the response language.
- **The mood check-in sheet reads the curated tag list** instead of the hardcoded categorized
  activities. Known/seed tags keep their original icons; user-added tags get a generic tag glyph.
- **Speech-to-text is pinned to French.** The private-vault dictation and the Cortex chat voice
  input now request `fr-FR` (`RecognizerIntent.EXTRA_LANGUAGE` / `EXTRA_LANGUAGE_PREFERENCE`)
  instead of the device default, so dictated notes and prompts transcribe in French.

## [9.3.0] — 2026-07-17

Home-screen widget suite + app actions, a full English localization pass, and three
dogfooding fixes.

### Added

- **Seven-widget home-screen suite** (Jetpack Glance). The "Pixel Dashboard" Companion
  widget is now *responsive* (compact → full: hero + XP bar, habits/tasks/chores progress
  rings, steps/sleep/heart-rate, vitality graph, quick-action row). Six new widgets:
  - **Health Today** — steps goal bar + sleep / heart-rate / active / calories / distance /
    hydration tile grid.
  - **Mood Check-in** — one-tap emoji mood logging straight from the home screen.
  - **Habits** — today's scheduled habits, tap to complete (writes vault + Room + XP).
  - **Today's Tasks** — daily-note tasks, ticked off in place.
  - **Chores Due** — chores due today, mark done to earn their effort as XP.
  - **Quick Actions** — six deep-link buttons (capture, daily, mood, habits, chores, ask).
- **App shortcuts.** Six launcher long-press shortcuts (Capture, Daily, Mood, Habits,
  Chores, Ask) with custom icons, routed through a new whitelisted `pixelbrain://open` /
  `pixelbrain://capture` deep-link contract that also backs the widget taps. Navigation is
  deferred safely when a widget/shortcut is triggered while logged out.
- **Richer widget snapshot.** `WidgetSnapshotManager` now aggregates full Health Connect
  metrics plus habit/task/chore progress into the shared snapshot, and `WidgetUpdateManager`
  refreshes the whole suite at once.

### Fixed

- **Gamification profile could be wiped to level 1.** `GamificationRepository.updateState`
  mutated the in-memory state, which is only hydrated when the Habits screen opens
  (`loadState()`). A widget quick-action (or any early caller) firing before hydration mutated
  the *default* state and saved it over the real profile — a single widget-chore tap reset a
  level-9 profile to level 1. `updateState` now bases every mutation on the freshest **on-disk**
  state, and the profile is hydrated on app startup. (The vault is git-backed, so a wiped profile
  is recoverable from history.)
- **Widgets couldn't be resized / cropped their content.** `maxResize` was capped (~4:3);
  it's now opened up (720×800) with `minResize` on all seven widgets, and the Companion +
  Health widgets are responsive (`SizeMode.Responsive`) so a larger widget reveals more content
  (rings → health → vitality graph / second tile row) instead of truncating.
- **"Analyze Folder" produced no AI summary.** The map step aborted the entire folder
  analysis on the first per-file failure — and Gemini Nano returns empty responses fairly
  often — so a single miss killed the run. It now re-probes model readiness up front, skips
  soft per-file failures (noting how many were skipped), and only fails when the model is
  genuinely unavailable or zero files summarized.
- **Snackbars hidden behind the floating nav bar.** Every remaining snackbar host (Daily
  note, Home, Chat) now lifts above the `ExpressiveNavBar` via `NavBarClearance`, matching
  the earlier Settings fix.
- **Nav-bar shadow too bright in dark theme.** The primary-tinted elevation shadow read as a
  glowing halo on dark surfaces; it is now dimmed toward black with lower elevation on dark
  themes (derived from the resolved surface luminance, so it tracks the actual theme).

### Changed

- **Full English localization.** All remaining French user-facing text is now English —
  dialogs, the Life Stats screen, settings, login, crash screen, and the on-device AI prompts
  (chat persona, oracle, briefing, private-journal assist, folder insight), which now also
  instruct the model to answer in English. The daily-note markdown burner writes an English
  "Ideas / Second Brain" section header; the parser still accepts the legacy French header so
  existing notes keep round-tripping.
- Version bumped to **9.3.0** (`versionCode` 930).

## [9.2.0] — 2026-07-17

Life-OS sync repair and configurability, surfaced during pre-dogfooding.

### Added

- **Import All / Export All config buttons** in Settings, replacing the old
  "Export & Sync All Configurations" + "Force Import Habits" pair. Each covers
  Habits + Rooms + Chores. Import runs the same `syncWithFileSystem` bridges the
  sync cycle uses (the first-sign-in mirror sync does not), so a fresh login can
  force the full import on demand; Export writes all three back and pushes.
- **"Show all habits" toggle** on the Habits screen — a Today / All segmented
  control. "Today" keeps the scheduled-for-today view; "All" lists every
  configured habit (grouped identically), so habits scheduled for other days are
  reachable without waiting for their day.
- **First-class chore `sortOrder` + `archived`.** Both round-trip through
  import/export (no longer stripped), chores order by `sortOrder`, and archived
  chores are hidden from the dashboard.

### Fixed

- **Chores/rooms importing as 0 (silent).** A hand-seeded `chores.json` without
  `lastDoneDate` deserialized it to `null` (Gson ignores Kotlin defaults), hit
  `ChoreEntity`'s NOT NULL column, and rolled back the whole rooms+chores
  transaction — so both imported as zero and only reappeared after an in-app
  edit. The importer's absent-prone fields are now nullable + coalesced.
- **Settings toasts hidden behind the floating nav bar.** The Settings snackbar
  is lifted above it (`NavBarClearance`) so import/export results are visible.

### Changed

- Version bumped to **9.2.0** (`versionCode` 920). Room DB **v29** (chore
  `sortOrder`/`archived` columns; destructive migration re-imports from vault).

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
