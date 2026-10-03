# DEVELOPMENT.md

# Development Conventions

This document describes development conventions for the Dayfile project.

Unlike `SPEC.md`, these conventions may change as the project evolves.

Naming and test conventions live here and nowhere else; `AI_GUIDELINES.md` points at the sections
below rather than keeping a second copy. Other material — chiefly its advice to AI collaborators
about working style — exists only in `AI_GUIDELINES.md`, and a few rules about *where decisions are
written down* are deliberately stated in both files.

---

# General philosophy

The project is maintained by a single experienced developer with AI assistance.

The goal is not to imitate large corporate development processes.

The goal is to produce a codebase that is pleasant to understand, modify and maintain over many years.

Good code has intrinsic value.

Development speed is important, but not at the expense of unnecessary technical debt.

---

# Naming

Apply these to new code and when naturally touching existing code. Do not perform broad renaming
solely to enforce consistency, and use contextual judgement where not following one of them would
be clearer.

ID naming: variables and properties whose value is an identifier should generally use an `Id` suffix (e.g. `categoryId`, `filterCategoryId`).

Collection naming: variables and properties whose value is a collection of `Foo` should generally be named `foos` (the English plural of "foo"), rather than `fooList` or `fooSet`. Include the collection type in the name only when the distinction between collection types is significant to the meaning or use of the property.

Flow naming: variables and properties whose value is a flow should generally use a `Flow` suffix and a name suggestive of the value emitted by the flow (e.g. `fooFlow` for a `Flow<Foo>`, or `foosFlow` for a `Flow<List<Foo>>`). Variables representing a collected flow value should generally use the corresponding un-affixed name.

Room queries: queries returning a `Flow` are named `observe...`; one-shot queries are named `get...`. This applies to SELECT queries only — `@Insert`, `@Update`, `@Delete` and `@Upsert` methods are outside this convention.

---

# Building and testing

Android Studio is the primary development environment, and the bundled Gradle wrapper is the
equivalent command-line route. The command line needs a JDK to run Gradle at all (CI uses 21;
Android Studio supplies its own).

Project configuration files required for a normal Android Studio workflow should be maintained appropriately.

| Task | Command |
| --- | --- |
| Build a debug APK | `./gradlew assembleDebug` |
| JVM unit tests | `./gradlew testDebugUnitTest` |
| Instrumented tests | `./gradlew connectedDebugAndroidTest` |
| Reformat to the project's style | `./gradlew spotlessApply` |
| Check formatting | `./gradlew spotlessCheck` |
| Android Lint (advisory) | `./gradlew lintDebug` |

The instrumented tests need a connected device or emulator. The release build is signed with the
*debug* key, but only so that switching between debug and release is frictionless during
development; published builds are signed by hand, for the reason given in `app/build.gradle.kts`.

---

# Kotlin style

Follow standard Kotlin conventions unless documented otherwise.

Prefer idiomatic Kotlin.

Use language features because they improve readability or correctness, not merely because they are available.

Avoid unnecessary cleverness.

---

# Formatting

Source code uses four-space indentation.

Formatting is performed automatically using Spotless with `ktfmt().kotlinlangStyle`.

Code should be written with automatic formatting in mind rather than attempting to manually align formatting.

---

# Compose

Compose UI should be particularly readable.

Composable functions should generally read from top to bottom in the same order as the UI appears on screen.

Extract reusable components where this genuinely improves readability.

Avoid excessive decomposition into tiny composables whose only purpose is to satisfy arbitrary size limits.

---

# State ownership

Compose makes state ownership manual; decide it deliberately for every piece of state.

* ViewModel (or repository): app state that must outlive the UI — anything whose silent loss would lose user data or break an in-flight operation. Survives configuration changes; dies with its back-stack entry.
* `rememberSaveable`: UI chrome whose silent loss would confuse the user — dialog visibility, half-typed dialog input, picker state. Survives configuration changes and process death (via saved instance state).
* `remember`: per-frame caches that can be recomputed without confusing anyone.

Dialogs are expected to stay open across rotation (the pre-Compose platform behaviour users learned); transient popups and menus may close. A dialog whose visibility lives in plain `remember` will close on rotation — use `rememberSaveable` for the flag.

## How state reaches the screen

The ownership rules above say how *long* a piece of state lives. The convention below is about its
*shape* on the way to the screen, and it is a default rather than a rule.

Prefer a ViewModel that exposes one flow of a single immutable state object, which the composable
collects into local state and renders. This keeps a screen's view of the world internally
consistent — there is no window in which "the date" and "the entries for that date" disagree because
two flows emitted at different moments — and it means initialisation is a property of the flow
rather than of a coroutine someone launched.

Two things it deliberately avoids: a ViewModel that launches a coroutine in `init` to load data into
a `MutableStateFlow` (the flow is the initialiser, and there is no separate "not loaded yet"
initial write to reason about), and a composable that assembles its own state from several
independent flows.

Deviate where the state is genuinely uncoupled, which is most of why the codebase looks the way it
does:

* `SettingsViewModel` exposes separate flows because the settings are independent toggles and
  values with nothing to keep in step. Combining them would mean one emission carries data most of
  the screen does not care about.
* `CategoryEditViewModel` exposes separate flows for the category list, whether it has loaded, the
  delete dialog and error messages. A single state object would be a bag of unrelated things, and
  the "loaded" latch in particular is easier to reason about on its own.

`HomeViewModel` and `HistoryViewModel` both follow the single-flow convention, so both shapes are
established and either is acceptable to read.

---

# Architecture

Architecture should reflect the scale of the project.

Avoid unnecessary layers.

Avoid introducing interfaces simply for theoretical future testing or extensibility.

When complexity becomes justified by real requirements, introduce it deliberately.

Below the root package, code is organised into two packages: `data` for persistence (Room entities, DAOs, databases, repositories and their helpers) and `ui` for everything else — screens, view models and the feature logic that serves them.

Shared composable building blocks used by more than one screen go in `ui/components`. The app theme (`ui/theme`) is a third, conventional subpackage that falls out of the Compose template rather than out of the split above.

The `data`/`ui` boundary is deliberately mechanical ("is this persistence?") so a new file can be classified without judgement; within `ui`, placement follows feature affinity rather than conceptual purity.

Resist introducing further packages (`domain`, `util`, ...) or per-feature subpackages until the scale justifies them: their membership decisions would reintroduce the judgement calls this split avoids, and the code reuse that would justify the structure does not yet exist.

Revisit this if the flat `ui` package stops scanning at a glance, or if logic gains a second independent consumer (for example a home-screen widget, or core logic shared between view models).

---

# Repetition

Avoid unnecessary duplication.

Shared behaviour should normally exist in one place.

However, do not create abstractions that are significantly harder to understand than the duplicated code they replace.

---

# Documentation

Document significant design decisions.

Prefer explaining *why* something exists rather than *what* the code does.

Code comments should generally explain intent rather than restate the implementation.

Larger technical documents should be placed under `docs/`.

Where a design decision lives depends on its nature:

* Decisions about the product belong in `SPEC.md`, as requirements with brief, self-contained
  rationale. SPEC.md is updated when a discussion with the user reveals a conflict with it or a
  clarification of it.
* Decisions about implementation that affect large parts of the code but are not relevant to
  SPEC.md belong in `docs/`, as technical notes written for another experienced developer. They
  are organised by topic, not chronologically.
* Open questions and future ideas belong in `TODO.md` and `ROADMAP.md`.

This project does not use a chronological "engineering decisions" log. If a past decision needs
explaining, the explanation belongs in the document it affects — not in a separate log that
would drift out of date with the code. Several existing documents carry a banner to that effect:
where they say a decision was "recorded in the decision log", read it as "decided on the stated
date".

---

# Git

Commits should normally represent one logical change.

Avoid mixing unrelated changes into a single commit.

Documentation updates should normally accompany permanent design changes.

---

# Testing

Business logic should normally be tested.

Tests should provide confidence rather than merely increasing coverage statistics.

Avoid generating tests that provide little practical value.

UI tests should be added where they significantly improve confidence or prevent regressions.

Test source sets (`test`, `androidTest`) mirror the package structure of the code under test.

## Asserting on collections

Prefer asserting whole-collection equality to asserting a size and then individual members. If the
test fails, the assertion failure prints the entire expected and actual collection, so you can see
what changed rather than being told that element 3 was not what you expected and left to work out
what it actually was.

```kotlin
// Good: one assertion, and a failure report you can act on.
assertEquals(listOf(foo23), result)

// Worse: two assertions, and a failure report about a size.
assertEquals(1, result.size)
assertEquals(foo23, result[0])
```

The size-plus-elements form is still right when the order or the size is genuinely not part of what
is being tested — a repository whose contract is "some subset, order unspecified", say — because
asserting on a whole collection there pins down more than the contract promises.

Note that this applies to assertions about a *value*, not to assertions about side effects: counting
the writes a DAO received is a statement about how many times something happened, which a
collection comparison cannot express.

## Dispatchers in ViewModel tests

Prefer `StandardTestDispatcher` plus hand-written fakes for ViewModel tests.

Do not combine an `UnconfinedTestDispatcher` with DataStore. DataStore's `first()`/`collect`
dispatches to its own IO dispatcher internally, so an init-time load can resume after a user action
and clobber in-memory state, producing failures that depend on coroutine scheduling rather than on
logic. See `docs/TESTING_LIMITATIONS_AND_REVIEW.md` §1 and §5 for the full account.

---

# Refactoring

Refactoring is encouraged when it clearly improves the codebase.

Avoid speculative refactoring.

Avoid rewriting working code without a clear benefit.

When a temporary implementation exists, either document that fact or replace it before it becomes permanent.

---

# Future maintenance

Assume this project will still be maintained many years from now.

Prefer solutions that make future changes easier, even if they require slightly more thought today.

The ideal codebase is one that an experienced developer can understand without needing the assistance of an AI system.
