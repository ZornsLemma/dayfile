package app.zornslemma.dayfile.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.zornslemma.dayfile.R
import java.io.File
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

// All databases used here are scratch files with dedicated names rather than the production
// MainDatabase.DATABASE_NAME / HistoryDatabase.DATABASE_NAME. Instrumented tests share the
// app's data directory, so using the production names would make these tests read and destroy
// whatever data manual testing left behind, and fail nondeterministically as a result.
class BackupRestoreHelperTest {

    private val scratchDbName = "backup_restore_test.db"
    private val scratchHistoryDbName = "backup_restore_history_test.db"

    // Used only as restore()'s `databaseName` argument in the post-close failure test, to point
    // the swap at a directory that cannot be renamed onto. Never opened as a real database.
    private val blockedDbName = "backup_restore_blocked.db"

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        deleteScratchDbs()
    }

    @After
    fun teardown() {
        deleteScratchDbs()
    }

    private fun deleteScratchDbs() {
        // The staging file lives in the databases directory regardless of which database name a
        // test passes, so clear it too - otherwise a test that asserts on the staging path
        // depends on every earlier test having run its cleanup.
        File(
                context.getDatabasePath(scratchDbName).parentFile,
                BackupRestoreHelper.TEMP_RESTORE_FILE,
            )
            .deleteRecursively()

        listOf(scratchDbName, scratchHistoryDbName, blockedDbName).forEach { name ->
            // Include Room's WAL/SHM sidecar files so no stale pages survive between runs.
            listOf("", "-wal", "-shm").forEach { suffix ->
                File(context.getDatabasePath(name).absolutePath + suffix).deleteRecursively()
            }
        }
    }

    private fun buildPopulatedDb(): MainDatabase {
        val db = Room.databaseBuilder(context, MainDatabase::class.java, scratchDbName).build()
        runBlocking {
            db.categoryDao().insert(CategoryEntity(name = "Diet", ordering = 0, enabled = true))
            db.entryDao()
                .insert(
                    EntryEntity(
                        categoryId = 1,
                        date = LocalDate.parse("2026-07-20"),
                        text = "salad",
                    )
                )
        }
        return db
    }

    // Shared mutation step for the round-trip-style tests: changes the live database AFTER the
    // backup was taken, so the post-restore assertions compare against the BACKUP state rather
    // than against "whatever is on disk". Without this, a restore that silently did nothing
    // would still pass (the on-disk state already equals the backup content); with it, the
    // post-backup row must be gone after restore, and a no-op restore fails loudly.
    // (category_id, date) carries a UNIQUE index, so a caller whose seed data already contains
    // the default key must pass a free one: inserting a duplicate fails the seed step itself
    // (SQLiteConstraintException) rather than exercising the restore.
    private fun insertPostBackupEntry(
        db: MainDatabase,
        categoryId: Long = 1L,
        date: LocalDate = LocalDate.parse("2026-07-21"),
    ) {
        runBlocking {
            db.entryDao()
                .insert(
                    EntryEntity(categoryId = categoryId, date = date, text = "post-backup edit")
                )
        }
    }

    @Test
    fun backupThenRestoreRoundTripsData() {
        val db = buildPopulatedDb()
        val backupUri = Uri.fromFile(File(context.cacheDir, "backup_roundtrip.db"))
        BackupRestoreHelper.backup(context, db, backupUri)
        insertPostBackupEntry(db)
        db.close()
        MainDatabase.resetInstanceForRestore()

        // Restore from the backup file (restore() closes the passed db and replaces the on-disk
        // file)
        val db2 = Room.databaseBuilder(context, MainDatabase::class.java, scratchDbName).build()
        BackupRestoreHelper.restore(context, db2, backupUri, scratchDbName)

        // Open a fresh instance against the replaced file and verify the whole table equals the
        // backup state: exactly the pre-backup row, with the post-backup edit gone.
        val db3 = Room.databaseBuilder(context, MainDatabase::class.java, scratchDbName).build()
        runBlocking {
            assertEquals(
                listOf(Triple(1L, LocalDate.parse("2026-07-20"), "salad")),
                db3.entryDao().getAllEntries().map { Triple(it.categoryId, it.date, it.text) },
            )
            assertEquals(listOf("Diet"), db3.categoryDao().getAllCategories().map { it.name })
        }
        db3.close()
    }

    @Test
    fun backupThenRestoreRoundTripsMultipleCategoriesAndEntries() {
        val db = Room.databaseBuilder(context, MainDatabase::class.java, scratchDbName).build()
        runBlocking {
            db.categoryDao().insert(CategoryEntity(name = "Diet", ordering = 0, enabled = true))
            db.categoryDao().insert(CategoryEntity(name = "Money", ordering = 1, enabled = true))
            db.categoryDao().insert(CategoryEntity(name = "Exercise", ordering = 2, enabled = true))
            db.entryDao()
                .insert(
                    EntryEntity(
                        categoryId = 1,
                        date = LocalDate.parse("2026-07-20"),
                        text = "salad",
                    )
                )
            db.entryDao()
                .insert(
                    EntryEntity(categoryId = 1, date = LocalDate.parse("2026-07-21"), text = "soup")
                )
            db.entryDao()
                .insert(
                    EntryEntity(categoryId = 2, date = LocalDate.parse("2026-07-20"), text = "4.50")
                )
            db.entryDao()
                .insert(
                    EntryEntity(categoryId = 3, date = LocalDate.parse("2026-07-20"), text = "walk")
                )
        }
        val backupUri = Uri.fromFile(File(context.cacheDir, "backup_multi.db"))
        BackupRestoreHelper.backup(context, db, backupUri)
        // (1, 2026-07-21) is already seeded ("soup") and (category_id, date) is unique, so
        // mutate via a key the seed does not use.
        insertPostBackupEntry(db, categoryId = 3L, date = LocalDate.parse("2026-07-21"))
        db.close()
        MainDatabase.resetInstanceForRestore()

        val db2 = Room.databaseBuilder(context, MainDatabase::class.java, scratchDbName).build()
        BackupRestoreHelper.restore(context, db2, backupUri, scratchDbName)

        val db3 = Room.databaseBuilder(context, MainDatabase::class.java, scratchDbName).build()
        runBlocking {
            // Whole-collection equality against the backup state, order-insensitive (the query
            // has no ORDER BY, so sort both sides): exactly the four pre-backup rows - which
            // also pins that the Money entry still references category 2 - with the post-backup
            // insert gone.
            assertEquals(
                listOf(
                    Triple(1L, LocalDate.parse("2026-07-20"), "salad"),
                    Triple(1L, LocalDate.parse("2026-07-21"), "soup"),
                    Triple(2L, LocalDate.parse("2026-07-20"), "4.50"),
                    Triple(3L, LocalDate.parse("2026-07-20"), "walk"),
                ),
                db3.entryDao()
                    .getAllEntries()
                    .map { Triple(it.categoryId, it.date, it.text) }
                    // sortedWith rather than sorted(): LocalDate implements
                    // Comparable<ChronoLocalDate>, not Comparable<LocalDate>, so this Triple is
                    // not Comparable and sorted() does not compile. compareBy's selectors only
                    // require Comparable<*>, which LocalDate satisfies.
                    .sortedWith(compareBy({ it.first }, { it.second }, { it.third })),
            )
            // The id -> name mapping survived intact (restore preserves primary keys).
            assertEquals(
                mapOf(1L to "Diet", 2L to "Money", 3L to "Exercise"),
                db3.categoryDao().getAllCategories().associate { it.id to it.name },
            )
        }
        db3.close()
    }

    @Test
    fun restoreThrowsWhenExpectedTablesMissing() {
        val badFile = File(context.cacheDir, "bad_db.db")
        badFile.delete()
        SQLiteDatabase.openOrCreateDatabase(badFile, null).use { sqliteDb ->
            sqliteDb.execSQL("CREATE TABLE foo (x INTEGER)")
            sqliteDb.version = 1
        }
        val uri = Uri.fromFile(badFile)
        val db = Room.databaseBuilder(context, MainDatabase::class.java, scratchDbName).build()
        try {
            BackupRestoreHelper.restore(context, db, uri, scratchDbName)
            fail("Expected UserVisibleException")
        } catch (e: UserVisibleException) {
            assertEquals(
                R.string.message_the_database_to_restore_was_not_created_with_this_app,
                e.resId,
            )
        } finally {
            badFile.delete()
        }
    }

    @Test
    fun restoreThrowsWhenUserVersionTooNew() {
        val newFile = File(context.cacheDir, "new_db.db")
        newFile.delete()
        SQLiteDatabase.openOrCreateDatabase(newFile, null).use { sqliteDb ->
            sqliteDb.execSQL("CREATE TABLE category (id INTEGER PRIMARY KEY)")
            sqliteDb.execSQL(
                "CREATE TABLE entry (id INTEGER PRIMARY KEY, category_id INTEGER, date TEXT, text TEXT)"
            )
            // Exactly one version newer than the app's current schema: the point under test is
            // "reject anything newer than we support", not any particular magic number. The
            // expectation is derived from MAIN_DB_SCHEMA_VERSION - the single source of truth
            // shared with the @Database annotation and the restore check - so a future schema
            // bump keeps this test meaningful without edits here.
            sqliteDb.version = MAIN_DB_SCHEMA_VERSION + 1
        }
        val uri = Uri.fromFile(newFile)
        val db = Room.databaseBuilder(context, MainDatabase::class.java, scratchDbName).build()
        try {
            BackupRestoreHelper.restore(context, db, uri, scratchDbName)
            fail("Expected UserVisibleException")
        } catch (e: UserVisibleException) {
            assertEquals(R.string.message_database_to_restore_too_new, e.resId)
            assertEquals(MAIN_DB_SCHEMA_VERSION + 1, e.args[0])
            assertEquals(MAIN_DB_SCHEMA_VERSION, e.args[1])
        } finally {
            newFile.delete()
        }
    }

    @Test
    fun restoreDoesNotTouchHistoryDatabase() {
        // Pre-populate a scratch history database with a row
        val historyDb =
            Room.databaseBuilder(context, HistoryDatabase::class.java, scratchHistoryDbName).build()
        runBlocking {
            historyDb
                .historyDao()
                .insertHistory(
                    HistoryEntryEntity(
                        categoryId = 1,
                        date = LocalDate.parse("2026-07-20"),
                        text = "recovery text",
                        savedAt = 12345L,
                    )
                )
        }
        // Main DB with data to backup/restore
        val db = buildPopulatedDb()
        val backupUri = Uri.fromFile(File(context.cacheDir, "backup_history_iso.db"))
        BackupRestoreHelper.backup(context, db, backupUri)
        // Same no-op-restore guard as the round-trip tests: the main-database assertion below
        // proves the restore actually happened, so the history assertion that follows is
        // meaningful rather than trivially true.
        insertPostBackupEntry(db)
        db.close()
        MainDatabase.resetInstanceForRestore()

        val db2 = Room.databaseBuilder(context, MainDatabase::class.java, scratchDbName).build()
        BackupRestoreHelper.restore(context, db2, backupUri, scratchDbName)

        // Main DB is back to the backup state (the post-backup row is gone)...
        val db3 = Room.databaseBuilder(context, MainDatabase::class.java, scratchDbName).build()
        runBlocking {
            assertEquals(listOf("salad"), db3.entryDao().getAllEntries().map { it.text })
        }
        db3.close()

        // ...while the history database is untouched: still exactly the seeded row.
        val historyRows = runBlocking {
            historyDb.historyDao().observeHistoryForDate(LocalDate.parse("2026-07-20")).first()
        }
        assertEquals(1, historyRows.size)
        assertEquals("recovery text", historyRows[0].text)
        historyDb.close()
    }

    // The two tests below cover the failure paths on either side of db.close(). They are the
    // counterpart to the happy-path round trips above: everything else in this class proves the
    // restore works when nothing goes wrong, which is exactly the situation in which a wrong
    // ordering of close() versus the file swap is invisible.
    //
    // Both force a genuine failure rather than asserting on internals: a directory sits where
    // either the staged file must be created or the live database must be renamed onto, and
    // rename(2) cannot replace a directory with a file. The directories are deliberately
    // NON-EMPTY, because both restore() and these tests start with File.delete() on the staging
    // path, and File.delete() removes an empty directory outright. Neither needs a new seam in
    // production code.

    @Test
    fun stagingFailureBeforeCloseLeavesTheLiveDatabaseOpenAndUsable() {
        val db = buildPopulatedDb()
        val backupUri = Uri.fromFile(File(context.cacheDir, "backup_stage_fail.db"))
        BackupRestoreHelper.backup(context, db, backupUri)
        insertPostBackupEntry(db)

        // The staging copy is the one step that can realistically fail in the field (no space,
        // quota, permissions), which is exactly why it is ordered before db.close(): a failure
        // there must leave the live database and every reference to it untouched and usable.
        //
        // The blocker must be a NON-EMPTY directory, not an empty one: restore() starts by
        // calling File.delete() on the staging path, and File.delete() removes an empty
        // directory outright. An empty one would simply be tidied away and the copy would
        // succeed. A child file makes the delete fail (ENOTEMPTY) so the directory survives to
        // block the copy.
        val stagedPath =
            File(
                context.getDatabasePath(scratchDbName).parentFile,
                BackupRestoreHelper.TEMP_RESTORE_FILE,
            )
        assertFalse("precondition: staging path should be free", stagedPath.exists())
        assertTrue("could not create the blocking directory", stagedPath.mkdirs())
        File(stagedPath, "blocker").writeText("x")
        try {
            try {
                BackupRestoreHelper.restore(context, db, backupUri, scratchDbName)
                fail("Expected the staging copy to fail")
            } catch (e: RestoreClosedDatabaseException) {
                // This is the regression this test exists for. The staging copy happens before
                // db.close(), so a failure here has NOT closed anything and must not demand a
                // restart - the app is still perfectly healthy.
                fail("Staging failure precedes db.close(), so it must not ask for a restart: $e")
            } catch (e: IOException) {
                // Expected: File.copyTo cannot write over a directory.
            }

            // Still holding everything it held before the attempt - including the post-backup row,
            // which proves the restore neither closed nor replaced anything. A successful query is
            // also the proof that the database is still open, asserted through use rather than
            // through a Room lifecycle flag (which is not public API).
            assertEquals(
                listOf("post-backup edit", "salad"),
                runBlocking { db.entryDao().getAllEntries().map { it.text } }.sorted(),
            )
        } finally {
            stagedPath.deleteRecursively()
        }
    }

    @Test
    fun renameFailureAfterCloseDemandsARestartAndCleansUpStagedFiles() {
        val db = buildPopulatedDb()
        val backupUri = Uri.fromFile(File(context.cacheDir, "backup_rename_fail.db"))
        BackupRestoreHelper.backup(context, db, backupUri)
        insertPostBackupEntry(db)
        db.close()
        MainDatabase.resetInstanceForRestore()

        // A genuinely open, genuinely populated database, so that closing it below is a real
        // teardown rather than a no-op close of something never opened.
        val openDb = Room.databaseBuilder(context, MainDatabase::class.java, scratchDbName).build()
        runBlocking { openDb.categoryDao().getCount() }

        // Make the swap impossible: rename(2) cannot replace a directory with a file. Note the
        // name passed to restore() is blockedDbName, not the name openDb was built with, so the
        // rename target is the blocking directory while db.close() still acts on the real,
        // populated database.
        val blockedPath = context.getDatabasePath(blockedDbName)
        assertTrue("could not create the blocking directory", blockedPath.mkdirs())
        File(blockedPath, "blocker").writeText("x")

        try {
            try {
                BackupRestoreHelper.restore(context, openDb, backupUri, blockedDbName)
                fail("Expected RestoreClosedDatabaseException")
            } catch (e: RestoreClosedDatabaseException) {
                // The underlying rename failure is preserved as the cause, so a log still shows
                // what actually went wrong.
                assertTrue(
                    "cause should be the rename failure, was ${e.cause}",
                    e.cause is UserVisibleException,
                )
            }

            // Confirms this really is the post-close case and not merely a mislabelled one. A
            // closed Room graph rejects further use, which is precisely why restarting is the
            // only recovery. Asserted by trying to use it, because Room's own lifecycle
            // accessors are not public API.
            val afterClose = runCatching { runBlocking { openDb.categoryDao().getCount() } }
            assertNotNull(
                "a closed Room graph should reject further use, but the query succeeded",
                afterClose.exceptionOrNull(),
            )

            // The rejected replacement is cleaned up rather than left sitting next to the
            // live database, where a later run would have to cope with it.
            val stagedPath =
                File(
                    context.getDatabasePath(blockedDbName).parentFile,
                    BackupRestoreHelper.TEMP_RESTORE_FILE,
                )
            assertFalse("staged restore file should be cleaned up", stagedPath.exists())
        } finally {
            blockedPath.deleteRecursively()
        }
    }
}
