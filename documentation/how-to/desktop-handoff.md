# How to set up the hand-off between the phone and your desktops

Problem: jot "next: …" for a project on the phone and see it when your next Claude Code session
on that project starts on the desktop — on the right machine only, when several desktops share
the vault but each owns different projects.

How it works:

- **Phone → desktop.** A project's page (toolkit → Projects → a project) has a **Hand off to
  desktop** card. Each note becomes one line in `<project folder>/Handoff.md`
  (`- [ ] yyyy-MM-dd HH:mm — text`), pushed with the next batched push.
- **Desktop.** A `SessionStart` hook runs `<vault>/scripts/handoff.sh`, which prints the open
  lines into the session's context. It is read-only, silent when there is nothing open, and with
  `WORKTRACE_PULL=1` it first fast-forwards the vault (`git pull --ff-only`; it never merges or
  pushes).
- **Done.** Tick the line in `Handoff.md` on the desktop (`- [x]`), or tap it on the phone.
- **Desktop → phone.** Nothing to set up: the `SessionEnd` worklog (`worktrace.sh`) already feeds
  the project page's activity and "Where you left off".

The vault's sync stays manual on both sides: a hand-off reaches the desktop once the phone has
pushed and the desktop has pulled.

## Prerequisites

- The vault cloned on each desktop with `worktrace.py`, `worktrace.sh` and `handoff.sh` in
  `<vault>/scripts/` (pull the vault).
- `SECOND_BRAIN_VAULT` pointing at that clone (or the clone at the hooks' default path).
- Each project mapped to its vault folder in its **local** map,
  `<project>/.project-memory/anonymization-map.json` → `"vault_folder": "20_Projects/<folder>"`
  (the worklog hook already needs it).

## 1. Give each machine its allow-list (do this first)

Each desktop may only read and write the vault folders it owns. The list is local and never
synced — create `~/.worktrace/machine.json` (Windows: `%USERPROFILE%\.worktrace\machine.json`):

```json
{
  "label": "this-machine",
  "allowed_folders": [
    "20_Projects/<folder this machine works on>",
    "20_Projects/<another one>"
  ]
}
```

With the file present, `worktrace.py autolog` refuses to write a worklog outside the list
(exit 3, so `worktrace.sh` stages nothing) and `handoff` stays silent for other folders. An
unreadable file refuses everything; no file only prints a warning.

Check it: `PROJECT_ROOT=<a project> python3 <vault>/scripts/worktrace.py handoff` prints the open
hand-offs of an allowed project and nothing for a forbidden one.

## 2. Add the SessionStart hook to each project

Put it next to the existing `SessionEnd` worklog hook — in the project's
`.claude/settings.local.json` when that repository's tracked settings must not change.

**macOS / Linux**

```json
{
  "hooks": {
    "SessionStart": [
      {
        "matcher": "startup|resume",
        "hooks": [
          {
            "type": "command",
            "command": "/bin/bash -c 'PROJECT_ROOT=\"$CLAUDE_PROJECT_DIR\" WORKTRACE_PULL=1 \"${SECOND_BRAIN_VAULT:-$HOME/perso/second-brain}/scripts/handoff.sh\"'",
            "timeout": 15
          }
        ]
      }
    ]
  }
}
```

**Windows (Git Bash)** — same entry with `bash` instead of `/bin/bash`; `handoff.sh` falls back
to `python` when `python3` is missing:

```json
"command": "bash -c 'PROJECT_ROOT=\"$CLAUDE_PROJECT_DIR\" WORKTRACE_PULL=1 \"${SECOND_BRAIN_VAULT:-$HOME/perso/second-brain}/scripts/handoff.sh\"'"
```

Merge it into an existing `"hooks"` object rather than replacing it. Drop `WORKTRACE_PULL=1` if
you prefer to pull the vault yourself.

## 3. Try it

1. On the phone, open a project and hand off "test hand-off"; run a sync (pull to refresh on the
   Daily screen) so it is pushed.
2. On the desktop that owns the project, start a Claude Code session in it: the first context
   lines read `📱 Hand-offs from the phone for this project (1 open, …)`.
3. Tick it in `Handoff.md` (or tap it on the phone) — it disappears from both after the next sync.

## Troubleshooting

- **Nothing shows up.** The project's map has no `vault_folder`, the folder is not in this
  machine's allow-list, the vault was not pulled (the hook says so when the fast-forward fails), or
  the line is already ticked.
- **"The vault could not be fast-forwarded".** The desktop has local vault commits (the worklog
  hook commits without pushing) and the remote moved: pull with rebase and push when you choose.
