/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.auth;

import io.github.dsheirer.database.SdrTrunkDatabase;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Immediate SQLite repository for normalized web-user rows. */
final class WebUserRepository
{
    private final Path mDatabasePath;

    WebUserRepository(Path databasePath)
    {
        mDatabasePath = Objects.requireNonNull(databasePath, "Web user database path cannot be null")
            .toAbsolutePath().normalize();
    }

    List<StoredAccount> loadSecurityAccounts() throws IOException, SQLException
    {
        List<StoredAccount> accounts = new ArrayList<>();
        try(Connection connection = SdrTrunkDatabase.open(mDatabasePath);
            PreparedStatement statement = connection.prepareStatement("""
                SELECT id, username, tier, primary_admin, credential_version, password_algorithm,
                       password_iterations, password_derived_key_bits, password_salt, password_hash,
                       password_changed_at_ms, auth_revision
                FROM web_user
                ORDER BY primary_admin DESC, username
                """); ResultSet resultSet = statement.executeQuery())
        {
            while(resultSet.next())
            {
                String username = resultSet.getString("username");
                AccessTier tier;
                try
                {
                    tier = AccessTier.valueOf(resultSet.getString("tier"));
                }
                catch(IllegalArgumentException exception)
                {
                    throw new SQLException("Persisted web user has an invalid tier", exception);
                }
                boolean primary = resultSet.getInt("primary_admin") == 1;
                WebPasswordVerifier verifier;
                try
                {
                    verifier = new WebPasswordVerifier(resultSet.getInt("credential_version"), username,
                        resultSet.getString("password_algorithm"), resultSet.getInt("password_iterations"),
                        resultSet.getInt("password_derived_key_bits"),
                        Base64.getEncoder().encodeToString(resultSet.getBytes("password_salt")),
                        Base64.getEncoder().encodeToString(resultSet.getBytes("password_hash")),
                        resultSet.getLong("password_changed_at_ms"), resultSet.getLong("auth_revision"));
                }
                catch(IllegalArgumentException exception)
                {
                    throw new SQLException("Persisted web password verifier is invalid", exception);
                }
                WebAccessAccount account = new WebAccessAccount(resultSet.getLong("id"), username, tier,
                    verifier.passwordChangedAtEpochMillis(), verifier.authRevision(), primary);
                accounts.add(new StoredAccount(account, verifier));
            }
        }
        return List.copyOf(accounts);
    }

    long insert(WebPasswordVerifier verifier, AccessTier tier, boolean primaryAdmin, String preferencesJson)
        throws IOException, SQLException
    {
        long now = verifier.passwordChangedAtEpochMillis();
        byte[] salt = verifier.decodeSalt();
        byte[] hash = verifier.decodePasswordHash();
        try(Connection connection = SdrTrunkDatabase.open(mDatabasePath);
            PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO web_user (
                    username, tier, primary_admin, credential_version, password_algorithm, password_iterations,
                    password_derived_key_bits, password_salt, password_hash, password_changed_at_ms, auth_revision,
                    preferences_json, preferences_revision, created_at_ms, updated_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS))
        {
            bindVerifier(statement, verifier, tier, primaryAdmin, preferencesJson, now, salt, hash);
            if(statement.executeUpdate() != 1)
            {
                throw new SQLException("Web user insert did not create one row");
            }
            try(ResultSet keys = statement.getGeneratedKeys())
            {
                if(!keys.next() || keys.getLong(1) <= 0)
                {
                    throw new SQLException("Web user insert did not return a stable identifier");
                }
                return keys.getLong(1);
            }
        }
        finally
        {
            Arrays.fill(salt, (byte)0);
            Arrays.fill(hash, (byte)0);
        }
    }

    void replaceVerifier(long id, long expectedRevision, WebPasswordVerifier verifier)
        throws IOException, SQLException
    {
        byte[] salt = verifier.decodeSalt();
        byte[] hash = verifier.decodePasswordHash();
        try(Connection connection = SdrTrunkDatabase.open(mDatabasePath);
            PreparedStatement statement = connection.prepareStatement("""
                UPDATE web_user SET
                    credential_version=?, password_algorithm=?, password_iterations=?,
                    password_derived_key_bits=?, password_salt=?, password_hash=?, password_changed_at_ms=?,
                    auth_revision=?, updated_at_ms=?
                WHERE id=? AND auth_revision=?
                """))
        {
            statement.setInt(1, verifier.version());
            statement.setString(2, verifier.algorithm());
            statement.setInt(3, verifier.iterations());
            statement.setInt(4, verifier.derivedKeyBits());
            statement.setBytes(5, salt);
            statement.setBytes(6, hash);
            statement.setLong(7, verifier.passwordChangedAtEpochMillis());
            statement.setLong(8, verifier.authRevision());
            statement.setLong(9, System.currentTimeMillis());
            statement.setLong(10, id);
            statement.setLong(11, expectedRevision);
            requireOne(statement.executeUpdate(), "Web user password changed concurrently");
        }
        finally
        {
            Arrays.fill(salt, (byte)0);
            Arrays.fill(hash, (byte)0);
        }
    }

    void replaceTier(long id, long expectedRevision, AccessTier tier, long newRevision) throws IOException, SQLException
    {
        try(Connection connection = SdrTrunkDatabase.open(mDatabasePath);
            PreparedStatement statement = connection.prepareStatement("""
                UPDATE web_user SET tier=?, auth_revision=?, updated_at_ms=?
                WHERE id=? AND auth_revision=? AND primary_admin=0
                """))
        {
            statement.setString(1, tier.name());
            statement.setLong(2, newRevision);
            statement.setLong(3, System.currentTimeMillis());
            statement.setLong(4, id);
            statement.setLong(5, expectedRevision);
            requireOne(statement.executeUpdate(), "Web user tier changed concurrently");
        }
    }

    void delete(long id, long expectedRevision) throws IOException, SQLException
    {
        try(Connection connection = SdrTrunkDatabase.open(mDatabasePath);
            PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM web_user WHERE id=? AND auth_revision=? AND primary_admin=0
                """))
        {
            statement.setLong(1, id);
            statement.setLong(2, expectedRevision);
            requireOne(statement.executeUpdate(), "Web user changed concurrently");
        }
    }

    PreferenceRow loadPreferences(long userId) throws IOException, SQLException
    {
        try(Connection connection = SdrTrunkDatabase.open(mDatabasePath);
            PreparedStatement statement = connection.prepareStatement("""
                SELECT preferences_json, preferences_revision FROM web_user WHERE id=?
                """))
        {
            statement.setLong(1, userId);
            try(ResultSet resultSet = statement.executeQuery())
            {
                if(!resultSet.next())
                {
                    throw new SQLException("Authenticated web user no longer exists");
                }
                return new PreferenceRow(resultSet.getString(1), resultSet.getLong(2));
            }
        }
    }

    PreferenceUpdate updatePreferences(long userId, long expectedRevision, String preferencesJson)
        throws IOException, SQLException
    {
        final long nextRevision;
        try
        {
            nextRevision = Math.incrementExact(expectedRevision);
        }
        catch(ArithmeticException exception)
        {
            throw new IOException("Web preference revision is exhausted", exception);
        }

        try(Connection connection = SdrTrunkDatabase.open(mDatabasePath);
            PreparedStatement update = connection.prepareStatement("""
                UPDATE web_user SET preferences_json=?, preferences_revision=?, updated_at_ms=?
                WHERE id=? AND preferences_revision=?
                """))
        {
            update.setString(1, preferencesJson);
            update.setLong(2, nextRevision);
            update.setLong(3, System.currentTimeMillis());
            update.setLong(4, userId);
            update.setLong(5, expectedRevision);
            if(update.executeUpdate() == 1)
            {
                return new PreferenceUpdate(true, nextRevision);
            }

            try(PreparedStatement lookup = connection.prepareStatement(
                "SELECT preferences_revision FROM web_user WHERE id=?"))
            {
                lookup.setLong(1, userId);
                try(ResultSet resultSet = lookup.executeQuery())
                {
                    if(!resultSet.next())
                    {
                        throw new SQLException("Authenticated web user no longer exists");
                    }
                    return new PreferenceUpdate(false, resultSet.getLong(1));
                }
            }
        }
    }

    private static void bindVerifier(PreparedStatement statement, WebPasswordVerifier verifier, AccessTier tier,
                                     boolean primaryAdmin, String preferencesJson, long now, byte[] salt,
                                     byte[] hash) throws SQLException
    {
        statement.setString(1, verifier.username());
        statement.setString(2, tier.name());
        statement.setInt(3, primaryAdmin ? 1 : 0);
        statement.setInt(4, verifier.version());
        statement.setString(5, verifier.algorithm());
        statement.setInt(6, verifier.iterations());
        statement.setInt(7, verifier.derivedKeyBits());
        statement.setBytes(8, salt);
        statement.setBytes(9, hash);
        statement.setLong(10, verifier.passwordChangedAtEpochMillis());
        statement.setLong(11, verifier.authRevision());
        statement.setString(12, preferencesJson);
        statement.setLong(13, now);
        statement.setLong(14, now);
    }

    private static void requireOne(int changed, String message) throws SQLException
    {
        if(changed != 1)
        {
            throw new SQLException(message);
        }
    }

    record StoredAccount(WebAccessAccount account, WebPasswordVerifier verifier)
    {
    }

    record PreferenceRow(String json, long revision)
    {
    }

    record PreferenceUpdate(boolean updated, long revision)
    {
    }
}
