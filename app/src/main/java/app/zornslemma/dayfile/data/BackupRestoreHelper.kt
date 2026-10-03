package app.zornslemma.dayfile.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import app.zornslemma.dayfile.R
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException

/**
 * Handles local backup and restore of the Room database using SQLite's VACUUM INTO and a validation
 * step on restore.
 *
 * Backup writes a clean copy of the database to a user-selected document via the Storage Access
 * Framework. Restore streams a user-selected document to a temp file, validates it, and atomically
 * replaces the live database file (staged sibling copy, then rename).
 *
 * All user-facing error text is carried by [UserVisibleException] (@StringRes plus args) and
 * resolved by the UI layer; the English message strings in this file are debug diagnostics for
 * logcat and are deliberately not translated.
 *
 * This object does not manage the Android process restart. The caller is responsible for restarting
 * the app after a *successful* restore, so that Room reinitialises against the new file without
 * lingering handles - and also after a *failed* restore that arrives as a
 * [RestoreClosedDatabaseException], which means the live file was kept but the process is now
 * holding a closed Room graph. Both cases are signalled by throwing rather than by a return value;
 * ordinary pre-close failures throw the usual [UserVisibleException].
 *
 * ## Scope: main.db only
 *
 * This helper backs up and restores **only the main database** (`main.db`: categories and entries).
 * It does **not** cover:
 * * `history.db` — the undo safety net. Restore leaves this separate database untouched, so
 *   pre-restore history remains available. Its category IDs/names are preserved in history_category
 *   and can therefore continue to describe the old category, even when the restored main database
 *   uses different category IDs.
 * * The two DataStore preference files (`settings`, `app_state`) — day-start time, retention days,
 *   CSV BOM, the default-categories flag, the selected date, the protection override and the
 *   history filter. They are also left untouched, so the device's current settings/app state is
 *   retained rather than being replaced.
 *
 * This is a deliberate simplification, but it is an inconsistency a user can trip over: the Android
 * auto-backup (`android:allowBackup="true"` with empty `fullBackupContent`, i.e. "back up
 * everything") covers all of the above, while the in-app manual backup covers only `main.db`. So a
 * manual backup is not a complete portable app backup: it does not contain the history or settings
 * that auto-backup can preserve.
 *
 * Known consequence of restoring main.db alone: the restored file's category IDs are whatever the
 * backup contained, and `history.db` still references the old IDs, so history entries keep
 * displaying against their archived category names (the history database denormalises category
 * names precisely so this degrades gracefully rather than breaking). Nothing crashes; it is just
 * that the history view shows the pre-restore history. Acceptable for now — see
 * `STATE_PRESERVATION_AND_PROCESS_DEATH.md`.
 */
object BackupRestoreHelper {

    private const val TEMP_BACKUP_FILE = "backup_temp.db"

    // Internal rather than private only so the instrumented failure-path test can place its
    // blocker at exactly this path instead of hardcoding a copy of the name (a rename of this
    // constant would otherwise silently stop testing anything). Nothing in production varies it.
    internal const val TEMP_RESTORE_FILE = "restore_temp.db"

    private val EXPECTED_TABLES = setOf("category", "entry")

    /**
     * Exports the current database to the given URI. Uses VACUUM INTO to produce a compact,
     * consistent snapshot, then copies it to the output URI obtained from the Storage Access
     * Framework.
     */
    fun backup(context: Context, db: MainDatabase, uri: Uri) {
        val tempFile = File(context.cacheDir, TEMP_BACKUP_FILE)
        tempFile.delete()

        // VACUUM INTO requires the destination file not to exist.
        val vacuumPath = tempFile.absolutePath.replace("'", "''")
        db.openHelper.writableDatabase.execSQL("VACUUM INTO '$vacuumPath'")

        context.contentResolver.openOutputStream(uri)?.use { out ->
            FileInputStream(tempFile).use { it.copyTo(out) }
        }
            ?: throw UserVisibleException(
                R.string.message_an_unknown_error_occurred,
                message = "Unable to open backup destination",
            )

        tempFile.delete()
    }

    /**
     * Validates and restores the database from the given URI. Returns normally if the restore
     * succeeded (caller must restart the app). Throws [UserVisibleException] if the source is not a
     * valid app database or is from a newer app version.
     *
     * The live database is closed before the swap and the replacement is staged as a sibling of the
     * live file so the final rename is atomic; see the inline comments.
     *
     * [databaseName] allows instrumented tests to operate on a scratch database instead of the
     * production one (instrumented tests share the app's data directory).
     */
    fun restore(
        context: Context,
        db: MainDatabase,
        uri: Uri,
        databaseName: String = MainDatabase.DATABASE_NAME,
    ) {
        val tempFile = File(context.cacheDir, TEMP_RESTORE_FILE)
        // Staged next to the live database so the final rename stays within one filesystem:
        // renameTo silently fails across filesystems, and cacheDir may be a different volume
        // from the databases directory.
        val stagedFile = File(context.getDatabasePath(databaseName).parentFile, TEMP_RESTORE_FILE)
        tempFile.delete()
        stagedFile.delete()

        // Whether the main database's Room instance has been closed yet. Everything up to that
        // point is safe to fail ordinarily; after it, the process holds a closed dependency graph
        // and the only recovery is a restart (see RestoreClosedDatabaseException below).
        var closed = false

        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { input.copyTo(it) }
            }
                ?: throw UserVisibleException(
                    R.string.message_an_unknown_error_occurred,
                    message = "Unable to open backup source",
                )

            // Sanity-check the imported file before touching the live database.
            // We open with the framework SQLiteDatabase (not Room) purely to inspect it.
            SQLiteDatabase.openDatabase(tempFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                .use { sqliteDb ->
                    if (!sqliteDb.isDatabaseIntegrityOk) {
                        throw UserVisibleException(
                            R.string.message_the_database_to_restore_was_not_created_with_this_app,
                            message = "Database integrity check failed",
                        )
                    }
                    val tables =
                        sqliteDb
                            .rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null)
                            .use { cursor ->
                                buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
                            }
                    if (!tables.containsAll(EXPECTED_TABLES)) {
                        throw UserVisibleException(
                            R.string.message_the_database_to_restore_was_not_created_with_this_app,
                            message = "Missing expected tables: $EXPECTED_TABLES",
                        )
                    }
                    val userVersion = sqliteDb.version
                    if (userVersion > MAIN_DB_SCHEMA_VERSION) {
                        throw UserVisibleException(
                            R.string.message_database_to_restore_too_new,
                            listOf(userVersion, MAIN_DB_SCHEMA_VERSION),
                            message = "DB version $userVersion > $MAIN_DB_SCHEMA_VERSION",
                        )
                    }
                }

            // Stage the validated replacement as a SIBLING of the live file, so the final swap
            // can be a same-filesystem rename. This deliberately happens BEFORE db.close().
            // The copy is the only step in the whole restore that can realistically fail (out of
            // space, quota, permissions), and while Room is still open a failure here is an
            // ordinary error: the live database is untouched and every DAO, repository and
            // ViewModel reference to it remains usable, so the app carries on unaffected.
            tempFile.copyTo(stagedFile, overwrite = true)

            // Now swap. db.close() must precede the rename: it checkpoints and removes the
            // WAL/SHM sidecars, so the live file is self-contained and nothing holds stale
            // handles to it when it is replaced. (The reverse order would risk close()
            // checkpointing stale pages back over the freshly renamed file.)
            //
            // renameTo is atomic within a filesystem (on Android it maps to rename(2), which
            // replaces an existing destination), so unlike delete-then-copy there is no window
            // in which process death would leave the app with no database at all. If the rename
            // fails the old live file is still there, so there is no data loss - but every
            // existing Room reference is now closed and Room will NOT reopen them, so the caller
            // is told via RestoreClosedDatabaseException that a restart is the only way back.
            val liveFile = context.getDatabasePath(databaseName)
            // Set before the call, not after: from the moment close() begins, the DAO graph is
            // potentially gone, so a failure inside close() itself must force a restart too.
            closed = true
            db.close()
            MainDatabase.resetInstanceForRestore()

            if (!stagedFile.renameTo(liveFile)) {
                throw UserVisibleException(
                    R.string.message_an_unknown_error_occurred,
                    message = "Unable to replace database file during restore",
                )
            }
        } catch (e: CancellationException) {
            // Structured concurrency, not a restore failure: never wrap this, or the caller would
            // report a restart it does not need.
            throw e
        } catch (e: Exception) {
            if (closed) throw RestoreClosedDatabaseException(e)
            throw e
        } finally {
            tempFile.delete()
            stagedFile.delete()
        }
    }
}

/**
 * Thrown when [BackupRestoreHelper.restore] fails at a point where the main database's Room
 * instance has already been closed.
 *
 * By the time this is raised the process holds a closed dependency graph: the `MainDatabase`, DAOs,
 * repositories and ViewModels obtained before the restore are all unusable, and clearing
 * `MainDatabase.INSTANCE` only lets a *future* `getDatabase()` call build a new instance - it
 * cannot repair the references already handed out. The next database-backed screen the user reaches
 * would therefore throw. Restarting the process is the only reliable recovery, so callers must
 * present a restart rather than a dismissible error.
 *
 * The old live database is still intact whenever this is thrown, so no user data is at risk.
 * [cause] is the underlying failure (for example a failed rename).
 */
class RestoreClosedDatabaseException(cause: Throwable) : Exception(cause)
