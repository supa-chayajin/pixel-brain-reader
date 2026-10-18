# Changelog

All notable changes to Pixel Brain Reader are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

Work toward **11.0.0** — the work-direction and dev/lead toolkit release (plan:
`documentation/plans/2026-10-18-v11-work-and-dev-toolkit.md`).

### Added

- **Template forms, `/` commands and `[[` completion in the editor.** Choosing a template for a
  new note shows one field per `{{variable}}` (a date picker for date-like ones); `{{date}}`,
  `{{time}}` and `{{title}}` fill themselves, a blank field stays as a placeholder, and the
  template's frozen `date_created` / `date_updated` become today. While editing, typing `/` at the
  start of a line or after a space offers commands (date, time, checkbox, my action `@moi`, due
  date, callout, code block, table, heading, quote, link, separator), and typing `[[` offers the
  vault's note names, accents and case aside — never the notes the current mode hides.
- **Connections.** A Connections button on an open note lists its backlinks (with the line that
  links), its unlinked mentions — notes naming it as a whole word, outside links and code, found
  through the full-text index — each linkable in one tap (the text's own spelling becomes the
  alias), and the notes closest in meaning from the on-device search.
- **Snippets & runbooks** (Work hub). Every fenced code block of the vault in one list, searchable,
  filtered by language, each with a copy button. A runbook — a note tagged `#runbook` or kept in
  a `Runbooks` folder — plays step by step (its `##` sections, or its list): copy the step's code,
  mark it done, failed or skipped with a note, and finishing (or stopping) writes a French
  incident log to the inbox. A rendered note now lists its code blocks at the end, each with a
  copy button. Code is highlighted in C#, PHP and TOML too.
- **Sync digest and vault activity.** After a sync that pulls changes, the Daily screen says what
  came from the remote — notes new, changed and removed, and where — until dismissed. Vault
  activity (Work hub) shows that digest, commits per day over 30 days, the notes that changed most
  this week and the new ones, all from the git history and filtered by the current mode.
- **Work hub and mission log.** A Work chip on the Daily screen opens the Work hub. Its first tool
  is the mission log: facts, decisions, requests, refusals and off-ticket time, each with the role
  you held, a status, minutes, who asked and who decided (as roles, never names), a reference and
  tags. One markdown file per month, one line per entry as Dataview fields so Obsidian can query
  them, in a vault folder of your choice (by default your first work folder) or encrypted in the
  private vault. Log from the app (dictation stays on the device), from the new Log and Work
  buttons of the quick-actions widget (now two rows of four) or from a Log tile in Quick Settings
  (after unlocking). Outside Work mode the log is hidden, but capture still works.
- **Leave & handover** (Work hub). Before leaving, pick a scope (a folder) and the dates: the app
  writes a handover draft to your inbox, in French — notes in progress and paused in the scope,
  the last month's decisions, open requests and refusals from the mission log, and the key dates
  during the leave, plus an optional on-device summary marked as such. In the three days after a
  leave, the Daily screen says how many work notes changed or appeared meanwhile (from the git
  history) and writes a "what happened" briefing on request. Leave periods and key dates are the
  ones set in Modes.
- **Evidence report** (Work hub). A month or a quarter of the mission log becomes a French draft
  for an annual review or a status meeting: counts by kind (with open requests), off-ticket time
  ("non mesuré" when no minutes were logged), breakdowns by role and by tag, the decisions,
  requests, refusals and facts, and the notes moved to done in your work folders during the
  period. Every number comes from the log; an on-device summary is optional and marked.
- **Boards** (Work hub). Any folder can be a board: its notes are cards in the vault's status
  columns (To do 📥, In progress 🏗️, Paused ⏸️, Done 🟢, Archived 📦), older statuses such as
  "active" or "🏗️ en cours" included. Moving a card rewrites only its `status` and `date_updated`;
  a card's next step, blockers and target date are `next_step`, `blockers` and `target_date` in
  its frontmatter, and late cards show in red. Columns sit side by side on the unfolded screen and
  in tabs when folded. "Export" writes a French synthesis for a status meeting to the inbox. The
  boards list syncs (`10_Journal/system/boards.json`); cards in work folders hide outside Work mode.
- **Objectives** (Work hub). Notes tagged `#objectif` are your objectives: their checkboxes are
  milestones (a due date as `📅 2026-12-01` or `[due:: 2026-12-01]`), ticked from the app with a
  surgical edit, and the mission-log entries sharing one of their other tags are listed as
  material for the review. An objective with no progress for three weeks is flagged. A weekly
  protected slot (day, start, length) sends a nudge when it starts — what is due within two weeks
  and what stalled — never outside Work mode.
- **Meetings & next actions** (Work hub). "New meeting" writes a French meeting note (agenda,
  notes, decisions, actions; its length in `duration_min`) to a meetings folder, by default
  `Réunions` in your first work folder. A meeting's decisions go to the mission log in one tap,
  never twice. Every `- [ ] @moi …` line in the vault is one of your next actions, soonest due
  first, ticked from the app. A week-by-week table shows meetings (and their time when
  recorded), requests and off-ticket time.
- **Load vs recovery** (Work hub). Eight weeks of work days: each day's load (meeting time and
  off-ticket time) against the next night's sleep, HRV and resting heart rate and the day's mood.
  For each, the average after heavy days (above your median load) and after light ones, with the
  number of days behind each, and the correlation when there are at least ten days — "a
  correlation is not a cause". An overload signal shows on the Work hub when the last seven days
  carry at least 30% more load than your usual week and a recovery measure is below your own norm
  (last night's recovery, the week's HRV or sleep, the week's mood). Hidden outside Work mode and
  while screen-share safe is on.
- **Modes: Work, Personal, Off.** A chip on the Daily screen and a Quick Settings tile switch
  modes. Outside Work mode, the folders marked as work disappear from the file list, search, AI
  answers and resurfaced notes; Off is for evenings, weekends and leave. A schedule (work days
  and hours) can switch modes automatically, a manual choice lasts until the next schedule
  change or midnight, and a leave period makes every day Off. **Screen-share safe** hides work,
  private and health content until it is turned off: the mood and health figures on the Daily
  screen, Life Stats and the mood history, and the Health, Companion and Mood widgets. Key dates get a countdown card on the Daily screen; work dates hide outside Work
  mode. The configuration lives in the vault (`10_Journal/system/context_modes.json`) so it
  syncs; the manual choice stays on the device. Settings → Modes.
- **Confidentiality guard.** Before every commit, what the change adds is checked against a
  list of protected names (clients, employers, people) and against secret formats (GitHub,
  GitLab, AWS, Google, Slack, Stripe and AI-provider keys, private keys, JSON Web Tokens,
  passwords in URLs, generated-looking `password=`/`token=` values). A finding holds the
  commit (fail-closed): nothing is recorded or pushed, the sync stops before the pull with
  "Sync held", and a notification and a Daily banner (counts only) lead to the review screen,
  where each finding can be opened, moved to the private vault (encrypted, clear copy deleted)
  or allowed in its note or folder. Only additions count: what is already committed never holds
  a later edit. Names match as whole words, ignoring case and accents, in text and new file
  names; a commit message that would name one gets a neutral message. The protected names and
  exceptions stay on the device in encrypted preferences — never in the vault, which would push
  them. Settings → Confidentiality guard.
- **Recovery on Life Stats.** Resting heart rate, heart rate variability (RMSSD) and sleep stages
  are read from Health Connect and saved with each day's health metrics. A Recovery card compares
  last night with your own previous 28 nights: in range, below your usual recovery (HRV more than
  one standard deviation under your norm, or resting heart rate more than one above), or better
  recovered than usual; deep / REM / light / awake time shows as a bar. The baseline needs seven
  measured nights, and nothing is estimated: a missing signal reads "not measured". The two new
  Health Connect grants are asked for from the card; Health Connect still counts as connected
  without them.
- **Prompt presets, and a Coach.** The chat's middle mode is now your presets, kept in
  `99_System/presets.md` so you can edit them in Obsidian: one `##` section per preset, with
  `notes: yes` to ground it on your notes and a `description:` for the menu. Tap the selected
  segment to switch presets or open the file ("Edit presets" writes the defaults the first time).
  Boardroom moved there, keeping its history, and **Coach** joins it: a pocket coach for a lead
  that rephrases, then asks one open question at a time. Whatever a preset says, answers stay in
  French, and a prompt is capped at 1,500 characters to leave Gemini Nano room.

### Changed

- **The AI search reads code as code.** Each fenced code block is now embedded as its own chunk,
  labelled with its language, instead of being packed into the prose around it; a `#` comment
  inside a fence is no longer taken for a heading, and a `#tag` line is no longer either. The
  vault is re-embedded once in the background. People notes (`99_Private/People/`, used by the
  coming People & 1:1s feature) are never embedded.
- **Search reads a full-text index.** Note bodies are searched through an SQLite FTS4 index
  (accent-insensitive: "reseau" finds "réseau"; every word is a prefix) ranked by BM25, with
  file and folder names first. It replaces a `LIKE '%…%'` scan of every note on each keystroke.

### Fixed

- **Notes the app creates follow the vault's frontmatter contract.** Voice captures, web
  imports, chat answers saved to the inbox and blank notes now carry the standard keys in
  order (title, date_created, date_updated, type, status, tags, aliases) with the type the
  folder implies and its default status, plus one `# H1`; new daily notes carry only `date:`.
  The vault's linter used to flag every phone capture, and the old daily-note template was the
  source of the `type: system` / `status: active` drift in the journal.

## [10.2.0] — 2026-10-17

The **vault insights** release. It ships the feature batch built with Antigravity (Gemini) —
note history, Memory Echoes, a vault link check, voice notes and a Boardroom chat mode —
after a full review and a hardening pass: the batch also carried data-loss bugs and
regressions, all fixed before this release (see
`documentation/plans/2026-10-17-antigravity-review-and-roadmap.md`).

### Added

- **Note history (Time Machine).** The history icon on a note lists its git commits and
  shows a real line diff (git's histogram algorithm, unchanged lines folded) against the
  current text. Restore asks for confirmation, can be undone from the snackbar, and is
  refused for a version git can't read (such as the commit that deleted the note) instead
  of emptying the note. Encrypted notes have no history view.
- **Memory Echoes on the Daily screen.** Notes untouched for at least two weeks —
  measured in git history, since clones and pulls rewrite file times — rotate daily among
  the 30 most forgotten. "Mark as reviewed" rests a note for two weeks; reviews are kept
  in the vault (`10_Journal/system/memory_echoes.json`), so they sync.
- **Vault link check** (Settings → *Check links & orphan notes*): dead wiki-links and
  orphan notes, with the share of healthy notes. Embeds, attachments and fenced code are
  not counted as dead links.
- **Voice notes** from the Daily screen's action menu: to-dos go to the day's tasks, the
  rest becomes an `00_Inbox/` note (with the transcript), linked from one timeline line.
  Structuring uses Gemini Nano when it is ready and an offline heuristic otherwise.
- **Boardroom** chat mode: three short expert takes on a technical question —
  architecture, security, delivery — and a decision, grounded in your notes.
- **Move to another folder** from the rename dialog; **Delete** for timeline entries,
  tasks and private notes; *RPG & Gamification Rules* reachable from Settings;
  Johnny-Decimal badges on sub-category folders.
- **Health bonuses are granted.** Step goal (+50 VIG), sleep goal (+25 MND) and a high
  mood × activity synergy (+30 MND), each at most once per day — the bonus code existed but
  read a path that doesn't exist, so it never paid out.
- `![[image]]` embeds render, resolved like Obsidian (by path, then by name anywhere in
  the vault) from the file index, in notes and chat.

### Changed

- **Dictation is transcribed on the device** — private journal, Daily voice note and chat
  mic — with a Listening dialog showing the live transcript. If the device can't recognize
  French offline yet, the language pack is requested; Chat and Daily may then use the system
  recognizer with a "may use the network" notice, the private journal never does.
- **Cortex retrieval is contextual.** Each indexed chunk carries its note's title, folder
  and heading (the index is rebuilt once after updating), the notes handed to Gemini Nano
  are budgeted to fit its input window, and the answer only cites what the model saw.
  Cortex stays grounded — it says when your notes don't cover a question — and Spark has
  its own creative persona.
- **Commit messages name what changed** ("projects: update plan", "journal(2026-10-17):
  update daily note"), read from the staged diff; encrypted notes are never named.
- **Smoother note rendering.** Markdown is parsed off the main thread, and image embeds
  are resolved in memory instead of on the disk.
- The 470 MB embedding model is memory-mapped from the APK instead of copied to the cache.

### Fixed

- **Templates load again.** Template lookups read a database table nothing ever filled;
  they now read the vault file.
- **No more lost lines in the daily note.** Timeline entries, tasks, scraps and gratitude
  are burned as one line each, and the parser only reads that line: a multi-line entry
  (the Add/Edit moment dialogs allowed one) lost everything after its first line on the
  next re-parse. Line breaks are now folded into " · ", and the dialogs are single-line.
- **The daily mantra survives a cold-start rebuild** instead of resetting.
- **Google Calendar**: the day window is right on DST days and events show in the device's
  time zone. **Google Tasks**: a task completed in the app stays completed across syncs.
- The sleep-goal slider no longer overwrote the step goal; large XP grants level up more
  than once; a widget task toggle grants XP; the habit widget logs the habit's target value.
- Archived habits are kept in the vault export; archived chores no longer trigger reminders.
- Imported articles: YAML-safe frontmatter and non-ASCII titles kept in file names.
- An invalid date in an `/event` command no longer crashes; callouts scale with the screen
  density; two moods logged in the same minute no longer duplicate.

### Removed

- The unused ML Kit proofreading/rewriting/summarization, MediaPipe and AICore SDK
  dependencies.

## [10.1.0] — 2026-09-10

The **dogfooding release**: two months of daily use reported lags, a weather card that
stopped refreshing after returning to the app, and screens that never refreshed on their
own. A full-app review fixed the root causes — and the data-safety bugs it found on the
way. Sync stays manual; everything else now refreshes by itself.

### Fixed

- **The weather card refreshes again after a resume.** The reverse geocoder's error
  callback was never implemented, so any geocoding error — typical right after a
  resume, before the network is back — suspended the weather fetch forever. Every
  external wait (cached location, active fix, geocoder, HTTP) is now bounded and the
  city name can no longer hold up the forecast. The last reading shows instantly from a
  30-minute cache, a failed refresh retries three times, the card re-checks each time
  the app returns to the foreground, and pull-to-refresh forces a network fetch. A reading
  fetched elsewhere in the app (the day's board, the briefing) now shows on the card at once
  instead of staying hidden behind "Weather unavailable", and an empty forecast response is
  logged instead of failing silently. Past days use Open-Meteo's real archive host (the old
  path did not exist).
- **Screens follow the real day.** Daily, Habits, Stats, Mood and the file browser's
  "today's note" captured the date once, so they went stale after midnight in a
  long-lived process. A new `AppClock` follows midnight, clock/time-zone changes and
  every return to the app.
- **Steps, sleep and automatic habits refresh on their own.** They used to update only
  when a screen was created or on a manual sync; today's Health Connect metrics and the
  health-driven habits are now refreshed each time the app comes to the foreground (at
  most every 5 minutes), and the Daily and Stats screens re-read them when they change.
- **Widgets no longer show zeros or stale numbers.** Health Connect only answers a
  foreground app, so a background snapshot rebuild published zeros; it now uses the
  metrics saved by the last foreground refresh. The periodic system widget update
  rebuilds the snapshot, and task edits and Google imports refresh the widgets too.
- **Unreadable vault JSON is never overwritten.** A mood day, a year of habit logs or
  the gamification profile that failed to parse was rewritten from defaults; the file is
  now moved aside (`<name>.corrupt-<time>`) before anything is written.
- **Private journal: wrong passwords are refused, saves are atomic.** Any password was
  accepted on unlock, so a typo encrypted new notes with the wrong key and replaced the
  stored password; unlock now verifies it against existing notes. A crash mid-save
  could truncate a note; encrypted writes are now staged and atomically renamed, and a
  title containing `/` can no longer write outside the private folder.
- **Nothing typed right before leaving is lost.** Closing the note editor, the Daily
  Second Brain or the private journal within the typing pause dropped the last edits;
  they are now saved when the screen goes away.
- **Vault writes can no longer mix.** Concurrent saves of one note shared a `<name>.tmp`
  file, so one could publish the other's half-written bytes, and temp files lived inside
  the vault where a commit could pick them up. Each write now uses its own temp file,
  staged outside the vault.
- **Pulling while editing no longer reverts the pulled version.** The editor kept its
  pre-pull copy and the next keystroke saved it over the remote change; with nothing
  unsaved it now adopts the pulled file.
- **Renaming or moving the open note keeps it on its new path** — the next save used to
  recreate it at the old one.
- **Sync is sturdier.** Pull-to-refresh runs in the application scope (leaving the screen
  mid-sync could cancel it between commit and push); a forced sync waits for a busy git
  lock instead of silently doing nothing; the indicator can no longer stay stuck on
  "Syncing"; git network operations time out after 30 seconds instead of hanging; a push
  the remote rejects is reported as a failure; a stuck Google authorization can no
  longer hang the cycle; and a failed Google fetch no longer empties today's events and
  tasks.
- **Reminders and the nightly burn stay on time.** WorkManager's `UPDATE` kept the old
  schedule, so a changed reminder time was ignored after the first run and daily jobs
  drifted later every day; runs are now pinned to their wall-clock time and re-pinned
  after each run. The nightly burn no longer retries forever on a deterministic failure.
- **Web imports validate every redirect.** Only the first URL was checked, so a public
  page could redirect the fetch into the local network; every hop is re-validated and
  must resolve to public addresses (IPv6 literals and every numeric IPv4 spelling
  included), and links shared from other apps go through the same guard. The vault path
  jail also rejects sibling folders that merely share the vault's name prefix.
- **Smaller fixes.** Two mood check-ins in the same minute crashed Mood history. Editing
  a chore un-archived it and reset its order. Headings typed inside Ideas/Notes were
  dropped (or switched sections) on ingest, and `/event` lines re-created their timeline
  events after a restart. Updating a habit's value granted its XP again on every update,
  fast double taps rewarded twice, and level-ups left the XP bar over-full. Health Connect
  asked for only three of the permissions it needs. A chore never marked done counted as
  clean. A second chat message started a parallel answer. Share intents and deep links
  were replayed after an activity recreation. After the first login the mood, habit and
  chore screens stayed empty until a restart. Pull-to-refresh on an open note did
  nothing. The Second Brain save indicator never showed, and the Daily mantra ignored the
  saved one. An AI failure could be written into an imported article as its summary.
  "Export all to vault" failed silently when the remote was ahead. On-device search could
  crash when indexing and a chat search ran at once.

### Changed

- **Local changes are pushed in the background, batched.** Editor saves, habit toggles,
  mood entries, chores, tags, private notes and imports write locally and queue a single
  commit + push 15 seconds after the last change, instead of a network round-trip per
  change. Nothing is pulled automatically.
- **Mood tags edited on another device appear after a sync** instead of after a restart.
- **Faster everywhere.** Git operations no longer walk the whole `.git` folder first and
  saves no longer stage the working tree; XP grants and config exports no longer
  re-index the entire vault, and vault scans run one at a time in a single transaction;
  habit statistics are computed off the main thread from indexed logs; reconciles write
  only what changed; the note editor no longer recomposes the whole screen per keystroke,
  and Markdown rendering and editor highlighting are built once; private-journal keys
  are derived once per session instead of on every autosave; on-device search caches
  parsed vectors; average heart rate is aggregated by Health Connect; Keystore and TLS
  setup moved off the main thread at cold start; a preference write no longer wakes every
  settings observer.

### Added

- Regression tests for the Daily board's refresh contract (day rollover, unsaved typing,
  weather cache/retry/resume, `/event` duplicates), habit statistics, task-completion
  detection, the private-vault key cache, wall-clock work scheduling, sync lock and
  cancellation handling, git push/commit behaviour, import URL classification, daily-note
  headings and concurrent atomic writes.

### Removed

- Dead `MorningBriefingSection` composable.

## [10.0.0] — 2026-07-22

The **final-release milestone**: first release-signed, R8-minified build shipped to the
daily-driver device. Release-hardening week — every keep-rule gap that only bites in
minified builds was found and fixed (web import, Gson models, frozen widgets), the UI
was unified in English (the AI keeps answering in French), fenced code finally
highlights, the two highest-risk untested sync paths got regression harnesses, and
lint went from 157 warnings to zero.

### Fixed

- **Daily-note frontmatter no longer degrades over repeated burns.** The burner
  received the YAML *without* its `---` fences and appended it verbatim, so the second
  burn of a day emitted an unfenced block (breaking Obsidian properties) and the third
  reset it, silently dropping user-added keys. The burner now always re-fences.
  Reported by the new round-trip test harness.
- **AI source citations are now tappable.** The source chips under a RAG answer open
  the cited vault note (chat → file browser detail pane) instead of doing nothing —
  the last dead button in the app.
- **Widgets froze in release builds — fixed.** WorkManager instantiates its
  `InputMerger` reflectively by class name; R8 stripped the no-arg constructor of
  `OverwritingInputMerger`, so every Glance widget re-render job died with
  `NoSuchMethodException` (found while dogfooding the first minified release
  candidate). Targeted keep rules added for InputMergers and Glance
  `ActionCallback`s.
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

- **The UI now speaks one language: English.** The app was a mixed English/French
  bag; every hardcoded user-visible string (screens, dialogs, buttons,
  accessibility descriptions, messages, widget + shortcut labels) is now unified
  in English to match the device's system language. The **AI keeps answering in
  French** by design (v9.4.0 French-first AI, `AiLanguage` directive, fr-FR
  dictation) — that layer is untouched. Technical identifiers (routes, persisted
  keys, date patterns, vault markdown headers) are intact; `stringResource`
  externalization remains planned post-v10.
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
