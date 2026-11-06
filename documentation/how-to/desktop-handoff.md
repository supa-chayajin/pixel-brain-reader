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

## 4. Hand off from anywhere, typed

A hand-off no longer starts only on a project's page:

- **From an open note**: the 🖥 button in the note's header hands it off with a link to it
  (`[[Note]]`), on its own project when the note sits in one (private notes can't be handed off:
  their name would land in a plain file).
- **From the share sheet**: "Hand off to desktop instead" on the share screen sends the page's
  title and address, or the shared text, instead of saving a note.
- **From the home screen**: the 🤝 at the top right of the Projects widget opens the hand-off
  composer.

The composer can type a hand-off. The prefix is written in French, like the rest of the vault,
and starts the line's text:

| Kind | Line written in `Handoff.md` |
|---|---|
| Note | `- [ ] 2026-11-06 10:15 — Relire [[Plan]]` |
| Task | `- [ ] 2026-11-06 10:15 — tâche: Relire [[Plan]]` |
| Question | `- [ ] 2026-11-06 10:15 — question: Faut-il garder Predis ?` |
| Decision | `- [ ] 2026-11-06 10:15 — décision: On garde PHPUnit 12 jusqu'au gel` |

Needs the desktop side: `worktrace.py handoff` prints the lines as they are; it may group them by
prefix (`tâche:`, `question:`, `décision:`). That change lives in the vault's `scripts/`, not in
this app.

## 5. Answer a question from the phone

When the project's `TODO.md` is the live list, its open questions show under **Open questions**
in Claude's queue on the project page: the lines under a `❓` heading, the ones typed
`question:`, and the ones ending with `?`. **Answer** writes one nested line under the question,
read from the file on the phone first:

```markdown
- [ ] The RetryPolicy ceiling?
  - 💬 2026-11-06 — 5 retries, then the dead-letter queue.
```

It is not a checkbox, so it never becomes an action. The next desktop session reads it with the
TODO and ticks the question once it has acted on the answer.

## Troubleshooting

- **Nothing shows up.** The project's map has no `vault_folder`, the folder is not in this
  machine's allow-list, the vault was not pulled (the hook says so when the fast-forward fails), or
  the line is already ticked.
- **"The vault could not be fast-forwarded".** The desktop has local vault commits (the worklog
  hook commits without pushing) and the remote moved: pull with rebase and push when you choose.
