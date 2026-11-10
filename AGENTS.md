# AGENTS.md — Pixel Brain Reader

Read this before changing anything, whatever the agent (Claude Code, Gemini in Antigravity,
opencode, …). It is short on purpose: it points to the documents that hold the rules.

## The rules that come first

**[The settled decisions](documentation/explanation/settled-decisions.md).** The owner made each
of them, most after a real incident. Never reverse one without asking. If the code or a comment
disagrees with that page, the page wins and the comment is the bug. In October 2026 an agent
reverted three of them while "fixing" code whose comments still described the old behaviour.

## Where the work is

- **The plan:** `documentation/plans/`. The newest plan is the living checklist: tick an item in
  the commit that ships it.
- **How-tos and explanations:** `documentation/how-to/`, `documentation/explanation/`.
- **Skills** (coding, tests, code review, lint, security): `.opencode/skills/`.

## Every commit

- `./gradlew :app:testDebugUnitTest` passes, and `./gradlew :app:lintDebug` stays at 0 errors and
  0 warnings.
- One concern per commit, on `main`. Never create a branch.
- A feature batch ships its bookkeeping: the version, a CHANGELOG section, the facts in the docs.
- Dependency versions are locked in `app/gradle.lockfile`. Never remove, downgrade or swap a
  dependency, a permission or a user-facing feature to make something compile: stop and ask. A
  deliberate change regenerates the lockfile (`./gradlew :app:dependencies --write-locks`) in its
  own commit.

## Never

- **Names:** this repository is public. No employer, client, product or people names in code,
  tests, docs or commit messages.
- **Cloud AI:** generation, embeddings and dictation run on the device. There is no cloud
  fallback.
- **The wrong language:** the UI is English; every AI prompt is French and ends with
  `AiLanguage.DIRECTIVE`.
- **A debug build on a release-signed device:** it forces an uninstall, and the device's data
  goes with it.
