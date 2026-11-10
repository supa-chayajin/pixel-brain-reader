# C8 — Explain this error (12.10.0): plan for review

**Status:** reviewed by the owner on 2026-11-10 (decisions in §6); building for 12.10.0. Items W1 and R1 of `2026-11-10-dream-app-roadmap.md`; R2 (hybrid retrieval) waits for
R1's numbers.

Unless a path starts with `app/` or `documentation/`, it is relative to
`app/src/main/java/cloud/wafflecommons/pixelbrainreader/`.

## 1. The job

An error shows up: a stack trace in a log, a failed CI step, a message pasted in a chat. On the
phone, offline, C8 answers **"have I seen this before, and what did I do?"**: the notes and runbooks
that mention it, a short French explanation grounded on them, and a bug note in one tap. Fixing
the code stays with the desktop tooling (its own "explain this error" RFC reads the codebase).

## 2. What it builds on

- The share sheet (`ShareImportRepository.start(SharedContent, html)`): shared text arrives as a
  `passage` without a URL.
- Full-text search (FTS4, `VaultDiscoveryRepository.fullTextHits`): error names, codes and paths
  are words, so FTS finds them where vectors blur them. Vectors (`VectorSearchEngine.search`, floor
  0.35) add the notes that describe the same failure in other words.
- Runbooks (`data/work/Runbooks.kt`): a note tagged `#runbook` or kept in a `Runbooks` folder.
- `LocalAiManager.generateResponse` (a `Result`, 256 output tokens, ~4,000 input).
- `DraftWriter` (never overwrites) and the vault's `T_Bug_Report` template (`{{title}}`, Context,
  Description, Reproduction, Fix Plan); the template forms' rules make its dates today and its
  type and status the inbox's.
- The confidentiality guard checks the bug note at commit: a client name or a secret in a trace
  holds it, as for any note.

## 3. Design

1. **Entry points.** On the share screen, shared text that reads as an error (§3.2) gets
   "Explain this error" as its first action; "Save as a note" stays. In the toolkit, an
   "Explain an error" tool with a paste box, for a trace copied from anywhere.
2. **Reading the error** (pure, `data/work/ErrorTrace.kt`). Recognises Kotlin/Java, Python, PHP,
   JavaScript/Node, .NET and Go traces, plus bare messages (`SQLSTATE[…]`, `errno`, HTTP 5xx).
   Keeps the headline (exception type + message), the top frames and their file names, and the
   codes. Normalises what changes between occurrences: timestamps, hex addresses, UUIDs, line
   numbers and quoted values become placeholders, so the same failure matches across logs.
3. **Finding past occurrences** (`ErrorLookupRepository`, ranking pure). FTS first: the exception
   type with the normalised message, then the distinctive identifiers (class and method names,
   codes), merged by rank. Vectors second, on the headline, floor 0.35 as everywhere. FTS hits
   lead, vector hits follow, duplicates dropped, at most 5, runbooks marked. The current mode's
   visibility and the private-notes switch (#20) apply. The golden set's error questions (Q26–Q28)
   measure exactly this path.
4. **Explaining** (optional, Nano, French). Input: the headline, the top frames (the middle of a
   long trace cut, ~2,000 characters) and up to three past-occurrence excerpts (~1,500
   characters), ending with `AiLanguage.DIRECTIVE`. Asked: the likely cause in three sentences,
   the note that says so, the next thing to check. Without Nano, the past occurrences show alone,
   with "No explanation: the on-device AI is not ready".
5. **When no note matches** (question D2 below). Recommended: say "Not seen in your notes" and
   offer "Ask Gemini Nano anyway", an explicit tap whose answer is marked "general knowledge, not
   from your notes". The rule of the chat (no general-knowledge fallback) holds by default.
6. **The bug note.** `T_Bug_Report` with `{{title}}` = the headline, today's dates and the inbox's
   type and status of its folder. The explanation goes under Description, marked "Explication
   (IA sur l'appareil)". New sections follow: **Seen before** (the past occurrences as links) and
   **Trace** (fenced, the first 80 lines). Without the template, a built-in note with the same
   sections. **Where (D3):** in `<project>/Bugs/` when the error belongs to a project — the project
   holding the best past occurrence (`ProjectFormat.projectFor`), else a project whose folder name
   appears in the trace — otherwise `00_Inbox/`. Written through `DraftWriter`, then opened.
7. **Screen** (`ui/work/ExplainErrorScreen.kt`, `FormScaffold`): the headline, past occurrences
   (excerpt, tap opens), runbooks, the explanation card, "Create bug note". A read-only device
   gets the lookup only: no Nano, no note.

## 4. Tests (each failing first)

- `ErrorTraceTest`: one real trace per ecosystem, bare messages, normalisation (two logs of the
  same failure give the same key).
- `ErrorLookupRankingTest`: FTS before vectors, duplicates merged, the floor, visibility, private
  notes off.
- `ErrorPromptTest`: French, the directive, the budget with a 400-line trace, the middle cut.
- `BugNoteTest`: the template filled, dates and type, the sections, a second note never
  overwriting the first.

## 5. R1 alongside: the harness

A debug action (Settings → Knowledge Vault, debug builds only) reads `99_System/rag_golden_set.md`
from the vault, runs each question through the production search with every folder visible,
ignores the golden-set note itself, and logs and shows recall@5, precision and the negatives that
returned something. Nothing about retrieval changes in 12.10.0. R2 (hybrid) is decided on these
numbers, measured on the Fold.

## 6. Decisions for the owner

| # | Question | Owner's decision (2026-11-10) |
|---|---|---|
| D1 | Entry points | The share sheet (when the text reads as an error) and a toolkit tool |
| D2 | No note matches | "Not seen in your notes", plus an explicit "Ask Gemini Nano anyway", marked general knowledge |
| D3 | Where the bug note goes | The project's `Bugs/` folder when the error belongs to a project (§3.6), otherwise `00_Inbox/` |
| D4 | Version code from 12.10.0 | major×10000 + minor×100 + patch: 12.10.0 = 121000, 13.0.0 = 130000 |
