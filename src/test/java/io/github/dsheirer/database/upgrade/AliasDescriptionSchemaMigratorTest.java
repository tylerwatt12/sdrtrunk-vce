/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AliasDescriptionSchemaMigratorTest
{
    @TempDir
    Path mTemporaryDirectory;

    @Test
    void migratesAliasSchemaV2AndPreservesRows() throws Exception
    {
        Path database = mTemporaryDirectory.resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = SdrTrunkDatabase.open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("ALTER TABLE alias DROP COLUMN description");
            statement.executeUpdate("""
                UPDATE database_metadata SET value = '2' WHERE key = 'alias_schema_version'
                """);
            statement.executeUpdate("""
                INSERT INTO alias(sort_order, name, alias_list_name, group_name, color)
                VALUES (0, 'Dispatch', 'County', 'Fire', 0)
                """);
        }

        runMigrator(database);

        try(Connection connection = SdrTrunkDatabase.open(database); Statement statement = connection.createStatement())
        {
            try(ResultSet resultSet = statement.executeQuery("""
                SELECT name, description FROM alias
                """))
            {
                assertTrue(resultSet.next());
                assertEquals("Dispatch", resultSet.getString("name"));
                assertNull(resultSet.getString("description"));
            }

            try(ResultSet resultSet = statement.executeQuery("""
                SELECT value FROM database_metadata WHERE key = 'alias_schema_version'
                """))
            {
                assertTrue(resultSet.next());
                assertEquals("3", resultSet.getString("value"));
            }
        }

        assertEquals(1, backupCount(database));
        runMigrator(database);
        assertEquals(1, backupCount(database), "already-current migration must not create another backup");
    }

    private void runMigrator(Path database) throws Exception
    {
        Path classDirectory = Files.createDirectories(mTemporaryDirectory.resolve("migration-classes"));
        Path source = Path.of("tools/sqlite-migrations/alias-description/AliasV2ToV3DescriptionMigrator.java")
            .toAbsolutePath().normalize();
        var compiler = ToolProvider.getSystemJavaCompiler();

        try(var fileManager = compiler.getStandardFileManager(null, null, null))
        {
            boolean compiled = compiler.getTask(null, fileManager, null,
                List.of("-classpath", System.getProperty("java.class.path"), "-d", classDirectory.toString()),
                null, fileManager.getJavaFileObjects(source)).call();
            assertTrue(compiled);
        }

        try(URLClassLoader loader = new URLClassLoader(new java.net.URL[]{classDirectory.toUri().toURL()},
            getClass().getClassLoader()))
        {
            Class<?> migrator = Class.forName("AliasV2ToV3DescriptionMigrator", true, loader);
            migrator.getMethod("main", String[].class).invoke(null, (Object)new String[]{database.toString()});
        }
    }

    private static long backupCount(Path database) throws Exception
    {
        try(Stream<Path> files = Files.list(database.getParent()))
        {
            return files.filter(path -> path.getFileName().toString()
                .startsWith(database.getFileName() + ".backup-alias-v2-to-v3-")).count();
        }
    }
}
