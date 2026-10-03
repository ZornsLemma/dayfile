# ROADMAP.md

# Roadmap

This file tracks outstanding work. It complements `TODO.md` (which contains raw notes) and
`docs/TESTING_LIMITATIONS_AND_REVIEW.md`.

Version 0.1 is the release this file was assembled for. Items below are work deferred past it, or
decisions left open at the time of writing it. None of them blocks that release.

Note: this is a solo hobby project, not a corporate QA process. Unchecked items mean "not yet
formally verified", not "never attempted" — informal manual testing happens continuously during
development and is not tracked here. AI collaborators should not infer testing history or process
maturity from the checkboxes (see AI_GUIDELINES.md).

## Product / branding
- [x] Rename the package from `com.example.dailylog` to `app.zornslemma.dayfile`.
- [x] Review hard-coded strings and ensure all user-facing text uses `strings.xml`.
- [ ] Check the applicationId against the Play Store for clashes. (F-Droid is clear: no app there
      matches the name "dayfile". Only relevant if this ever goes to Play.)
- [ ] Optionally, join the history timestamp and the third-party licence heading through format
      arguments so translators can reorder them. Every string a user *reads* is already in
      `strings.xml`, and Spanish is complete, so this is cosmetic.

## UI / UX polish
- [ ] Test keyboard hints (autocorrect, capitalization) on a real device.
- [ ] Address long date wrapping on home screen (e.g. abbreviated day names or non-breaking spaces).
- [ ] Take screenshots of key states and get MD3 compliance feedback (with pinch of salt).
- [ ] Investigate a landscape/tablet home layout before any tablet push (two-pane category/editor,
      or a compacted toolbar). Phone-landscape is deliberately second-class: it now works (state
      survives, IME inset consumed around the NavHost) but stays cramped — toolbar plus keyboard
      leave a thin band. Candidate cheap improvement while touching the home screen anyway:
      hide/compact the date toolbar while the IME is visible (phase-B candidate, August 2026;
      declined for phase B September 2026 — see docs/HOME_SCREEN_NOTES.md §7.4).

## Data / migrations
- [ ] Decide the Room migration strategy now that user data exists. Both databases are still at
      version 1, so the first schema change is where this gets decided rather than deferred.
- [ ] Document backup/restore format stability for end users, so a future schema change has a
      stated answer about what a backup taken with an older version means.
- [ ] Decide whether to keep opting in to Android Auto Backup with empty rule files. The current
      default sends the user's notes to Google cloud backup along with everything else, and
      `docs/SPEC.md` has to carry a caveat explaining it. Keeping it is defensible; having to
      explain it in the specification is less good.

## Testing
- [x] Adopt policy: no `ViewModel` test with `UnconfinedTestDispatcher` + DataStore; use `StandardTestDispatcher` + fakes. (Demonstrated in practice by `MainDispatcherRule`, used by `CategoryEditViewModelTest`; the policy rationale lives in `docs/TESTING_LIMITATIONS_AND_REVIEW.md`.)
- [x] Remove or rewrite remaining low-value tests (`SettingsViewModelExportTest` duplicates `CsvExportHelper` JVM coverage). (Resolved August 2026: the file never existed — a phantom reference from an earlier draft of `TODO.md`; no such test is anywhere in the tree. Doc mentions corrected; the caller-side selection policy it supposedly duplicated is now pinned by `SettingsExportSelectionTest`.)
- [x] Investigate settings-screen test coverage (the agreed next area after the category suites): read `SettingsScreen.kt` / `SettingsViewModel.kt` and the existing settings tests first, then discuss. If the screens turn out to be thin pass-throughs over already-tested helpers, concluding "little or nothing worth adding" is a valid outcome. (Done August 2026: added JVM `SettingsExportSelectionTest` and instrumented `SettingsScreenBasicsTest`, plus a one-line retention-field test tag in `SettingsScreen.kt`; concluded no `SettingsViewModelTest` class is warranted — see `docs/TESTING_LIMITATIONS_AND_REVIEW.md` §8. This also closed out the phantom `SettingsViewModelExportTest` item above.)

## Process
- [ ] Rewrite git history to fix "Your Name" author entries if desired.
- [ ] Manual QA pass on a real device, consolidating all accepted-gap items in one place:
      keyboard hints on a real device (see docs/TESTING_LIMITATIONS_AND_REVIEW.md §7 for a
      suggested one-time logcat verification technique); dimmed (alpha 0.38) rendering of
      disabled categories on the Categories screen; rotation (state preservation implemented
      app-wide — home-screen focus/IME restoration after rotation verified September 2026, see
      docs/HOME_SCREEN_NOTES.md §9; History scroll preservation across rotation still to verify:
      the list state's saved position survives rotation, and a signature check stops the
      scroll-to-top effect from firing on an unchanged list after a configuration change); IME
      behaviour with the on-screen keyboard (portrait: focusing the bottom category raises it
      fully above the keyboard, and focus restoration scrolls the field into view; landscape
      home: only a thin scrollable band remains under the fixed headers — accepted gap);
      **backgrounding clears focus and the OSK does not re-pop on return — new September 2026
      behaviour (docs/HOME_SCREEN_NOTES.md §7.6); verify on an OSK-enabled device, portrait and
      landscape, and confirm that rotating the phone in the foreground still preserves focus —
      the isChangingConfigurations() guard must not have broken the §6 restore path**;
      landscape accepted gaps: the home screen is cramped by design (future layout
      investigation tracked under UI/UX polish) and History's fixed filter controls consume
      roughly half the viewport (accepted: they are the screen's controls and stay visible);
      date/time pickers and the longest dialogs (delete-with-date-range) may clip on short
      landscape windows; walk every nav route once (nav wiring is deliberately untested — see
      docs/TESTING_LIMITATIONS_AND_REVIEW.md §7); settings items (per §8): SAF-driven
      backup/restore/export happy paths; settings error dialogs (backup/restore/export/
      clear-history failure mapping); restore-restart flow (informally exercised during
      development; formal pass pending); success snackbars; time-picker round trip;
      dimmed rendering of the disabled clear-history row.

## Documentation
- [x] Decisions live where they are read, not in a separate log. Product decisions are in
      `SPEC.md` (requirements with brief rationale); implementation decisions that affect large
      parts of the code are in `docs/` as topic-organised technical notes; open questions and
      future ideas are in `TODO.md` and this file. See `AI_GUIDELINES.md` and
      `DEVELOPMENT.md` ("Documentation").

## Done
- [x] Choose the application name. "Daily Log" was taken on F-Droid; the app is now called
      Dayfile, with `strings.xml` and `rootProject.name` updated to match, and the package
      identifier renamed to `app.zornslemma.dayfile` (see "Product / branding" above).
- [x] Design an app icon (vector, bi-colour) and add to `res/`. The source SVGs and the
      generation scripts are in `icon/`, with a note on their provenance in `icon/README.md`;
      the traced Android Studio layers are in `res/drawable/ic_launcher_*`.
- [x] Remove duplicate `ui/MainActivity.kt` stub.
- [x] Remove unused `historyFilterCategory` / `historyFilterIncludeDeleted` flows from `SettingsRepository`.
- [x] Simplify `HomeViewModel` load/init (no recursive emit).
- [x] Extract prune logic in `DateResetHelper`.
- [x] Delete brittle `HistoryFilterStateTest` androidTest and JVM stub.
- [x] Add valuable instrumented backup/restore round-trip tests (`BackupRestoreHelperTest`, using scratch database names so it neither reads nor destroys real app data).
- [x] Trim low-value tests: `HistoryDaoTest` reduced to decision-pinning cases; `SettingsRepositoryTest` pass-through round-trips removed; `EntryDaoTest` tidied.
- [x] Delete superseded stub tests `HomeViewModelTest` and `DateResetHelperTest` (their intents are covered by the home/history instrumented suites and `ResetViewModelTest`).
- [x] Add history-screen test coverage: JVM `HistoryLogicTest`, instrumented `HistoryScreenBasicsTest` / `HistoryScreenFilterStateTest` / `HistoryScreenSmokeTest`, and `ResetViewModelTest` (10-minute expiry: filter clear, home-gated date reset, prune).
- [x] Move the background timestamp out of DataStore into a `SavedStateHandle` held by a new
      activity-scoped `ResetViewModel`, so that swipe-away (task removed) and background-kill
      (task kept) are handled by the platform's own state-preservation semantics rather than by
      inference. `DateResetHelper` deleted; `AppStateRepository` no longer touches a timestamp.
- [x] Update SPEC.md: the date auto-resets on return to the *home screen* (and discards the
      back stack from any screen), not "returns to the app". Resolved — see `docs/SPEC.md`,
      "Returning to the application".
- [x] Add category-screen test coverage: JVM `CategoryRepositoryTest` / `CategoryEditViewModelTest` (+ new `MainDispatcherRule`), instrumented `CategoryScreenBasicsTest` / `CategoryScreenSmokeTest`, and add/edit-form coverage in `CategoryAddEditBasicsTest` / `CategoryAddEditSmokeTest`.
- [x] Make category writes report failure instead of crashing. `CategoryEditViewModel` now funnels
      every database write through one `launchCatching`, and `CategoryRepository.upsertCategory`
      raises `UserVisibleException` rather than `checkNotNull` for a stale edit route.
- [x] Stop the white flash in dark mode. `values-night/themes.xml` and `values-night/colors.xml`
      give the pre-Compose window a dark background.
- [x] Reconcile the documentation with the 0.1 release: `SPEC.md`, `AI_GUIDELINES.md`,
      `DATABASE.md` and this file no longer describe a pre-release state, and `DATABASE.md`
      documents `HistoryDatabase` alongside `MainDatabase`.
