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

    /** Performs all source-specific, read-only admission checks and returns the preflight effects. */
    List<DatabaseMigrationEffect> validateSource(Connection connection) throws SQLException;

    /** Mutates only the caller-provided staged database inside the caller-owned transaction. */
    void migrate(Connection connection) throws SQLException;
}
