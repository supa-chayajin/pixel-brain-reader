# Plan — a read-only device (and why a second writer is a different project)

Date: 2026-11-08. Status: **read-only built in 12.6.0** (layers 2–8 and the tripwire; layer 1
is the token the owner creates). Phase 2 (writes from a second device) is a proposal, below.

## Goal

A tablet (or any second device) logs into the **real** vault, keeps every note readable and
fresh, and **cannot change the vault in any way**: no file written in its clone, no commit, no
push. The AI layer is off on that device (no Gemini Nano, no embeddings, no chat).

## Why not simply "write mode on a second device"

The app assumes one writer. On a second device writing to the same vault (all verified in code):

- **Today's board is last-burn-wins.** Each device keeps today's tasks, timeline, scraps and
  gratitude in its own Room database and rewrites today's note from it (on leaving the app,
  before each sync, at 23:50). Today is never re-read from a pull (`ingest` refuses today), so the
  device that burns last erases the other's entries for the day.
- **Health is overwritten.** `ForegroundRefresher` rewrites today's health JSON from the device's
  own Health Connect every time the app comes to the foreground; a device without the watch writes
  zeros, and the health-driven habits are recomputed from them.
- **Shared state files conflict.** Habits, mood, chores, XP and twelve configuration files live in
  the vault (`10_Journal/data/…`, `10_Journal/system/…`, `99_System/presets.md`); edits on two
  devices stop the sync on a rebase conflict.
- **The confidentiality deny-list lives on one device** (settled decision #14), so a second device
  commits unchecked.

Making all of that safe means a new board format with stable item ids, per-device health roles
and mergeable state files: a project of its own (see "Later" below). **Read-only can be made
bulletproof now.**

## Design: eight independent layers

Each layer alone stops a write; a bug has to get past all of them.

1. **GitHub refuses.** The device logs in with a fine-grained token that has **Contents:
   Read-only** on the vault. Even a push that slipped through every layer below is rejected by
   GitHub. (No code: the login screen says so next to the switch.)
2. **The role is chosen at login, and only there.** A "Read-only device" switch on the login
   screen (off by default). Stored in plain preferences, read once at startup into a `DeviceRole`
   singleton (`isReadOnly`). Changing it means logging out and in: no half state at runtime.
3. **A fresh clone on login.** Logging in read-only (or to another repository) deletes
   `filesDir/vault` and clears the vault-derived Room tables before cloning, so no foreign commit
   (a sandbox's, an old write-mode clone's) can ever sit under the real vault. If the old clone
   holds changes that GitHub doesn't have, the login says how many and asks before discarding them.
4. **Git refuses.** In read-only, `JGitProvider.commit`, `push`, `commitAndForcePush`, `addAll`,
   `addFile`, `removeFile` and `setRemote` return `Result.failure(ReadOnlyDeviceException)` without
   touching the repository. `pull` becomes **fetch + fast-forward only**: never a rebase, never a
   merge commit; a diverged branch or a dirty tree stops with a clear error and a "Reset to
   GitHub" action (which can only discard what a read-only device should never have).
5. **Files refuse.** One gate, `VaultWriteGate.check()`, at the top of every vault writer:
   `SafeFileProvider.atomicWrite` (through `NoteRepository.saveNote` / `saveFileLocally`),
   `PrivateNoteRepository` (write, delete), `MissionLogRepository`, `AssetRepository`,
   `FileRepository` (rename, delete, `moveAsideCorrupt`), `DailyNoteRepository` (backup, restore)
   and `ConflictResolver`. In read-only it throws `ReadOnlyDeviceException`, which the callers turn
   into a "Read-only device" message. A guard test (like `UiLanguageGuardTest`) fails the build if
   code outside those files mutates a file under the vault, so a future writer can't bypass it.
6. **Background writers never start.** In read-only: no `VaultPushScheduler` push (the 23 callers
   of `requestPush` all go through it), no `ForegroundRefresher` health write or habit automation,
   no `DailyExportWorker` (23:50 burn) and no `ON_STOP` burn, no evening check-in or reminder
   workers, the sync cycle is pull → reconcile into Room → file index (no burn, no Health, no
   Google import, no commit, no push), widgets show their state but their taps open the app,
   Quick Settings tiles are inactive, and the share target / `pixelbrain://import` refuse with a
   message. (Room-only state — file index, today's board rehydrated from disk — stays: it is
   derived and never leaves the device.)
7. **The AI layer is off.** `LocalAiManager` never initialises (no Nano download or pre-load),
   `IndexingWorker` is never scheduled (no embeddings; keyword search still works from
   `file_contents`), and the Chat tab, "Analyze folder", "Ask Coach", clip summaries and voice
   compilation are hidden.
8. **The UI says so and offers nothing that writes.** A "Read-only" badge on the Daily header and
   the rail; every note opens like a desktop mirror today (`MirrorNotes`: banner, no Edit toggle,
   no drop target); the Add box, the + button, check-in and the habit/chore/task toggles are
   hidden or disabled; Settings that write vault files (Modes, mood tags, habit config, boards,
   presets…) are disabled with "Change this on your phone". Device-local settings (theme, Daily
   layout, reminders off) still work.

**Tripwire.** At every start and after every pull, a read-only device checks that its working
tree is clean (`git status`). If anything changed locally, a red banner lists the files and the
device stops syncing until "Reset to GitHub" — a write that got past layers 4–6 is then visible
at once instead of silently piling up.

## Tests

- JVM: each gated writer refuses in read-only and works in normal mode (temp vault dirs, like
  `JGitProviderConflictTest`); `JGitProvider` refuses commit/push and pulls fast-forward only
  (real JGit temp repos, including a diverged branch and a dirty tree); the source guard test
  of layer 5.
- On the tablet, against the **sandbox** first with a read-only token for it: a full day of use
  (open notes, rotate, background/foreground, 23:50, widgets, tiles, share sheet), then
  `git status` clean in the device clone, no push attempt in logcat, nothing new on GitHub.
- Only then the real vault, with a read-only token scoped to it.

## Bookkeeping

12.6.0, CHANGELOG, CLAUDE.md facts (a new settled decision: a read-only device never writes,
enforced in layers 1–8), lint 0/0, one concern per commit.

## Later (optional, separate plan): a "capture-only" second device

The one kind of write that cannot collide is a **new file with a unique name**: inbox captures,
shared pages, new notes. A second role could allow exactly those (still no board, health,
habits, XP, configuration, edits of existing notes or deletes), keeping layers 4–6 as the
allow-list. Editing existing notes from two devices remains protected only by the conflict
stop and its backups; a full second writer needs the board/state redesign above.

## As built (12.6.0)

- `data/device/`: `VaultWriteGate` (the switch, `check()` / `refuses()`, the refusal notices),
  `DeviceRole` (saved role, loaded before Hilt starts in `PixelBrainApplication.onCreate`),
  `LocalCopyReset` (fresh clone at login: the old copy is moved aside in one rename and deleted,
  Room is cleared except the chat history, the `filesDir` caches go; today's board is burned
  first on a writing device so it counts among the changes the login asks about).
- Git (`JGitProvider`): every writing command refuses; `pull` = fetch + `FF_ONLY` merge;
  `localChanges()`, `resetToRemote()` (read-only only), `deleteLocalClone()`; a read-only
  `setupRepository` only clones.
- Sync: `SyncOrchestrator.runReadOnlyCycle` (pull → digest → tripwire → today's board reloaded
  from disk → file index → Room reconcile), `checkReadOnlyDrift`, `resetToGitHub`;
  `FileRepository.syncRepository` has the same read-only branch.
- A refused write that no screen guarded still can't crash the app: `GlobalExceptionHandler`
  lets a `ReadOnlyDeviceException` go (every vault write runs in a coroutine), and the
  notice "Read-only device: nothing was changed." shows once per burst.
- Settings → About shows the role and has **Log out** again (with a confirmation): the button had
  been gone since January, and the role only changes at a login.
- Tests: `JGitProviderReadOnlyTest` (real JGit: refusals, fast-forward, diverged branch,
  local changes, reset), `SyncOrchestratorTest` (a read-only cycle never burns, commits or
  pushes; a changed copy stops it), `VaultWriteGateTest`, `VaultWriteGuardTest` (layer 5's
  source guard).
- Checked on the tablet against the sandbox (2026-11-08): the tripwire caught the health file the
  tablet had written in normal mode (its own zeros) and "Reset to GitHub" restored the phone's;
  a pull fast-forwards and pushes nothing; task and habit taps are refused with the notice and
  leave the vault files byte-identical; notes open view-only.

**Not done in 12.6.0 (polish, data-safe either way):** the Settings sub-pages and toolkit tools
that edit vault files (habit and home config, mood tags, gamification, Modes, presets, boards,
mission log, objectives, meetings…) still open; their saves are refused with the notice rather
than each entry being disabled with "Change this on your phone".

## Decisions (2026-11-08)

1. The role changes only by logging out and in, with a fresh clone.
2. AI off: Chat and every AI button are hidden.
3. Writes from a second device: assess which could be safe (Phase 2 below), no code yet.

## Phase 2 — which writes could a second device make safely? (proposal)

**Short answer: none of them is safe today, and health never will be.** Every one of these
lives in a file the phone rewrites as a whole, so a second writer either stops the sync on a
rebase conflict (nothing lost, thanks to the backups, but the owner has to sort it out) or —
worse — has its change replaced by the phone's next write of the same file:

| Data | Where it lives | Why a second writer collides |
|---|---|---|
| Health | `10_Journal/data/health/metrics/<date>.json` | Rewritten whole from the device's own Health Connect at every foreground; a tablet has no watch data, so it would write zeros over the phone's day and the health-driven habits would follow. |
| Mood | `10_Journal/data/health/mood/<date>.json` | One file per day holding the day's entries: two devices logging the same day edit the same file. |
| Habits | `10_Journal/data/habits/log_<year>.json` | One file for the whole year: any toggle on both devices between two syncs conflicts. |
| Chores | `10_Journal/data/home/chores.json` | One file with every chore's last-done date. |
| XP / level | `10_Journal/system/` profile | Running totals: two devices adding XP from the same base lose one side's points. |
| Today's board | `10_Journal/<date>.md` | Last-burn-wins: Room holds it until the burn rewrites the note. |

**What would make mood, habits and chores safe: one log per device, merged on read.**

- Each device writes only its own files, e.g. `10_Journal/data/devices/<device-id>/mood/<date>.json`,
  `…/habits/log_<year>.json`, `…/chores.json`. No file has two writers, so no conflict and
  nothing to overwrite. The phone keeps its current files (nothing to migrate); a device id is
  created once per install.
- Every device reads its own files **and** every other device's, and merges: mood = the union
  of the entries (each has its own timestamp); chores = the latest done date per chore; habits =
  per habit and day, the latest change wins (a toggle off is recorded, not deleted, so it can win).
- XP stops being a stored total for the second device: its completions are counted from its own
  logs, so nothing is added twice.
- **Health stays the phone's**, read-only everywhere else (the second device has no data worth
  writing), and so does today's board (a second device could add inbox captures as new,
  uniquely named notes: the "capture-only" idea).

**Cost and risks.** Every reader of these files (Room reconcile, widgets, stats, the
health-driven habits, the burn) has to merge several files; clocks on two devices disagree
(last-change-wins needs a tie rule); the confidentiality guard's deny-list lives on the phone
only (settled decision #14), so a second writer would commit unchecked unless the list is
copied to it. That is a project of its own, with its own tests (two simulated devices, the
same day, every kind of change), and nothing of it is needed for reading.

**Recommendation:** keep the tablet read-only. If writing from it becomes worth it, start with
mood (already one file per day, entries independent), then chores, then habits — health and
the board stay with the phone.
