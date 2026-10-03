# Home screen: state, focus and rotation notes

Audience: a future AI (or human) session about to work on `HomeScreen.kt` / `HomeViewModel.kt` —
in particular the planned "phase B" work. This file consolidates analysis, observed behaviours and
open decisions that were spread across a long development conversation. Wherever possible it points
at existing code and decision-log entries rather than duplicating them; the focus/IME analysis,
however, exists nowhere else, so it is recorded here in full.

Read first: `DEVELOPMENT.md` ("State ownership") and the header comment of `HomeViewModel.kt`.
This document assumes both. For background on the live-editing pattern in general — written as a
standalone tutorial so it can outlive this code's specifics — see `docs/COMPOSE_LIVE_EDITING.md`.

> **Note on the decision log:** there was once a set of numbered `docs/DECISIONS-*.md` files. They
> have since been removed from the repository and their substance folded into documents like this
> one. Where this file says "recorded in the decision log", read it as "on date X we decided Y" —
> a past decision that may have been revised since, not as gospel. If such a note contradicts the
> code, the code wins.

---

## 1. Status and context

Rotation-state work is complete and committed (as of August 2026):

* Phase 0: test-suite comment corrections, retention pin (`ResetViewModelTest`).
* Phase A: `CategoryAddEditScreen` and the home date picker moved to `rememberSaveable`;
  history scroll decision (the scroll-to-top rule and its rotation exception are described in
  §3 and the signature check in `HistoryScreen.kt`); SPEC foreground-frozen note.
* Phase A.1: `verticalScroll` on the add/edit form; signature-gated history scroll-to-top;
  IME inset consumed once around the NavHost (`MainActivity`); ROADMAP/AI_GUIDELINES updates.

Phase B progress:

1. ~~Two pinning tests FIRST (§2.3)~~ — done September 2026 (`HomeScreenPinningTest`, all three green).
   Keep them green throughout the phase-B edits; they are the truce's guard.
2. ~~Config-gated focus restoration (§6)~~ — implemented September 2026; the final design recorded
   in §6 supersedes the original sketch kept there (orientation-only token, explicit consumption
   rules, protected-day skip). §3 now describes the mechanism as built. Manual verification (§9):
   first pass done September 2026, including rotation restore on protected and unprotected days.
3. ~~The protection carry-over fix (§7.1)~~ — done August/September 2026; final shape differs
   from the sketch below (see the §7.1 banner).
4. Decisions: ~~auto-reset focus (§7.2)~~ — resolved September 2026 (keep-and-document, see the
   §7.2 banner); ~~menu-open clearFocus (§7.3)~~ — resolved September 2026 (clearFocus on
   menu-item click, see the §7.3 banner); toolbar-hide-while-IME (§7.4) — declined for phase B,
   deferred to the ROADMAP landscape investigation; scroll-across-days (§7.5) still open,
   deferred to real use.
5. ~~Comment/log cleanup (§8)~~ — done September 2026 alongside item 2 (truce helper composable
   with the invariant comment, REVIEW musing and linear-search REVIEW struck, HomeScreen WTAF
   logs removed early).
6. ~~Manual verification pass (§9)~~ — first pass done September 2026 (items 1–3 and 5–8 OK;
   item 4's 10-minute scenarios deferred, cheap technique noted in §9). The checklist will be
   re-run as part of manual QA.
7. ~~Clearing focus when the app leaves the foreground (§7.6)~~ — done September 2026: a
   lifecycle observer on the home screen clears focus on `ON_PAUSE` unless the pause is a
   rotation, so returning to the app never re-pops the OSK. Rotation preserves focus via the
   existing §6 gate. The bottom-of-file TODO in `HomeScreen.kt` is struck by this change.

---

## 2. The text-field architecture ("the truce")

### 2.1 Summary

The hard problem — who owns text state: the `TextFieldState` or the database — has a deliberate,
working answer. After initial seeding, **`TextFieldState` is the master; the database is a
durability sink, not a display driver.**

* Each field is `key(category.id, selectedDate) { rememberTextFieldState(initialText) }`.
  The initial value seeds from `uiState.entries` **only** on first composition for that
  (category, date) pair; every later re-emission of `uiStateFlow` (including ones carrying the
  text just typed, bounced back from the DB) is **deliberately ignored** by the field.
* Persistence: `InputTransformation.maxLength(...).then { onTextChanged(...) }` does not run
  merely because the field's displayed initial value changes, but it does run for input and
  selection transformations. Cursor-only text changes are filtered by `HomeViewModel.saveEntry`;
  a final selection-collapse callback can occur when focus is cleared during ViewModel teardown,
  so `onUserTyped()` ignores callbacks after that scope has been cleared. Edits then flow through
  `onUserTyped` → `Channel(UNLIMITED)` → a single consumer coroutine → `saveEntry` (read-decide-write:
  insert / update / delete; blank means the row is absent). Ordering is guaranteed by the channel;
  the UI never suspends.
* The `text != existingText` filter in `saveEntry` exists mainly to protect history timestamps
  from cursor-move "updates" (see 2.2), with entry-table write-reduction as a side benefit.
* **The channel is closed when the consumer stops.** The `viewModelScope.launch` that drains
  `userEdits` has a `finally { userEdits.close() }`, which runs both when the ViewModel is cleared
  and if `saveEntry` throws. That matters for more than tidiness: the writer side does
  `check(trySend(...).isSuccess)`, which is what detects that nothing is consuming any more. Without
  the close, a `trySend` into a channel whose only reader has gone would silently succeed and the
  edit would be lost — the original bug this pipeline was written to prevent. No test can observe
  the `finally` itself; `HomeViewModelTeardownTest` only pins that input after teardown is dropped
  without crashing. See `TODO.md`.
* History: `HistoryCaptureHelper` — 400 ms debounce, per-(date, category) `HistoryPrefixCompactor`,
  flush watermark advanced once per flush rather than per write (ids are global but the compactors
  are per key, so advancing mid-flush silently drops a later key's lower pending id — pinned by
  `HistoryCaptureHelperTest`'s interleaving test). Offers are stored trimmed; see SPEC.md §Recovery.
  Durability trade-offs are argued in the `HomeViewModel` header comment; do not
  re-litigate them without evidence (a measured performance problem, or a demonstrated durability
  gap wider than documented).

Why the alternatives stay rejected: a fully DB-driven TextField needs echo suppression and fights
IME composition (strictly worse); holding `TextFieldState`s in the ViewModel per (category, date)
relocates the same problem and adds eviction lifecycle.

### 2.2 Costs already accepted — do not "fix"

* **A DB read per real edit**: intrinsic to read-decide-write (you must read to choose
  insert/update/delete). It predates and is independent of the cursor-move issue.
* **Cursor moves travel the pipeline**: the `InputTransformation` trick re-emits on cursor moves;
  the no-op filter in `saveEntry` makes them read-only. Removing the re-emissions would mean
  caching last-persisted text per field — a second "who is master" state. Not worth it.
* **A few-ms loss window** before text reaches the DB (also across process death, slightly wider).
  Accepted in the `HomeViewModel` header.
* **No debounce on main-screen entries** — deliberate (see header comment). History has the
  400 ms debounce; main entries do not.

### 2.3 Pinning tests — write these BEFORE touching the file

> Done September 2026: three tests now exist in `HomeScreenPinningTest` and are green. The
> descriptions below are the record of what they pin and why.

All are instrumented (real Room, existing base class):

1. **Cursor-move is a DB no-op** (the `saveEntry` TODO asks for this). Type "ab", wait out the
   debounce, assert history == ["ab"]. Move the cursor without changing text (e.g. a tap placing
   the caret mid-string), wait out another debounce window, assert history is STILL exactly
   ["ab"] — a regression would show a duplicate "ab" with a newer timestamp (losing the real
   edit time). Entry-table assertion too (still exactly one row).
2. **A disposed and recreated field is reseeded from the database.** Type "foo"; force a
   categories-flow re-emission by disabling and re-enabling the category out-of-band via the DAO,
   as `HomeScreenBasicsTest` already does. The new field must show "foo", must not take focus,
   and further typing must append ("foobar" in the database). This is a genuine seeding test, but
   deliberately not the general live re-emission case: the original field has been disposed.
3. **A live UI-state re-emission does not reseed the composed field.** Type "foo", place the caret
   after the first character, and force a categories-flow re-emission without disposing the field
   (for example, rename the category). The field must retain the text *and* the caret position:
   typing "X" must produce "fXoo", not reset the field to "foo" and append at the end. This is
   the broader re-emission guard for the truce.

---

## 3. Focus mechanism as it stands today

(Updated September 2026 to describe the §6 gate as built. The pre-gate mechanism is preserved in
git history; §4 records which of its observed behaviours the gate changed.)

* `focusedCategoryId: Long?` in `rememberSaveable` — survives rotation, process death and
  child-screen returns (nav-scoped saved state).
* `restoredOrientation: Int?` in `rememberSaveable` — the orientation as of the last save or
  restore pass. The gate (`restoredOrientation != LocalConfiguration.current.orientation`) is
  open exactly on the first composition after a genuine configuration change, and is consumed —
  set to the current orientation — by the restore pass itself (see below).
* Screen-level `LaunchedEffect(Unit)`: when the gate is open, either consumes the token
  (nothing to restore: no remembered focus, a stale id, a protected day) or scrolls the focused
  item into view (`listState.scrollToItem`), leaving consumption to the field.
* Per-field `LaunchedEffect(focusRequester)`: when the gate is open and this is the remembered
  field, request focus (unless the day is protected) and consume the token. Because the target
  may be outside the initial viewport and compose only after the screen-level scroll, the field
  half — not the screen half — consumes in the real-restore case. Both effects read the state
  live when they run, so consumption order within a pass does not matter.
* `clearFocus()` helper (prev/next buttons, date-picker confirm): nulls `focusedCategoryId` FIRST
  (so nothing re-grabs it), then `focusManager.clearFocus()`. These explicit clears are
  load-bearing: the TextField *node* survives a date change (only the state inside `key()` is
  recreated), so without an explicit clear the node carries focus onto the new day's field.
* The overflow-menu items call `clearFocus()` on click (§7.3: ends the OSK re-emergence while a
  child screen slides in; opening/dismissing the menu alone deliberately keeps focus). The
  auto-reset path does **not** call `clearFocus()` (§7.2 keep-and-document).

Rule in one line: **focus exists only where the user put it or a configuration change restored
it.**

---

## 4. Observed and predicted focus/IME behaviours

> September 2026: the §6 gate changed the outcomes of items 2, 3 and the effect-grab half of
> item 4 — see §6's case table. Items are kept for the record.

Verified on an Android 16 emulator with the on-screen keyboard (OSK) enabled (§5); predictions from
code are marked (P) and are cheap to confirm.

1. **Rotation** — the intended trigger. Text/cursor survive via `TextFieldState.Saver`; focus is
   restored via the mechanism above. Focus restoration with a visible OSK after rotation is still
   on the phase-B verification list (§9).
2. **Return from a child screen** (Settings/Categories/History) — home recomposed fresh, the
   per-field effect fires, focus is re-grabbed AND the screen-level effect scrolls to the field,
   overriding the restored scroll position; the OSK re-appears. **Observed.** The maintainer's
   recorded inclination: this is *bad* — the OSK eagerly re-appearing on return from settings
   should not happen.
3. **Scrolling a focused field out of the LazyColumn viewport and back** (P) — LazyColumn disposes
   off-screen items; returning re-runs the effect and yanks focus back. Focus pinball in normal
   portrait use. Clearly unintended; the redesign kills it.
4. **10-minute auto-reset date change** (P) — the per-field effect is keyed on `selectedDate`, so
    the date change re-runs it for the same category: the new date's field grabs focus (node may
    also simply retain focus in place). Keyboard stays up over the new date's content. Accidental,
    not decided; see §7.2. (Since September 2026 the reset writes the date only when the stored
    selection differs from the current logical day, so a same-day expiry does not write a date or
    protection override. It appears as a no-op in the ordinary case; an open home date picker is
    nevertheless closed by the new-session boundary in §7.7.)
5. **The menu-open "OSK dance"** — observed sequence when opening Settings via the overflow menu
   while a field has focus and the OSK is up: menu opens → OSK hides (popup takes focus); brief
   OSK re-emergence while Settings slides in (popup dismissal restores focus momentarily, then the
   transition takes home out of play); OSK hides when Settings lands. September 2026 correction:
   the popup-open hide and popup-dismiss restore are stock window-focus mechanics, but the
   re-emergence was OURS — the field still held Compose focus when the popup dismissed. Fixed by
   the §7.3 menu-item clearFocus; the stock parts remain and are accepted.
6. **Platform baseline** — system back dismisses the OSK but never removes focus; focus is
   inescapable on this screen by design (there is no other focus target). Note the focused
   field itself surviving back-dismiss of the OSK is stock behaviour, not a bug (September 2026
   review); only the *re-grabs* above were ours.
    >
    > **Updated September 2026 (§7.6):** the "inescapable" part no longer holds across a
    > backgrounding. The home screen's lifecycle observer clears focus on `ON_PAUSE` unless the
    > pause is a rotation, so focus is now escapable by leaving the app — which is the point.
    > The stock back-dismiss of the OSK is deliberately *not* extended to clearing focus: a user
    > pressing back to say "no OSK this time, I'll type on a physical keyboard" must keep focus
    > (§7.6). Only the backgrounding clear is new.

---

## 5. Emulator IME setup and verification techniques

* Google's Android 16 emulator defaults to "no on-screen keyboard" when a virtual hardware keyboard
  is connected. Enable the OSK via Settings → System → Languages & input → Physical keyboard →
  "Show virtual keyboard while hardware keyboard is connected" (path may vary slightly by image).
* While a field is focused, `adb shell dumpsys input_method` shows the active EditorInfo including
  input-type flags — the way to verify capitalization/autocorrect hints actually reach the IME.
  This is the note that was "made permanent once verified"; it is here now.
* Bring-into-view targets the CARET, not the field's full bounds: the typed text rises above the
  OSK but the field container's bottom edge (padding + indicator) stays partly behind it. This is
  standard Material behaviour (Messages/Keep/Gmail identical) — **normal, do not re-investigate**.
  The bug threshold is "typed text invisible".
* A DataStore write issued from test code (via `runBlocking` on the test thread) is invisible to
  the idling machinery in its final hop: DataStore notifying collectors parked on the main
  dispatcher (DataStore -> combine -> recomposition) is not tracked, so `waitForIdle()` can
  observe an idle main thread before the UI reflects the write. When a test's expectation depends
  on propagated state rather than on a performed interaction, wait on the state itself
  (`waitUntil { viewModel.uiStateFlow.value... }`) — see
  `HomeScreenBasicsTest.dayStartChangePromotesSelectedHistoricalDateToUnprotectedToday`, which
  failed intermittently until this was applied. Room invalidation re-emissions have the same
  theoretical exposure but have been stable in practice.

---

## 6. Phase B redesign: config-gated focus restoration

> **Implemented September 2026** along the lines below, with three deliberate departures from the
> original sketch (kept at the end of this section for the record): the token is a bare
> orientation `Int` rather than a `ConfigSignature` data class — an Int needs no custom Saver, and
> width/height can be perturbed by IME show/hide on some API levels for no benefit here, at the
> accepted cost that size-only changes (split-screen entry, free-form resize) do not restore; the
> token is consumed by explicit rules (below) rather than "in the screen-level effect after the
> restore", because that sketch variant silently breaks offscreen targets (they compose only
> after the scroll); and the gate additionally refuses to restore on a protected day. This was
> also recorded in the now-removed decision log — the substance is repeated here: the
> token is a bare orientation `Int`, consumed by explicit rules, and protected days skip restore.

Goal: restore focus only on an actual configuration change. The one-line rule: **focus exists
only where the user put it or a configuration change restored it.**

Case table — every cause of a fresh composition, and what the mechanism does:

| Composition cause | Outcome |
| --- | --- |
| Rotation / configuration change | Gate open: the remembered field takes focus (unprotected days only) and is scrolled into view; token consumed |
| Process death | Same rebuild, unchanged orientation: gate closed. `focusedCategoryId` survives but restores nothing — death is not rotation |
| Return from child screen | Home destination recomposed from intact nav saved state: gate closed; no grab, no scroll (§4.2 fixed) |
| LazyColumn viewport recycling | Item recomposed: gate closed; text re-seeds from the database, cursor offset is the accepted loss (§4.3 fixed) |
| Category enable/disable | Section removed/added: gate closed (§4.3 family) |
| Date change, user-initiated | `clearFocus()` runs first; keyed field state recreated; no effect re-runs (the date is no longer an effect key) |
| Date change, cross-day auto-reset | Same, minus the explicit clear: the reused field node may retain focus over the new day — kept and documented (§7.2) |
| uiState re-emissions | Recomposition with identical keys; no effect re-runs |
| Window focus changes (dialogs, popups, IME) | Not composition events; platform mechanics |

Consumption rules (the one-shot protocol):

* The gate comparison is performed *inside* the effects, reading the token state live when the
  effect runs, so one effect's consumption is visible to the others regardless of launch order
  within a pass.
* The screen-level effect consumes when there is *provably nothing to restore*: no remembered
  focus, a remembered id matching no enabled category (e.g. disabled while away), or a protected
  day. Its nothing-to-restore path has no suspension point before the write.
* Otherwise — a real target on an editable day — the screen-level effect only scrolls and does
  NOT consume: the target may be outside the initial viewport and compose only after the scroll.
  The target field consumes when it handles the restore. Every other item's id mismatches and
  never consumes.
* Accepted hyper-theoretical edge: navigating away within the frame or two between the scroll
  and the target's consumption would save a pending token, and the next fresh composition with
  an unchanged orientation would then restore once. Noted rather than engineered around.
* Accepted behavioural edges (verify in §9, do not engineer around): rotation with the date
  picker open restores focus behind the dialog; rotate-with-IME-up re-pops the IME (defensible);
  `scrollToItem` snaps the focused item to the viewport top rather than merely revealing it, so
  a focused mid-list field recentres the scroll on restore.

Original sketch (superseded, kept for the record — its "implementation caution" is superseded by
the consumption rules above):

```kotlin
// Screen level
var restoredSignature by rememberSaveable { mutableStateOf<ConfigSignature?>(null) }
val currentSignature = ConfigSignature(
    orientation = LocalConfiguration.current.orientation,
    heightDp = LocalConfiguration.current.screenHeightDp,
    widthDp = LocalConfiguration.current.screenWidthDp
)
val shouldRestoreFocus = restoredSignature != currentSignature
```

* Per-field effect becomes: `if (focusedCategoryId == category.id && shouldRestoreFocus)
  focusRequester.requestFocus()`.
* The screen-level scroll-into-view effect gets the same gate.
* `restoredSignature` is set to `currentSignature` once the restore pass has run (e.g. in the
  screen-level effect after it has allowed the per-field restore), so a later fresh composition
  with unchanged configuration does not restore again.

Outcomes: rotation/unfold restores focus; return-from-child does not (kills behaviour §4.2, which
matches the maintainer's recorded inclination); scroll-out-and-back pinball dies (§4.3); process
death does not restore focus — `focusedCategoryId` survives but `restoredSignature` equals current,
consistent with "process death is not rotation". Text always survives regardless (`TextFieldState`
saver is independent of this logic).

Accepted edges: dismiss the IME, then rotate → focus restored and IME re-pops. Defensible; flag if
it annoys. Product note: return-from-child no longer scrolls to a field either — the "come back to
make a note" convenience goes away with the OSK re-pop. This is the intended trade.

Implementation caution: keep the signature comparison at screen level (one `shouldRestoreFocus`
value consumed by the per-field effects) rather than comparing per field; and make sure the
signature update happens only after a restore pass, or rotation-restore becomes one-shot-broken in
subtle ways.

---

## 7. Companion decisions and fixes

### 7.1 Protection carry-over (real bug; fix in phase B)

> **Current state (do not read the historical account below as describing the code):** the
> protection override is a persisted DataStore key in `AppStateRepository`. It is dropped by
> construction: `AppStateRepository.setSelectedDate` clears it inside the same atomic write as the
> new date, and every date movement — user navigation, the date picker, the 10-minute background
> auto-reset, first-install initialisation — goes through that one function. A manual lock on
> today survives any absence until the user navigates, because a same-day auto-reset writes nothing
> (the stored selection already matches the current logical day). The override is the app's only
> non-persisted state that was once in-memory; it is now deterministic across process death. Do not
> re-propose in-memory storage, and do not re-propose clearing the override on every `selectedDate`
> emission (DataStore echoes would clear it on every brief backgrounding).

The bug, as it was: `MainActivity.onResetToCurrentDate` (the 10-minute background reset) writes the date **directly to
`AppStateRepository`**, bypassing `HomeViewModel.setSelectedDate`, which is the only thing that
clears `protectionOverride`. Consequence: a manual lock set on one current date survives the
auto-reset and lands LOCKED on the new current date, contradicting SPEC ("Moving to another date
always resets to the default protection state for that date"). This is unrelated to the
agreed foreground-frozen policy (SPEC: continuous foreground never auto-changes anything); it is
purely about the one automatic date change the app does perform — the background-reset transition.

Recommended fix (Option A): in `HomeViewModel.init`, collect
`appStateRepository.selectedDate` and clear `protectionOverride` on every emission. Manual
navigation already clears imperatively in `setSelectedDate` (keep that — it documents intent at
the API); the collector is what catches externally-written date changes. A same-date write
(date picker confirmed on the current date) already clears the override today via
`setSelectedDate`, so reacting to every emission introduces no behaviour change there. The
first-install write in `dateStateFlow` clearing a null override is harmless.

Rejected: routing the reset callback through the ViewModel (it is nav-scoped inside composition;
MainActivity has no clean handle); moving `protectionOverride` to the repository (session UI state
does not belong in DataStore).

The date change will also re-run the per-field focus effects (§4.4); that is §7.2's business, not
this fix's.

### 7.2 Auto-reset focus behaviour (resolved: keep-and-document)

> **Resolved September 2026**: keep-and-document. With the §6 gate in place, the per-field effect
> no longer re-runs on a date change (the date was dropped from its key), so the *grab* half of
> §4.4 is gone. What remains is the *retain* half: on a cross-day auto-reset the TextField node is
> reused (only the state inside `key()` is recreated), so a focused field can keep focus over the
> new day's content. Building initiator-tracking machinery to drop it contradicts this section's
> own recommendation ("implement drop ONLY if it falls out cheaply") — it did not fall out. The
> OSK behaviour on this path is on the §9 checklist. A cross-day expiry is rare: a same-day expiry
> does not write a date or protection override and therefore appears as a no-op in the ordinary
> case, though the new-session boundary still closes an open home date picker (§7.7). A manual
> lock on today survives any absence until the user navigates.

> **Superseded September 2026 by §7.6:** the question this section posed — "does the cross-day
> auto-reset take or keep focus on the new date?" — is now moot for a different reason. §7.6 clears
> focus whenever the app leaves the foreground, and a cross-day expiry only happens after the app
> has been backgrounded for 10 minutes or more, so by the time the reset runs there is no focus
> left to take or keep. The §7.2 "keep and document" branch is dead code in practice; the
> initiator-tracking machinery it recommended against building was never needed. The keep branch
> is left in the code unchanged (the reused TextField node still retains focus across a date
> change when the app stays in the foreground) because §7.6 only clears on a real backgrounding,
> not on a date change — but it can no longer be observed through the auto-reset path.

When the auto-reset moves the date, the same category's field on the new date takes/keeps focus
(§4.4). Options:

* **Drop focus and hide the IME** — "the app moved your date while you were away; start fresh",
  calm and consistent with navigation (which always clears focus). Cost: the screen must
  distinguish user-initiated from auto date changes, which needs initiator tracking (plumbing).
* **Keep and document** the current accidental behaviour — arguably convenient for the
  "return to make a note" flow.

Recommendation: implement drop ONLY if it falls out cheaply once the §6 gate restructure exists;
otherwise keep-and-document. Do not build heavy initiator-tracking machinery for this alone.

### 7.3 Menu-open clearFocus (resolved September 2026: clear on menu-item click)

> **Implemented September 2026**, with a refinement over the original sketch: `clearFocus()` is
> called in the three menu-item `onClick` handlers, not when the menu opens. The dance only bites
> on the navigate path (the field still holding focus when the popup dismisses is what re-pops the
> OSK while the child screen slides in); opening and dismissing the menu with focus intact is
> desirable standard behaviour (type → peek at menu → dismiss → keep typing, OSK returns
> seamlessly). Clearing at item-click kills the re-emergence exactly where it occurs and costs
> nothing elsewhere.

Original analysis: a targeted fix — call `clearFocus()` when the overflow menu opens — kills most
of the §4.5 dance, at the cost that merely dismissing the menu leaves the field unfocused. But the
§6 gate already removes the worst part (return-from-child restore). Only the
transient-while-settings-slides-in part is stock-mechanics noise.

### 7.4 Toolbar hide/compact while IME visible (declined for phase B)

> **Declined September 2026**: landscape is effectively unusable in practice regardless (§9 item
> 6), so reclaiming ~56 dp does not change the picture; the real fix is the parked layout
> investigation (ROADMAP). Revisit only if that investigation proceeds.

Reclaiming the date toolbar (~56 dp) while typing is the one affordable landscape improvement;
it is parked as the ROADMAP "UI / UX polish" phase-B candidate. Few lines, fully reversible; drop
it if it feels wrong in practice. Not a commitment.

### 7.5 Scroll position across day navigation (open, left to taste)

Current: index-based preservation via `rememberLazyListState` (existing comment calls it
"half-reasonable"). Alternatives: reset to top on date change (wrong for comparing a category
across days); anchor-by-category (remember first visible category id, scroll IT to top on the
new date — best for the compare-across-days use case, ~10 lines). Recommendation: keep current
unless it annoys in real daily use; revisit only then. If focus is ever retained across a date
change (§7.2 keep branch), the focus-scroll must win over any policy here.

> September 2026: deferred to real use; the phase-B work did not change this behaviour.

### 7.6 Clearing focus when the app leaves the foreground (resolved September 2026)

> **Implemented September 2026.** A `DisposableEffect` on the home screen's lifecycle owner
> clears focus on `ON_PAUSE` unless the pause is a rotation (`isChangingConfigurations()`).

The home screen is both a viewer and the app's primary editor, which is what makes focus a
liability rather than a help once the user is done with it: a field that kept focus across a
backgrounding would have the OSK re-popped on return, eating the screen they wanted to browse.
The text is already persisted immediately (§2), so clearing focus loses nothing — resuming is
a tap on the field. This is closer to standard Android than the previous behaviour: most apps
come back without the keyboard up, and the user taps to resume.

The one exception is rotation, excluded by `isChangingConfigurations()`. While the app stays
on screen and the phone rotates, the user is still actively typing (or just was), so losing focus
there would break the §6 restore path. That guard is the standard discriminator for exactly this
distinction and is reliable here.

Placement matters, and it is deliberate:

* Scoped to the home screen's lifecycle owner, so it only fires when HOME is the resumed
  destination. Pushing Settings/History/Categories keeps the app RESUMED (no `ON_PAUSE`), so
  intra-app navigation is untouched — and the §6 gate already makes return-from-child not
  re-grab focus anyway (§4.2).
* The observer is disposed when the home screen is navigated away from, so child screens cannot
  accidentally trigger it.
* Dialogs are separate windows and do not fire `ON_PAUSE`, so the date picker cannot clear focus
  mid-selection.
* Split-screen: tapping the other app fires `ON_PAUSE` and clears focus; tapping back returns
  without the OSK. Consistent with the norm.
* Process death while backgrounded is double-safe: `focusedCategoryId` is `rememberSaveable`,
  so the null survives the kill, and the §6 gate finds nothing to restore on the next composition.

A physical-keyboard device is the argument for the *opposite* of "clear focus when the OSK is
dismissed" — a user pressing back to say "no OSK this time, I'll type on a physical
keyboard" must keep
focus. That is why the clear is tied to the app leaving the foreground and not to the IME
state: dismissing the OSK while staying in the app never clears focus. This decision is recorded
here so the distinction is not re-litigated as "why did we clear focus when I dismissed the
keyboard".

Case table for this mechanism alone:

| Event | Outcome |
| --- | --- |
| App leaves the foreground (another app, home, incoming call) | `ON_PAUSE`, not a rotation → focus cleared, OSK hidden on return |
| Rotation (foreground) | `isChangingConfigurations()` == true → no clear; §6 gate restores focus |
| Intra-app navigation (Settings/History/Categories) | No `ON_PAUSE`; §6 gate does not re-grab; focus as left |
| Date picker open | Separate window, no `ON_PAUSE`; focus untouched |
| Dismissing the OSK with back while staying in the app | Focus retained (the physical-keyboard case) |
| Process death after backgrounding | `focusedCategoryId` already nulled by the clear; gate restores nothing |

### 7.7 Resetting a home session already on Home

> **Implemented with the ten-minute threshold consistency fix.** `MainActivity` deliberately
> retains the current home back-stack entry when a long reset happens while Home is already the
> current destination. This avoids needlessly replacing its ViewModel, field states, and scroll
> position. The one stale transient state that must not cross that new-session boundary is the
> `rememberSaveable` date picker: leaving it open would let the user confirm a date selected for
> the previous session and undo the reset. `MainActivity` therefore increments a small in-process
> token in that retained-home case; `HomeScreen` observes it and closes the picker.

From a child route, the reset already replaces the entire back stack, so the newly created Home
screen starts with a closed picker and needs no special coordination. The token deliberately does
not cause Home to be rebuilt, and it does not affect short returns from the background.

This is a small data/state-ownership fix, not a new reset mechanism: the SPEC's rule that a new
session discards transient UI state already covers it.

`HomeScreenSessionResetTest` pins the retained-home picker close, including the negative case that
an ordinary recomposition must *not* dismiss the picker. **UNPINNED:** the MainActivity side — the
`else` branch that increments the token when the current destination is already home — is not
exercised by that test, which drives the token directly. It is a single branch, but a mistake in it
(for example incrementing on the short-return path too) would not be caught there.

---

## 8. Cleanup while in the file

* ~~Promote the seed-once/ignore-re-emissions invariant from the long in-item REVIEW musing to one
  tight comment pointing at §2 of this file; answer the musing's own questions there (yes the
  re-emissions are mostly redundant; yes that is deliberate; no hoops wanted).~~ — done September
  2026: the lookup + `key(...)` + `rememberTextFieldState` now live in the
  `rememberEntryFieldState` helper in `HomeScreen.kt`, whose doc comment states the invariant and
  points at §2; the musing is gone.
* ~~Linear-search REVIEW: fine at this scale (dozens of categories); strike or reduce to one
  line.~~ — done September 2026: struck; the search moved inside `rememberEntryFieldState` with a
  one-line disposition.
* ~~The bottom-of-file TODO (history written on click/cursor-move)~~ and the `saveEntry` inline
  "// TODO: We need a test case to exercise this": both struck September 2026 — the no-op filter
  fixed the behaviour and pinning test 1 (§2.3) now exercises it.
* ~~Debug logs: the screen-level gate redesign is a natural moment to remove the WTAF ones
  early.~~ — done September 2026: all three HomeScreen `WTAF` calls are gone (they were also a
  composition-body side-effect smell). What remains is `Log.d` in `ResetViewModel`, which is
  deliberately kept for now: it is the one place where the 10-minute reset's decisions (should we
  reset, when was the background timestamp, what cutoff did we prune to) are worth being able to
  read off a device. Re-grep for `Log.d` rather than trusting this list.
* ~~TODO.md "Naming niggles ... RENAME markers (newUiState, newDateWithEntries, DateWithEntries)"~~
  — verified September 2026: `HomeViewModel` has `uiStateFlow` / `HomeUiState` / `HomeDateState`
  and no RENAME markers; the TODO.md item is struck.

---

## 9. Phase B verification checklist (manual, emulator, OSK enabled)

> First pass September 2026 (Android 16 emulator, OSK enabled): items 1–3 and 5–8 verified OK;
> item 4 deferred as orthogonal to the focus work (cheap technique noted below). Landscape
> remains effectively unusable on a phone — that is the accepted second-class-layout gap
> (ROADMAP), not a focus defect. The checklist will be re-run as part of manual QA;
> the marks below record the phase-B pass only.

1. Rotation with focus in a field: text, cursor, focus and IME all restored; field scrolled into
   view (now that the IME inset is consumed app-wide). The focused item may snap to the top of the
   viewport rather than merely being revealed — accepted restore-pass behaviour. Confirm on
   an UNPROTECTED day; then confirm a PROTECTED day restores no focus at all.
   — Verified September 2026, both protection states.
2. Return from Settings/Categories/History: NO focus grab, NO OSK re-pop, scroll position where
   you left it (this is the §6 behaviour change — confirm it feels right).
   — Verified September 2026 (quick pass, repeated after the §7.3 menu-item clearFocus landed:
   the dance's OSK re-emergence while a child screen slides in is gone).
3. Scroll a focused field out of view and back: focus does not get yanked back.
   — Verified September 2026 (quick pass).
4. Protection across the 10-minute boundary: lock today, background
    >10 min, return → the screen is exactly as you left it: same day, still locked (a same-day
    expiry is a no-op). Then navigate to yesterday (protected by default), unlock it, background
    >10 min from home, return → the date resets to the current day AND arrives unlocked (the
    cross-day fix: the override dies with the date movement). Short background with a lock set:
    lock survives, as intended. (A field can no longer be focused at a cross-day expiry: §7.6
    clears focus whenever the app leaves the foreground, so the §7.2 "retain focus over the new
    day" case is moot — there is nothing left to retain.) The new-session boundary also closes an
    open home date picker (§7.7), so the "exactly as you left it" wording here applies to the
    date/protection state rather than to that deliberately discarded transient dialog.
— Deferred September 2026: the underlying logic is pinned by ResetViewModelTest
    (the override-clear and same-day no-op halves); this item is a feel-check. Cheap technique
    when run: temporarily set ResetViewModel.AUTO_RESET_TIMEOUT_MS to ~15s, exercise both
    scenarios, revert (never commit the temporary value).
5. Type in the bottom category, portrait: typed text visible above OSK (container bottom edge
   behind OSK is normal, §5). — Verified September 2026.
6. Landscape: entry remains possible in the thin band; toolbar-hide experiment evaluated (§7.4).
   — Verified September 2026: entry possible but effectively unusable on a phone emulator, as
   expected; §7.4 declined (see its banner).
7. Date picker: stays open across rotation, in-dialog selection survives (phase A work — re-confirm
   once focus logic is touched). Rotation with the picker OPEN may restore focus behind the
   dialog — accepted edge, just confirm it is not jarring.
   — Verified September 2026: picker survives rotation; no focus-behind-dialog oddity observed.
8. Toggle protection while a field is focused: locking mid-edit (OSK left up over a read-only
   field?) and unlocking a protected day (OSK pops unexpectedly?). Neither is currently considered
   wrong; if either annoys, the fix is one `clearFocus()` call in the toggle handler (noted
   September 2026, deliberately not pre-fixed).
   — Observed September 2026 and ACCEPTED (see §10): lock mid-edit closes the OSK; unlock while
   focused re-opens it at the existing caret; clicking a read-only field focuses it (indicator
   bar, no OSK), and unlocking then opens the OSK there. All standard platform mechanics given
   that read-only fields stay focusable — which is what makes select/copy work (SPEC).
9. Backgrounding clears focus and the OSK does not re-pop on return. Tap a field → OSK appears →
   press Home → return (any duration) → no OSK, no focus, the screen is a clean browse, and
   tapping a field re-focuses it. — Deferred September 2026 to this change (§7.6); verify on an
   OSK-enabled device, both portrait and landscape, and confirm rotation still preserves focus
   (the §6 gate must not have been broken by the `isChangingConfigurations()` guard).

---

## 10. Settled — do not re-litigate without new evidence

* Caret-vs-container-edge above the OSK is normal (§5).
* Per-keystroke DB read is the standing cost of read-decide-write; no cache layer (§2.2).
* Foreground-frozen across the day-start boundary is deliberate policy (SPEC, "Returning to the
  application").
* Immediate persistence (no debounce) for main entries, debounced history: `HomeViewModel` header.
* Landscape phone layout is second-class by decision; redesign investigation parked in ROADMAP.
* `maxEntryLength` no-op commits still calling `onTextChanged`: documented, accepted.
* Protection override semantics (persisted DataStore key; dropped only by a date movement;
  same-day auto-reset is a no-op): settled September 2026 after an extended adversarial review.
  Do not re-propose in-memory storage or unconditional same-day clears.
* Focus restoration happens on configuration change only, never on child-screen return, recycling
  or enable/disable, and never on a protected day (§6, September 2026).
* **Focus is cleared when the app leaves the foreground** (§7.6, September 2026): `ON_PAUSE`
  clears it unless the pause is a rotation. Rotation is the only case where focus is preserved
  across leaving the screen. Dismissing the OSK with back while staying in the app does NOT
  clear focus — that is the physical-keyboard case, and it is the reason the clear is tied to
  backgrounding rather than to the IME state.
* Protection-toggle focus behaviour (lock mid-edit closes the OSK; unlock while focused re-opens
  it at the existing caret; read-only fields stay focusable so select/copy works, per SPEC):
  observed September 2026, accepted as standard platform mechanics (§9 item 8). If
  unlock-while-focused popping the OSK ever annoys in real use, the fix is one `clearFocus()`
  call in the protection toggle handler.
