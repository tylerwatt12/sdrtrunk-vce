/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Tracks the explicit SQLite transactions owned by the Application Migrator on the calling thread. */
public final class ApplicationMigrationTransaction implements AutoCloseable
{
    private static final ThreadLocal<Set<Connection>> ACTIVE = new ThreadLocal<>();
    private final Connection mConnection;

    private ApplicationMigrationTransaction(Connection connection)
    {
        mConnection = connection;
    }

    /** Register ownership only after SQLite has successfully opened the actual transaction. */
    static ApplicationMigrationTransaction begin(Connection connection, Statement statement) throws SQLException
    {
        statement.execute("BEGIN IMMEDIATE");
        Set<Connection> connections = ACTIVE.get();
        if(connections == null)
        {
            connections = Collections.newSetFromMap(new IdentityHashMap<>());
            ACTIVE.set(connections);
        }
        connections.add(connection);
        return new ApplicationMigrationTransaction(connection);
    }

    /**
     * JDBC-managed transactions report autocommit disabled. Xerial leaves that flag enabled for SQL BEGIN,
     * so those transactions instead require proof from the migrator that opened them on this exact connection.
     */
    public static void requireActive(Connection connection) throws SQLException
    {
        Set<Connection> connections = ACTIVE.get();
        if(connection.getAutoCommit() && (connections == null || !connections.contains(connection)))
        {
            throw new SQLException("Tone method upgrade requires the Application Migrator transaction");
        }
    }

    /** The runner retains ownership of COMMIT/ROLLBACK; this scope only clears its transaction proof. */
    @Override
    public void close()
    {
        Set<Connection> connections = ACTIVE.get();
        if(connections != null)
        {
            connections.remove(mConnection);
            if(connections.isEmpty())
            {
                ACTIVE.remove();
            }
        }
    }
}
