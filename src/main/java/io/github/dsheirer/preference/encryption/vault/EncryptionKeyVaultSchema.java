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

package io.github.dsheirer.preference.encryption.vault;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

/**
 * SQLite schema for the encryption key vault.
 */
public final class EncryptionKeyVaultSchema
{
    public static final int SCHEMA_VERSION = 1;

    private EncryptionKeyVaultSchema()
    {
    }

    public static void create(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS vault_metadata (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS vault_payload (
                    id INTEGER PRIMARY KEY CHECK(id = 1),
                    nonce BLOB NOT NULL,
                    ciphertext BLOB NOT NULL,
                    updated_at_ms INTEGER NOT NULL
                )
                """);
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO vault_metadata (key, value)
            VALUES ('schema_version', ?)
            ON CONFLICT(key) DO UPDATE SET value = excluded.value
            """))
        {
            statement.setString(1, Integer.toString(SCHEMA_VERSION));
            statement.executeUpdate();
        }
    }

    public static void validate(Connection connection) throws SQLException
    {
        validateTable(connection, "vault_metadata", "key", "value");
        validateTable(connection, "vault_payload", "id", "nonce", "ciphertext", "updated_at_ms");
    }

    private static void validateTable(Connection connection, String table, String... columns) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?
            """))
        {
            statement.setString(1, table);

            try(ResultSet resultSet = statement.executeQuery())
            {
                if(!resultSet.next())
                {
                    throw new SQLException("Encryption vault schema is missing table [" + table + "]");
                }
            }
        }

        Set<String> existingColumns = new HashSet<>();

        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + table + ")"))
        {
            while(resultSet.next())
            {
                existingColumns.add(resultSet.getString("name"));
            }
        }

        for(String column: columns)
        {
            if(!existingColumns.contains(column))
            {
                throw new SQLException("Encryption vault schema is missing column [" + table + "." + column + "]");
            }
        }
    }
}
