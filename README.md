# Pixel Brain Reader

A **native Android, local-first Markdown vault reader and life-OS** that turns a GitHub repository of `.md` notes into a working second brain — with calendar, health, habits, mood, news, and on-device AI all wired into the same vault.

- Single Gradle module (`:app`) · Kotlin · Jetpack Compose · Material 3 (adaptive)
- Min/compile SDK **36**, JVM target **17**, package `cloud.wafflecommons.pixelbrainreader`
- Current version: **9.1.0** (`versionCode = 910`)

---

## What it actually does

The app keeps a **real `git` working tree on the device** at `context.filesDir/vault` (managed by [Eclipse JGit](https://www.eclipse.org/jgit/)). All reads and writes touch local files. "Sync" means `git pull --rebase` → mutate → `add` → `commit` → `push`. There is no REST GitHub client.

On top of that vault, the app layers:

- **Markdown reader/editor** (Markwon + syntax highlighting + tables/tasklists/HTML).
- **Daily Note + Journal + Scribe** — structured authoring tied to templates.
- **Life OS** — habits, chores, gamification, dashboards, mood, life stats.
- **Health** — Health Connect → Room → markdown export in the vault.
- **Google ecosystem (read-only sync)** — Calendar + Tasks via Google Auth + AuthorizationClient.
- **Cortex AI** — chat over your vault, **100% on-device** (Gemini Nano via ML Kit GenAI + a local TFLite MiniLM RAG index). No cloud, no API key. Chat, briefing, oracle, folder-insight and the web-import summary all run locally.
- **Private Vault** — a separate journal surface (`PrivateJournalActivity`).
- **Home-screen widget** — Jetpack Glance, reads pre-rendered snapshots.

The entire app runs in a **single Compose Activity** (`MainActivity`) using a Material 3 adaptive `ListDetailPaneScaffold`, so the same UI handles phones, tablets, and the Pixel Fold's inner display.

---

## Architecture at a glance

```
┌─────────────────────────────────────────────────────────────────────┐
│                       Compose UI (single Activity)                  │
│  journal · lifeos · health · mood · cortex · daily · ai · …         │
│                          ▲                                          │
│                          │ hiltViewModel()                          │
│                          ▼                                          │
│                       ViewModels                                    │
│                          ▲                                          │
│                          │ injected via Hilt                        │
│                          ▼                                          │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │                       Repositories                          │   │
│   │  NoteRepository · VaultDiscoveryRepository · MoodRepo · …   │   │
│   └───────────────────┬──────────────────────┬──────────────────┘   │
│                       ▼                      ▼                      │
│              ┌────────────────┐     ┌─────────────────────────┐     │
│              │  Room (v24)    │     │  Vault filesystem       │     │
│              │  derived/cache │     │  source of truth        │     │
│              │  pixel_brain_db│     │  filesDir/vault (JGit)  │     │
│              └────────────────┘     └─────────────────────────┘     │
└─────────────────────────────────────────────────────────────────────┘
              ▲                                          ▲
              │                              SyncOrchestrator
              │                                          │
              │                            ┌─────────────┴────────────┐
              │                            │  JGitProvider (pull/push)│
              │                            │  Health Connect sync     │
              │                            │  Google Calendar+Tasks   │
              │                            └──────────────────────────┘
              │
        WorkManager (Hilt) — DailyExportWorker, ImportWorker,
                             IndexingWorker, VaultReminderWorker, …
```

### Two persistence layers, distinct roles

| | Vault filesystem | Room (`pixel_brain_db`, v24) |
|---|---|---|
| Role | **Source of truth** for user-authored content | Derived/indexed state |
| Format | Markdown files in a git working tree | SQLite, accessed via DAOs |
| Migrations | n/a — files | `fallbackToDestructiveMigration(dropAllTables = true)` |
| Examples | notes, daily notes, mood logs (as markdown), chore configs | file index, embeddings, news cache, mood entries, habits, dashboard, calendar outbox |

Schema bumps wipe local cache — that's intentional, because the source of truth is the vault.

### SyncOrchestrator owns the sync sequence

`data/sync/SyncOrchestrator.kt` runs a strict, mutex-guarded cycle, triggered from a `ProcessLifecycleOwner` `ON_RESUME` observer with a 60-second cooldown:

1. `JGitProvider.pull()` (rebase; conflicts handled by `ConflictResolver`, conflicting files are **backed up**, not dropped).
2. `SyncHealthDataUseCase` — pull Health Connect data into Room + write markdown into the vault. **Non-fatal.**
3. `GoogleSyncRepository` — Calendar + Tasks sync. **Non-fatal.**
4. `git addAll → commit → push`.

Pull failure aborts; health/google failures don't. Preserve this Pull-then-Push ordering when editing sync code.

### Auth model

- **GitHub:** Personal Access Token, stored in `EncryptedSharedPreferences` via `data/local/security/SecretManager.kt`.
- **Login state = "is a token saved?"** (`MainActivity.kt:42`). No biometric layer.
- **Google:** Credential Manager + AuthorizationClient (V6 flow), email captured via `AuthorizationResult.toGoogleSignInAccount()`.
- `FLAG_SECURE` is set in **release** builds only (`MainActivity.kt:54`) — debug builds allow screenshots.

---

## AI stack

**100% on-device — no cloud, no API key.** Cloud Gemini (and `GeminiScribeManager`) were removed; `data/ai/GeminiRagManager.kt` is now a local RAG facade over `VectorSearchEngine` + `LocalAiManager`.

### Local RAG retrieval
- On-device embeddings via a raw **TFLite** model (`assets/sentences_encoder.tflite`, `paraphrase-multilingual-MiniLM-L12-v2`, 384-dim) tokenized by DJL's `HuggingFaceTokenizer` (`assets/tokenizer.json`), stored in Room as `EmbeddingEntity`. Indexing is owned by `IndexingWorker`.

### On-device Gemini Nano (ML Kit GenAI)
- `data/ai/LocalAiManager.kt` wraps the `com.google.mlkit:genai-prompt` API (Gemini Nano via Android AICore).
- **Lifecycle is owned by Settings, not the inference path.** `generateResponse()` never triggers a download or warmup — it fast-fails with a typed `NanoException.Unavailable` when the model is not `Ready`.
- States exposed as `StateFlow<NanoState>`:
  `Unknown · Checking · NotDownloaded · Downloading(progress, bytes…) · Ready · Unavailable(reason) · Error(cause)`.
- `Settings → Intelligence` renders the lifecycle inline under the "Cortex (On-Device)" radio: Download (~1.5 GB) → progress → green checkmark + "Manage storage" deep-link to AICore.
- The "Cortex (On-Device)" radio stays disabled until the model is actually `Ready`.
- **Privacy contract:** `LocalAiManager` never falls back to cloud. The chat layer (`ChatViewModel`) raises an explicit consent dialog before any prompt leaves the device.

### ML Kit GenAI utility models
`prompt`, `proofreading`, `rewriting`, and `summarization` are all available for on-device text features.

---

## Module / package layout

Inside `app/src/main/java/cloud/wafflecommons/pixelbrainreader/`:

```
PixelBrainApplication.kt   Hilt app entry; ProcessLifecycle observer kicks sync
MainActivity.kt            Single Activity; gates login vs. main on token presence
ui/                        Compose feature surfaces (see below)
data/                      All non-UI logic
  ai/                      LocalAiManager, GeminiRagManager, VectorSearchEngine, …
  auth/                    GoogleAuthRepository, …
  local/                   Room (AppDatabase, DAOs, entities) + security/
  remote/                  JGitProvider — the *local* git client
  repository/              The only layer allowed to touch vault + Room together
  sync/                    SyncOrchestrator + ConflictResolver
  workers/                 WorkManager workers (Hilt-injected)
  model/                   Plain data classes (AiModel, AppThemeConfig, …)
  usecase/                 Cross-repo coordinators (e.g. SyncHealthDataUseCase)
di/                        Hilt modules (AppModule, DatabaseModule, NetworkModule,
                           GoogleModule, HealthModule) + WidgetEntryPoint
widget/                    Jetpack Glance widget + WidgetSnapshotManager
domain/                    Pure-Kotlin domain types
```

### UI feature surfaces (`ui/<feature>`)

| Surface | What it owns |
|---|---|
| `main` | `MainScreen`, adaptive list/detail scaffold |
| `journal`, `daily` | Daily note, journal entries |
| `lifeos`, `homeos`, `homeconfig` | Habits, chores, dashboards, gamification |
| `health`, `lifestats` | Health Connect + analytics |
| `mood` | Mood logger + emoji mapping |
| `cortex`, `ai` | Cortex chat (`ChatPanel`, `ChatViewModel`) — Oracle (RAG) + Scribe (persona) |
| `settings` | Theme, AI model, Health Connect, Google sync, on-device model lifecycle |
| `gamification`, `privatevault`, `login`, `crash` | The remaining special-purpose screens |

`PrivateJournalActivity` and `CrashActivity` (in the `:crash` process via `ui/crash/GlobalExceptionHandler.kt`) are the only Activities besides `MainActivity`.

### Entry points beyond the launcher (`MainActivity.handleIntent`)

- `ACTION_SEND` (`text/plain` or `text/html`) → `viewModel.handleShareIntent` (universal collector / Phase B).
- `ACTION_VIEW` with scheme `pixelbrain://import?url=...` → enqueues `ImportWorker`.

### WorkManager (Hilt-driven)

`PixelBrainApplication` implements `Configuration.Provider` with the injected `HiltWorkerFactory`. The default `WorkManagerInitializer` is disabled in `AndroidManifest.xml` (`tools:node="remove"`) — init goes through Hilt.

Workers in `data/workers/`:

- `IndexingWorker` — builds/refreshes the local embedding index.
- `DailyExportWorker` — unique periodic, **23:50 daily, unconditional** (burns the complete day to markdown). Health sync and morning briefing are **not** separate workers — health runs via `SyncHealthDataUseCase` inside the `SyncOrchestrator` cycle, and the briefing is generated inline in repositories.
- `ImportWorker` — reader-mode fetch + clean-markdown conversion of shared/deep-linked web content.
- `VaultReminderWorker` / `ChoresHabitsReminderWorker` — reminder notifications.

---

## Tech stack

| Layer | Choice |
|---|---|
| UI | Jetpack Compose, Material 3, Material 3 Adaptive (foldable/tablet) |
| DI | Hilt + KSP |
| Persistence | Room (v24) + Vault filesystem (JGit) + DataStore (preferences) |
| Background | WorkManager + Hilt Work |
| Networking | Retrofit + OkHttp (RSS, Google APIs); no network for the vault itself |
| Markdown | Markwon (core, strikethrough, tables, tasklists, linkify, image, HTML, syntax highlighting via prism4j) |
| HTML→MD | jsoup + flexmark-html2md-converter |
| Charts | Vico (Compose + core + views) |
| AI (cloud) | `com.google.ai.client.generativeai` (Gemini Flash/Pro) |
| AI (on-device) | `com.google.mlkit:genai-prompt` (Gemini Nano via AICore) + MediaPipe Tasks Text |
| Auth (GitHub) | PAT in `EncryptedSharedPreferences` |
| Auth (Google) | Credential Manager + Google Identity + AuthorizationClient |
| Widget | Jetpack Glance + Glance Material 3 |
| Health | Health Connect Client |
| Confetti / FX | Konfetti Compose |
| Tests | JUnit4 + MockK + kotlinx-coroutines-test (instrumented: Espresso + Compose UI test) |

---

## Build & run

### Prerequisites

- Android Studio Iguana+ / Gradle JDK 17
- A device or emulator on **SDK 36** (`compileSdk = 36`, `minSdk = 36`)
- For on-device Gemini Nano: a Pixel that ships with **Android AICore** (`com.google.android.aicore`) installed and up-to-date

### Required local config (gitignored)

- **`app/google-services.json`** — Firebase / Google Services config, required by the `googleServices` plugin. **Required.**
- **`local.properties`** — no AI key is needed (the app is 100% on-device). Optional keys: `githubOauthClientId` (enables "Login with GitHub" via OAuth Device Flow — a public client id, no secret), and the release-signing keys (`storeFile`/`storePassword`/`keyAlias`/`keyPassword`).

### Common Gradle commands

Run from repo root:

```bash
# Build & install
./gradlew assembleDebug
./gradlew installDebug
./gradlew assembleRelease

# Tests
./gradlew :app:testDebugUnitTest
./gradlew :app:testDebugUnitTest --tests "cloud.wafflecommons.pixelbrainreader.data.repository.MoodRepositoryTest"
./gradlew :app:lintDebug
./gradlew connectedDebugAndroidTest    # requires a connected device
```

### First-run setup in the app

1. Launch the app — you'll land on the login screen because no PAT is stored.
2. Enter your GitHub PAT (with `repo` scope) plus `owner/repo` for the markdown vault. The token is encrypted via `EncryptedSharedPreferences`.
3. The vault is cloned to `context.filesDir/vault`. From then on, foreground transitions trigger the sync cycle automatically.
4. Optional: open **Settings → Intelligence → Cortex (On-Device)** to download Gemini Nano (~1.5 GB) and run AI fully offline.
5. Optional: **Settings → Health Connect** and **Settings → Google Calendar & Tasks** to wire those integrations.

---

## Project conventions worth knowing

- All sync/IO uses **`Dispatchers.IO`** explicitly via `withContext` — never the implicit default.
- Sync state is exposed as **`StateFlow<SyncState>`** from `SyncOrchestrator` — observe it instead of polling.
- **`FileRepository` is deprecated.** New code should hit `NoteRepository`, `VaultDiscoveryRepository`, or `AssetRepository` directly.
- When you bump entities in `AppDatabase`, bump `version = …`. Destructive migration is intentional.
- New DAOs must be added to **both** `AppDatabase` and `DatabaseModule`.
- ViewModels are `@HiltViewModel`; Composables get them via `hiltViewModel()`.
- Mandatory build settings (don't remove without re-testing):
  - `androidResources { noCompress += "tflite" }` — MediaPipe loads `universal_sentence_encoder.tflite` directly.
  - `configurations.all { exclude(group="org.jetbrains", module="annotations-java5") }` — fixes a "Duplicate Class" crash.

### AI lifecycle contract (`LocalAiManager`)

When working on anything touching Gemini Nano, hold this line:

1. `generateResponse()` **NEVER** triggers a download.
2. `generateResponse()` **NEVER** falls back to cloud.
3. All failures are returned as `Result.failure(NanoException.*)` — never thrown.
4. The Settings screen is the **only** orchestrator of `downloadModel()` and `openAicoreSettings()`.

See [CHANGELOG.md](./CHANGELOG.md) for the lifecycle-decoupling rationale.

---

## Testing notes

- Unit tests are **pure JVM** (JUnit4 + MockK + kotlinx-coroutines-test). They mock `Context` and avoid Android Runtime entry points.
- `android.util.Log` returns defaults in unit tests (`testOptions { unitTests { isReturnDefaultValues = true } }`).
- The ML Kit GenAI inference path requires AICore at runtime; it is covered by **instrumented tests on a real Pixel**. JVM tests focus on the public contract (state transitions, fast-fail behavior, typed exception mapping).
- The `LocalAiManagerTest` enforces the lifecycle-decoupling contract via `generateResponse fast-fails with Unavailable when state is not Ready`.

---

## Specialized AI skills (for contributors using Claude Code / OpenCode)

This repo carries a per-skill operating manual at `.opencode/skills/<skill>/SKILL.md`. CLAUDE.md routes Claude Code to the right one based on the task — `tech-lead`, `coding`, `refactoring`, `test`, `code-review`, `lint-purge`, `security-audit`, `feature-scaffold`, `diataxis-doc`. When in doubt, the entry point is **`tech-lead`**, which orchestrates the others and enforces "no merge until `:app:testDebugUnitTest` and `:app:lintDebug` are green."

See [CLAUDE.md](./CLAUDE.md) for the full routing table.

---

## License

MIT — see [LICENSE](./LICENSE).
