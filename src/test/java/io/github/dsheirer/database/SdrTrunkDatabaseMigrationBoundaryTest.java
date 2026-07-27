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
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Keeps mutating legacy-data normalization at the explicit import boundary.
 */
class SdrTrunkDatabaseMigrationBoundaryTest
{
    private static final Path MAIN_SOURCE = Path.of("src", "main", "java");
    private static final Pattern IMPORT_NORMALIZATION_TYPES = Pattern.compile(
        "\\bAliasListDefinitionResolver\\b");
    private static final String IMPORT_BOUNDARY = "io/github/dsheirer/database/importer/";

    @Test
    void mutatingLegacyNormalizationHasOnlyImportCallers() throws IOException
    {
        List<String> violations = new ArrayList<>();

        try(var paths = Files.walk(MAIN_SOURCE))
        {
            for(Path path: paths.filter(candidate -> candidate.toString().endsWith(".java")).sorted().toList())
            {
                String relative = MAIN_SOURCE.relativize(path).toString().replace('\\', '/');

                String source = Files.readString(path);

                if(IMPORT_NORMALIZATION_TYPES.matcher(source).find() &&
                    !relative.startsWith(IMPORT_BOUNDARY))
                {
                    violations.add(relative);
                }
            }
        }

        assertEquals(List.of(), violations,
            "Legacy normalization belongs to database/importer and must never run during normal startup");
    }
}
