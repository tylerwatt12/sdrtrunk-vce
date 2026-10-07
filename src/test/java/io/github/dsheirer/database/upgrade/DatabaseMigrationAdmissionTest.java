/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Admission remains strict while the shared chain avoids re-fingerprinting already admitted sources. */
class DatabaseMigrationAdmissionTest
{
    @TempDir Path mTemporaryFolder;

    @Test
    void chainFingerprintsOnlyInitialSourceEveryTargetAndFinalResult() throws Exception
    {
        Path database = Format30TestDatabase.create(mTemporaryFolder.resolve("source30.sqlite"));
        AtomicInteger scans = new AtomicInteger();
        try(Connection connection = open(database))
        {
            connection.setAutoCommit(false);
            Connection counted = countSchemaScans(connection, scans);
            var admitted = DatabaseFormatCatalog.inspectForMigration(counted);
            var report = DatabaseMigrationChain.migrate(counted, admitted, false, ignored -> {});
            DatabaseFormatCatalog.requireCurrent(counted);
            assertEquals(report.steps().size() + 2, scans.get(),
                "One initial admission, each exact adjacent target, and one final strict admission");
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION, report.target().version());
            connection.rollback();
        }
    }

    @Test
    void completingCurrentAdmissionDoesNotRepeatFingerprintButStillChecksRequiredRows() throws Exception
    {
        Path database = CurrentFormatTestDatabase.create(mTemporaryFolder.resolve("current.sqlite"));
        AtomicInteger scans = new AtomicInteger();
        try(Connection connection = open(database))
        {
            Connection counted = countSchemaScans(connection, scans);
            var source = DatabaseFormatCatalog.inspectForMigration(counted);
            DatabaseFormatCatalog.requireCurrent(counted, source);
            assertEquals(1, scans.get());
            try(Statement statement = connection.createStatement())
            {
                statement.executeUpdate("DELETE FROM application_settings WHERE key='setup_wizard'");
            }
            assertThrows(SQLException.class, () -> DatabaseFormatCatalog.requireCurrent(counted, source),
                "Reusing exact schema admission must not bypass strict required-data checks");
        }
    }

    @Test
    void currentAdmissionRetainsStrictMarkerTimestampAndDetectsChangedMarkers() throws Exception
    {
        Path database = CurrentFormatTestDatabase.create(mTemporaryFolder.resolve("marker.sqlite"));
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA ignore_check_constraints=ON");
            statement.executeUpdate("UPDATE database_metadata SET updated_at_ms=0 WHERE key='database_format_version'");
            statement.execute("PRAGMA ignore_check_constraints=OFF");
            var damaged = DatabaseFormatCatalog.inspectForMigration(connection);
            assertThrows(SQLException.class, () -> DatabaseFormatCatalog.requireCurrent(connection, damaged));
            statement.executeUpdate("UPDATE database_metadata SET updated_at_ms=1 WHERE key='database_format_version'");
            var admitted = DatabaseFormatCatalog.inspectForMigration(connection);
            statement.executeUpdate("UPDATE database_metadata SET value='45' WHERE key='database_format_version'");
            assertThrows(SQLException.class, () -> DatabaseFormatCatalog.requireCurrent(connection, admitted));
        }
    }

    @Test
    void everyStandaloneStepRejectsTheWrongSourceBeforeMutation() throws Exception
    {
        Path database = CurrentFormatTestDatabase.create(mTemporaryFolder.resolve("wrong-source.sqlite"));
        byte[] before = Files.readAllBytes(database);
        try(Connection connection = open(database))
        {
            for(var descriptor: DatabaseMigrationChain.steps())
            {
                DatabaseMigrationStep step = (DatabaseMigrationStep)Class.forName(getClass().getPackageName() +
                    ".Format" + descriptor.sourceVersion() + "To" + descriptor.targetVersion() + "DatabaseMigration")
                    .getDeclaredConstructor().newInstance();
                assertThrows(SQLException.class, () -> step.validateSource(connection), descriptor.id());
                assertThrows(SQLException.class, () -> step.migrate(connection), descriptor.id());
                assertThrows(SQLException.class, () -> step.migrateAndReport(connection), descriptor.id());
            }
        }
        assertArrayEquals(before, Files.readAllBytes(database));
    }

    @Test
    void anUnexpectedIntermediateSchemaFailsAtItsExactTargetAndRollbackAllowsRetry() throws Exception
    {
        Path database = Format30TestDatabase.create(mTemporaryFolder.resolve("rollback.sqlite"));
        byte[] before = Files.readAllBytes(database);
        try(Connection connection = open(database))
        {
            connection.setAutoCommit(false);
            var source = DatabaseFormatCatalog.inspectForMigration(connection);
            assertThrows(SQLException.class, () -> DatabaseMigrationChain.migrate(connection, source, true, step -> {
                if(step.sourceVersion() == 31)
                {
                    try(Statement statement = connection.createStatement())
                    {
                        statement.execute("CREATE TABLE unexpected_upgrade_object(id INTEGER)");
                    }
                    catch(SQLException exception) { throw new IllegalStateException(exception); }
                }
            }));
            connection.rollback();
        }
        assertArrayEquals(before, Files.readAllBytes(database));
        try(Connection connection = open(database))
        {
            connection.setAutoCommit(false);
            DatabaseMigrationChain.migrate(connection);
            connection.commit();
            DatabaseFormatCatalog.requireCurrent(connection);
        }
    }

    static Connection countSchemaScans(Connection connection, AtomicInteger scans)
    {
        return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
            (proxy, method, arguments) -> {
                try
                {
                    Object result = method.invoke(connection, arguments);
                    if(method.getName().equals("createStatement") && result instanceof Statement statement)
                    {
                        return Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{Statement.class},
                            (statementProxy, statementMethod, statementArguments) -> {
                                if(statementMethod.getName().equals("executeQuery") && statementArguments != null &&
                                    statementArguments[0] instanceof String sql &&
                                    sql.contains("SELECT type, name, sql") && sql.contains("FROM sqlite_master"))
                                    scans.incrementAndGet();
                                try { return statementMethod.invoke(statement, statementArguments); }
                                catch(InvocationTargetException exception) { throw exception.getCause(); }
                            });
                    }
                    return result;
                }
                catch(InvocationTargetException exception) { throw exception.getCause(); }
            });
    }

    private static Connection open(Path database) throws SQLException
    {
        return DriverManager.getConnection("jdbc:sqlite:" + database);
    }
}
