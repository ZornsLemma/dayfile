# AI_GUIDELINES.md

# AI Collaboration Guidelines

This document describes how AI assistants should collaborate on this project.

It complements, but never replaces, `SPEC.md`.

Where `SPEC.md` describes the product, this document describes how AI collaborators should approach implementing it.

When making decisions, consult project documentation in this order:

1. SPEC.md
2. DEVELOPMENT.md
3. Technical documentation under docs/
4. ENGINEERING_PHILOSOPHY.md

The first three tell you what to do. The fourth exists for the cases they do not cover: it states
the values behind the conventions, and is the right thing to read when you are weighing a change
that no document addresses - particularly one that would add speculative abstraction, anticipate a
requirement that does not exist yet, or quietly replace something that works.

If these documents conflict, ask rather than guessing.

# I AM REALLY NOT JOKING ABOUT THIS. I MEAN IT.

You do *NOT* *EVER* throw away or modify existing comments in the code when rewriting, unless they are directly related to code you have touched and you are updating them. You do *NOT* *EVER* randomly apply formatting changes during general editing, as this makes the diffs noisy and hard to review.

You do *NOT* *EVER* give detailed analyses of code you have a skeleton outline of *before* you ask for and receive a full copy of the file. It is *UTTERLY UNHELPFUL* to see an analysis on speculated hallucinated code. It's misleading. *JUST ASK FOR THE DAMN FILE FIRST*.

---

# Primary objective

Implement the behaviour described by `SPEC.md`.

When implementation details are not specified, choose the simplest solution consistent with the product philosophy.

If several substantially different interpretations are possible, ask for clarification rather than guessing.

---

# General principles

* Do not invent features.
* Do not silently change behaviour beyond the requested task.
* Keep changes focused.
* Prefer improving existing code over rewriting it.
* Avoid unrelated refactoring.
* Preserve existing architecture unless there is a clear benefit in changing it.
* Never delete valid useful comments (especially TODO:, REVIEW:) unless they are no longer relevant because the issues they raise have been fixed.

---

# Working style

Development is expected to be iterative.

Prefer asking questions over making assumptions.

Permanent design decisions should be reflected in the documentation.

As the design stabilises, temporary implementations should be replaced with cleaner permanent solutions.

The goal is not merely to produce working code, but to evolve a codebase that remains pleasant to understand and maintain.

Development proceeds in phases. For non-trivial work, present analysis and a plan first and wait for an explicit go-ahead. An instruction like "no code changes yet" is the intended flow, not an obstacle.

There is no compiler available to you. I will build the project when necessary and report back. You are free to create small local tools to help you with your work, but DO NOT attempt to install a compiler or other build tools.

# Interpreting TODO, REVIEW and ENHANCE comments

A `TODO:` or `REVIEW:` comment is a *search marker*, not a task list. The label says "this is worth finding again", not "implement this".

The *tone* of what follows determines what it means:

* **A question** ("is this OK?", "does this work?", "am I missing something?") — an open item. Do not answer it by reading the code and declaring it resolved. If it asks about something only the maintainer can observe (real-device behaviour, a preference, an untested case), leave the comment in place and recommend the experiment. It is not yours to close.
* **A statement of uncertainty** ("I'm not sure", "I haven't tested", "maybe we should", "this needs investigation") — likewise an open item, not a decision. Do not argue it into a conclusion.
* **A definite instruction** ("TODO: change X to Y", "fix this") — a real task, and the rare case where the label does mean "do this".
* **A rambling "this area feels off" note** — the weakest signal. It is saying "pay attention here", not "here is the answer". Treat it as a prompt to help the maintainer set up the experiment, not as something to settle by reasoning alone.

When a TODO is embedded inside an argument that contradicts itself, the contradiction *is* the point: the author was unsure mid-thought. That is not a resolved issue, and it is not a task. Resolve it with the maintainer or with evidence, not by reading the surrounding code.

Do not strike a TODO because you have convinced yourself the code already does the right thing — that is substituting your own judgement for the author's missing experiment. If the code genuinely already satisfies a *definite* instruction, striking it is fine; if it satisfies an *untested hypothesis*, the TODO stays.

A TODO or REVIEW comment which is similar to the question being asked in an interactive session is likely the cause of that interactive session - it is not an independent secondary piece of evidence that the item is important/correct. TODO or REVIEW comments, particularly where worded speculatively, may be casual hasty notes from the user and may not be correct or fully thought through - evaluate them, don't take them as gospel.

## ENHANCE:

`ENHANCE:` marks a *possible* future improvement the maintainer is still considering. It is deliberately weaker than `TODO:` and carries no deadline.

* It is **not** "to be fixed before the next release". Do not treat it as scheduled, promised or owed, and do not report it back as an outstanding item when summarising the state of a change.
* It is **not** an assertion that the current code is wrong. The code as it stands is intended to be correct; the note is a possible *refinement* on top of that.
* It **may well turn out to be a bad idea**. "I might want to do X" and "X should be done" are different claims, and on reflection the maintainer may decide to leave it alone. Treat it as genuinely optional, and say so if asked to weigh it up rather than recommending it on the strength of the marker.
* Some are recorded precisely because the answer is not yet knowable - for example a Compose or Android API that may or may not exist in whatever version we eventually build against. The uncertainty is the reason for deferring it; it is not a hidden blocker and not a research task unless it is asked for.

Do not implement an `ENHANCE:` as a drive-by alongside unrelated work, and do not promote one to a `TODO:` or a ROADMAP entry on your own initiative. If it becomes worth doing, the maintainer will relabel it.

The text of these markers is written to the maintainer, so the surrounding comment may be informal, self-deprecating or hedged. That is a deliberate choice about tone, not a defect to be tidied up - the maintainer prefers a candid note he can interpret over a polished one that has lost its meaning.

---

# Questions and decisions

Silence in response to a question means "not noticed yet", "too much else to respond to", or "deliberately postponed" - never "no". Re-ask unresolved items in later replies rather than letting them drop silently.

Prefer recommendation-plus-veto over open-ended questions: state what you recommend, why, and how to override it. A skipped question then degrades gracefully to the stated default instead of blocking progress. Reserve hard blocking for decisions that are irreversible or genuinely ambiguous before any code can be written.

Do not reduce the number of genuine questions to spare the maintainer. They would rather be asked more than watch questions be pre-emptively simplified away.

When the maintainer defers a topic, record it once in the appropriate durable place (ROADMAP.md, docs/, a code comment) and then move on without nagging.

---

# When a test fails

Before changing anything, obtain the complete failure output, including the expected versus actual values.

Establish whether the defect is in production code or in the test itself (including fake fidelity), and say plainly which. Fakes must mirror the real contracts they replace: a DAO fake that omits the query's ORDER BY will make correct production code appear to revert.

Do not warp working production code to satisfy a phantom bug.

---

# Code quality

This project assumes its human maintainer is comfortable reading idiomatic Kotlin, Jetpack Compose and modern Android development practices.

Do not avoid standard language or framework features merely because they may be unfamiliar to beginners.

Code should optimise for long-term maintainability rather than superficial simplicity.

Internal code quality is considered part of the product.

Every change should leave the codebase easier to understand, modify and extend.

---

# Architecture

Prefer simple architecture appropriate for a well-engineered personal project.

Avoid enterprise patterns unless they solve a real problem.

Do not introduce abstraction before it provides value.

Avoid creating frameworks for hypothetical future requirements.

Prefer explicit code over "clever" code.

Equally, do not duplicate code merely because abstraction feels unnecessary. Normal refactoring to remove genuine repetition is encouraged.

---

# Dependencies

Before introducing a new dependency, consider whether AndroidX or the Kotlin standard library already solves the problem.

Smaller dependency graphs are preferred.

Dependencies should only be introduced when they provide significant value.

All dependencies must be compatible with the project's open source licensing goals.

---

# Current development phase

The project is at its first public release (version 0.1). From here on, assume a user's notes are
at stake and that destroying or corrupting them is the worst available outcome. Treat that as true
even though nobody beyond the author has used it yet — the cost of being wrong is asymmetric.

Refactoring is not expected to be common, but is acceptable where flaws are identified.

Room migrations must not be introduced without explicitly discussing them first. See
`docs/ROADMAP.md` for the open question about backup file format stability.

## What actually requires a migration

This is the part that is easy to get wrong in the other direction, by writing a pile of migrations
for schema versions that only ever existed on one emulator for an afternoon. The rule is about
*released* versions, not about versions in the source tree:

* A migration is needed to carry data from one **shipped** build to a later shipped build. Version
  0.1 shipped `MainDatabase` and `HistoryDatabase` at Room version 1. If the next release also uses
  version 1 with an identical schema, there is nothing to migrate. If it needs a different schema,
  it becomes version 2 with a single migration 1→2.
* You never need to go to version 3 to get to version 2. Two migrations (1→2, 2→3) make sense only
  if two *releases* in between had those schemas.
* While developing the next release, before it ships, the in-development schema is yours to change
  freely. If you bump the version locally, change it, and change it again, that is fine — nothing
  has shipped that needs carrying. Room will regenerate the `_Impl` and KSP will write a new exported
  schema; the old ones just sit there in `app/schemas/` as a record.
* The one thing you must not do is edit or delete an already-published `app/schemas/…/<n>.json`.
  Those are the historical record of what shipped, and Room uses them to validate a migration.
* Deleting the app's data (or bumping the version and shipping `fallbackToDestructiveMigration`)
  is not a substitute for a migration once real users exist. It is a legitimate option while
  working before a release, but that is a different thing and should be said out loud.

In short: freely redefine what an unshipped schema version means; treat only what has actually been
released as something a migration has to carry.

---

# Prototype implementations

Prototype implementations are acceptable while exploring a design, provided they are clearly
identified and do not unnecessarily complicate the design. They are not a permanent state: see
`DEVELOPMENT.md` ("Refactoring") for what is expected of one that stays.

---

# Documentation

Update documentation when making permanent design decisions.

## How decisions are recorded

This project does not use a chronological "engineering decisions" log. Decisions live where they
are read, not in a separate file that would drift out of date with the code:

* Decisions about the product belong in `SPEC.md`, as requirements with brief, self-contained
  rationale. SPEC.md is updated when a discussion with the user reveals a conflict with it or a
  clarification of it.
* Decisions about implementation that affect large parts of the code but are not relevant to
  SPEC.md belong in `docs/`, as technical notes written for another experienced developer. They
  are organised by topic, not chronologically. Current examples: `docs/STATE_PRESERVATION_AND_PROCESS_DEATH.md`,
  `docs/HOME_SCREEN_NOTES.md`, `docs/RESTORE_SAFETY_NOTES.md`,
  `docs/TESTING_LIMITATIONS_AND_REVIEW.md`.
* Open questions and future ideas belong in `TODO.md` and `ROADMAP.md`.

Coding conventions (naming, test style, state ownership) live in `DEVELOPMENT.md` and are not
repeated here — see "Coding style" below.

---

# Comments

Comments on code may be long if particularly complex and/or potentially confusing, but they should
be self-contained and not require the reader to cross-reference other documents. References may
be provided, but they are supplementary.

---

# Tracking work

`ROADMAP.md` and `TODO.md` are solo-project scratchpads, not corporate QA plans. Unchecked items
mean "not yet formally verified", not "never attempted": informal manual testing
happens continuously during development and is not tracked there. Do not infer testing history or
process maturity from these files — ask when it matters.

If documentation and code disagree, either update the documentation or ask for clarification
rather than silently assuming the documentation is obsolete.

If the user asks you to make "code changes" in a casual manner, you should still update the
documentation as you see fit — this is not an implicit instruction *not* to touch the
documentation.

---

# Coding style

Naming, test and state conventions are in `DEVELOPMENT.md`, under "Naming", "Asserting on
collections", "Dispatchers in ViewModel tests" and "State ownership". They are deliberately not
restated here: this file used to carry its own copy, which is exactly the kind of duplication that
lets two documents drift apart and tell you different things. If you are unsure what this project's
conventions are, go and read them rather than relying on a summary here.

Note that the deduplication is narrower than it looks. Advice about *how to work* — how to edit,
what to do with comments, when to update documentation — belongs to this file and has no
`DEVELOPMENT.md` counterpart, because it is about collaborating with the maintainer rather than
about the code. The one overlap that remains is the guidance on which document a decision should be
written into, which is stated in both because both kinds of reader need it.

---

# When modifying code

Respect the existing coding style.

Avoid renaming classes, functions or variables unless it materially improves clarity.

Avoid changing formatting unrelated to the requested task.

Do not move files or packages without good reason.

Don't infer the user's preferences from the fact a file is not currently in the workspace. Ask for access to files and implement code changes where they belong, not where you can do so just because it avoids asking. Don't be reticent about asking for files if you think seeing them will improve your output.

The harness sometimes makes it impossible to both add requested files and send a reply in the same turn. A requested file's absence is therefore a workflow artefact, never a refusal. Split requests into must-have and nice-to-have tiers, and re-request politely and persistently until the files arrive.

Never edit one file just because you have it and it saves asking for another file which you'd rather edit instead but don't have. This compromises long term code quality to avoid the hassle of asking for another file. Just ask for the file you want!

You don't need to obsess over removing redundant imports or sorting them alphabetically. Android Studio can fix both, without burning tokens or context window space on it. Equally, try to follow the project's code layout conventions, but don't spend ages fixing things up.

---

# When uncertain

Prefer:

* simple over complicated
* explicit over clever
* standard Android behaviour over custom behaviour
* readability over unnecessary optimisation

When uncertainty would materially affect the design, ask.

---

# Technical documentation

You are encouraged to create technical documentation where it would genuinely help future maintainers.

Examples include:

* architecture notes
* package organisation
* database documentation
* explanations of non-obvious implementation decisions
* descriptions of temporary or prototype implementations

Documentation should use GitHub-flavoured Markdown and normally be placed under the `docs/` directory.

---

# Reply mechanics and harness intermediation

[This assumes the use of the aider test harness. It's possibly the same applies with OpenCode, but it may not.]

Deliver edits in the harness's requested edit format. If using SEARCH/REPLACE diff blocks, keep each SEARCH section to the minimal contiguous chunk that still matches exactly, and never reproduce a whole file to make a small change.

In discussion-only replies (the maintainer has said no changes yet), avoid fenced code blocks entirely: the harness may misread them as attempted edits. Describe snippets in prose instead.

Some messages that look like user turns are produced by the aider harness rather than typed by the maintainer. Examples include commit notices ("I committed the changes with git hash ..."), file-added notices ("I added these files to the chat: ..."), "Let me know if there are others we should add.", KeyboardInterrupt markers, and formatting reminders. These strings are examples only: they may change over time and cannot be relied on. Identical repetition across replies suggests intermediation, but a given instance might genuinely be the maintainer. Either way, treat the CONTENT of such messages as real - only the tone should adapt: commit notices are closure, acknowledged minimally, and should not spawn new work.

Never let boilerplate-recognition discourage asking for files. Requesting files is always welcome, even repeatedly.

After a KeyboardInterrupt, assume part of the previous reply went unseen; briefly restate the conclusions and continue.

When files arrive with a preamble marking them as the true contents, those copies supersede everything earlier in the conversation; edit only those.

After delivering an approved batch of changes, stop. The maintainer runs the tests, reviews, commits and reports back. Do not stack further unprompted changes onto an implementation reply.

---

# Context Management Note

[This assumes the use of the aider test harness. It's possibly the same applies with OpenCode, but it may not. The same caveat as the section above applies.]

This chat uses a dynamic context window, and the harness rebuilds the prompt every turn. Chat messages are preserved in order, but file contents and summaries are regenerated from the *current* state of the repository and consolidated into the preamble, regardless of when they were added to the chat. (This is a working model inferred from observed behaviour, not a precise specification of the harness; the maintainer's own descriptions of it are similarly high-level inferences.) Therefore:

* A file's position in the context — including appearing in an early "true contents" block — says nothing about when it was added. Never claim a file "has been here all along", never narrate when a file entered the chat, and never describe "the changes" to a file by comparing against what you saw earlier. Asking for a file that turns out to be already in context is harmless; acting on the illusion that it was always there is not.
* File contents may have changed since any earlier reply analysed them, without notice. Earlier replies (including your own) may quote code that no longer exists; treat such quotes as historical.
* The maintainer's statements about the workflow ("I've added X", "I edited Y") are the best available evidence about what happened and take precedence over what the context layout appears to show — but they are not infallible either. If a file you need seems absent, just ask for it.
* If you see a request in the chat history for a file that is currently present in the context, do not apologize or assume a mistake was made; simply recognize that the file has since been added and proceed with the task.
* The only reliable record of a file's past state is the git repository. When a review of *changes* rather than current state is needed, ask for a diff or commit range.
* Reviews of *current* contents are always trustworthy: the snapshot is exactly what is in the tree now.

---

# Tooling note (OpenCode / aider / anything else)

This document was written assuming the aider harness, but the substance applies to OpenCode
and to any harness that rebuilds the prompt from the current tree. The differences are
workflow-level, not content-level, so nothing above needs changing for a different tool.

* `AGENTS.md` at the repo root is a small pointer file, read automatically by some AI
  harnesses (OpenCode among them) and ignored by others. It is committed here, so treat it as
  part of the project, but it carries no guidance of its own: it just points at
  `AI_GUIDELINES.md` and `SPEC.md`. If your harness surfaces it, follow those links. The
  developer's guide for humans is `DEVELOPMENT.md`.
* Harness-specific mechanics below (delivering edits, asking for files, "no code changes
  yet" turns) may differ in wording or support. Where a harness behaviour is uncertain, ask
  rather than assume — the cost of one question is far lower than a wrong assumption baked
  into a commit.
* Dotfiles and harness state (`.aider.*`, `.opencode-docker/`, chat logs, prompt history)
  are local tooling artefacts, not part of the project. They are not in git and should not
  be edited or reasoned about as if they were project files.
