# State preservation and process death

This document is a refresher on how Android state preservation works in general, and
specifically how this app (Dayfile) has chosen to handle it. It is written for a
competent but not expert Compose developer.

It exists because the app's choices here are deliberate and non-obvious, and the justification is
scattered across `HOME_SCREEN_NOTES.md` and inline comments. If you are asking "does this survive a
background kill?", this is the place to look. (There was once a numbered `docs/DECISIONS-*.md`
decision log. It has since been removed from the repository, and its substance folded into
documents like this one. Where this document says "recorded in the decision log", read it as
"on date X we decided Y" — a past decision that may have been revised since. If it contradicts the
code, the code wins.)

## 1. The kinds of "state" in an Android/Compose app

When people say "state", they usually mean one of several quite different things.
Confusing them is the root of most of the subtlety in this area.

### 1.1 Transient UI state (per-frame)

Owned by the composition itself. Recomputed or recreated on every recomposition.

* `remember { ... }` (without `saveable`).
* Examples in this app: the `DropdownMenu` expanded flag on the category edit screen
  (`capitalizationExpanded` in `CategoryAddEditScreen`), the `FocusRequester` instances on the
  home screen.

**Lifetime:** dies when the composable is removed from the composition, or when the
process dies. Nothing important is ever stored here.

### 1.2 Saved instance state (survives configuration change and process death)

The framework saves a bundle for you on `onSaveInstanceState`, and restores it on the
next creation. In Compose this is exposed as `rememberSaveable`, which uses a built-in
`Saver` (or your own).

**Lifetime:** survives rotation, and **survives process death** (including
`adb shell am kill` and OS low-memory reclaim). The saved bundle is held by the
`ActivityManager` rather than by your process.

* Examples in this app:
  * `showDatePicker` in `HomeScreen` — the date picker dialog visibility.
  * `focusedCategoryId` in `HomeScreen` — which home entry field held focus.
  * `restoredOrientation` in `HomeScreen` — the orientation token behind the
    focus-restore gate (see §4).
  * `scrolledSignature` in `HistoryScreen` — lets the scroll-to-top effect
    distinguish "same items, just rotated" from a real change.
  * `autoCorrect` and `capitalization` in `CategoryAddEditScreen` — the category
    name, auto-correct and capitalization on the add/edit screen.

**The important caveat:** saved instance state is *not* a database. It is held in
memory by the system in the process that launched your app, and it is discarded when
that process goes away for good — in practice, when the task is removed, since the
bundle's lifetime is tied to the task (see `SESSION_STATE_LIFETIMES.md` §1). It is
also not guaranteed across arbitrary events — it is specifically for the activity
recreation case. Do not read "survives process death" as "is guaranteed to survive
arbitrary process termination" (`COMPOSE_LIVE_EDITING.md`, §"A note about saved state
and durability").

### 1.3 ViewModel state (survives configuration change, dies with the process and the
back-stack entry)

A `ViewModel` survives rotation and child-screen navigation (it is scoped to a
back-stack entry). It does **not** survive process death. When the process is killed,
every `ViewModel` in the app dies with it; on relaunch, fresh ones are constructed.

* Examples in this app: `HomeViewModel`, `HistoryViewModel`, `SettingsViewModel`,
  `CategoryEditViewModel`.

This is where the app's most important design decision lives: **the app deliberately
does not rely on ViewModels surviving process death.** Instead, anything that must
survive is written to disk (Room or DataStore) *before* it is exposed through a
ViewModel. See the `AppStateRepository` KDoc:

> "Holds app-level UI state that must survive process death reliably (unlike
> SavedStateHandle)."

### 1.4 Durable state on disk (survives everything)

Room databases and `DataStore` (preferences). These survive process death, swipe-away,
and app uninstall/reinstall (for DataStore) or reinstall-with-backup (for Room).

* Examples in this app: every typed entry, every history snapshot, every category,
  the selected date, the protection override, the history filter, all user settings.

## 2. What "process death" actually means, and the four cases

"Android killed the app in the background" is not one thing. There are four distinct scenarios,
and this app treats them differently. The question of which is which is taken up properly in
`SESSION_STATE_LIFETIMES.md` §1 and §6; this section is about what each one means to the code.

### 2.1 The app is backgrounded, then the process is killed

This is the case your testing simulates: press Home/Back (or navigate away), then
`adb shell am kill`.

* `ON_PAUSE` fires. The app is still alive, just not visible.
* `ResetViewModel.onMoveToBackground()` records the current timestamp in the activity's
  saved-state bundle (`ResetViewModel.kt`).
* The process is killed.
* On relaunch, `ON_RESUME` fires, `onReturnToForeground()` reads the timestamp, and
  the 10-minute grace period is measured against **real wall-clock elapsed time** —
  exactly as if the process had survived.

**This is the case the app handles best, and it is the whole point of persisting the
background timestamp.** The principle, recorded when the background timestamp was first persisted
(there is no longer a decision log in the repository, so read this as "on date X we decided Y"):

> "process death (user swipe-away or OS low-memory kill) is invisible to the user and
> must not alter behaviour. The background timestamp is therefore persisted; the
> 10-minute grace is measured by real wall-clock elapsed time since the app was last
> in the foreground, not by whether the process happened to survive."

So a 20-minute background followed by a kill behaves identically to a 20-minute
background followed by a resume: the date resets to today and the session restarts on
home.

### 2.2 The process is killed while in the foreground (no ON_PAUSE)

The process is killed without the app ever being backgrounded. This happens for:

* OS low-memory reclaim while the app is visible (rare, and the user is looking at it).
* An app upgrade or incremental re-deploy.
* **The user swiping the app away from the overview screen.**

In none of these cases does `ON_PAUSE` fire, so **no background timestamp is recorded**.

`ResetViewModel.kt`:

> "Process death while the app was still in the foreground. This does NOT fire
> ON_PAUSE first, so no timestamp is recorded. ... The reset here is deliberate:
> without a timestamp we cannot tell 'was away' from 'was here', so we assume
> 'fresh'. For swipe-away-from-overview this is even the desired outcome — the user
> has discarded the app, so restarting on today is right."

So: a null timestamp means "treat as a fresh session". For swipe-away this is correct;
for an OS reclaim mid-use it means "start on today", which is the same thing a cold
start does. Nothing is lost that could not be recovered — committed entries and
settings are on disk.

**Note:** "swiped away while foregrounded" and "swiped away while backgrounded" are now the same
case. A swipe-away removes the task, which discards the saved-state bundle whether or not a
timestamp had been written to it, so both arrive fresh. The distinction that used to matter —
whether `ON_PAUSE` had fired before the swipe — no longer affects the outcome.

### 2.3 Configuration change (rotation, split-screen, font-size change)

The process does not die. The activity is recreated. ViewModels survive (scoped to the
back-stack entry). Saved instance state is restored. This is the case `rememberSaveable`
and the focus-restore gate are really for, and it is the case the platform guarantees.

### 2.4 The app is never killed at all

Normal pause/resume. `onMoveToBackground` records a timestamp, `onReturnToForeground`
clears it and, if less than the timeout elapsed, does nothing (`ResetViewModel.kt`).

## 3. Why SavedStateHandle was deliberately not used — and now is

You may wonder why the app persists the selected date, the protection override and the
history filter to DataStore rather than to `SavedStateHandle` (the ViewModel-flavoured
cousin of `rememberSaveable`). Three reasons, all documented:

1. **SavedStateHandle is not reliable enough for this purpose.** Its documented
   contract is that it survives process death *in the activity recreation case*, but
   it is not a general durable store. `COMPOSE_LIVE_EDITING.md`, §"A note about saved
   state and durability":
   "saved instance state should not be treated as the application's durable store, nor
   should 'survives process death' be interpreted as 'the text is guaranteed to survive
   arbitrary process termination.'"

2. **It is shared across the whole process, which makes correctness harder, not
   easier.** The `AppStateRepository` KDoc notes it is "intentionally separate from
   SettingsRepository" — the app's UI state is kept separate from user preferences so
   the two cannot be confused.

3. **DataStore gives the app a single, testable, observable source of truth.** Every
   piece of app state is a `Flow` from DataStore, so the ViewModel just combines
   flows. This is what makes the self-healing history filter possible
   (the filter `combine` in `HistoryViewModel`) and what makes the protection override's persistence
   deterministic (the override is a DataStore key in `AppStateRepository`, dropped
   atomically inside `setSelectedDate`, so it survives process death deterministically
   rather than depending on whether the process happened to be reclaimed).

**The background timestamp is the exception that proves the rule.** It is *session
signal*, not durable user data: its correct lifetime is tied to the task, not to the
process. DataStore outlives the task, which is exactly the wrong container for it —
persisting it there is what made "swiped away while foregrounded" and "swiped away
while backgrounded" behave differently. It now lives in `SavedStateHandle` via
`ResetViewModel`, which is discarded with the task on swipe-away. Everything else in
this section still holds; only the timestamp moved.

The one place `SavedStateHandle` is mentioned in the codebase is in comments explaining
why it was *not* chosen for the durable state (the notes at the top of `HomeViewModel` and
`HistoryCaptureHelper`) — and now in `ResetViewModel.kt`, where it is chosen for
the one piece of state whose lifetime is task-bound rather than process-bound.

## 4. The trickiest part: the home screen focus-restore gate

This is the one piece of logic that looks like state preservation but is actually a
heuristic, and it is worth understanding because it is easy to misread.

The home screen's entry fields can hold focus. On rotation, the platform restores the
focused view's ID (in the classic View framework), but Compose's `TextFieldState`
restores *text and cursor*, never *focus*. So the app hand-rolls focus restoration.

The problem: Compose offers no way to tell *why* a composable is being recomposed.
Rotation, returning from a child screen, process death and LazyColumn viewport
recycling all produce an identical fresh composition. If the app simply restored focus
whenever `focusedCategoryId` pointed at the current item, it would re-grab focus on
every one of those events — including on return from Settings (the "OSK dance") and
when the auto-reset changes the date.

The solution is a gate (the focus restoration gate in `HomeScreen`, which
`HOME_SCREEN_NOTES.md §6` discusses in full): a `rememberSaveable` orientation token
compared against the current configuration, open only on the first composition after a genuine
configuration change.

* `focusedCategoryId` (`rememberSaveable`) — which field held focus.
* `restoredOrientation` (`rememberSaveable`) — the orientation as of the last
  save/restore pass.

The gate is open only when `restoredOrientation != currentOrientation`, i.e. on the
first composition after a **genuine configuration change**. Every other fresh
composition finds it closed.

The rule, in one line (the gate comment in `HomeScreen`):

> **focus exists only where the user put it or a configuration change restored it.**

Consequences (see `HOME_SCREEN_NOTES.md §6` for the full case table):

* Rotation restores focus and scrolls the field into view (on unprotected days).
* Process death, child-screen return, viewport recycling and category enable/disable
  **never** restore anything.
* Protected days never restore focus.

This is a heuristic, not a platform guarantee. It is pinned by instrumentation tests
for the "no restore" cases, but rotation restore itself remains manual QA. If you are changing
this area, read `HOME_SCREEN_NOTES.md §3` and `§6` first.

## 5. What is deliberately NOT preserved

Not everything should survive. The app's stance is `DEVELOPMENT.md`, "State ownership", summarised:

* **ViewModel (or repository):** app state whose silent loss would lose user data or
  break an in-flight operation. Survives configuration changes; dies with its
  back-stack entry.
* **`rememberSaveable`:** UI chrome whose silent loss would confuse the user — dialog
  visibility, half-typed dialog input, picker state.
* **`remember`:** per-frame caches that can be recomputed without confusing anyone.

Deliberately lost on process death:

* **In-flight async work outcomes.** `SettingsViewModel` keeps its restore state
  (`_restoreState`) in the ViewModel, not in `rememberSaveable`, because a saved
  "in progress" flag would revive into a non-dismissable dialog with no work behind it
  (`_restoreState` in `SettingsViewModel`). The fresh composition correctly starts from Idle.
* **The protection override, before it was persisted.** This used to be in-memory only, so
  whether a manual lock survived a background kill depended on whether the process happened to be
  reclaimed at the same moment — a behavioural fork invisible to the user. It is now a DataStore
  key in `AppStateRepository`, dropped atomically inside `setSelectedDate`, so the behaviour is
  deterministic: a manual lock survives until the next date movement, and a same-day auto-reset
  writes nothing, so a lock on today survives any absence until the user navigates.
* **The history filter, before the reset.** Cleared by the 10-minute reset, which is the
  mechanism that keeps session state from leaking across long absences.

## 6. The one real gap: the history debounce window

This is the only place where a background kill can lose something the user might care
about, and it is worth understanding precisely what is and is not at risk.

* **Main entry text is written immediately, with no debounce**
  (the note on immediate entry persistence at the top of `HomeViewModel`). Type "foo123" and
  "foo123" is in the database before you
  lift your finger. A background kill cannot lose it.

* **History (the undo safety net) is debounced by 400ms**
  (the `debounce(SAVE_DEBOUNCE_MS)` pipeline in `HistoryCaptureHelper`). If the process is killed
  within 400ms of the last keystroke, the history snapshot for that burst of typing is
  lost. The main entry survives, but the undo record does not.

This was considered and explicitly accepted (the note on history de-bouncing at the top of
`HomeViewModel`):

> "We do *not* attempt to accommodate the double Murphy situation here where the user
> fumbles the phone and somehow the app crashes or Android kills it before the debounced
> history gets written to the database. This would be possible, but the extra complexity
> is unlikely to be thoroughly tested and adds the risk of subtle bugs that are more
> harmful than the low probability death of the app with unpersisted history."

And again at the top of `HistoryCaptureHelper`.

**Mitigation considered and deliberately not taken:** `HistoryCaptureHelper` exposes a
hook — `offerEntryText` pushes onto a `MutableSharedFlow` that the debounce pipeline
consumes (the same `debounce` pipeline in `HistoryCaptureHelper`). Flushing that pipeline on
`onMoveToBackground` (before recording the background timestamp) would convert the
"kill within 400ms of typing" case into the "kill after typing finished" case, which
the database already handles. It is a small, low-risk change because it reuses the
existing write path rather than adding new state machinery.

**It was not implemented**, and the reasoning is at the top of `HomeViewModel` and of
`HistoryCaptureHelper`: the extra complexity is unlikely to be thoroughly
tested and adds the risk of subtle bugs that are more harmful than the low-probability
death of the app with unpersisted history. The accepted residual is a kill that lands
*during* the flush itself, which the existing write path already handles as well as it
handles the normal debounce case.

## 7. Quick reference: does it survive?

| What | Rotation | Background + kill | Foreground kill / swipe-away | Uninstall |
|---|---|---|---|---|
| Typed entries (Room) | yes | yes | yes | no (backup only) |
| History snapshots (Room) | yes | yes, except <400ms window | yes | no |
| Categories (Room) | yes | yes | yes | no |
| Selected date (DataStore) | yes | yes | resets to today | no |
| Protection override (DataStore) | yes | yes | resets to default | no |
| History filter (DataStore) | yes | yes, cleared after 10min | resets | no |
| Settings (DataStore) | yes | yes | yes | no |
| Dialog/picker open (`rememberSaveable`) | yes | yes | no | no |
| Focused field id (`rememberSaveable`) | yes | yes (ignored on restore) | no | no |
| In-flight restore work (ViewModel) | yes | no | no | no |
| **Background timestamp (`SavedStateHandle`)** | cleared on resume | **yes (task kept)** | **no (task gone)** | no |

The background timestamp is the one piece of state whose lifetime is *task-bound* rather than
*process-bound*, which is why it is the only thing here in `SavedStateHandle` and not in DataStore.
"Background + kill" preserves it (the task survived the kill), so a long background still resets on
return; "Foreground kill / swipe-away" discards it (the task is gone), so both arrive fresh. That
last column is the case that used to fork: a DataStore timestamp would have survived a swipe-away
taken after a backgrounding, preserving a non-today date instead of starting fresh.

## 8. Restore state and the required restart boundary

Manual restore deliberately replaces only the main database, not history or either
DataStore file, and a successful replacement is followed by an app restart.

The restart is load-bearing rather than cosmetic: `BackupRestoreHelper.restore()` closes
the current Room instance before the final file replacement, and clearing
`MainDatabase.INSTANCE` only permits a *future* `getDatabase()` call — it does not replace
the `MainDatabase`, DAO, repository and ViewModel references already held by the activity and
navigation back stack. Those belong to the closed instance, so a new process is the only way
to rebuild them.

The analysis, including a separate post-close replacement-failure path that
this boundary does not currently cover, is in `RESTORE_SAFETY_NOTES.md` §10. Both that path and
the staging ordering around it are now fixed — that §10 is where both are described. No restore
production behaviour changes here.
