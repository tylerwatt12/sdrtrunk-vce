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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immediate SQLite repository for non-default configurable access policies. */
final class WebAccessPolicyRepository
{
    private final Path mDatabasePath;

    WebAccessPolicyRepository(Path databasePath)
    {
        mDatabasePath = Objects.requireNonNull(databasePath, "Web policy database path cannot be null")
            .toAbsolutePath().normalize();
    }

    Map<WebCapability,AccessTier> load() throws IOException, SQLException
    {
        Map<WebCapability,AccessTier> policies = new LinkedHashMap<>();
        try(Connection connection = SdrTrunkDatabase.open(mDatabasePath);
            PreparedStatement statement = connection.prepareStatement("""
                SELECT capability_id, required_tier FROM web_access_policy ORDER BY capability_id
                """); ResultSet resultSet = statement.executeQuery())
        {
            while(resultSet.next())
            {
                String id = resultSet.getString("capability_id");
                WebCapability capability = WebCapability.fromId(id)
                    .orElseThrow(() -> new SQLException("Unknown persisted web capability: " + id));
                AccessTier tier;
                try
                {
                    tier = AccessTier.valueOf(resultSet.getString("required_tier"));
                }
                catch(IllegalArgumentException exception)
                {
                    throw new SQLException("Invalid persisted tier for web capability: " + id, exception);
                }
                if(!capability.configurable() || tier == capability.defaultTier())
                {
                    throw new SQLException("Redundant or fixed persisted web capability override: " + id);
                }
                policies.put(capability, tier);
            }
        }
        return Map.copyOf(policies);
    }

    void save(WebCapability capability, AccessTier tier) throws IOException, SQLException
    {
        Objects.requireNonNull(capability, "Web capability cannot be null");
        Objects.requireNonNull(tier, "Web policy tier cannot be null");
        if(!capability.configurable())
        {
            throw new IllegalArgumentException("Fixed web capability cannot be persisted");
        }
        if(tier != AccessTier.PUBLIC && tier != AccessTier.USER && tier != AccessTier.ADMIN)
        {
            throw new IllegalArgumentException("Invalid web policy tier");
        }

        if(tier == capability.defaultTier())
        {
            try(Connection connection = SdrTrunkDatabase.open(mDatabasePath);
                PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM web_access_policy WHERE capability_id=?"))
            {
                statement.setString(1, capability.id());
                statement.executeUpdate();
            }
            return;
        }

        try(Connection connection = SdrTrunkDatabase.open(mDatabasePath);
            PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO web_access_policy(capability_id, required_tier, updated_at_ms)
                VALUES (?, ?, ?)
                ON CONFLICT(capability_id) DO UPDATE SET
                    required_tier=excluded.required_tier,
                    updated_at_ms=excluded.updated_at_ms
                """))
        {
            statement.setString(1, capability.id());
            statement.setString(2, tier.name());
            statement.setLong(3, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }
}
