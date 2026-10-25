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

import io.github.dsheirer.database.SqliteSchemaValidator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HexFormat;

/** Exact populated format-2 schema frozen from the existing synthetic fixture, independent of current DDL. */
public final class Format2TestDatabase
{
    private static final String RESOURCE = "/database/application-format-2.sql";
    private static final String RESOURCE_SHA256 =
        "34bbfca902359f95833ce617ebee7a782d9d09c37c1334fc56ef7a9220eaf598";

    private Format2TestDatabase() {}

    public static Path create(Path database) throws Exception
    {
        if(Files.exists(database))
        {
            throw new IllegalArgumentException("Refusing to overwrite format-2 fixture target: " + database);
        }
        byte[] frozen;
        try(var input = Format2TestDatabase.class.getResourceAsStream(RESOURCE))
        {
            if(input == null) throw new IllegalStateException("Missing immutable format-2 fixture: " + RESOURCE);
            frozen = input.readAllBytes();
        }
        if(!RESOURCE_SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(frozen))))
        {
            throw new IllegalStateException("Frozen format-2 fixture bytes changed");
        }
        Files.createDirectories(database.toAbsolutePath().normalize().getParent());
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var statement = connection.createStatement())
        {
            for(String definition: new String(frozen, StandardCharsets.UTF_8).lines().toList())
            {
                if(!definition.isBlank() && !definition.startsWith("--")) statement.execute(definition);
            }
            DatabaseFormatCatalog.DetectedFormat detected = DatabaseFormatCatalog.inspect(connection);
            String fingerprint = SqliteSchemaValidator.fingerprint(connection);
            if(detected.version() != 2 || detected.markerPresent() ||
                !DatabaseFormatCatalog.requireVersion(2).fingerprint().equals(fingerprint))
            {
                throw new IllegalStateException("Global format 2 fixture signature mismatch: " + fingerprint);
            }
        }
        return database;
    }
}
