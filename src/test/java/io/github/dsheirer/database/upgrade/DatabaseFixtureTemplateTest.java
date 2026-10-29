/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.dsheirer.database.ConfigurationHistoryDatabase;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseFixtureTemplateTest
{
    @TempDir Path mTemporary;

    @Test
    void buildsOnceAndKeepsLaterCopiesIndependentOfMutatedFixtures() throws Exception
    {
        AtomicInteger builds = new AtomicInteger();
        DatabaseFixtureTemplate template = new DatabaseFixtureTemplate(32, database -> {
            builds.incrementAndGet();
            Format32TestDatabase.create(database);
        });
        Path first = template.create(mTemporary.resolve("first.sqlite"));
        Path second = template.create(mTemporary.resolve("second.sqlite"));
        byte[] clean = Files.readAllBytes(second);
        mutate(first);
        Path third = template.create(mTemporary.resolve("third.sqlite"));

        assertEquals(1, builds.get(), "Repeated fixtures must not rebuild the predecessor migration chain");
        assertArrayEquals(clean, Files.readAllBytes(second), "Mutating another copy must leave this file unchanged");
        assertArrayEquals(clean, Files.readAllBytes(third), "Caller mutations must never reach cached bytes");
        assertFormat(second, 32);
        assertFormat(third, 32);
    }

    @Test
    void preservesExistingTargetsAndJournalsBeforeCreatingAnyFixtureFiles() throws Exception
    {
        Path database = mTemporary.resolve("existing.sqlite");
        Files.writeString(database, "Existing fixture");
        assertThrows(FileAlreadyExistsException.class, () -> Format32TestDatabase.create(database));
        assertEquals("Existing fixture", Files.readString(database));

        Path other = mTemporary.resolve("journal.sqlite");
        Path journal = other.resolveSibling(other.getFileName() + "-wal");
        Files.writeString(journal, "Existing journal");
        assertThrows(FileAlreadyExistsException.class, () -> Format32TestDatabase.create(other));
        assertEquals("Existing journal", Files.readString(journal));
        assertFalse(Files.exists(other));
    }

    @Test
    void refusesToCacheAnUnfinalizedFixtureWithoutLeavingCallerFiles() throws Exception
    {
        AtomicInteger builds = new AtomicInteger();
        DatabaseFixtureTemplate template = new DatabaseFixtureTemplate(32, database -> {
            builds.incrementAndGet();
            Format32TestDatabase.create(database);
            Files.writeString(database.resolveSibling(database.getFileName() + "-wal"), "Unfinalized journal");
        });
        Path target = mTemporary.resolve("unfinalized.sqlite");
        assertThrows(IOException.class, () -> template.create(target));
        assertThrows(IOException.class, () -> template.create(target));
        assertEquals(2, builds.get(), "A failed template must not enter the cache");
        assertFalse(Files.exists(target));
    }

    private static void mutate(Path database) throws Exception
    {
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var statement = connection.createStatement())
        {
            statement.execute("CREATE TABLE fixture_mutation(id INTEGER)");
        }
    }

    private static void assertFormat(Path database, int version) throws Exception
    {
        try(var connection = ConfigurationHistoryDatabase.openReadOnly(database))
        {
            assertEquals(version, DatabaseFormatCatalog.inspect(connection).version());
        }
    }
}
