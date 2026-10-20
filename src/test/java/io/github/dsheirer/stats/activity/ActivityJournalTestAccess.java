package io.github.dsheirer.stats.activity;

import org.sqlite.SQLiteUpdateListener;

/** Test-only writer operations for the independent reader's concurrency contracts. */
public final class ActivityJournalTestAccess
{
    private ActivityJournalTestAccess() { }
    public static void commit(ActivityCommitJournal journal, long... ids)
    {
        journal.begin();
        for(long id: ids) journal.updated(SQLiteUpdateListener.Type.UPDATE, "main", "receiver_activity_event", id);
        journal.committed();
    }
    public static void reset(ActivityCommitJournal journal) { journal.invalidate(); }
}
