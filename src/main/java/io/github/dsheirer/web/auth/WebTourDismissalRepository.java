/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.web.auth;

import io.github.dsheirer.database.SdrTrunkDatabase;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Bounded primary-key reads and atomic additive writes for an authenticated account. */
final class WebTourDismissalRepository
{
    private final Path mDatabasePath;

    WebTourDismissalRepository(Path databasePath)
    {
        mDatabasePath = Objects.requireNonNull(databasePath).toAbsolutePath().normalize();
    }

    Map<String,Integer> get(WebAccessAccount account) throws IOException, SQLException
    {
        try(Connection connection = SdrTrunkDatabase.open(mDatabasePath))
        {
            requireAccount(connection, account);
            return read(connection, account.id());
        }
    }

    Map<String,Integer> merge(WebAccessAccount account, Map<String,Integer> incoming)
        throws IOException, SQLException
    {
        try(Connection connection = SdrTrunkDatabase.openWriteTransaction(mDatabasePath))
        {
            requireAccount(connection, account);
            Map<String,Integer> combined = new LinkedHashMap<>(read(connection, account.id()));
            incoming.forEach((id, version) -> combined.merge(id, version, Math::max));
            if(combined.size() > WebTourDismissalService.MAXIMUM_GUIDES)
            {
                throw new IllegalArgumentException("Tour dismissals exceed the account limit");
            }
            try(var statement = connection.prepareStatement("""
                INSERT INTO web_user_tour_dismissal(user_id, guide_id, guide_version) VALUES (?, ?, ?)
                ON CONFLICT(user_id, guide_id) DO UPDATE
                    SET guide_version=max(guide_version, excluded.guide_version)
                """))
            {
                for(var entry: incoming.entrySet())
                {
                    statement.setLong(1, account.id());
                    statement.setString(2, entry.getKey());
                    statement.setInt(3, entry.getValue());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            SdrTrunkDatabase.commitWriteTransaction(connection);
            return combined;
        }
    }

    private static void requireAccount(Connection connection, WebAccessAccount account) throws SQLException
    {
        try(var statement = connection.prepareStatement("SELECT id FROM web_user WHERE id=? AND created_at_ms=?"))
        {
            statement.setLong(1, account.id());
            statement.setLong(2, account.createdAtEpochMillis());
            try(var rows = statement.executeQuery())
            {
                if(!rows.next()) throw new SQLException("Authenticated web account no longer exists");
            }
        }
    }

    private static Map<String,Integer> read(Connection connection, long userId) throws SQLException
    {
        Map<String,Integer> result = new LinkedHashMap<>();
        try(var statement = connection.prepareStatement("""
            SELECT guide_id, guide_version FROM web_user_tour_dismissal WHERE user_id=? ORDER BY guide_id LIMIT ?
            """))
        {
            statement.setLong(1, userId);
            statement.setInt(2, WebTourDismissalService.MAXIMUM_GUIDES + 1);
            try(var rows = statement.executeQuery())
            {
                while(rows.next()) result.put(rows.getString(1), rows.getInt(2));
            }
        }
        if(result.size() > WebTourDismissalService.MAXIMUM_GUIDES)
            throw new SQLException("Stored tour dismissals exceed the account limit");
        return result;
    }
}
