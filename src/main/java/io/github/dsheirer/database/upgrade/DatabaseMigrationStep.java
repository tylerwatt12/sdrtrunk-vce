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

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** One immutable adjacent whole-file database migration. */
interface DatabaseMigrationStep
{
    String id();

    String description();

    int sourceVersion();

    int targetVersion();

    /** Static policy visible even when an earlier step has not yet produced this step's source layout. */
    List<DatabaseMigrationEffect> declaredEffects();

    /**
     * Static policy for this step in one migration plan.  Most steps are path-independent.  A semantic-only step may
     * additionally recover damage from its directly selected historical source without repeating that recovery on
     * an intermediate layout produced by earlier steps in the same transaction.
     */
    default List<DatabaseMigrationEffect> declaredEffects(boolean selectedSourceStep)
    {
        return declaredEffects();
    }

    /** Standalone inspection retains exact source admission, including fixture and tool callers. */
    default List<DatabaseMigrationEffect> validateSource(Connection connection) throws SQLException
    {
        requireSource(connection);
        return inspectSource(connection);
    }

    /** Standalone mutation retains exact source admission before any writes. */
    default void migrate(Connection connection) throws SQLException
    {
        requireSource(connection);
        migrateSource(connection);
    }

    default List<DatabaseMigrationEffect> migrateAndReport(Connection connection) throws SQLException
    {
        return migrateAndReport(connection, true);
    }

    default List<DatabaseMigrationEffect> migrateAndReport(Connection connection, boolean selectedSourceStep)
        throws SQLException
    {
        requireSource(connection);
        return migrateSourceAndReport(connection, selectedSourceStep);
    }

    private void requireSource(Connection connection) throws SQLException
    {
        DatabaseFormatCatalog.DetectedFormat source = DatabaseFormatCatalog.inspectForMigration(connection);
        if(source.version() != sourceVersion())
        {
            throw new SQLException("Migration step " + id() + " requires exact source format " + sourceVersion() +
                "; found " + source.version());
        }
    }

    /** Internal hooks: the chain has admitted this source, or just verified the preceding exact target. */
    List<DatabaseMigrationEffect> inspectSource(Connection connection) throws SQLException;

    void migrateSource(Connection connection) throws SQLException;

    default List<DatabaseMigrationEffect> migrateSourceAndReport(Connection connection) throws SQLException
    {
        List<DatabaseMigrationEffect> effects = List.copyOf(inspectSource(connection));
        migrateSource(connection);
        return effects;
    }

    default List<DatabaseMigrationEffect> migrateSourceAndReport(Connection connection, boolean selectedSourceStep)
        throws SQLException
    {
        return migrateSourceAndReport(connection);
    }
}
