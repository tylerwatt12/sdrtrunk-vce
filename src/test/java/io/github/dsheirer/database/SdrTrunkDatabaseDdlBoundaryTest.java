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

package io.github.dsheirer.database;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Guards the database ownership boundary: schema definition classes own DDL, and only the startup/migrator
 * orchestrators may invoke schema creation.
 */
class SdrTrunkDatabaseDdlBoundaryTest
{
    private static final Path MAIN_SOURCE = Path.of("src", "main");
    private static final Pattern DDL = Pattern.compile(
        "(?i)\\b(?:CREATE\\s+(?:(?:UNIQUE|TEMP|TEMPORARY|VIRTUAL)\\s+)*(?:TABLE|INDEX|VIEW|TRIGGER)|" +
            "ALTER\\s+TABLE|DROP\\s+(?:TABLE|INDEX|VIEW|TRIGGER))\\b");
    private static final Pattern SCHEMA_DDL_CALL = Pattern.compile(
        "\\b(?:(?:SdrTrunkDatabaseSchema|DmrActivitySchema|TrunkedSiteSchema|" +
            "EncryptionKeyVaultSchema|TrunkedIdentitySchema)\\.create|P25ActivityLogSchema\\.create)\\s*\\(");
    private static final Pattern FRESH_SCHEMA_CALL = Pattern.compile(
        "\\bSdrTrunkDatabaseStartup(?:::|\\.)(?:createGlobalDatabase|createVaultDatabase)\\b");
    private static final Set<String> DDL_OWNERS = Set.of(
        "java/io/github/dsheirer/database/SdrTrunkDatabaseSchema.java",
        "java/io/github/dsheirer/stats/activity/P25ActivityLogSchema.java",
        "java/io/github/dsheirer/stats/activity/TrunkedIdentitySchema.java",
        "java/io/github/dsheirer/stats/activity/DmrActivitySchema.java",
        "java/io/github/dsheirer/stats/site/TrunkedSiteSchema.java",
        "java/io/github/dsheirer/preference/encryption/vault/EncryptionKeyVaultSchema.java",
        "java/io/github/dsheirer/database/upgrade/Alpha9DatabaseMigration.java");
    private static final Set<String> CREATION_ORCHESTRATORS = Set.of(
        "java/io/github/dsheirer/database/SdrTrunkDatabaseStartup.java",
        "java/io/github/dsheirer/stats/activity/P25ActivityLogSchema.java",
        "java/io/github/dsheirer/database/upgrade/Alpha9DatabaseMigration.java");
    private static final Set<String> FRESH_DATABASE_CALLERS = Set.of(
        "java/io/github/dsheirer/database/SdrTrunkDatabaseBootstrap.java",
        "java/io/github/dsheirer/database/importer/LegacyXmlConfigurationImporter.java");

    @Test
    void ddlIsConfinedToSchemaAndMigrationOwners() throws IOException
    {
        List<String> violations = matchingSources(DDL).stream()
            .filter(path -> !DDL_OWNERS.contains(relative(path)))
            .map(SdrTrunkDatabaseDdlBoundaryTest::relative)
            .toList();

        assertEquals(List.of(), violations,
            "Runtime stores/services must not create, repair, alter, or drop database objects");
    }

    @Test
    void schemaCreationHasOnlyStartupAndMigratorCallers() throws IOException
    {
        List<String> violations = matchingSources(SCHEMA_DDL_CALL).stream()
            .filter(path -> !CREATION_ORCHESTRATORS.contains(relative(path)))
            .map(SdrTrunkDatabaseDdlBoundaryTest::relative)
            .toList();

        assertEquals(List.of(), violations,
            "Fresh schema creation belongs to startup; deployed transitions belong to the Application Migrator");
    }

    @Test
    void freshSchemaEntryPointHasOnlyBootstrapAndImportCallers() throws IOException
    {
        List<String> violations = matchingSources(FRESH_SCHEMA_CALL).stream()
            .filter(path -> !FRESH_DATABASE_CALLERS.contains(relative(path)))
            .map(SdrTrunkDatabaseDdlBoundaryTest::relative)
            .toList();

        assertEquals(List.of(), violations,
            "Only first-start bootstrap and one-way import may create a fresh database");
    }

    private static List<Path> matchingSources(Pattern pattern) throws IOException
    {
        List<Path> matches = new ArrayList<>();

        try(var paths = Files.walk(MAIN_SOURCE))
        {
            for(Path path: paths.filter(Files::isRegularFile)
                .filter(SdrTrunkDatabaseDdlBoundaryTest::isSqlBearingSource).sorted().toList())
            {
                if(pattern.matcher(Files.readString(path)).find())
                {
                    matches.add(path);
                }
            }
        }

        return matches;
    }

    private static String relative(Path path)
    {
        return MAIN_SOURCE.relativize(path).toString().replace('\\', '/');
    }

    private static boolean isSqlBearingSource(Path path)
    {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".java") || name.endsWith(".sql");
    }
}
