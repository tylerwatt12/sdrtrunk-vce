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

    /** Inspects the staged source without mutation and returns exact effects for this step. */
    List<DatabaseMigrationEffect> validateSource(Connection connection) throws SQLException;

    /** Mutates only the caller-provided staged database inside the caller-owned transaction. */
    void migrate(Connection connection) throws SQLException;

    /**
     * Migrates the staged database and returns the effects actually observed.  Historical steps retain their
     * validate-then-migrate behavior by default; recovery-oriented steps can override this hook to build their
     * migration input once and report skipped or repaired rows precisely.
     */
    default List<DatabaseMigrationEffect> migrateAndReport(Connection connection) throws SQLException
    {
        List<DatabaseMigrationEffect> effects = List.copyOf(validateSource(connection));
        migrate(connection);
        return effects;
    }
}
