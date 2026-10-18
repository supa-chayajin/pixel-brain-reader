# Settled decisions — and why they must not be reversed silently

Every decision below was made by the project owner, most of them after a real
incident during daily use. They are written here, in the versioned docs, because
an AI assistant's private memory is invisible to the other agents working on the
repo: in October 2026 one agent reverted three of them while "fixing" code whose
comments still described the old behaviour (see
`documentation/plans/2026-10-17-antigravity-review-and-roadmap.md`).

The local, gitignored `CLAUDE.md` carries the same list for the agents' routing.
**If a comment in the code disagrees with this page, the page wins and the comment
is the bug.** Changing one of these is fine — but only as an explicit decision by the
owner, recorded here.

## 1. Gemini Nano downloads and pre-loads itself

`LocalAiManager` starts the model download once per process when ML Kit reports
`DOWNLOADABLE`, then pre-loads it with one throwaway `generateContent` call — never
`GenerativeModel.warmup()`, which has wedged AICore on Pixel devices. Generation calls
never download anything; they fast-fail while the model isn't ready.

*Why:* the original "download only from Settings" contract meant a fresh install (or
AICore evicting the model) silently broke every AI surface — "Nano not responding
anywhere" — until the user found a button in Settings.

## 2. The RAG relevance floor is 0.35

`VectorSearchEngine.MIN_SIMILARITY = 0.35`, calibrated on the multilingual MiniLM
embedder: two sentences with the same meaning in English and French score ≈ 0.97,
unrelated text ≈ −0.03. Below the floor, retrieval returns nothing and the assistant
says the notes don't cover the question.

*Why:* without a floor the top-K chunks were *always* injected, so the model answered
from irrelevant notes. Lowering the floor or adding score boosts requires a measured
evaluation on a golden question set — not intuition.

## 3. Today's journal and `99_System` are not embedded

*Why:* today's note changes all day (each embedding would be stale within minutes and
cost a model load per run); `99_System` holds machine JSON that only adds noise to
retrieval.

## 4. 100% on-device AI

No cloud LLM fallback, ever — generation and embeddings run on the device, and so does
speech-to-text (`OnDeviceDictation`: Android's on-device recognizer, fr-FR). If the device
can't recognize French offline yet, Chat and Daily may fall back to the system recognizer
with a visible "may use the network" notice; the private journal never falls back.
*Why:* it is the product's promise; the vault holds a private journal and health data.

## 5. English UI, French AI, French dictation

All user-visible strings are English (the device's system language). Every AI prompt
is written in French and ends with `AiLanguage.DIRECTIVE`, because French prose is the
strongest steer for Gemini Nano to answer in French. Dictation is pinned to fr-FR.

## 6. Sync is manual

No automatic pull. Freshness comes from `ForegroundRefresher` (Health Connect on
foreground), `AppClock` (day changes) and `VaultPushScheduler` (debounced push-only).
*Why:* automatic pulls rebased under the user's feet and fought the editor.

## 7. The daily note round-trips losslessly

Whatever Room holds for the daily board must survive `MarkdownBurner.burn` →
`DailyMarkdownParser.parse`: external ids travel through `DailyMarkers`, and a
timeline entry is exactly one line. *Why:* Room is wiped by destructive migrations and
rebuilt from the markdown; a lossy round-trip once tripled every Google Calendar event.

## 8. Background writers read the disk first

A repository that persists vault JSON and can be written by a widget or a worker must
base each mutation on the on-disk state, never on an in-memory default. *Why:* one
widget tap once reset a level-9 gamification profile to level 1.

## 9. No silent dependency or feature changes

Never remove, downgrade or swap a dependency, a permission or a user-facing feature to
make something compile — stop and ask. *Why:* a duplicate catalog entry was "cleaned
up" by deleting the stable Health Connect version, which silently downgraded the SDK
to an older alpha and removed mindfulness tracking to make the build pass.

## 10. Every feature batch ships its bookkeeping

`versionCode`/`versionName` bump, a CHANGELOG section, accurate `CLAUDE.md` facts,
lint at 0 errors / 0 warnings, and one concern per commit.

## 11. Work happens on `main`

No feature branches: the owner is the only developer and dogfoods from `main`.

## 12. The theme follows the system

There is no manual light/dark selector: Android's force-dark overrides a per-app choice,
which made the switch unreliable.

## 13. No public version until the owner says so

No Play Store listing, showcase or public announcement — and no preparation aimed at
one — until the owner explicitly decides to go public. Publishing releases on the
(public) GitHub repository is fine: it is how versions and their R8 mappings are archived.

*Why:* the app is a personal, dogfooded tool for now; going public is a deliberate decision,
not something to drift into.

## 14. The confidentiality deny-list stays on the device

The protected names (clients, employers, people) and the guard's exceptions live in
encrypted preferences on the phone — never in the vault, never in this repository, never
in a commit message. Every commit goes through the guard (`CommitGuard`), and a finding
holds the commit (fail-closed) until the user reviews it.

*Why:* the vault is pushed to a git remote and this repository is public. A deny-list
written to either would publish the very names it protects; the work rule is "no client
data in any external tool".
