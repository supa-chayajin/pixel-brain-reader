# v11.0.0 — Work direction & Dev/Lead toolkit (2026-10-18)

**Scope:** everything approved on 2026-10-18: the eight work-direction features (#1–#8), the improved
backlog ideas (E1–E9) and the new dev/lead tools (N1–N11), plus the roadmap enablers they need
(2.1 full-text index, 2.3 links index, 2.6 inbox filing, 2.7 prompt presets). One version bump at
the end: **11.0.0**. Until then every commit keeps `CHANGELOG.md` `[Unreleased]` current, lint at
0/0 and the unit tests green (settled decision #10).

Unless a path starts with `app/` or `documentation/`, it is relative to
`app/src/main/java/cloud/wafflecommons/pixelbrainreader/`.

## Ground rules for this release

- **This repository is public.** No employer, client, product or people names in code, tests,
  docs or commit messages. Work folders, person aliases and the confidentiality deny-list are user
  settings, never constants.
- **The deny-list stays on the device** (encrypted preferences). Writing it to the vault would push
  the very names it protects to the git remote.
- **App-created notes follow the vault DTO** (title, date_created, date_updated, type, status, tags,
  aliases — in that order; type inferred from the folder; emoji-prefixed status). Existing notes are
  edited surgically (one frontmatter line at a time), never re-serialized.
- **Read-only inputs:** auto-generated worklogs (`*/Worklog/YYYY-MM-DD.md`), the manual dashboard
  note, encrypted notes outside an unlocked session.
- App state that must sync lives under `10_Journal/system/` as JSON; writers read the disk first
  (settled decision #8). Derived, re-buildable state lives in Room (destructive migrations).
- Generated documents (reports, handovers, briefings) are French, like every AI output; UI copy is
  English (settled decision #5). Every number in a generated document comes from data; nothing is
  estimated ("non mesuré" when it wasn't measured).
- **Correction to the proposal:** F1 was moot — private chunks are already stored encrypted
  (`IndexingWorker` encrypts them, `VectorSearchEngine` decrypts on demand). Only the vectors are
  plain, a documented trade-off. What remains of F1: people notes are never indexed at all.

## Checklist

### A — Foundations
- [x] **A1** Vault schema in code: folder → type → default status, an R1-compliant frontmatter
      builder, a surgical frontmatter line editor. Voice captures and web imports use them (E7).
- [x] **A2** Full-text index (roadmap 2.1): Room FTS4 over `file_contents`, accent-insensitive,
      BM25 ranking. Replaces the `LIKE` search.
- [ ] **A3** Links index (roadmap 2.3): `links(source, target, kind)` maintained by the vault scan.
- [ ] **A4** Modes (N1): Work / Personal / Off, a schedule, leave periods, key dates, screen-share
      safe; one visibility policy applied to lists, search, AI search, echoes, health/mood cards,
      widgets and notifications; a Quick Settings tile.
- [ ] **A5** Confidentiality guard (#8, E9): on-device deny-list + secret patterns, checked on the
      staged diff before every commit; a hit holds the commit (fail-closed) until reviewed.
- [ ] **A6** Recovery signals (E8): HRV, resting heart rate, sleep stages from Health Connect.
- [ ] **A7** Code-aware chunking (E9): code blocks are their own chunks, labelled with their
      language; people notes are never embedded.
- [ ] **A8** First-seen times in the git history walk (for N11).
- [ ] **A9** Prompt presets (roadmap 2.7) in `99_System/presets.md` + the Coach preset (N9).

### B — Work direction
- [ ] **B1** Mission log (#1): fact / decision / request / refusal / off-ticket time, a role
      dimension, a status label, optional minutes, requester/decider by role, reference, tags; one
      markdown file per month (optionally encrypted); capture from the app, by voice, from the
      quick-actions widget and a Quick Settings tile.
- [ ] **B2** Leave mode & handover (#3): key-date countdown, leave = Off for a date range, a
      handover draft for a scope and period, a "what happened" briefing on the first day back.
- [ ] **B3** Evidence report (#2): monthly / quarterly draft from the log.
- [ ] **B4** Boards (#4, E3): any folder as a board on the vault's status vocabulary; moving a card
      rewrites `status` + `date_updated` only; next step / blockers / target date; synthesis export.
- [ ] **B5** Objectives tracker (#5): objective notes with milestones, a weekly protected slot,
      nudges, tagged log entries as material.
- [ ] **B6** Meetings & requests (#6): meeting notes with decisions and owned actions; my actions
      become next actions; workload metrics.
- [ ] **B7** Load vs recovery (#7): off-ticket time and meetings against sleep, HRV and mood, with
      sample sizes; an overload warning.

### C — Dev & lead toolkit
- [ ] **C1** Template forms (E1): every `{{variable}}` becomes a field; `/` commands in the editor.
- [ ] **C2** Connections panel (E2): backlinks, unlinked mentions, related notes; `[[` completion.
- [ ] **C3** Snippets & runbooks (E4): code-block index with copy, runbook player with incident log,
      copy buttons in rendered notes, PHP / TOML / C# highlighting.
- [ ] **C4** Sync digest + vault activity (E5).
- [ ] **C5** Fold (E6): tabletop layouts, drag & drop into notes.
- [ ] **C6** Vault rules on the phone (E7): advisory lint badge; inbox filing with folder and tag
      suggestions (roadmap 2.6).
- [ ] **C7** AI pairing dashboard (N2) from the auto-generated worklogs.
- [ ] **C8** Explain this error (N3): shared stack trace → past occurrences, explanation, bug note.
- [ ] **C9** Decision records (N4).
- [ ] **C10** People & 1:1s (N5), encrypted; pre-meeting reminders.
- [ ] **C11** Project dashboard + next actions + widget (N6).
- [ ] **C12** Security watch (N7): public feeds, matched on the device.
- [ ] **C13** Hand-off to desktop sessions (N8) + the desktop hook how-to.
- [ ] **C14** Workshop kit (N10).
- [ ] **C15** Authorship timeline (N11).

### D — Release 11.0.0
- [ ] **D1** `versionCode 1100` / `versionName "11.0.0"`, CHANGELOG section, CLAUDE.md facts,
      settled decision #14 (deny-list stays on the device), lint 0/0, tests, `assembleRelease`,
      instrumented tests compile; an emulator run before calling it a release candidate.
