# Antigravity review & roadmap — 2026-10-17

**Scope.** The three commits on `main` that are not yet on `origin/main`:

| Commit | What |
|---|---|
| `1872c55` | Gradle wrapper 9.6.1 → 9.7.1 (fine) |
| `866e0d5` | Antigravity / Gemini "Sprints 1–3": 93 files, +4,225 / −623 |
| `6d80afd` | `-Djdk.lang.Process.launchMechanism=vfork` in `gradle.properties` |

The rest of the app had a full review for v10.1.0 (2026-09-10), so this document covers the new code plus the roadmap.
Unless a path starts with `app/` or `gradle`, it is relative to `app/src/main/java/cloud/wafflecommons/pixelbrainreader/`.

**Verdict: Changes required.** Fix forward (keep the commit). About 20 of the small fixes in it are good.

---

## 1. Verification run

| Check | Result |
|---|---|
| `./gradlew :app:testDebugUnitTest` | ✅ BUILD SUCCESSFUL |
| `./gradlew :app:lintDebug` | ⚠️ 0 errors, **2 warnings**. The baseline is 0/0. `ui/main/FileDetailPane.kt:543` LocalContextResourcesRead, `widget/data/WidgetDataFetcher.kt:104` DiscouragedApi |
| Kotlin compiler | New deprecation warnings: `TabRow` (`ui/components/VaultGraphDialog.kt:134`), `rememberModalBottomSheetState` (`ui/components/NoteTimeMachineSheet.kt:46`) |
| `vfork` flag | Standalone repro on your JDK 22: `java.lang.Error: vfork is not a supported process launch mechanism on this platform: MACOS`. Gradle does not put it on the daemon command line (checked with `jcmd`), so today it has no effect at all |
| Health Connect actually resolved | `androidx.health.connect:connect-client:1.1.0-alpha07` (`dependencyInsight`). It was 1.1.0 before this commit |

---

## 2. Keep — what Gemini got right

- **Templates read from disk first** (`TemplateRepository`, `DailyNoteRepository`). Nothing wrote `file_contents` before this commit, so template lookups silently returned nothing. This is a real bug fix.
- `GamificationSettingsScreen`: the sleep slider no longer overwrites the step target.
- Multi-level-up `while` loops in both `GrantXpUseCase` paths.
- Daily mantra survives rehydrate, and `![[image]]` embeds render. Both were open v10 backlog items (the embeds still need B3).
- Google Calendar: the day window uses the end-of-day offset (correct on DST days), and event times are converted to the device zone.
- Google Tasks: stable Room IDs, and local completion is preserved across syncs.
- `ImportWorker`: YAML-safe frontmatter scalars and Unicode file names.
- `MarkdownCommandParser`: an invalid ISO date no longer throws.
- `CancellationException` is now rethrown across `HealthConnectManager`, `IndexingWorker`, `BriefingGenerator` and `OracleGenerator`.
- Embedder: the model is memory-mapped straight from the APK instead of copying 470 MB into `cacheDir`.
- The **contextual chunk header** (`[Note: title (folder) > heading]`) is a good retrieval idea.
- `FileDao` `@Upsert` with explicit embedding invalidation, and streaming SHA-1 (no OOM on large attachments).
- The habit year query uses an index (`BETWEEN`). Archived habits are no longer dropped from the vault export. Archived chores are excluded from reminders.
- Mood: same-minute de-duplication and robust time parsing.
- Delete for timeline entries, tasks and private notes (private notes get a confirm dialog). The Move-to-folder UI is wired up, and RPG rules are reachable from Settings.
- Widgets: a task toggle grants XP, and the habit toggle uses `targetValue`.
- Startup rehydrates before the reindex. Callout metrics scale with density.
- Placeholder tests were replaced with real ones (`ObsidianHelperTest`, `FrontmatterManagerTest`).

---

## 3. Findings

### Blocking

**B1 — Time Machine "Restore" can erase a note (data loss)**
- `ui/components/NoteTimeMachineSheet.kt:141` sets `val history = historicalContent ?: ""`. The Restore button (`:162`) writes it through `ui/main/MainViewModel.kt:516`.
- `getFileContentAtCommit` returns `null` in two cases:
  - The selected commit is the one that *deleted* the file (`git log -- path` lists those).
  - Any JGit error.
- In both cases Restore writes an **empty note**. There is no confirm dialog and no undo.
- Restore goes through `onContentChanged`, so `TaskCompletionDetector` grants XP for tasks that were checked in the old version.
- `computeSimpleDiff` (`:283`) is a set comparison, not a diff. It lists every removed line first, then marks lines "unchanged" by set membership, so moved or duplicated lines are reported wrongly.

**B2 — Voice notes break the daily-note format (data loss on re-parse)**
- `ui/daily/DailyNoteViewModel.kt:408` stores the multi-line `structuredMarkdown` as **one** timeline entry.
- `MarkdownBurner` writes `- HH:mm <content>`, so every continuation line has no time. `DailyMarkdownParser`'s timeline regex drops those lines.
- They are lost whenever the day is re-parsed (past-day view, cold-start rehydrate), and gone for good at the next burn. This breaks the burn↔parse contract (see `MarkdownBurnerRoundTripTest`).
- Also in the same feature:
  - `suggestedCategory` is computed and never used.
  - `data/ai/VoiceCompiler.kt:64` catches `Exception` without rethrowing `CancellationException`.
  - `:59` treats any reply containing "indisponible" as a failure.
  - The prompt has no `AiLanguage.DIRECTIVE`.
  - The toast is French.

**B3 — Whole-vault filesystem walk on the main thread while rendering**
- `ui/utils/ObsidianImagePlugin.kt:45` runs `walkTopDown()` over the entire vault, plus `canonicalFile` syscalls, for every `![[embed]]` that is not at the vault root.
- Obsidian attachments usually live in a subfolder, so that is nearly every embed.
- It runs inside `processMarkdown`, which `MarkwonContent` calls from `remember { markwon.toMarkdown() }` (`ui/main/FileDetailPane.kt:607`). That is composition on the main thread, and it also covers chat bubbles.
- The code-review skill classes main-thread I/O as blocking, and it feeds straight into the "lags" dogfooding feedback.

**B4 — Gemini Nano auto-download was reverted**
- `data/ai/LocalAiManager.kt:435`: `DOWNLOADABLE` now just sets `NotDownloaded`.
- This undoes your 2026-07-18 decision, which was the fix for "Nano not responding anywhere". After a fresh install or an AICore eviction, every AI surface fails until you find the Settings button.
- The KDoc of `refreshAvailability()` still promises auto-download.
- **Root cause:** the class KDoc at `:42` ("STRICT CONTRACT … Settings is the sole orchestrator of the download lifecycle") was stale. Gemini "fixed" the code to match the stale comment.

**B5 — RAG relevance floor removed in practice ("P0 fix" is a regression)**
- `data/ai/VectorSearchEngine.kt:669` lowers `MIN_SIMILARITY` from 0.35 to 0.22. 0.35 was the value you calibrated (EN/FR same-meaning ≈ 0.97, unrelated ≈ −0.03).
- `:488` adds a flat **+0.25** when any query word of 3+ characters is a *substring of the path*:
  - A question mentioning "journal" boosts every `10_Journal` chunk.
  - French stop words ("les", "des", "est") match many file names.
  - The spread guard and the floor are now computed on these boosted scores.
- 8 exit paths now fall back to `fallbackLexicalSearch` (`:597`):
  - It matches the whole question as one `LIKE '%…%'`, so natural-language questions practically never match.
  - `%` and `_` are not escaped, and it scans the full table.
  - It returns up to 6 × 1,200 chars, which can push a Boardroom prompt toward Nano's **4,000-token input limit**.
- `ui/ai/ChatViewModel.kt:75` changes the Oracle persona:
  - It now *invites general-knowledge answers* when the notes don't cover the question.
  - It drops the concision rule that existed for latency.

**B6 — Health Connect silently downgraded and mindfulness removed**
- `gradle/libs.versions.toml` lost the `connectClient = "1.1.0"` entry. The old `healthConnect = "1.1.0-alpha07"` entry (line 31) now wins.
- To compile against the older alpha, the `MindfulnessSessionRecord` read and its permission request were deleted from `HealthConnectManager`.
- The manifest still declares `READ_MINDFULNESS`, and `HabitConfigScreen` still offers the `health_connect_mindfulness` auto-source.

### Important

**I1 — About 38 French UI strings in 9 files**
- Files: `VaultGraphDialog` (all of it), `NoteTimeMachineSheet`, `MemoryEchoCard` plus `MemoryEchoRepository:55` labels, `LifeStatsScreen:141`, the `ChronoBiometricCalculator` insight copy, `SettingsScreen:446`, `DailyNoteViewModel:423` and `MainViewModel:521`.
- This breaks the CLAUDE.md contract that UI copy is English. `ChronoBiometricCalculatorTest` even pins French copy.

**I2 — Lint baseline broken**
- 2 warnings (see §1) plus the new deprecation warnings.

**I3 — Health-synergy XP: the first condition wins**
- `domain/gamification/ApplyHealthSynergyUseCase.kt:47/82` uses a single `lastHealthSynergyAppliedDate` to gate all three rewards.
- It now runs from `AutomateHabitsUseCase.kt:36` on every foreground refresh. The morning sleep bonus marks the day as applied, so the step-goal bonus (+50) and the synergy bonus (+30) can never fire that day.
- *(Correction during Phase 1: I first claimed it also double-counted with automatic habits. It doesn't: automatic habits never grant XP; only manual toggles do.)*
- Before this commit, the use case read a path that does not exist (`filesDir/10_Journal/...`; the vault is `filesDir/vault`), so it never granted anything. Gemini revived dead code.

**I4 — "Chrono-biometric" insights invent numbers**
- `domain/lifeos/ChronoBiometricCalculator.kt:49` shows **"+25%"** whenever short-sleep days average 0% completion. Days without tasks count as 0%, so this is common.
- There is no minimum sample size: the test asserts a "correlation" from 2 days.
- The heart-rate line ("propice au flow et à la concentration") is made up.

**I5 — Graph health is wrong on real vaults**
- `data/utils/VaultGraphAnalyzer.kt:38` also matches `![[image.png]]`. Every attachment embed, and every link to a non-`.md` file, counts as a dead link.
- The score `100 − 3×dead − 2×orphans` (`:117`) hits 0% almost immediately.
- Every open runs a full content scan.

**I6 — Memory Echoes treat file mtime as "last edited"**
- On-disk mtime is reset by clone, pull and checkout, so after a re-clone every note looks the same age.
- The labels are French.
- Reviewed paths are stored as a `path:ts,…` string, which breaks on commas in note names.

**I7 — Every background commit walks the working tree again**
- `data/remote/JGitProvider.kt:294` runs `git.status()` for every "generic" message, which includes the push scheduler's "Auto-save: local changes" (the most frequent commit).
- The previous code removed exactly this walk for performance, and the comment explaining why was deleted.
- `data/sync/SemanticCommitMessageGenerator.kt:3` imports a **UI** file (`ui.components.parseJohnnyDecimal`).

**I8 — Domain depends on UI**
- `domain/gamification/GrantXpUseCase.kt:17` injects `ui.utils.UiEffectManager` to fire confetti, including from widgets and workers.

**I9 — `SecretManager` built by hand**
- `data/repository/FileRepository.kt:78` constructs `SecretManager(context)` on every sync. That bypasses the Hilt singleton and creates a second EncryptedSharedPreferences/Keystore instance.

**I10 — Release crash screen lost its stack trace**
- `ui/crash/GlobalExceptionHandler.kt:27`. Your Fold runs release builds every day, so on-device crash reports now just say "omitted".
- `CrashActivity:49` sets `FLAG_SECURE` in debug as well. CLAUDE.md says release-only.

**I11 — Boardroom persona**
- `ChatViewModel.kt:91` hardcodes your full name and three fictional personas.
- The 4-section long-form answer runs with `maxOutputTokens = 2048` against a 120 s stream timeout, on a device where AICore latency is already the bottleneck. Expect timeouts.
- There is no `AiLanguage.DIRECTIVE`.

**I12 — AI failures detected by string matching**
- `BriefingGenerator.kt:19`, `OracleGenerator.kt:70` and `VoiceCompiler.kt:59` match against `GeminiRagManager.kt:73`'s French error sentence.
- CLAUDE.md prescribes `LocalAiManager.generateResponse`, which returns a `Result`.

**I13 — Today's journal is embedded again**
- The shield was removed from `data/workers/IndexingWorker.kt:150` without a word. The `todaySkipped` log at `:171` now reports something that no longer happens.
- Pre-existing: notes that produce 0 chunks, or fail to decrypt, stay "needing embeddings" forever, so every run initialises the model.

**I14 — A raw NUL byte hides the private vault ViewModel from diffs**
- `ui/privatevault/PrivateJournalViewModel.kt:153` contains a literal NUL inside `'…'`. Git treats this security-critical file as binary, so Gemini's edits were invisible in `git show`.
- I reviewed them by hand. They are fine, apart from S3.

**I15 — Process: nothing was bumped**
- The version is still 10.1.0 (1010).
- CHANGELOG still says `[Unreleased] — No unreleased changes`.
- CLAUDE.md is out of date:
  - It still says Gradle 9.6.1.
  - It lists ML Kit proofreading/rewriting/summarization, but those dependencies (and MediaPipe/AICore) were removed.

**I16 — The `vfork` flag**
- `gradle.properties:9` is invalid on macOS and a no-op today. It does not fix spawn-helper crashes.
- Real fixes: `./gradlew --stop` (a daemon outlived a JDK update), or `FORK`.

### Suggestions

- **S1** Johnny-Decimal badges hardcode a taxonomy, so `99_System` gets a red "[Private]" badge (`ui/components/BreadcrumbBar.kt:41`). `parseJohnnyDecimal` belongs in `data/utils`.
- **S2** The `@param:ApplicationContext` migration is half done (13 files left). Add `-Xannotation-default-target=param-property` once instead.
- **S3** `PrivateJournalViewModel` zeroes shared `CharArray`s in place (`unlock`, `lockVault`, `onCleared`) while save coroutines may still hold the same array. Copy per operation instead. It is a narrow race, but it could encrypt a note with a blank password.
- **S4** Google scopes are full `CALENDAR` + `TASKS` (`data/auth/GoogleAuthRepository.kt:187-188`) while the app only reads. The label "Synced (Read-Only)" became "Synced (Calendar & Tasks)". Either switch to read-only scopes or implement write-back (3.5).
- **S5** `-keep class …widget.data.** { *; }` is broader than kotlinx.serialization needs. Narrow it after an R8 smoke test.
- **S6** The in-app search now *does* search note content (`file_contents` is finally filled), but with `LIKE '%q%'` on every keystroke. Replace it with FTS (2.1).
- **S7** The `.idea/` JDK-25 changes in the working tree are unrelated. Commit or discard them.

---

## 4. Gemini's headline features — verdicts

| Feature | Verdict | Why | Action |
|---|---|---|---|
| Contextual chunker and mmap model | **Keep** | Good ideas, cheap | Add tests (2.2) |
| RAG scoring (0.22 floor, path boost, LIKE fallback) | **Revert** | Undoes calibrated tuning, adds off-topic context | 1.5, then FTS hybrid (2.1/2.2) |
| Time Machine (git history, diff, restore) | **Keep — fix now** | Best idea of the batch: it uses your git-backed vault | 1.1, then v2 (2.4) |
| Vault Graph (dead links, orphans) | **Keep — rebuild** | Useful, but currently wrong | 1.9, then links index and backlinks (2.3) |
| Memory Echoes | **Keep — rework** | A real second-brain feature with a naive implementation | 1.9, then v2 (2.5) |
| Voice Compiler | **Rework** | Capture is valuable, but it currently corrupts the daily note | 1.2, then Inbox capture (2.6) |
| Cortex Boardroom | **Generalize or drop** | Fun, but too long for Nano, and your name is hardcoded | 1.5, then prompt presets (2.7) |
| Chrono-biometric insights | **Remove** | Shows invented statistics | 1.9, then an honest version (3.3) |
| Semantic commit messages | **Re-implement** | Nice idea, but the implementation is too expensive | 1.10 |
| Johnny-Decimal badges | **Optional** | Cosmetic, and mislabels folders | 1.8 (fix or drop) |

---

## 5. Why this happened, and the guardrails

1. **Decisions were invisible to other agents.**
   - The 3 reverted decisions (Nano auto-download, the 0.35 floor, today's journal not indexed) were written down only in Claude's private memory (`~/.claude/projects/...`).
   - The in-code KDoc said the opposite, and Gemini followed the code.
   - → Phase 0.3: a "Settled decisions" section in CLAUDE.md, which Antigravity's `tech-lead`/`coding` skills already point to.
2. **Tests were written to match the code, not the requirement.**
   - The chrono test asserts a correlation from N=2 and pins French copy.
   - The graph tests have no image embeds.
   - There is no test for restore or for the voice round-trip.
   - → Every fix below lands with a test that fails first.
3. **No automated gates** for lint warnings, UI language, dependency versions or version/CHANGELOG. → Phase 4.
4. **Batch size.** One 93-file commit mixed fixes, features, refactors and dependency changes. → One concern per commit. Agents must stop and ask before removing or downgrading a dependency, or before reversing a documented decision.

---

## 6. Plan

Sizes: S ≤ 2 h, M ≤ 1 day, L > 1 day. Commit straight to `main`, one commit per item.

Definition of done for every item:
- `:app:testDebugUnitTest` is green.
- `:app:lintDebug` reports **0 errors / 0 warnings**.
- `assembleDebug` succeeds.
- The code-review skill gives "Approved".

### Phase 0 — Stabilize (≈ ½ day)

- [x] **0.1 (S)** Remove the `vfork` flag (I16). Discard or commit the `.idea` noise (S7).
- [x] **0.2 (S)** Replace the raw NUL in `PrivateJournalViewModel.kt:153` with `'\u0000'`. Check that `git diff` shows the file as text (I14).
- [x] **0.3 (S)** Add a **"Settled decisions — never reverse without asking"** section to CLAUDE.md:
  - Nano auto-download and prewarm (no `warmup()`)
  - RAG floor 0.35, recalibrated only with the golden set
  - Today's journal and `99_System` are not embedded
  - English UI, French AI, fr-FR dictation
  - Manual sync only
  - Destructive Room migrations
  - Widget and worker writes read disk first
  - Burn↔parse identity through `DailyMarkers`
  - No dependency downgrades or removals without approval
  - Version, CHANGELOG and CLAUDE.md updated with every feature batch

  `CLAUDE.md` is gitignored (local to this machine, read by every agent here), so the versioned copy with the reasoning is `documentation/explanation/settled-decisions.md`.
- [x] **0.4 (S)** Rewrite the stale `LocalAiManager` class KDoc (B4 root cause).

### Phase 1 — v10.2.0 "Antigravity, hardened" (≈ 3–4 focused days)

Order: data safety → main thread → AI → health → gamification → UI → insights → perf/DI → crash screen → lint → release.

- [ ] **1.1 (M) Time Machine safe restore (B1)**
  - Restore is disabled unless the revision content loaded and is non-null.
  - Add a confirm dialog.
  - Restore goes through a dedicated VM path with no `TaskCompletionDetector`.
  - Add an "Undo" snackbar that keeps the pre-restore text.
  - Use a real diff: JGit `DiffAlgorithm` (HISTOGRAM) over `RawText`.
  - Hide history for `.md.enc`.
  - *Tests:* VM restore with null content does not write. A JGit temp-repo test covers history and content at a commit, including the deletion commit. A diff unit test covers moved and duplicated lines.
- [ ] **1.2 (M) Voice capture keeps the daily-note format (B2)**
  - A timeline entry is a single line (summary). The body goes to a linked `00_Inbox/` note, or to the Ideas section.
  - Use `LocalAiManager.generateResponse` (`Result`) instead of string matching.
  - Rethrow `CancellationException`, add `AiLanguage.DIRECTIVE`, and make the toast English.
  - *Tests:* extend `MarkdownBurnerRoundTripTest` with multi-line content, and test that the timeline always stays single-line.
- [ ] **1.3 (M) Embeds without main-thread I/O (B3)**
  - Resolve `![[name]]` from the `files` table: build a name→path map on IO. No `File` access inside the plugin.
  - Parse markdown off the main thread (`produceState` + `Dispatchers.Default`).
  - *Test:* the plugin resolves names through an injected map.
- [ ] **1.4 (S) Restore Nano auto-download (B4)**
  - Bring back the `autoDownloadAttempted` guard plus `startDownload()`.
  - *Test:* with a mocked `DOWNLOADABLE` status, `download()` is called exactly once.
- [ ] **1.5 (M) Fix RAG scoring and prompts (B5, I11, I12, I13)**
  - Restore 0.35 and remove the path boost; the chunk header already carries the title.
  - Remove the lexical fallback (empty means "not in your notes") until 2.1 lands.
  - Add a prompt budget: context ≤ ~2,500 chars and history ≤ ~1,200, or use `countTokens`.
  - Oracle persona: keep the concision rule, and say plainly when the notes don't contain the answer. No general-knowledge fallback.
  - Boardroom:
    - Remove your name.
    - Make the answer shorter: 3 bullet critiques plus a decision.
    - Add `AiLanguage.DIRECTIVE`.
  - Briefing, Oracle and Voice use `Result`-based calls.
  - Re-apply the today's-journal shield (D2) and fix the log.
  - *Tests:*
    - `VectorSearchEngine` scoring with fake vectors: the floor holds and stop words don't boost.
    - The prompt builder stays within budget.
    - A guard test checks that every prompt ends with the directive.
- [ ] **1.6 (S) Health Connect (B6)**
  - Keep one catalog entry at `connect-client:1.1.0`, or newer stable.
  - Restore the mindfulness read and permission (`@OptIn(ExperimentalMindfulnessSessionApi::class)`).
  - Check with `dependencyInsight`.
- [ ] **1.7 (M) Gamification (I3, I8)**
  - Per-reward idempotency per date, e.g. a set `{steps, sleep, synergy}` for each date.
  - `GamificationRepository` exposes a `levelUps` `SharedFlow`; the UI turns it into confetti, so the domain no longer imports the UI.
  - *Tests:* sleep bonus in the morning, then the steps bonus later the same day. Each reward is granted at most once.
- [ ] **1.8 (S) English UI sweep (I1, S1)**
  - Translate all ~38 strings and update the tests that pin French.
  - Fix or drop the JD badges, and move `parseJohnnyDecimal` to `data/utils`.
  - *Guard test:* fail the build on accented-French literals in `ui/**` and in UI-facing labels (allow-list the `data/ai` prompts).
- [ ] **1.9 (M) Make the insights honest (I4, I5, I6)**
  - Remove the chrono card for now.
  - Graph analyzer:
    - Skip `![[…]]` embeds.
    - Resolve non-`.md` targets against all files.
    - Score = % of notes with no problem.
  - Echoes:
    - English labels.
    - Review state in vault JSON (`99_System/echoes.json`, so it syncs).
    - Age from the git last-commit time: one `RevWalk`, cached.
  - *Tests:* image embeds are not dead links, and echo ages don't change after a re-clone.
- [ ] **1.10 (S) Commits and DI (I7, I9)**
  - `VaultPushScheduler.requestPush(path)` accumulates dirty paths, and the message is built from them with no `git.status()`.
  - Inject `SecretManager` into `FileRepository`.
- [ ] **1.11 (S) Crash screen (I10)**
  - Keep the stack trace in release (it never leaves the device).
  - Set `FLAG_SECURE` on `CrashActivity` in release builds only.
- [ ] **1.12 (S) Back to 0 lint warnings (I2, S2)**
  - Use `LocalDensity.current.density`.
  - Replace `getIdentifier` with an explicit avatar map.
  - Use `PrimaryTabRow` and the current bottom-sheet API.
  - Add `-Xannotation-default-target=param-property`.
- [ ] **1.13 (M) Truly on-device dictation**
  - All three voice inputs (private journal, daily capture, chat mic) use `RecognizerIntent`, which hands the audio to Google's speech service and may process it online. For the encrypted private journal, that is a real privacy gap.
  - Use `SpeechRecognizer.createOnDeviceSpeechRecognizer` with the offline fr-FR model, through one shared `OnDeviceDictation` helper.
  - If the on-device recognizer is unavailable, fall back to `RecognizerIntent` with `EXTRA_PREFER_OFFLINE` and tell the user it may not be fully offline.
  - *Test:* the helper's language and offline flags are pinned by unit tests.
- [ ] **1.14 (S) Release v10.2.0**
  - `versionCode 1020` / `versionName "10.2.0"`.
  - CHANGELOG `[10.2.0]`: Added / Fixed / Removed, with credit for the Antigravity batch.
  - CLAUDE.md: Gradle 9.7.1, only `genai-prompt` from ML Kit, the new features.
  - Tag, then build the signed APK in Android Studio, then run the Fold checklist in §8.

### Phase 2 — v10.3.0 "Make the new features real"

- [ ] **2.1 (M) Full-text index**
  - A Room `@Fts4(contentEntity = FileContentEntity::class)` table. Use the unicode61 tokenizer with diacritics removed, so French accents are ignored.
  - Room triggers keep it in sync with the `file_contents` mirror that the scan now fills.
  - Rank with `matchinfo()` (BM25).
  - It powers in-app search (replacing the `LIKE`) and the lexical half of RAG.
- [ ] **2.2 (M) Hybrid retrieval and an eval harness**
  - Reciprocal-rank fusion of vector top-20 and FTS top-20, with the 0.35 floor applied to the vector side.
  - A golden set of ~30 questions from your vault, run from a debug Settings action that logs recall@6 and precision.
  - Recalibrate the floor only against this set.
- [ ] **2.3 (L) Links index, backlinks and Graph Health v2**
  - A `links(source, target, kind)` table maintained by the scan.
  - A backlinks and unlinked-mentions panel in the note view.
  - Dead link → "Create note from template".
  - The graph report becomes a few queries instead of a full scan.
- [ ] **2.4 (M) Time Machine v2**
  - A vault activity screen: commits with their changed files.
  - Word-level highlights.
  - Follow renames (`FollowFilter`).
  - "What changed since the last sync".
- [ ] **2.5 (M) Memory Echoes v2**
  - Opt-in scope (folders, or a `#review` tag).
  - SM-2-lite scheduling stored in the vault.
  - An "Echo of the day" Glance widget.
- [ ] **2.6 (M) Capture → Inbox → Triage**
  - Voice and share go to an `00_Inbox/` note (tasks are extracted heuristically first, Nano optional).
  - An Inbox screen where you swipe a note into a folder.
  - Folder suggestions from **embedding centroids**: deterministic, offline, no LLM.
- [ ] **2.7 (S) Prompt presets**
  - Generalize Boardroom: presets (persona plus format) live in `99_System/presets.md`, so you can edit them in Obsidian. Each output is short enough for Nano.

### Phase 3 — v10.4+ new features

- [ ] **3.1 (M) Weekly and monthly review notes**
  - Burn `10_Journal/Weekly/YYYY-Www.md` on Sunday evening, pinned with `DailyWorkScheduler`.
  - Deterministic stats: habits, mood trend, sleep and steps averages, tasks and chores done, XP gained, notes created or edited (from `git log`).
  - Optional French reflection from Nano.
- [ ] **3.2 (M) "Ask this note / this folder"**
  - RAG scoped by `fileId` or folder.
  - Map-reduce summaries for notes larger than the 4K window.
- [ ] **3.3 (M) Honest health insights**
  - Lagged Spearman correlations (sleep → next-day mood and task completion).
  - Show N, and hide below N = 14.
  - Vico charts (already a dependency).
- [ ] **3.4 (M) Sync conflict resolution screen**
  - List the backups made by `ConflictResolver`.
  - Side-by-side diff, with keep mine / keep theirs / edit.
- [ ] **3.5 (M) Google Tasks two-way**
  - Push local completion back to Google. Otherwise switch to `*_READONLY` scopes (S4).
- [ ] **3.6 (M) Private vault: change password and auto-lock**
  - "Change password" re-encrypts every note with a new salt.
  - Auto-lock after N minutes in the background.
- [ ] **3.7 (M) Quantize the embedder**
  - int8 dynamic range in `scripts/convert_minilm.py` (~470 MB → ~120 MB APK asset, faster inference).
  - Gate it on the 2.2 golden set, and bump `EMBEDDER_SCHEMA_VERSION`.
- [ ] **3.8 (S) A one-tap voice-capture widget** (Glance).

### Phase 4 — Guardrails (start alongside Phase 1)

- [ ] **4.1 (M) GitHub Actions CI**
  - Runs `testDebugUnitTest`, `lintDebug` and `assembleDebug` on every push.
  - LFS is not needed (tests never load the model).
  - `google-services.json` comes from a repo secret.
- [ ] **4.2 (S)** `lint { warningsAsErrors = true }` to lock 0/0.
- [ ] **4.3 (S)** Gradle dependency locking (`dependencyLocking { lockAllConfigurations() }`), so any upgrade or downgrade shows up as a lockfile diff.
- [ ] **4.4 (S)** Guard tests: UI language (1.8), prompt directive (1.5), and version/CHANGELOG consistency (`versionName` must appear in CHANGELOG).
- [ ] **4.5 (L)** Split the hotspots behind tests: `MainScreen.kt` (1,274 lines), `DailyNoteScreen.kt` (1,239) and `MainViewModel.kt` (1,124).
- [ ] **4.6 (L)** Externalize strings into `strings.xml` (v10 backlog).
- [ ] **4.7 (M, optional)** A baseline profile for cold start and Daily scrolling. It needs a `:baselineprofile` module, which breaks the single-module rule — your call.

### Product thesis for Phases 5–6

*"A second brain that lives entirely on your phone: it reads your notes, remembers your days, and never sends a thought to the cloud."*

The edge is a combination no other app has: on-device AI, a git-versioned Obsidian vault (= time), and life data (Health Connect, mood, habits). Every feature below uses at least two of the three.

**Design rule:** *embeddings do the thinking, Nano does the talking.* Retrieval and statistics are deterministic and testable. Gemini Nano (4K input tokens, slow on the Fold) only writes short French narration, and every feature still works when Nano is unavailable.

**Prerequisites:** Phase 1 (stability), plus 2.1 (FTS), 2.2 (hybrid retrieval and golden set) and 2.3 (links index).

### Phase 5 — v11.0.0 "Signature" (the showcase)

- [ ] **5.1 (M+M) Contextual recall**
  - **Meeting prep.** Ten minutes before a calendar event, a notification shows the top vault notes about its title and attendees, with one line from Nano ("Last time with Camille you agreed…").
    - Built on the Google Calendar import, hybrid retrieval and `DailyWorkScheduler`/AlarmManager.
    - Respect quiet hours, and add an opt-in toggle in Settings.
  - **Related while writing.** A live "Related" rail in the editor shows the top 3 semantically close notes, based on the current paragraph's embedding (debounced around 1.5 s and computed off the main thread). It is a side pane on the unfolded screen and a bottom chip row when folded.
  - *Tests:* event-to-query building, rail debouncing and cancellation, and the paragraph extractor.
- [ ] **5.2 (L) "Days like this"**
  - Each day becomes one embedded **day document** combining the daily note, mood entries, sleep/steps/HR, habits done and calendar titles. It is stored in a `day_embeddings` table and rebuilt by the indexer.
  - Queries:
    - "Find days like today" (vector kNN over days).
    - "When did I last feel <x>, and what helped?" (mood/health filters + kNN).
    - The UI shows the matched days, what differed (sleep delta, habits, notes) and an optional 3-line Nano summary.
  - Opt-in for private notes. Never embed `99_Private` content unless the vault session is unlocked.
  - *Tests:* the day-document builder (deterministic text) and similarity ranking with fake vectors.
- [ ] **5.3 (M-L) "How has my thinking changed?" (time-travel questions)**
  - "What did I think about X a year ago?":
    - Hybrid retrieval finds today's top notes on X.
    - For each one, JGit reads the version at the target date (`RevWalk` up to that date, then `TreeWalk`), embedded on demand and cached.
    - The UI shows "then vs now" with a real diff (1.1 engine) and a 3-line Nano summary of the shift.
  - Also: "When did X first appear?" (the earliest commit whose blob mentions X, through FTS over historical snapshots of the top candidates).
  - *Tests:* JGit temp repo with dated commits, checking the version-at-date resolution.
- [ ] **5.4 (L) PixelBrain Wrapped (monthly + yearly)**
  - A story-format recap generated on-device:
    - mood journey, best streaks, sleep/steps trends, XP and level-ups
    - top topics (embedding clusters from 6.5, or folder-level as a fallback)
    - most-edited notes and "ideas born this year" (new files, from `git log`).
  - Shareable cards rendered from Compose to a bitmap, exported through the share sheet.
  - Nothing private by default: `99_Private` is excluded, and a preview is required before sharing.
  - *Tests:* the stats aggregation over a fixture vault and git history.
- [ ] **5.5 (M) Foldable postures**
  - Jetpack WindowManager `FoldingFeature`:
    - **Tabletop** (half-opened, horizontal hinge): the answer, note or chart on the top half, and controls or voice capture on the bottom half (chat, capture, Time Machine).
    - **Unfolded:** a side-by-side Time Machine diff, and the editor with the Related pane.
  - *Tests:* posture-to-layout mapping as a pure function, plus a Compose preview per posture.

### Phase 6 — v11.x "Platform & knowledge"

- [ ] **6.1 (S-M) The system Notes app + a Quick Settings tile**
  - Hold Android's Notes role (`RoleManager.ROLE_NOTES`, `Intent.ACTION_CREATE_NOTE`, Android 14+) so the system can launch quick capture, from the lock screen where the platform allows (`showWhenLocked` on a dedicated capture activity that never shows vault content).
  - Add a `TileService` for one-tap voice capture into the Inbox.
- [ ] **6.2 (S-M) Live Updates**
  - Android 16 `Notification.ProgressStyle` / promoted ongoing notifications.
  - Focus sessions (timer). At the end, "what you worked on" comes from git diff stats of the notes edited during the session, plus XP.
  - Optionally, today's habits and tasks progress.
- [ ] **6.3 (M, stretch) App Functions**
  - `androidx.appfunctions`, Android 16+.
  - Expose `captureNote`, `logMood`, `addTask` and `askVault` to system agents (Gemini).
  - The API is very early and its callers are platform-gated: prototype behind a flag, and don't promise it.
- [ ] **6.4 (L) Personal experiments (N-of-1)**
  - A hypothesis ("No screens after 22:00"), an intervention habit, and a baseline of 14 days or more followed by a trial of 14 days or more.
  - The effect on sleep, mood and steps comes with a bootstrap 95% CI and N, with the verdict "likely helps / no clear effect / likely hurts".
  - This is the honest successor of the removed chrono card. The stats are pure Kotlin with heavy unit tests.
- [ ] **6.5 (L) Brain map**
  - k-means over note embeddings, with a PCA 2D projection (pure Kotlin, off the main thread). Nano labels each cluster from its titles.
  - An interactive canvas on the unfolded screen: tap a node to open the note, with wiki-link edges from 2.3.
  - A time slider (git history) shows topics appearing and growing.
- [ ] **6.6 (M) Capture anything**
  - A photo of a whiteboard, book page or receipt goes through ML Kit Text Recognition (on-device). An optional on-device image description comes from ML Kit GenAI if the device supports it.
  - The result is an `00_Inbox/` note with the image attached under the vault's attachments folder, indexed by FTS and embeddings.
- [ ] **6.7 (M) Privacy ledger**
  - Every network boundary records host, bytes, purpose and timestamp (never content) in a small Room table:
    - OkHttp `EventListener` (weather, RSS, imports)
    - JGit push/pull wrappers
    - the Google API client (Calendar/Tasks)
  - A screen "What left this phone today" shows: "0 bytes of notes sent to any AI service".
  - The credibility piece for a privacy showcase.

### Showcase prerequisites (do before any public demo)

- [ ] **7.1 (M) Demo mode**
  - A synthetic bilingual demo vault (git repo) plus fake health and mood generators behind a debug or `demo` flavor.
  - Never record the real vault: it holds private and health data.
- [ ] **7.2 (M) Ship size**
  - The APK is about 600 MB because of the 470 MB model. Quantize it (3.7) or download the model on first launch with SHA-256 verification.
- [ ] **7.3 (M) Story**
  - An English README with an architecture diagram, a privacy page (the ledger plus "what runs where"), and a 60–90 s video on the Fold built around 3 moments: Related-while-writing, Days like this, Wrapped.
  - Plus a CI badge (4.1).
- [ ] **7.4 (S) Distribution**
  - GitHub Releases first. The Play Store needs Health Connect declarations, a privacy policy and a sensitive-permission review.
  - Before publishing the repo, scrub personal defaults: RSS feed list, names in prompts, Google client IDs.

---

## 7. Decisions needed

Defaults applied on 2026-10-17 ("add everything to the plan and proceed"): every recommendation below.

| # | Question | Recommendation |
|---|---|---|
| D1 | Fix forward on `866e0d5`, or revert it? | **Fix forward.** The small fixes are worth keeping, and nothing is pushed yet |
| D2 | Today's journal in RAG? | **Keep it excluded**, as documented. Revisit with the golden set |
| D3 | Boardroom? | **Generalize into prompt presets** (2.7) |
| D4 | Steps or sleep XP from both the auto-habit and the synergy? | **Moot:** automatic habits never grant XP, so the synergy bonus is the only source |
| D5 | Google scopes? | **Read-only now**, two-way later (3.5) |
| D6 | Version for Phase 1? | **10.2.0** (1020), released once Phase 1 is done |

---

## 8. Release checklist (every version)

1. The version is bumped in `app/build.gradle.kts`, the CHANGELOG section is dated, and CLAUDE.md is accurate.
2. Tests are green, lint is 0/0, and `assembleRelease` (R8) works. For anything reflective, run an R8 smoke test on the emulator.
3. Build the signed APK in Android Studio (the keystore is remembered there) and install it over the Fold build.
4. On the Fold:
   - Fresh state → Nano downloads and then answers.
   - A chat question off-topic for the vault returns "not in your notes".
   - A note with 5 or more embeds scrolls without jank.
   - Time Machine diff and restore work, and undo works.
   - A voice note survives a past-day view and a re-burn.
   - A mindfulness session counts.
   - The synergy sleep bonus is granted in the morning and the steps bonus later the same day.
   - All widgets render (R8).
5. Tag `vX.Y.Z`, then push.
