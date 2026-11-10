# Action Plan: PixelBrainReader as the phone side of the Claude Code workflow (2026-11-06)

**For:** the Claude session that develops PixelBrainReader.
**Scope:** let the owner follow, steer and unblock desktop Claude Code sessions from the phone, using what the vault already holds: `TODO.md`, `Worklog/`, `Handoff.md`, `Claude/Reports/` and the generated mirrors `Repo/` and `Claude/Memory/`.
**Not in scope:** git or GitHub operations from the phone (merge, push, review). Those stay on the desktop with the owner.

Paths are relative to `app/src/main/java/cloud/wafflecommons/pixelbrainreader/` unless they start with `app/`, `documentation/` or `vault:`.

## 0. Ground rules (from the app's CLAUDE.md, binding)

- The settled decisions apply, in particular #6 (manual sync), #8 (read the disk first), #9 (no silent dependency changes) and #10 (each batch ships its bookkeeping: version bump, CHANGELOG, CLAUDE.md facts, lint at 0/0, one concern per commit). Also #11: commit on `main`, no branches.
- **Public repo:** no employer, client, product or people names in code, tests, docs or commits. Write "a project with desktop worklogs", never a project's name.
- UI copy is English. AI prompts and generated notes are French.
- Desktop-side changes (vault `scripts/`, `worktrace.py`, `repo_mirror.py`) are **outside this repo**. List them as "needs the desktop side" and never edit them from this session.
- **Before planning code:** check the v12.3 plan (`documentation/plans/2026-11-05-v12.3-career-and-project-loop.md`) for overlap. Step 6 below merges into its Step 5.2 rather than duplicating it.

## 1. What the vault looks like now (facts to design against)

| Vault file | Written by | Shape |
|---|---|---|
| `<project>/TODO.md` | Claude sessions and the owner; committed at SessionEnd | The **live TODO**. `- [ ] @moi …` lines are the owner's. Lines without `@moi` are Claude's queue. Sections are `##` headings, and due dates use `📅 yyyy-MM-dd`. |
| `<project>/Worklog/yyyy-MM-dd.md` | SessionEnd hook | `## 🧬 Commits` per repo. `## 🤖 AI sessions` has three lines: `N session(s) · N prompt(s) · N tool call(s)`, `Tools: Bash×187, Edit×49…`, and `N tokens total (… in · … out · … cache-write · … cache-read)`. |
| `<project>/Handoff.md` | the phone (C13); ticked on either side | `- [ ] yyyy-MM-dd HH:mm — text` |
| `<project>/Claude/Reports/yyyy-MM-dd_<Topic>.md` | Claude | One dated report per piece of work: what was done, decided, and left. |
| `<project>/Repo/**`, `<project>/Claude/Memory/**` | `repo_mirror.py` (desktop), regenerated each session | ~230 **generated, read-only** notes with `mirror_of:` in their frontmatter. They are overwritten on the next run, and contain ~34 open checkboxes (roadmap and ticket criteria) that are not the owner's actions. |

## 2. Steps, by priority

### P1: make the existing promise true (patch release, 12.2.x)

**Step 1: `TODO.md` actions on the project page**
- The current code (`Projects.kt:141-143`) excludes `TODO.md` from `actions`. The CLAUDE.md line "TODO.md … is a prose session journal — link it, never parse it" is out of date: the vault now tells the owner that `@moi` lines appear under *Next actions*, and they don't.
- **Owner decision first (D1).** Recommended: parse `TODO.md` with the existing `MeetingFormat.actions` (it already handles `@moi`, `mine` and `📅`).
- Then, on `ProjectsScreen`:
  - `@moi` lines go under *Next actions*, yours first, then by due date (the existing sort).
  - Non-`@moi` open lines go in a compact **"Claude's queue"** card: a count plus the first three lines, tap to open the TODO.
  - Keep the section heading as context (the `##` the line sits under).
- Update the CLAUDE.md fact.
- Tests (`ProjectsTest`): a TODO with `@moi`, non-`@moi`, done and `📅` lines.

**Step 2: treat generated mirror notes as read-only**
- Recognise `mirror_of:` in the frontmatter (one pure helper next to `WorkNote`).
- For those notes:
  - No editing: show a "Generated, edit the source on the desktop" banner instead of the editor.
  - Excluded from project actions, "recent notes" (each mirror refresh would flood it) and the vault-rules lint badge.
  - Still listed and still linkable.
- RAG: **owner decision (D2).** Recommended: keep `Repo/` indexed (asking Cortex "what's in the next release?" is useful) and leave `Claude/Memory/` out (it duplicates the desktop memory and is noise for retrieval).
- Tests: the helper, plus the action and recent-note filtering.

**P1 status (2026-11-06):** Steps 1–2 done on `main` (`7bdf5ea` mirrors read-only, `2019c94` `Claude/Memory/` out of RAG, `076618e` TODO actions + Claude's queue), released as 12.2.1. D1 and D2 are settled decisions #15 and #16. Beyond the plan: mirrors also leave the project's **key notes** (43 mirrored roadmaps/RFCs against 3 hand-written key notes on the framework project); each mirror's `:__index__` note stays as one key note. Board moves into a mirror are refused too.

**P2/P3 status (2026-11-06):** all done, released as 12.4.0: Step 3 `fbcd70d`, Step 4 `ebdde7f`, Step 5 `fa69263`, Step 8 `18407a7` (+ `57d1267` outline polish, done before Step 7 because it adds the segmented reader), Step 7 `55b04ea` (Mermaid 12.2.0 bundled, drawn offline on the emulator). Step 6 shipped inside 12.3.0 (`920c352`). Needs the desktop side: `worktrace.py handoff` grouping lines by prefix.

**Follow-up (2026-11-06), 12.4.1:** the cockpit installed in four projects (the vault's `HOWTO.md` §13) showed two layouts the app didn't read yet — `Next_Actions.md` as the live list when the repo's own `TODO.md` is copied over the vault's (`fed62c5`), and sub-projects, a folder with its own `Handoff.md` inside a project (`4253d79`) — released as `b53b6d3`. Checked against the vault clone with a throwaway JVM probe (never committed). Desktop side, still open: a per-app worklog written both by a workspace session (`per_repo`) and by a session started in the app's repository — the last one to end rewrites it.

### P2: close the loop with the desktop

**Step 3: "What's new for you" in the sync digest (C4)**
- After a manual sync, the existing digest also shows, per project, only from what the pull changed:
  - new or changed `@moi` lines;
  - new `Claude/Reports/` notes;
  - hand-off lines ticked on the desktop (meaning "picked up").
- No auto-pull: settled decision #6 stays as it is.

**Step 4: hand-off upgrades (C13)**
- Hand off from more places:
  - from any open note ("Hand off this note", which links `[[note]]`);
  - from the share sheet;
  - from the mission-log Quick Settings tile's long-press, or a new action on the Projects widget.
- Optional typed prefixes the desktop can read: `décision:`, `question:`, `tâche:`. The owner can then answer an open question in the TODO (e.g. a `❓` section line) from the phone, and the next session reads the answer.
- Write the French prefix vocabulary into `documentation/how-to/desktop-handoff.md`.
- Needs the desktop side: `worktrace.py handoff` may group lines by prefix. Note it in the how-to; don't implement it here.

**Step 5: "Latest Claude report" on the project page**
- `isKeyNote` gains the newest `Claude/Reports/*.md`. The project header shows its title and date.
- One line, no new screen.

### P3: reading and insight

**Step 6: AI pairing dashboard (C7). Merge with v12.3 Step 5.2**
- `parseWorklog` already reads sessions, prompts and tokens. Extend it to:
  - tool calls (`N tool call(s)` on the same line);
  - the `Tools:` breakdown;
  - the four token buckets (in, out, cache-write, cache-read).
- Show over 14 days, measured values only ("not recorded" when a field is missing):
  - sessions per day;
  - prompts per session;
  - top tools;
  - cache-read share;
  - commits per session.
- No estimated cost, no scores (the v12.3 rule).
- Tests: a worklog fixture with all three lines, plus an older worklog without the tools line.

**Step 7: Mermaid diagrams, offline**
- The owner announced it, and Obsidian already renders them.
- A fenced ` ```mermaid ` block renders as a card. Tapping it opens an **offline** WebView with a bundled `mermaid.min.js` asset, pinned version, no network, `GuardedWebViewClient`-style blocking of every request.
- Markwon can't do it, and no plugin may touch the disk.
- This adds a dependency, so it needs the **owner's OK (D3)** under settled decision #9. State the asset size.
- Fallback: show the source with a copy button.

**Step 8: long technical notes on the Fold**
- Check first whether this exists. Nothing obvious turned up.
- What to check for:
  - a heading outline (jump to a section) for RFCs and roadmaps;
  - horizontal scroll for wide tables;
  - a sticky "back to top".
- Ship only what's missing.

## 3. Decisions for the owner (ask before coding the step)

| # | Question | Recommendation |
|---|---|---|
| D1 | Parse `TODO.md` into actions (reverses "link it, never parse it")? | Yes. The vault TODO is now the live list. **Decided 2026-11-06: the live TODO only.** A TODO that `worktrace.py` mirrors from a repository ("Mirrored automatically" banner: 1,400 lines, 77 open boxes, no `@moi`) stays a link and is read-only. |
| D2 | Which generated mirror notes go into the RAG index? | `Repo/` yes, `Claude/Memory/` no. **Decided 2026-11-06: as recommended** (old vectors purged, no re-index). |
| D3 | Bundle Mermaid (new asset/dependency)? | Yes, offline only, pinned. **Decided 2026-11-06: bundle `mermaid.min.js` 12.2.0** (MIT, 5.2 MB, published 2026-10-13). |
| D4 | Order relative to v12.3 | P1 as 12.2.x first (it fixes a broken promise), then v12.3 with Step 6 folded in, then P2 and the rest of P3 as 12.4. **Decided 2026-11-06: as recommended, both plans the same day;** the owner builds the signed APK in Android Studio. |

## 4. Commit sequence (on `main`, one concern each)

1. `feat(work): the project TODO's actions on the project page, Claude's queue apart`
2. `feat(vault): generated mirror notes are read-only and stay out of actions and recents`
3. `chore(release): 12.2.x`
4. *(v12.3 batch, with Step 6 merged in)*
5. `feat(sync): what's new for you in the sync digest`
6. `feat(work): hand off from any note, the share sheet and the widget; typed hand-offs`
7. `feat(work): the latest Claude report on the project page`
8. `feat(notes): offline Mermaid diagrams` *(after D3)*
9. `feat(notes): outline and wide tables for long notes` *(only what's missing)*
10. `chore(release): 12.4.0`

Each release must pass `./gradlew :app:testDebugUnitTest` and `:app:lintDebug` at 0 errors / 0 warnings, and update CHANGELOG and CLAUDE.md.
