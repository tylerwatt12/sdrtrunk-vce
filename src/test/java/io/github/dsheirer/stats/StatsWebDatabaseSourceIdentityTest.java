/* Copyright (C) 2026 Dennis Sheirer. GNU General Public License, version 3 or later. */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatsWebDatabaseSourceIdentityTest
{
    private static final Path SOURCE = Path.of("data", "sdrtrunk.sqlite");
    private static final FileTime CREATED = FileTime.from(Instant.parse("2026-01-01T00:00:00.123456700Z"));

    @Test
    void windowsNullKeyStampSurvivesWritesAndCheckpointsAndNormalizesThePath() throws Exception
    {
        Object before = StatsWebDatabase.p25SourceIdentity(SOURCE,
            new Attributes(null, CREATED, true, 4096, CREATED));
        Object after = StatsWebDatabase.p25SourceIdentity(Path.of("data", "unused", "..", "sdrtrunk.sqlite"),
            new Attributes(null, CREATED, true, 8192, FileTime.fromMillis(CREATED.toMillis() + 10_000)));
        assertEquals(before, after, "size/mtime changes must not reset a Windows source");
    }

    @Test
    void replacementCreationTimeUsesItsFullPrecisionAndPathRemainsPartOfTheStamp() throws Exception
    {
        Object original = StatsWebDatabase.p25SourceIdentity(SOURCE, attributes(null, CREATED, true));
        FileTime replacementTime = FileTime.from(CREATED.toInstant().plusNanos(100));
        assertEquals(CREATED.toMillis(), replacementTime.toMillis(), "fixture differs below millisecond precision");
        assertNotEquals(original, StatsWebDatabase.p25SourceIdentity(SOURCE, attributes(null, replacementTime, true)));
        assertNotEquals(original, StatsWebDatabase.p25SourceIdentity(Path.of("other", "sdrtrunk.sqlite"),
            attributes(null, CREATED, true)));
    }

    @Test
    void providerKeyRemainsPrimaryEvenWhenTimestampsChangeOrAreUnknown() throws Exception
    {
        Object key = new Object();
        Object original = StatsWebDatabase.p25SourceIdentity(SOURCE, attributes(key, CREATED, true));
        assertSame(key, original);
        assertSame(key, StatsWebDatabase.p25SourceIdentity(SOURCE, attributes(key, FileTime.fromMillis(0), true)));
        assertNotEquals(original, StatsWebDatabase.p25SourceIdentity(SOURCE, attributes(new Object(), CREATED, true)));
    }

    @Test
    void nullKeyWithUnknownCreationTimeIsRejected()
    {
        for(long milliseconds: new long[]{0, -1})
        {
            assertThrows(IOException.class, () -> StatsWebDatabase.p25SourceIdentity(SOURCE,
                attributes(null, FileTime.fromMillis(milliseconds), true)));
        }
    }

    @Test
    void nonRegularSourceIsRejectedEvenWithAProviderKey()
    {
        assertThrows(IOException.class, () -> StatsWebDatabase.p25SourceIdentity(SOURCE,
            attributes(new Object(), CREATED, false)));
    }

    @Test
    void ioFailureKeepsItsServerSideCauseAndGenericClientMessage(@TempDir Path temporaryFolder)
    {
        StatsWebDatabase database = new StatsWebDatabase(null, temporaryFolder.resolve("missing.sqlite"));
        StatsApiException failure = assertThrows(StatsApiException.class, database::p25SourceIdentity);
        assertEquals(503, failure.status());
        assertEquals("P25 source identity is unavailable", failure.getMessage());
        assertInstanceOf(NoSuchFileException.class, failure.getCause());
    }

    private static Attributes attributes(Object key, FileTime creationTime, boolean regular)
    {
        return new Attributes(key, creationTime, regular, 4096, CREATED);
    }

    private record Attributes(Object fileKey, FileTime creationTime, boolean isRegularFile, long size,
                              FileTime lastModifiedTime) implements BasicFileAttributes
    {
        @Override public FileTime lastAccessTime() { return lastModifiedTime; }
        @Override public boolean isDirectory() { return false; }
        @Override public boolean isSymbolicLink() { return false; }
        @Override public boolean isOther() { return !isRegularFile; }
    }
}
