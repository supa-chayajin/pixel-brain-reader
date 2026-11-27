# Pings when the vault changes — parked (2026-11-27)

The owner's question, 2026-11-27: could the app tell you, away from the desk, that something worth
knowing landed in the vault, without opening it and syncing? Analysed the same evening (read-only),
then interviewed. **Status: not now** — Claude Code's own notifications and the digest after a sync
are enough for today. Nothing below is built or scheduled until the owner brings it back.

## The owner's answers (interview of 2026-11-27)

1. **Events:** the three the sync digest already finds (`WhatsNew.of`): a new note in a project's
   own `Claude/Reports/`, a new or reworded `@moi` line in a live list, a hand-off ticked on the
   desktop. No desktop change.
2. **Speed:** minutes first. A check every 15 minutes that only fetches (`ls-remote`, then a fetch
   of the branch under a `tryLock` that never waits) — 15–30 min while the phone is carried, hours
   when it lies still. A content-free push wake-up (FCM, a dependency change under #9) is decided
   after a week of measured push-to-ping latency.
3. **Devices:** the phone **and** the read-only tablet. The tablet repeats every ping; accepted.
4. **When:** not now.

## What it would change in the settled decisions

Both are recorded, with the owner, before any code:

- **#6 (sync is manual):** one more sentence — a background check may fetch to tell you what
  landed; it never pulls, merges, rebases, checks out or writes the working tree.
- **#18 (a read-only device never writes):** its "no background … worker" gains the same check as
  its only exception; the check writes nothing in the copy (only `refs/remotes/origin/<branch>`
  moves, as a read-only pull's fetch already does).

## The shape, when it comes back

- **VN1 `JGitProvider.checkRemote()`:** `ls-remote` outside the lock; when the tip moved, a
  `tryLock` of `gitMutex` (busy → skip the round), repair the shallow boundary, fetch
  `refs/heads/<branch>` only, restore the boundary on failure; unchanged / moved (old, new) / busy /
  failed. A new ledger purpose for it.
- **VN2 `VaultNews` (pure):** `WhatsNew.of` between the old and new tracking tips (sub-projects by
  "exists at that commit"); anything HEAD already holds dropped; one key per item.
- **VN3 the device's store:** waiting items, keys already pinged, the last tip; cleared once HEAD
  holds that tip, and at logout or a fresh clone (a force-push or a re-clone pings nothing).
- **VN4 `VaultCheckWorker`:** every 15 min, network connected, battery not low, KEEP; scheduled by
  the switch and the login; skipped without a clone, while a conflict waits, while a sync, a push or
  a history step runs; flushes the ledger before returning.
- **VN5 the ping:** its own channel, one notification replaced in place; a count only on the lock
  screen, `setLocalOnly`; the project and the title or line unlocked, only for projects the mode
  shows (work ones in Work mode only, never while the screen is shared); "Sync now"; none while the
  app is open.
- **VN6 the tap:** a fixed screen key, no path in the intent; a "Waiting on GitHub" card with Sync
  now.
- **VN7 the switch:** off by default, with a line saying it checks GitHub every 15 minutes and never
  pulls.
- **VN8 tests:** each event; a line typed on the phone dropped; mirrors skipped; reruns,
  force-pushes and re-clones silent; the mode filter; on a temporary bare origin, only the tracking
  ref moves (HEAD, the index and the tree byte-identical), a shallow clone keeps its boundary, the
  next pull still rebases and its digest lists the same items; the worker's gates on both roles.
- **Not to do:** pull in the background; commit progress lines to the vault; send any content
  through a push service; rely on a desktop hook alone (many desktop commits reach GitHub from
  another clone).
