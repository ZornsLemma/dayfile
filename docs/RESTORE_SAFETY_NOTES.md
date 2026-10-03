# Restore safety notes

This document explains a subtle, hard-to-reproduce bug that can occur when the
user restores the app's database from a backup, and the options for fixing it.

The bug in §3 has since been fixed; §10 covers what changed and why. Sections 2-9 are the
analysis that led to the fix and are kept as written, so the reasoning stays legible — read them
for the "why", and §1 plus §10 for what the code does now.

It is written for a competent but not expert Compose/Android developer, and also
for a future LLM working on this codebase. It assumes you know Room, Jetpack
Compose, ViewModels, and how SQLite/WAL works at a basic level.

## 1. The restore flow today

The app can back up and restore its main database (main.db) through the
Storage Access Framework. See `BackupRestoreHelper.restore()`.

The relevant part of the flow is:

    val liveFile = context.getDatabasePath(databaseName)
    // validate the candidate, while Room is still open
    ...
    // Stage the replacement as a sibling of the live file. This happens BEFORE
    // db.close() so that a failure here leaves the live database untouched and usable.
    tempFile.copyTo(stagedFile, overwrite = true)
    db.close()                          // <-- the interesting line
    MainDatabase.resetInstanceForRestore()
    stagedFile.renameTo(liveFile)

The ordering here is the September 2026 fix; §10 records what it was before and why it mattered.
The property that makes the current order safe is that `copyTo` is the only step that can
realistically fail (out of space, quota, permissions), and it now happens while Room is still open,
so such a failure is an ordinary error the app carries on from. `renameTo`, by contrast, is atomic
within a filesystem and its failure is not recoverable in-process — see §10.

After a successful restore, the UI asks the app to restart so Room reopens
against the new file.

## 2. Why db.close() is there

SQLite in WAL mode keeps three files: main.db, main.db-wal, main.db-shm.

If you rename a new file over main.db but leave the old -wal/-shm in place, the
next time SQLite opens the restored main.db it sees the old WAL and recovers
those old frames into the new file. That silently corrupts the restore.

So something has to neutralise the WAL sidecar before the rename. db.close()
does that: it checkpoints and removes the sidecars. That is the entire reason
close() is there.

## 3. The bug

Room 2.8.5 added a check: throwIfClosed() is now called when a Room Flow is
CREATED (i.e. when the DAO query method runs and registers with the
InvalidationTracker), not only when it is collected or invalidated.

In this app, HomeViewModel.uiStateFlow is:

    .stateIn(viewModelScope, SharingStarted.Eagerly, null)

over a chain whose inner hop is:

    entryDao.observeEntriesForDate(state.date)   // inside flatMapLatest

So every time the outer combine emits, the Room flow is re-created, and
throwIfClosed() runs at that moment.

HomeViewModel is created in the "home" composable and scoped to the home back
stack entry. Navigating to Settings pushes it on top, so HomeViewModel stays
alive while the user restores.

During restore, db.close() marks the database closed. If anything then emits
into one of HomeViewModel's dateStateFlow inputs, the combine re-runs,
flatMapLatest re-creates observeEntriesForDate on the closed database, and
throwIfClosed() throws:

    java.lang.IllegalStateException: Database is closed
        at androidx.room.InvalidationTracker.createFlow(...)

That is the crash.

## 4. How narrow is the window really?

HomeViewModel's dateStateFlow combines three inputs:

    appStateRepository.selectedDate
    settingsRepository.dayStartTime
    appStateRepository.protectionOverride

Each is written only from a small number of places:

    setSelectedDate       -> HomeViewModel.setSelectedDate (home screen),
                             HomeScreen callbacks (home screen),
                             ResetViewModel.onReturnToForeground (ON_RESUME)
    setDayStartTime       -> SettingsViewModel.updateDayStartTime (settings screen)
    setProtectionOverride -> HomeViewModel.setProtection (home screen only)

So during a restore, the only things that could emit into dateStateFlow are:

  a) the activity resuming (background -> foreground), which fires
     ResetViewModel.onReturnToForeground and may call setSelectedDate, or
  b) the user tapping the day-start setting on the settings screen while the
     restore is in flight.

Restore runs on Dispatchers.IO and is always followed by an app restart, so
this is a very narrow window. It is not something that has been observed in the
wild. It is a theoretical exposure, and the only production path that closes
the database at all.

The home screen is the app's start destination and is always on the back stack,
which is why HomeViewModel is the one that is exposed. If the restore flow ever
changed to close the database differently, or a future screen started writing
one of these three keys, the exposure would grow.

## 5. Why removing Eagerly would not fix it

You might think: if I switch stateIn to WhileSubscribed(5000), the pipeline
only stays alive for five seconds after the last collector leaves, so the
window shrinks.

It does not eliminate the bug. The user can still navigate home -> settings
within five seconds and start a restore in that window. The pipeline would
still be alive, the database would still get closed, and the same crash would
fire.

It would also reintroduce the return-to-home jank that the `SharingStarted.Eagerly` comment above
`.stateIn` in `HomeViewModel` deliberately weighed and rejected: the whole point of
Eagerly is to pre-compute state so returning to the home screen is jank-free.
That is a good trade for this app, and the eager pipeline staying alive while
backgrounded is desirable, not wasteful - it means a background write (the
ten-minute date reset, history prune) is reflected as fresh state by the time
you return.

The real issue is not that the pipeline is eager. It is that the pipeline's
lifetime is decoupled from the database's lifetime: the pipeline outlives the
database it depends on. That mismatch only opens up when something closes the
database out from under a still-living ViewModel.

## 6. Option: catch the exception in the pipeline

Wrap the HomeViewModel flow chain so that the closed-database exception
degrades to "no data" instead of crashing:

    .flatMapLatest { ... }
        .catch { emit(null) }     // or similar
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

The screen already renders blank when uiState is null (the `if (uiState != null)` guard in
`HomeScreen`), so
this would show a blank home screen for the brief restore window. Harmless,
because restore is always followed by an app restart, after which a fresh
HomeViewModel is built.

Why this is not great:

* It masks the symptom rather than the cause. The pipeline is still pointing at
  a closed database; you are just choosing not to crash on it.
* It adds error handling to the core home screen for a case that essentially
  never happens. That is noise in the most important screen of the app.
* Getting the exception plumbing right around stateIn is fiddly. Exceptions
  thrown inside a stateIn-collected pipeline propagate into the scope in ways
  that are easy to get wrong, and you would want to verify empirically that the
  catch actually suppresses the throw and does not, say, cancel the whole
  pipeline permanently. Hard to verify, and easy to leave behind something that
  rots.

Verdict: works, but fiddly and a band-aid. Not recommended unless you want
something tiny and are willing to accept the masking.

## 7. Option: checkpoint in place instead of closing

Replace db.close() with an in-place WAL checkpoint that neutralises the
sidecars without marking the database closed:

    // PRAGMA wal_checkpoint(TRUNCATE) via openHelper.writableDatabase
    // delete main.db-shm
    // then rename the restored file over main.db

Because the database is never marked closed, throwIfClosed() passes when the
pipeline re-creates the flow. The live HomeViewModel keeps its connection open
against the old inode (stale data, but harmless - the app restarts anyway).

Why this is risky:

* Correctness depends on subtle WAL runtime behaviour: whether a debounced
  history write or a stray write during the restart window could land in the
  WAL and then get recovered into the restored file after restart.
* The current code has careful comments about atomicity and stale handles
  (the class KDoc and inline comments in `BackupRestoreHelper`, including the note that `renameTo`
  is atomic only within a filesystem). This steps into that area.
* You cannot easily verify it, and even if you do today, the behaviour could
  change with a future SQLite/Room version.

Verdict: clever, small, and attacks the crash at its root (no close means no
throwIfClosed). But it trades a one-in-a-million crash for a correctness
dependency on WAL semantics you cannot easily verify, and that dependency
endures. If you are uneasy about it now, you will probably be uneasy about it
later too, when the code has evolved and the original reasoning is gone.

## 8. Option: double-buffer the database (recommended if you want to fix it)

This is the one that actually makes the crash impossible rather than unlikely.

The idea: keep two database files, main-1.db and main-2.db (plus their
-wal/-shm), and a small persistent record saying which one is active. The app
opens the active one. A restore writes into the *inactive* file, flips the
record, and the app restarts. The open database is never closed, never
checkpointed, never touched.

### Why this sidesteps the WAL concern

The worry with the checkpoint-in-place option is that something might touch the
open database after you checkpoint, and stale WAL frames could contaminate the
restored file. With double-buffering you never touch the open database at all -
you write into a completely separate file that is not currently in use. There
is no WAL to worry about for the active file, because you are not modifying it.
That is the whole point, and it is why this option does not depend on WAL
runtime behaviour you cannot verify.

### How it would work

Since a restore is always followed by an app restart, the singleton cache does
not even matter during the restore itself - the process dies, INSTANCE is null,
and on restart getDatabase() just needs to read the new active file.

1. Two files: main-1.db and main-2.db (plus sidecars).

2. A persistent "active DB" record. The existing app_state DataStore already
   survives process death and is the natural place for it. getDatabase(context)
   reads it, builds Room against that file, and caches the instance.

3. Restore (BackupRestoreHelper.restore):
   - delete main-2.db and its -wal/-shm (the inactive file - it must be clean so
     no stale sidecar contaminates the restore),
   - write the backup into main-2.db,
   - set the active record to 2,
   - trigger the restart (the existing RestartRequired flow).
   The open main-1.db connection is never closed, never checkpointed, never
   touched. throwIfClosed() is structurally impossible to fire.

4. On restart: getDatabase() reads active=2, opens main-2.db (a clean file,
   no WAL contamination). Optionally, best-effort delete main-1.db and its
   sidecars for disk space and schema hygiene. This deletion is safe: if it
   fails or the app crashes mid-way, the next restart just finds a dead-weight
   file pointing at the wrong number, which is harmless.

### Costs

* Disk: up to 2x the main database temporarily, until the inactive one is
  deleted on restart. For an app whose data is categories plus text per day,
  this is modest.
* Schema hygiene: the "delete inactive on restart" step is not just
  disk-saving - it ensures the inactive file is always fresh, so a future
  schema migration cannot leave one file lagging behind the other. Worth
  keeping even if you do not care about disk.
* Test hook: BackupRestoreHelper.restore currently takes a databaseName
  parameter so instrumented tests can use a scratch database (see the comment above
  `TEMP_RESTORE_FILE` in `BackupRestoreHelper`). With double-buffering the restore target
  is the inactive file, driven by the active record, so that hook needs
  reworking. Doable, but a wrinkle.
* More code than the other options: MainDatabase companion changes, the
  DataStore key, the restore rewrite, and the restart-time cleanup.

### Why this is the recommended option

It is double-buffering, a standard, well-understood pattern. Its correctness
rests on simple file management that you can reason about, not subtle runtime
WAL semantics. It makes the crash impossible rather than unlikely. And it does
not add error handling to the core home screen or change the home screen's
performance characteristics.

## 9. Option: leave it alone (current decision)

The test fix that accompanied this investigation is complete and correct, and
it covers everything that was reported. Production is left as-is.

The restore exposure is very narrow: restore runs on Dispatchers.IO, is always
followed by an app restart (so the old process and its ViewModels die
regardless), and the only things that can emit into the failing pipeline
during that window are the activity resuming or the user tapping the
day-start setting in that exact moment.

If this ever needs addressing, the double-buffering option above is the one
worth reaching for.

## 10. A separate post-close replacement-failure path (FIXED September 2026)

This is distinct from the narrow `throwIfClosed()` race in §3, and was found
later, during a September 2026 code review.

### The problem as it was

`restore()` used to call `db.close()` and *then* copy the validated replacement
into its staging path. A comment at the time claimed that if the rename failed,
"Room reopens against it lazily" — that was wrong. Room does not reopen a closed
instance: clearing `MainDatabase.INSTANCE` only lets a *future* `getDatabase()`
call build a new one, and cannot repair the `MainDatabase`, DAO, repository and
ViewModel references already handed out.

So any failure at or after the staging copy left the process holding a closed
dependency graph while the UI reported an ordinary dismissible error. The old
live file was still intact — `renameTo` returning `false` means the rename never
happened, and the rejected bytes sat in the staging file until the `finally`
block deleted them — so there was **no data-loss path**. The realistic outcome
was: error dialog, then a crash on the next database-backed screen, then a clean
relaunch against the intact old file. Self-healing, but a crash the user could
not have anticipated, after being told the app had recovered.

### The fix

1. **The staging copy now happens before `db.close()`.** The copy is the only
   step in the whole restore that can realistically fail in the field (no space,
   quota, permissions). While Room is still open, a failure there is an ordinary
   error: the live database is untouched and every reference to it stays usable,
   so the app carries on completely unaffected. `db.close()` must still precede
   the rename — it checkpoints and drops the WAL/SHM sidecars — but by then the
   only remaining step is a same-directory `rename(2)` on a file that has just
   been written successfully, which is very unlikely to fail.

2. **A genuine post-close failure now asks for a restart instead of pretending
   it recovered.** `BackupRestoreHelper.restore` tracks whether it has closed the
   database and, if a later step throws, rethrows as `RestoreClosedDatabaseException`.
   `SettingsViewModel` catches that separately and sets
   `RestoreState.RestartRequired(restoredDataApplied = false)`. The dialog is the
   same non-dismissable "app will restart" one, but the wording changes: it says
   the restore could not be completed and the existing data is still there. The
   success case passes `true` and keeps the original "applying restored data"
   message. This is the distinction the old design could not make — it had no way
   to tell the user "your data was not restored, and we are restarting to recover".

### Pinned by

`BackupRestoreHelperTest.stagingFailureBeforeCloseLeavesTheLiveDatabaseOpenAndUsable`
and `BackupRestoreHelperTest.renameFailureAfterCloseDemandsARestartAndCleansUpStagedFiles`.
Both force a real failure — a directory where the staging file must be created,
and a directory where the live database must be renamed onto — rather than
asserting on internals, so no file-operation seam was needed in production code.

### Still not done

The §3 race itself is untouched and still decided against (§9): it is a different
bug with a different trigger, and the double-buffered design in §8 remains the
answer if it ever needs one. Nothing about this section changes that.

