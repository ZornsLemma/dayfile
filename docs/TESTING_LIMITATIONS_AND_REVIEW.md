# Testing Limitations and Honest Code Review

This document was written after a long, painful episode (July 2026) where attempting to
support brittle UI tests caused repeated breakage of both the tests and the main app code.
Its purpose is to record what we learned so we do not repeat the exercise, and to give an
honest assessment of the current state of the codebase.

---

## 1. What we cannot reliably test

* **DataStore-backed `ViewModel` state with `UnconfinedTestDispatcher`.**
  `HistoryFilterState` (and any `ViewModel` that reads `SettingsRepository` flows in `init`)
  cannot be reliably tested with `UnconfinedTestDispatcher` because DataStore's `first()`/
  `collect` internally dispatches to its own IO dispatcher. The `init` load can resume after
  a user action and clobber in-memory state, producing non-deterministic failures that depend
  on coroutine scheduling rather than logic. We deleted the `HistoryFilterStateTest` androidTest
  and its JVM stub because fixing it required warping the production code.

  *Update (August 2026):* the underlying persisted filter state is now covered anyway by
  `HistoryScreenFilterStateTest`, which drives the real DataStore through the real UI and polls
  storage with `waitUntil` rather than trying to control coroutine scheduling. The dispatcher
  policy stands unchanged: no JVM `ViewModel` test combining `UnconfinedTestDispatcher` with
  DataStore.

* **Process-death / Activity-recreate scenarios via `ViewModelProvider` factories.**
  The 10-minute background filter reset and `HistoryFilterState` persistence are driven by
  `MainActivity` lifecycle observers and a real `SettingsRepository` DataStore. Robolectric or
  instrumented tests that fake process death are brittle and not worth the maintenance cost.

  *Update (August 2026):* process death is now simulated at the repository level — seeding
  `AppStateRepository` before the ViewModel is constructed — which sidesteps faking the Activity
  layer entirely (`staleFilterSeededBeforeLaunchHealsAndStaysHealed`). True Activity-recreate
  scenarios remain untested; rotation is covered manually instead (see the QA checklist in
  `docs/ROADMAP.md`).

* **Exact scroll position of `LazyColumn` in `HistoryScreen`.**
  We force `scrollToItem(0)` on items change; verifying this needs a Compose UI test with
  controlled timing, which is flaky on CI. *Still untested as of August 2026; deliberately
  deferred.*

* **`HomeViewModel` date/protection logic tied to `LocalDateTime.now()`.**
  `HomeViewModel`'s logical-date behaviour is driven by an injected clock, so the arithmetic itself
  is testable — `HomeLogicTest` pins `logicalDateFor` across the day-start boundary, and
  `HomeScreenBasicsTest.dayStartChangePromotesSelectedHistoricalDateToUnprotectedToday` covers the
  interaction through the UI. What is *not* covered is real-clock behaviour at the boundary
  itself, i.e. what happens if the wall clock crosses the day-start time while the app is
  foregrounded. That is deliberate: `SPEC.md` says the selected date does not move until the user
  navigates.

* **A Compose test rule permits only ONE `setContent` per test method.** (Lesson learned
  August 2026.) Close/reopen journeys therefore cannot relaunch content. Two reusable
  simulation patterns exist instead:
  - For close/reopen lifecycles: hold the ViewModel in Compose state, cancel the old
    `viewModelScope`, swap in a fresh one — implemented in
    `BaseHistoryScreenTest.reopenHistoryScreen`.
  - For forward-navigation flows (list → sub-screen and back): render a state-swapped two-way
    switcher under the single `setContent`, flipping on the screens' own callbacks while sharing
    ONE ViewModel instance, faithfully reproducing the real nav graph's scoping — see
    `CategoryAddEditSmokeTest`.

---

## 2. How the main app code was obfuscated by deleted tests (historical)

Everything in this section describes code that has since been removed. It is kept as a record of
what went wrong, not as a description of the current tree — several of the types and functions
named below no longer exist at all. §2 to §5 are historical; §1 and §6 onwards describe current
behaviour.

* `HistoryFilterState` went through at least four incompatible rewrites: `collect` + `dirty`
  flag, one-shot load + `dirty`, `collect` + `cancel init job`, and finally a plain one-shot
  load. The final version is clean, but the git history is polluted with reverts and the
  `dirty`/`cancel` experiments added zero value. **All experiments have been removed; the current
  code uses a simple one-shot `viewModelScope.launch` load.** (The class itself was later removed
  altogether: the History screen's filter state now lives in `AppStateRepository` as DataStore-backed
  Flows, collected directly in `HistoryScreen`.)

* `SettingsRepository` gained `open val historyFilterCategory` / `historyFilterIncludeDeleted`
  flows solely to support the `collect` approach in `HistoryFilterState`. **These flows have been
  removed; only the suspend getters/setters remain.** (Filter persistence subsequently moved to
  `AppStateRepository` as Flows, collected directly in `HistoryScreen`; `SettingsRepository` remains
  preferences-only.)

* **`MainActivity`** contained the `HistoryFilterState` factory and the `ON_RESUME` reset wiring.
  This was added to persist filter state across process death; the code was correct but more
  complex than the original in-memory field. **The ON_RESUME reset has since moved into
  `ResetViewModel.onReturnToForeground`, and the duplicate `ui/MainActivity.kt` stub has been
  deleted from the repo.**

* `HomeViewModel` delegates to `HistoryCaptureHelper`, which is good separation. The cold-launch
  prune and pending-history flush that once lived in a separate `DateResetHelper`, and in
  `HomeViewModel`'s own `init` block, were later consolidated into `ResetViewModel` — the class
  `DateResetHelper` no longer exists, and neither does `flushPendingForOtherDates`.

---

## 3. Known buggy / needing-rewrite areas (all since RESOLVED)

As with §2, this is a record of issues that have since been dealt with, kept so the resolution is
traceable. Several of the symbols named below no longer exist.

* **`HomeViewModel.loadEntriesIntoStates`** previously re-emitted pending text via helper; it has
  since been replaced, and the name no longer exists.
* **`HistoryCaptureHelper.flushPendingForOtherDates`** was simplified and later removed entirely.
* **`DateResetHelper`** was extracted to remove duplication, and has since been folded into
  `ResetViewModel`.
* ~~**`HistoryViewModel.applyCategorySelection`** still calls `filterState.setSelectedCategory`
  then `loadHistory()`; this is intentional and harmless.~~ Superseded by the 2026-07-26
  refactor: `HistoryFilterState` no longer exists and `HistoryViewModel` derives
  the effective filter reactively from `AppStateRepository`.
* **`MainActivity` duplicate file** deleted.

---

## 4. Tests that add no real value

* `HistoryFilterStateTest` (deleted) – already removed.
* ~~`HomeViewModelTest.day start change flips protection only on current date boundary` – it
  asserts a tautology (`isCurrentDate == (selectedDate == logicalDate)`) and does not verify
  any user-visible behaviour.~~ Deleted (August 2026) along with the rest of the stub file.
* ~~`SettingsViewModelExportTest` – only tests `CsvExportHelper` filtering which is already covered
  by dedicated JVM unit tests; the fake DB setup is overkill.~~ Never existed (August 2026):
  the name appears only in ROADMAP prose — no such file is anywhere in the tree. The
  caller-side export selection policy it supposedly duplicated is now pinned by
  `SettingsExportSelectionTest`.
* ~~`HomeViewModelTest.init syncs category names to history` – merely checks that `upsert` was
  called once; does not verify the name map content or error handling.~~ Deleted (August 2026)
  along with the rest of the stub file.

---

## 5. Path to production quality

1. ~~Delete the empty `app/src/main/java/app/zornslemma/dayfile/ui/MainActivity.kt` stub.~~ DONE.
2. ~~Remove `historyFilterCategory` / `historyFilterIncludeDeleted` flows from `SettingsRepository`~~ DONE.
3. ~~Rewrite `HomeViewModel` load/init~~ DONE (simplified, no recursive emit).
4. ~~Extract prune logic in `DateResetHelper`~~ DONE.
5. ~~**Add a `ROADMAP.md`** for the real pre-release tasks: icon, package rename, final strings,
   Room migrations strategy, and a small set of *valuable* instrumented tests (e.g. backup/restore
   round-trip) that do not depend on `UnconfinedTestDispatcher` + DataStore races.~~ DONE
   (`ROADMAP.md` exists; `BackupRestoreHelperTest` covers the round-trip).
6. ~~**Adopt a policy: no new `ViewModel` test that uses `UnconfinedTestDispatcher` with DataStore`.**~~
   DONE. Use `StandardTestDispatcher` + `advanceUntilIdle` and fake DAOs/repositories (as in
   `CategoryEditViewModelTest`) instead of real `SettingsRepository` on disk. The policy is stated
   in `docs/DEVELOPMENT.md` ("Dispatchers in ViewModel tests").
7. **Manual QA pass** for the History screen should be performed by a human on a real device.
    (*Update (August 2026):* filter persistence across close/reopen is now
    automated in `HistoryScreenFilterStateTest`, and the 10-minute expiry decisions — filter-key
    clearing, home-gated date reset, and retention pruning — are automated in
    `ResetViewModelTest`. The remaining manual items are real-device end-to-end sanity
    of the background/foreground flow and scroll behaviour. Tracked in the QA item in
    `docs/ROADMAP.md`.)

---

## 6. Accepted data limitation: history may be misattributed after a restore (2026-08-25)

History intentionally survives a backup restore (2026-07-18: retained as a safety net), but a
restore replaces the main database wholesale while leaving the history database untouched. A
restored backup can therefore assign different meanings to the same category IDs:

* History rows whose `category_id` matches no live or archived category silently disappear from
  the History screen (pinned by `orphanedHistoryRowsAreDroppedWithoutCrashing`; harmless).
* Worse, a row can be *misattributed*: pre-restore history recorded for category ID 2 ("Diet")
  renders under whatever post-restore category now owns ID 2 (perhaps "Exercise"). No crash —
  just quietly wrong recovery data.

This is inherent to the "history survives restore" decision rather than a bug in the display
code, and is accepted for now. Mitigations such as stamping history rows with a
database-generation identifier were judged disproportionate to the risk. Revisit only if this
causes real confusion in practice.

---

## 7. Category screens: framework findings and accepted gaps (2026-08-26)

Findings from building the category test suites, recorded here so the knowledge outlives the
test comments that discovered it:

* **`InputTransformation.maxLength` REJECTS over-capacity commits wholesale rather than
  truncating them mid-string** (empirically established on this Compose version). Typing
  limit+N characters in a single commit into an empty field leaves the field completely empty;
  typing past an already-full field leaves it unchanged. Tests must therefore fill exactly to
  the limit, assert, then attempt to append and assert unchanged — see
  `HomeScreenBasicsTest.entryTextIsTruncatedToMaxLength` and
  `CategoryAddEditBasicsTest.nameFieldEnforcesMaxLength`. Users can never exceed the limit
  under either reading, so production behaviour is correct either way; only naive tests assume
  truncation.

* **The `entry` table enforces `UNIQUE (category_id, date)`**: one entry per category per day,
  by design. Knock-on effects testers must respect: seeding two same-date entries for one
  category violates the constraint (`SQLiteConstraintException`); the delete dialog's no-date
  message variant is reachable only with exactly one entry; and of the two delete-message
  plurals, range-"one" is unreachable in every locale (see the comment in `strings.xml`).

* **Per-category keyboard hints: what is and isn't automatable.** The chain from the user's
  editor choice to the IME decomposes into five links, and every automatable one is pinned:
  editor persistence form→DB (`CategoryAddEditBasicsTest.saveEditUpdatesFieldsKeepsOrderingEnabledAndSyncsHistoryName`
  for the edit path and `.keyboardHintChoicesRoundTripOnNewCategory` for the add path, both
  driving the real UI controls and reading back through the real DAO);
  DB→form prefill (`.editModePrefillsAllFieldsIncludingNonDefaultHints`); Room storage of the
  `auto_correct`/`capitalization` columns (subsumed by those real-DAO read-backs); the home
  flow emitting changed category entities live (subsumed, plus
  `HomeScreenBasicsTest.disablingCategoryHidesItWithoutDeletingItsEntry` proving the same
  emission reaches the home screen reactively); and the entity→`KeyboardOptions` mapping
  (pinned on the JVM by `CategoryKeyboardOptionsTest`, which also gives
  `CapitalizationMode.toKeyboardCapitalization()` its first direct coverage). The final
  in-screen pass-throughs into `TextField` cannot be asserted by any instrumented test:
  `KeyboardOptions` are consumed when a field gains focus and are exposed nowhere in Compose
  semantics (verified against a captured semantics dump: `IsEditable`, `MaxTextLength` and
  `ImeAction` appear; capitalization and correction do not), and synthesized `SetText` input
  bypasses the IME entirely. Residual risk is confined to required named parameters in one
  file (removal is a compile error; only a deliberate constant substitution could slip through
  silently), which falls to the real-device manual QA item. Deliberately rejected after two
  rounds of review discussion: exposing the options via custom semantics property keys
  published alongside the `TextField` call. Any such exposure is a parallel channel by
  construction and can never observe the real handoff: deleting the `keyboardOptions = ...`
  argument at the `TextField` call site compiles (the parameter has a default), leaves the
  published values correct, passes any exposure-based test, and silently breaks UX — the one
  silent edit that matters most, which the exposure would even help conceal (the parameter
  still looks consumed). The edit it *would* catch — substituting a constant for the
  `buildKeyboardOptions(category)` call — is implausible, and the comprehensibility cost of
  unusual semantics machinery outweighed converting one unlikely silent edit into a loud one.
  Accepted mitigations instead: daily use surfaces gross failures (autocorrect suddenly on
  everywhere) in ordinary use; framework API drift fails at compile time rather than silently,
  because the options are built with named arguments; and a one-time logcat verification
  during the real-device QA pass (temporarily log the built options on field focus, confirm
  they match each category's settings, then remove the logging) settles "is the app calling it
  correctly?" as a settled historical fact rather than a perpetual doubt. Restructuring so
  `CategoryEntrySection` derives the options itself was likewise rejected (marginal risk
  reduction, real coupling cost): it shrinks but never eliminates the gap between the observed
  channel and the real consumption.

  While a field is focused, `adb shell dumpsys input_method` shows the active `EditorInfo`,
  including its input-type flags. Capitalization modes map to the well-known
  `CAP_SENTENCES`/`CAP_WORDS`/`CAP_CHARACTERS` flag family, and the autocorrect setting affects
  the suggestion/auto-correct flags. So this allows the capitalization and autocorrect settings to
  be verified against the keyboard during manual testing.

* **Accepted gaps on the category screens** (candidates for the manual QA pass): the dimmed
  (alpha 0.38) rendering of disabled categories is invisible to Compose semantics; the behaviour
  of the add/edit form across rotation is not verified automatically (its state is
  `rememberSaveable` / `rememberTextFieldState`, so it is *meant* to survive, but nothing asserts
  it — rotation belongs on the manual checklist); real `NavController` routing
  (route-argument parsing, back-stack scoping of the shared `CategoryEditViewModel`) is
  deliberately untested — the list↔form interaction is covered by the state-swapped switcher
  (section 1), and route wiring misconfigurations fail loudly under manual use.

The app's core logic (`HomeLogic`, `HistoryLogic`, `CsvExportHelper`) is solid and well-tested
on the JVM. The Android UI layer is functional and the earlier test-induced warping has been
reverted. The project is in a maintainable state, and shipped as version 0.1; the outstanding work
is a human QA pass on a real device, tracked in the QA checklist in `docs/ROADMAP.md`.

---

## 8. Settings screens: framework findings and accepted gaps (August 2026)

Outcome of the settings-screen coverage investigation (ROADMAP Testing item), recorded so the
reasoning outlives the work:

* **Verdict: no `SettingsViewModelTest` class, deliberately.** Most of `SettingsViewModel` is
  thin pass-through over `SettingsRepository`, `BackupRestoreHelper` and `CsvExportHelper`,
  each of which already has dedicated coverage. Constructing the ViewModel on the JVM would
  require a `Context` (needed only by backup/restore/export), forcing either Robolectric or a
  constructor refactor — disproportionate to the logic being pinned. This mirrors the
  `HomeViewModel` resolution: cover behaviour where a real Context lives (instrumented), and
  don't warp production code for JVM testability.

* **What was added instead:**
  - JVM `SettingsExportSelectionTest`: pins the export selection policy (disabled-category
    inclusion, blank-entry dropping, orphan-entry dropping alongside their excluded categories,
    order preservation), extracted verbatim from `SettingsViewModel.export()` into top-level
    `selectExportData()` in the same file so it is testable without a Context. CSV
    formatting/sorting itself remains covered by `CsvExportHelperTest`; the row-inclusion
    policy this implements is: an exported CSV row appears if and only if its entry text is
    non-blank AND its category is among the selected categories (all categories when "Include
    disabled categories" is on; enabled categories only otherwise). Both conditions are applied
    caller-side in `selectExportData`.
  - Instrumented `SettingsScreenBasicsTest`: retention dialog input rules (digits-only,
    3-char cap, OK disabled while blank, 0 accepted explicitly), clear-history safety gating
    (row inert when empty; confirming clears the database and re-disables the row), and the
    BOM toggle's full persistence chain (row → ViewModel → DataStore).
  - One-line production change: `testTag("settings_retention_days_field")` on the retention
    dialog's TextField, matching the house convention that every tested input has a tag.

* **`SettingsViewModelExportTest` never existed.** The name appears only in ROADMAP prose;
  no such file is anywhere in the tree (see §4).

* **Minor finding, left alone:** `SettingsViewModel` wraps Room suspend calls in
  `Dispatchers.IO`, which is redundant (Room suspend functions are main-safe by design) but
  harmless. Left untouched under the focused-changes principle.

* **Verified cross-screen journey (pinned by two `HomeScreenBasicsTest` cases):** changing
  "Day starts at" in Settings reclassifies the home screen's retained selection live, because
  `HomeViewModel` consumes `dayStartTime` as a reactive `combine` input cached `Eagerly` — the
  home ViewModel survives underneath Settings, so the recompute happens before navigation back.
  A date that stops being the current logical day gains the padlock and read-only fields; one
  that becomes current loses them and unlocks. The selection itself is never moved (no
  auto-navigation). Deliberately pinned because a plausible simplification — reading day-start
  once in `init` — would compile cleanly, pass every other test, and silently leave the
  classification stale until the 10-minute reset or process death masked it.

* **Accepted gaps (manual QA):** SAF-driven backup/restore/export happy paths; error-dialog
  mapping (`FileOpState.Error` / `RestoreState.Error` → dialogs) including fault injection,
  which would need static mocking or an injection refactor judged not worthwhile; the
  restore-restart flow (`safeRestartApp` exits the process, so it cannot be exercised
  in-process); snackbar presentation of success states; time-picker round trip; dimmed
  (alpha 0.38) rendering of the disabled clear-history row (alpha is invisible to Compose
  semantics, per §7).

* **Shared-state hazards for future tests:** every instrumented suite in the process shares
  the app's real "settings" DataStore file (`SettingsRepositoryTest`, `SettingsScreenBasicsTest`,
  and via `BaseHomeScreenTest` all home-screen suites). Any suite that writes a settings key
  must reset known keys to defaults in setup *and* teardown: `SettingsScreenBasicsTest` does
  this for day-start/BOM/retention, and `BaseHomeScreenTest` does it for day-start after its
  day-start-journey tests leaked a non-default value into the shared store and silently
  reclassified "current day" for every later test — protection icons appearing/vanishing and
  typed input refused in suites far from the culprit, purely depending on execution order.
  Use in-memory Room databases so nothing touches real app data.
