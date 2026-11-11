# The dream app — RFC triage and release train (2026-11-10)

**Status:** accepted by the owner on 2026-11-10 (decisions D1–D8, §5). This is the living
checklist: tick an item in the commit that ships it, and update the vault's `ROADMAP_V13` in the
same batch (gate 8). It carries on the open parts of `2026-10-17-antigravity-review-and-roadmap.md`
(phases 2–6), of `2026-10-18-v11-work-and-dev-toolkit.md` (C8, C10, C12, C14, C15) and of the
vault's ROADMAP_V12.

**Sources.** Every RFC, roadmap, note and TODO in the app's vault project folder (RFC-000 to
RFC-008 with 006a; roadmaps V4.5, V5, V6 and V12; the ideas list, the Google ecosystem note, the QA
feedback, the 2026-10-22 review, the Gemini Nano feasibility TODO, the V4 retro) and the plans in
this folder. Each item was checked against the CHANGELOG (10.2.0 → 12.8.0) and the code, not
against the checklists, which were stale in places.

Unless a path starts with `app/` or `documentation/`, it is relative to
`app/src/main/java/cloud/wafflecommons/pixelbrainreader/`.

## 1. The thesis

The app already knows the day: calendar, tasks, health, mood, habits, projects, notes and their
git history, all on the device. The dream app uses that knowledge to **prepare** each part of the
day and **close** it, without opening ten screens. This is the V12 "companion" thesis, which the
owner chose on 2026-11-10 to lead right after C8.

Principles:

1. The vault is the truth; writers read the disk first (#8).
2. Embeddings and statistics think; Nano talks. Every number comes from data, every feature works
   without Nano, and Nano writes short French narration (≤ 256 output tokens).
3. 100% on-device AI (#4). Sync stays manual (#6).
4. Honest numbers: N shown, "not measured" rather than an estimate, "a correlation is not a cause".
5. Proactive, but quiet. Things are prepared in one place (Daily); nothing notifies unless switched
   on; nothing work-related shows outside Work mode.
6. Close the loop: a capture ends filed, linked, reviewed or counted.
7. The owner arranges the screens. New Daily blocks arrive in "More today"; Settings sections never
   move.

## 2. Already true (12.8.0)

| Area | Shipped |
|---|---|
| Vault & sync | JGit vault on the device; manual sync that stops on a conflict and keeps backups; debounced push-only saves; the confidentiality guard; read-only devices (12.6.0) |
| Daily | The board (tasks, timeline, scraps, gratitude) burned losslessly; the Add box; the evening check-in; blocks in the owner's order; project cards (12.8.0) |
| Life OS | Habits (automatic ones from Health Connect), chores, mood (tag icons, emoji sets), health and recovery, RPG gamification, eight widgets |
| Knowledge | FTS4 search; links, backlinks and unlinked mentions; on-device RAG (MiniLM, 0.35 floor); prompt presets; Time Machine; vault activity; Mermaid; the note reader |
| Work toolkit | Modes, mission log, decision notes (C9), boards, objectives, meetings, evidence report, leave & handover, load vs recovery, runbooks & snippets, inbox filing |
| Desk ↔ phone | Projects tab and widget (C11), hand-offs (C13), the live TODO and Claude's queue, answers, what's new after a sync, AI-pairing figures (C7), mirrors and worklogs read-only |
| Platform | Wide layouts (rail, two columns), tabletop, drag & drop, share import in reader mode, Quick Settings tiles, launcher shortcuts |

## 3. Release train

Versions are indicative; one release is one concern with its own CHANGELOG section. Sizes:
S ≤ 2 h, M ≤ 1 day, L > 1 day.

**Version codes.** `versionCode` is major×100 + minor×10 + patch (12.9.0 = 1290). It still
climbs through 12.10 (1300) and 12.18 (1380), but 13.0.0 would then come out at 1300, lower than
1380, and Android refuses a lower code. Decided (owner, 2026-11-10): major×10000 + minor×100 + patch from 12.10.0 (= 121000), so
13.0.0 = 130000 still updates in place.

### 12.9.0 — Trust (all S, no new dependency)

- [x] **T1 AI self-test.** *(12.9.0)* Settings → Intelligence → "Run a test": one `generateContent` through
      the production request builder, then one streaming call. It shows the latency, the finish
      reason and the exact error. It runs after every (re)download, and Daily shows a one-line
      banner when it fails. *(V12 11.2-A: the 11.1.0 "Ready, but every request fails" class.)*
- [x] **T2 Read-only Google scopes.** *(12.9.0)* `CALENDAR_READONLY` + `TASKS_READONLY` in
      `GoogleAuthRepository.authorize`, since both syncs only import. Drop the stale "bidirectional
      sync needs read+write scopes" comment. Google asks for consent once more at the next
      sign-in. *(Oct 17 S4, D5 and 3.5; D4.)*
- [x] **T3 Private notes reach the AI only when switched on.** *(12.9.0)* A row in Knowledge Vault (RAG), off
      by default. When off, `IndexingWorker` never embeds `99_Private`, their vectors are dropped
      the way `neverEmbedded` drops Claude's memory (no re-index), and retrieval never decrypts a
      private chunk. When on, the app behaves as it does today. Add settled decision #20 in the
      same commit. *(RFC-008 §1: "invisible to the AI"; D3.)*
- [x] **T4 Guardrails.** *(12.9.0: 19 of the 20 compiler deprecations; the last one is in §4)*
  - Gradle dependency locking (`dependencyLocking { lockAllConfigurations() }`, lockfiles
    committed), so any upgrade or downgrade shows up as a diff: settled decision #9, made
    mechanical.
  - A unit test that `versionName` has its CHANGELOG section.
  - The Material3 deprecations listed in the 2026-10-22 review.
  - A public-safe root `AGENTS.md` that points every agent to
    `documentation/explanation/settled-decisions.md` and `documentation/plans/`.

  *(Oct 17 4.3, 4.4, 4.8; the 2026-10-22 review.)*
- [x] **T5 Authorship timeline (C15).** *(12.9.0)* Connections shows a note's first appearance,
      its last change and its commit count, from `GitFileAges.history()`, which already feeds Vault
      activity and Leave. *(v11 C15.)*
- [x] **T6 Chat answers saved with their sources.** *(12.9.0)* "Save to Inbox" passed only the
      text. The note now ends with a `## Sources` section: the notes the model saw, as path
      `[[links]]`, never a private one. *(V12 E13.)*

### 12.10.0 — Explain this error (C8), on measured retrieval

- [x] **R1 Golden set and harness.** *(12.10.0; the set's draft is in the vault for the owner to validate)* Claude drafts ~30 questions from the vault, each with the
      notes that should answer it (error lookups for C8 among them); the owner validates them. The
      set lives in the private vault (e.g. `99_System/rag_golden_set.md`, never embedded per #3),
      never in this repo. A debug action runs it and logs recall@6 and precision.
      *(Oct 17 2.2; V12 11.2-D; D2.)*
- [x] **W1 Explain this error (C8).** *(12.10.0)* Share a stack trace or a log excerpt and get, offline:
  - its past occurrences in the vault (FTS first, because exception names and error codes are
    lexical), with the runbooks and notes that mention them;
  - a short French explanation grounded on them (the trace's middle cut to fit Nano's window,
    `AiLanguage.DIRECTIVE`);
  - a bug note from the vault's `T_Bug_Report` template, written through `DraftWriter`.

  The phone answers "have I seen this before, and what did I do?"; fixing the code stays with the
  desktop tooling (it has its own "explain this error" RFC). **A plan for review comes before any
  code** (owner, 2026-11-10). *(v11 C8.)*
- [ ] **R2 Hybrid retrieval.** Reciprocal-rank fusion of the FTS4 top 20 and the vector top 20,
      with the 0.35 floor kept on the vector side (#2). It ships in this release only if R1 shows
      recall@6 up and no off-topic context; otherwise it waits.

### 12.11.0 — The companion: the day

Built in 12.11.0 (2026-11-10). The design, written for the owner's review, is `2026-11-10-12.11-companion-day.md`.

- [x] **D1 Task rollover.** Yesterday's unfinished tasks are offered on today's board: Bring adds
      the task to today, Dismiss is remembered on the phone. Yesterday's note is never rewritten
      (owner, 2026-11-10), so the burn format does not change. *(RFC-002 "Reporter à demain"; V12 6.3.)*
- [x] **D2 Due today.** A Daily block listing objective milestones and `@moi` actions due today or
      overdue, plus the chores due; the recovery verdict joins Mood & health. It arrives in "More
      today". *(V12 6.1, the deterministic part of the morning brief.)*
- [x] **D3 Health section.** Each burn ends the day's note with a `## 🩺 Health` section (owner,
      2026-11-10): sleep, steps, HRV and resting heart rate, measured values only. It is derived:
      rewritten from the day's health file at each burn, and `DailyMarkdownParser` knows the
      section and skips it, so nothing enters Room and the round trip holds (#7); an unknown `##`
      would otherwise be read as the previous section's content and grow at every burn. Today's
      note stays unembedded (#3), so a day becomes searchable the day after.
      *(RFC-006 §4 "Physio-context".)*

### 12.11.1 — Fixes (2026-11-11)

- [x] **Indexing always finishes.** Resumable per passage (`NoteEmbedder`), a changed note keeps
      its vectors for reuse, runs hand over before the 10-minute job limit, four inference threads.
- [x] **The first clone gets through.** Newest commit first, the history in resumable steps
      (`VaultHistoryFetcher`), a boundary broken by a dropped step repaired from the objects here.

### 12.12.0 — The companion: meetings and the watch

Built in 12.12.0 (2026-11-11). The design, reviewed by the owner: `2026-11-11-12.12-meetings-and-watch.md`.

- [x] **D4 Meeting prep.** A calendar entry on the timeline opens its prep: the vault notes about
      its title (hybrid retrieval when R2 has shipped) and the last decisions about it. An opt-in
      notification comes 10 min before, in Work mode only, silent in quiet hours. People's open
      points join with P1. *(Oct 17 5.1; V12 6.1.)*
- [x] **D5 Tech watch (RFC-003 revived).** A Daily block of headlines grouped by feed, each with
      Read (in the app) and Import (the share reader, to the inbox).
  - The feeds move from the hard-coded personal list in `NewsRepository` to a vault config file
    edited in Settings.
  - It refreshes on foreground through a cache, never on a timer.
  - `DailyBriefingRepository` is folded into it or removed.
  - It arrives in "More today".

  *(RFC-003; D7.)*

### 12.13.0 — The companion: the week and the month

- [ ] **D6 Weekly review note.** `10_Journal/Weekly/YYYY-Www.md`, burned on Sunday evening (pinned
      with `DailyWorkScheduler`). It never overwrites an existing note (the " (2)" rule). It holds:
  - habits, the mood trend, sleep and steps averages, and the tasks and chores done;
  - XP, plus the notes created and edited (from git);
  - mission-log counts and decisions without an ADR;
  - objectives that moved or stalled, and the overload signal;
  - an optional 3-line French reflection;
  - a "Next week" section, filled in on Monday.

  *(Oct 17 3.1; V12 6.4 and E5.)*
- [ ] **D7 Monthly evidence draft.** On the 1st, last month's evidence report lands in the inbox
      (work content stays hidden outside Work). *(V12 6.4.)*

### 12.14.0 — The companion: the end of work

- [ ] **D8 Shutdown ritual.** At the end of Work hours (the Modes schedule), one Daily banner:
  - unfinished tasks go to tomorrow, someday or nowhere (D1);
  - the win of the day becomes a mission-log fact;
  - the inbox count;
  - "Close the day" burns the note and *suggests* the evening mode, never forces it.

  It is the Work-end counterpart of the 21:00 check-in. *(V12 6.3.)*
- [ ] **D9 Focus sessions.** A focus block shows as a promoted ongoing notification with a
      progress bar (a Live Update). At the end, the notes edited during it (from the git diff)
      become an optional mission-log fact and XP. Stop and ask if this needs a library bump (#9).
      *(Oct 17 6.2; V12 6.2.)*

### 12.15.0 — Sync safety

- [ ] **S1 Conflict screen.** It lists the `*_sync_conflict_*` backups that `ConflictResolver`
      leaves in the vault, with a side-by-side diff (the Time Machine HISTOGRAM engine) and keep
      mine / keep theirs / edit; resolving removes the backup. The new risk is the phone's TODO
      answers and hand-offs colliding with the desktop sessions' edits of the same files. **S1
      jumps the queue the first time a sync stops on a conflict.** *(V12 11.2-E; Oct 17 3.4; the
      ideas list's "modified files" view.)*
- [ ] **S2 Read-only polish.** On a read-only device, the Settings pages and tools that edit vault
      files say "Change this on your phone" instead of saving into a refusal. *(The read-only plan,
      "Not done in 12.6.0".)*

### 12.16.0 — Private vault hardening

- [ ] **V1 Change password.** Re-encrypts every `.md.enc` with a new salt: the journal, the
      encrypted mission log and ADRs, People. It must be crash-safe:
  - new copies are staged and checked, then swapped in one pass;
  - both passwords are accepted until the swap ends;
  - a test kills the process mid-way.

  *(Oct 17 3.6; V12 E10.)*
- [ ] **V2 Auto-lock** after N minutes in the background. *(Oct 17 3.6.)*
- [ ] **V3 (optional) Fingerprint-bound password.** With T3 off, nothing in the background needs
      the stored password, so it can sit behind a Keystore key that requires user authentication.
      *(RFC-008 §2.1.)*

### 12.17.0 — People & 1:1s (C10)

- [ ] **P1 People & 1:1s.** Encrypted notes in `99_Private/People/` (role, context, last 1:1, open
      points, agreed actions), never embedded (already excluded). Names live only there; the
      mission log keeps roles. A reminder comes before a 1:1 calendar event (Work mode,
      `muteWorkNotifications`). The open points show once the vault is unlocked, and they join
      D4's meeting prep. *(v11 C10; V12 11.3.)*

### 12.18.0 and after — The second brain (each gated on R1)

- [ ] **K1 Smaller embedder.** int8 MiniLM (`scripts/convert_minilm.py`): the ~448 MB asset
      drops to ~120 MB and the APK from ~600 MB to ~170 MB, and indexing gets faster. Bump
      `EMBEDDER_SCHEMA_VERSION`. It ships only if R1's recall holds. *(Oct 17 3.7.)*
      Measured 2026-11-11: passages are ~99 tokens (median, p90 156) but every inference pays the
      full 256, because the export hard-codes the shape (`resizeInput` fails on a RESHAPE). A
      dynamic-length export of the same weights would keep today's vectors (no re-index) at ~2.5×
      less compute, before any int8 gain.
- [ ] **K2 Ask this note / this folder.** Chat scoped to one note or a folder, with chained
      summaries for notes longer than Nano's window, and a Continue chip when an answer stops at
      the 256-token cap. *(Oct 17 3.2; V12 E12 and 11.2-B.)*
- [ ] **K3 Time Machine v2.** Word-level highlights, renames followed, and "changed since my last
      sync". *(Oct 17 2.4; V12 E9.)*
- [ ] **K4 Time-travel questions.** "What did I think about X a year ago?" Git gives the note as
      it was on that date; the app shows then vs now and a 3-line summary of the shift. Comes after
      K3. *(Oct 17 5.3.)*
- [ ] **K5 Related while writing.** The 3 notes closest to the current paragraph: a side pane when
      unfolded, chips when folded. Inference runs off the main thread, behind the inference lock.
      *(Oct 17 5.1.)*
- [ ] **K6 Memory echoes v2.** An opt-in scope (`#review` or folders), SM-2-lite spacing kept in
      the vault, and an echo widget. *(Oct 17 2.5; V12 E8.)*

### Then — the work cockpit

- [ ] **W2 Workshop kit (C14).** An agenda timer, a capture board, and a French synthesis note at
      the end. *(v11 C14.)*
- [ ] **W3 Security watch (C12).** A weekly digest note of public advisories (OSV) for a watchlist
      kept in `99_System/`, matched on the device. The desktop tooling scans lockfiles (its own
      RFC); the phone keeps the weekly view and the evidence-report line. *(v11 C12.)*
- [ ] **W4 Small toolkit wins** *(V12 §8)*:
  - mission-log entry templates, one tap from the tile (E4);
  - a leave from an all-day calendar event (E3);
  - board swipe and WIP limits (E6);
  - inbox swipe triage (E7);
  - a Toolkit widget (E17);
  - per-mode tab and widget visibility (E1);
  - the tile's custom mode icon (E2).

### Then — the private life OS

- [ ] **L1 Honest health insights.** Lagged Spearman correlations (sleep → next-day mood, load →
      next-night HRV), shown from N ≥ 14, with N and "a correlation is not a cause".
      *(Oct 17 3.3; V12 6.5.)*
- [ ] **L2 Personal experiments (N-of-1).** A hypothesis, an intervention habit, a 14-day baseline
      then a 14-day trial, with a bootstrap 95% CI. Comes after L1. *(Oct 17 6.4.)*
- [ ] **L3 Days like this.** Each day becomes one embedded day document, for "find days like
      today". Private notes count only when T3 is on. *(Oct 17 5.2.)*
- [ ] **L4 Small wins.** Streak protection (E15), a 15-minute chore reset (E16), and dropped images
      compressed (the ideas list, 6.2).

### 13.0 — Platform (each starts from a written plan)

- [ ] **X1 Wear OS companion** (RFC-007, deferred on 2026-11-04). Tiles for mood, the next action
      and focus; voice capture to the inbox. It needs a new module and new dependencies (#9).
- [ ] **X2 Capture anything.** A photo goes through on-device text recognition into an inbox note,
      with the image attached. A new dependency (#9). *(Oct 17 6.6.)*
- [ ] **X3 Privacy ledger.** "What left this phone today": host, bytes and purpose, never content.
      D5 and W3 add network calls worth showing. *(Oct 17 6.7.)*
- [ ] **X4 Capture from anywhere.** A voice-capture tile, and the system Notes role for
      lock-screen capture. *(Oct 17 6.1 and 3.8.)*
- [ ] **X5 PixelBrain Wrapped**, private by default. *(Oct 17 5.4.)*

## 4. Gates (every release)

1. **Plan before code** for anything that touches sync, the burn, crypto or retrieval (W1, R2,
   D1, D3, D6, S1, V1): a short plan the owner reviews.
2. **A failing test first.**
   - Burn↔parse round trips for D1, D3 and D6.
   - Real JGit temp repos for S1, K3 and K4.
   - A kill-mid-way test for V1.
   - A prompt-budget test for every new prompt: French, `AiLanguage.DIRECTIVE`, ≤ ~3,000
     characters in, 256 tokens out.
3. **Read-only devices.** Every new writer goes through `VaultWriteGate` (`VaultWriteGuardTest`).
   Every new worker, notification, tile or widget action is off there.
4. **Modes.** Work content hides outside Work, work notifications check `muteWorkNotifications`,
   and nothing notifies unless switched on.
5. **The owner's screens.** New Daily blocks (D2, D5) arrive in "More today". Banners (T1, D8)
   show only when relevant. Settings sections never move.
6. **Release.**
   - Version bump, CHANGELOG, CLAUDE.md facts.
   - Lint 0/0 and tests green.
   - One concern per commit, on `main`.
   - The tag and the GitHub release go out together (#19).
   - The owner builds the signed APK.
   - A Fold check for anything that touches R8, Nano, widgets or the burn: never a debug build on
     the Fold, never a sync from the emulator.
7. **Public repo.** No names. The golden set, People notes and the feed list live in the private
   vault.
8. **Vault bookkeeping.** Each release ticks its items here, updates the vault's `ROADMAP_V13`,
   the matching RFC's status note and the project hub's "Where things stand". The owner asked for
   the vault documents to follow the code.

**Stop rules.**

- R2 and K1 ship only if the golden set says so; otherwise the floor stays at 0.35 (#2).
- A Nano step that misses its latency budget on the Fold falls back to its deterministic content.
- A new dependency, a new permission or a removed feature means: stop and ask (#9).
- The hotspots (`MainScreen` 1,515 lines, `DailyNoteScreen` 1,388, `MainViewModel` 1,188) are
  split behind tests when an item touches them (K2 and K5 the editor, D1 and D8 the Daily screen),
  never as a release of their own.
- The last compiler deprecation, `FileListPane`'s swipe (`confirmValueChange`, deprecated without
  a replacement), moves to `onDismiss` the same way: when an item touches the file list, with a
  device check, since the row would then slide out and back.

## 5. Decisions (owner, 2026-11-10)

| # | Decision | Lands in |
|---|---|---|
| D1 | A trust batch before C8 | 12.9.0 |
| D2 | Claude drafts the golden set and the owner validates it; it stays in the private vault | R1 |
| D3 | Private notes reach the AI only when switched on (off by default) | T3, settled decision #20 |
| D4 | Read-only Google scopes | T2 |
| D5 | The companion leads after C8; the other themes follow in §3's order (Claude's proposal) | §3 |
| D6 | C10, meeting prep, C12 and C14 stay | P1, D4, W3, W2 |
| D7 | RFC-003's news comes back as a Daily block | D5 |
| D8 | Commits in the vault and in this repo; pushes stay with the owner | — |

Defaults the owner can change:

- Weekly reviews go in `10_Journal/Weekly/`.
- The shutdown ritual suggests the evening mode and never switches it.
- The meeting-prep notification is opt-in.

## 6. Parked and not planned

**Parked** (not planned until the owner asks):

- Read-only Phase 2 (per-device logs; the tablet stays read-only).
- A baseline profile (it needs a second Gradle module).
- The brain map.
- App Functions (an early API).
- RFC-005's "Echo" (location in frontmatter: the location history would sit in git for good).
- The showcase prerequisites (#13).

**Not planned** (superseded, or against a settled decision):

| Idea | Source | Why |
|---|---|---|
| Cloud fallback and its feature flag | V5 | #4: 100% on-device |
| A YAML parser for frontmatter | V4.5, V5, V6, the V4 retro | Replaced by the surgical line editor (A1): existing notes are never re-serialized |
| Mood nudges every 2 h | V4.5, V5 | Replaced by the evening check-in |
| `/task`, `/mood`, `/log` in the daily note | V12 E14 | The Add box routes a line |
| `strings.xml` | Oct 17 4.6, V12 E18 | The UI is English by #5 and no public release is planned (#13); reopen with one |
| Setup nudges | V12 11.2-C | Their trigger (empty work folders) is gone |
| Git branches or amend from the phone | Ideas 2.2 | The vault is main-only, and amend rewrites pushed history |
| Drive, Gmail, Keep and People APIs | The Google ecosystem note | Git is the backup; Gmail needs restricted scopes; Keep has no API; people live in encrypted notes (P1) |
| Skeleton screens, OLED black | V6 | Low value; the theme follows the system (#12) |
| Hiding widgets on the lock screen | RFC-004 | Screen-share safe covers it |

## 7. Where each source stands

| Source | Status | Open parts → item |
|---|---|---|
| RFC-000 Cortex | The January 2026 inventory (archived) | — |
| RFC-001 Neural Vault | Shipped, built differently: MiniLM TFLite and a DJL tokenizer, the ML Kit Prompt API, the 0.35 floor, source chips | R1, R2, K1 |
| RFC-002 LifeOS | Shipped | D1 |
| RFC-003 Briefing & news | The weather insight, the mantra and `pixelbrain://import` shipped; the news half lost its screen | D5 |
| RFC-004 Widgets | Shipped (eight widgets) | — |
| RFC-005 Osmosis | Calendar (through the Google API) and share-to-brain shipped | Echo parked |
| RFC-006 BioLoop | Feature A shipped | D3 (Feature B) |
| RFC-006a Bio-Link | Shipped | — |
| RFC-007 Wear OS | Deferred (owner, 2026-11-04) | X1 |
| RFC-008 Private Vault | Shipped | T3, V1, V2, V3 |
| ROADMAP V4.5, V5, V6 | Done or superseded | §6 |
| ROADMAP V12 | C7, C9, C11 and C13 shipped; the rest is this plan | §3 |
| The Oct 17 plan, phases 2–6 | 2.1, 2.3, 2.6 and 2.7 shipped in 11.0.0 | R1, R2, K1–K6, D6, L1–L3, S1, T2, V1, V2, X1–X5 |
| The v11 plan | C7, C9, C11 and C13 shipped | W1 (C8), P1 (C10), W3 (C12), W2 (C14), T5 (C15) |
| The ideas list | 12 of 14 shipped | L4 (image optimizer); 2.2 not planned |
| The QA feedback | All addressed | Nano's latency: T1, K1 |
| The 2026-10-22 review | Approved | T4 |
