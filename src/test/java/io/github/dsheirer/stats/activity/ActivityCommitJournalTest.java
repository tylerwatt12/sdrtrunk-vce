package io.github.dsheirer.stats.activity;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteConnection;

class ActivityCommitJournalTest
{
    @Test void jdbcHooksTrackCommittedUpdatesDeletesAndCascadesButNeverRollback() throws Exception
    {
        ActivityCommitJournal journal = new ActivityCommitJournal();
        String epoch = journal.boundary().sourceEpoch();
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:"))
        {
            var sql = connection.createStatement();
            sql.execute("PRAGMA foreign_keys=ON");
            sql.execute("CREATE TABLE owner(id INTEGER PRIMARY KEY)");
            sql.execute("CREATE TABLE receiver_activity_event(id INTEGER PRIMARY KEY, owner_id INTEGER REFERENCES owner(id) ON DELETE CASCADE, value TEXT)");
            sql.execute("INSERT INTO owner VALUES(1)");
            ((SQLiteConnection)connection).addUpdateListener(journal::updated);
            journal.begin();
            connection.setAutoCommit(false);
            sql.execute("INSERT INTO receiver_activity_event VALUES(1,1,'initial')");
            assertEquals(0, journal.boundary().sequence(), "SQL callbacks are not committed publication");
            connection.rollback(); journal.rolledBack();
            assertTrue(journal.range(epoch, 0, null).ids().isEmpty());
            journal.begin();
            sql.execute("INSERT INTO receiver_activity_event VALUES(2,1,'initial')");
            sql.execute("UPDATE receiver_activity_event SET value='enriched' WHERE id=2");
            connection.commit(); journal.committed();
            assertEquals(List.of(2L), journal.range(epoch, 0, null).ids(), "same-ID enrichment coalesces");
            journal.begin();
            sql.execute("DELETE FROM owner WHERE id=1");
            connection.commit(); journal.committed();
            assertEquals(List.of(2L), journal.range(epoch, 1, null).ids(), "cascade must report the removed Activity ID");
            journal.begin();
            sql.execute("INSERT INTO receiver_activity_event VALUES(3,NULL,'retained')");
            connection.commit(); journal.committed();
            journal.begin();
            sql.execute("DELETE FROM receiver_activity_event WHERE id=3");
            connection.commit(); journal.committed();
            assertEquals(List.of(3L), journal.range(epoch, 3, null).ids());
            journal.invalidate();
            assertFalse(journal.range(epoch, 0, null).available(), "source reset invalidates previous cursors");
        }
    }

    @Test void fullCollectorAndRingAreExplicitGapsAndNewRangesRecover()
    {
        ActivityCommitJournal journal = new ActivityCommitJournal(2, 2);
        String epoch = journal.boundary().sourceEpoch();
        ActivityJournalTestAccess.commit(journal, 1, 2, 3);
        assertFalse(journal.range(epoch, 0, null).available());
        ActivityJournalTestAccess.commit(journal, 4);
        assertEquals(List.of(4L), journal.range(epoch, 1, null).ids());
        ActivityJournalTestAccess.commit(journal, 5);
        assertFalse(journal.range(epoch, 0, null).available());
        assertEquals(List.of(4L, 5L), journal.range(epoch, 1, 3L).ids());
    }

    @Test void blockedConsumerCannotBlockOrGrowWriterPublication() throws Exception
    {
        ActivityCommitJournal journal = new ActivityCommitJournal(2, 2);
        CountDownLatch blockedReader = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(1);
        Thread reader = Thread.ofPlatform().start(() -> {
            blockedReader.countDown();
            try { release.await(); } catch(InterruptedException ignored) { Thread.currentThread().interrupt(); }
        });
        assertTrue(blockedReader.await(1, TimeUnit.SECONDS));
        Thread writer = Thread.ofPlatform().start(() -> {
            for(int index = 0; index < 10_000; index++) ActivityJournalTestAccess.commit(journal, index + 1);
            done.countDown();
        });
        try { assertTrue(done.await(2, TimeUnit.SECONDS)); }
        finally { release.countDown(); reader.join(1_000); writer.join(1_000); }
        assertEquals(10_000, journal.boundary().sequence());
    }
}
