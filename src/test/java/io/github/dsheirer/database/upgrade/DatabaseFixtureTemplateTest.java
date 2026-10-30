/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.ConfigurationHistoryDatabase;
import io.github.dsheirer.database.SqliteSchemaValidator;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseFixtureTemplateTest
{
    @TempDir Path mTemporary;

    @Test
    void preservesPopulatedFormat14CopiesAfterCallerMutation() throws Exception
    {
        assertPopulatedCopies(14, Format14TestDatabase::create);
    }

    @Test
    void preservesPopulatedFormat30CopiesAfterCallerMutation() throws Exception
    {
        assertPopulatedCopies(30, Format30TestDatabase::create);
    }

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
        List<DatabaseFixtureTemplate.Factory> factories = List.of(Format14TestDatabase::create,
            Format30TestDatabase::create, Format32TestDatabase::create);
        for(int index = 0; index < factories.size(); index++)
        {
            DatabaseFixtureTemplate.Factory factory = factories.get(index);
            Path database = mTemporary.resolve("existing-" + index + ".sqlite");
            Files.writeString(database, "Existing fixture");
            assertThrows(FileAlreadyExistsException.class, () -> factory.create(database));
            assertEquals("Existing fixture", Files.readString(database));

            for(String suffix: new String[]{"-wal", "-shm", "-journal"})
            {
                Path other = mTemporary.resolve("journal-" + index + suffix + ".sqlite");
                Path journal = other.resolveSibling(other.getFileName() + suffix);
                Files.writeString(journal, "Existing journal");
                assertThrows(FileAlreadyExistsException.class, () -> factory.create(other));
                assertEquals("Existing journal", Files.readString(journal));
                assertFalse(Files.exists(other));
            }
        }
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

    private void assertPopulatedCopies(int version, DatabaseFixtureTemplate.Factory factory) throws Exception
    {
        Path first = mTemporary.resolve("first.sqlite");
        factory.create(first);
        byte[] original = Files.readAllBytes(first);
        Path second = mTemporary.resolve("second.sqlite");
        factory.create(second);
        mutate(first);
        Path third = mTemporary.resolve("third.sqlite");
        factory.create(third);

        for(Path copy: new Path[]{second, third})
        {
            assertArrayEquals(original, Files.readAllBytes(copy),
                "Each returned fixture must retain the complete populated template independently");
            try(var connection = ConfigurationHistoryDatabase.openReadOnly(copy);
                var statement = connection.createStatement())
            {
                assertEquals(version, DatabaseFormatCatalog.inspect(connection).version());
                assertEquals(DatabaseFormatCatalog.requireVersion(version).fingerprint(),
                    SqliteSchemaValidator.fingerprint(connection));
                try(var rows = statement.executeQuery("SELECT count(*) FROM alias"))
                {
                    assertTrue(rows.next());
                    assertTrue(rows.getInt(1) > 0, "Migration inputs must retain their populated configuration");
                }
                try(var rows = statement.executeQuery("PRAGMA quick_check"))
                {
                    assertTrue(rows.next());
                    assertEquals("ok", rows.getString(1));
                    assertFalse(rows.next());
                }
                try(var rows = statement.executeQuery("PRAGMA foreign_key_check"))
                {
                    assertFalse(rows.next());
                }
            }
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
