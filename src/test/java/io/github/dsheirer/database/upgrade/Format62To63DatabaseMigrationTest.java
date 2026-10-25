/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.ActivityDatabasePath;
import io.github.dsheirer.database.ConfigurationHistoryDatabase;
import io.github.dsheirer.database.ConfigurationHistorySchema;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class Format62To63DatabaseMigrationTest
{
    @TempDir Path directory;

    @Test void migrationPreservesHistoryWithoutCopyingItsFile() throws Exception
    {
        Path database=Format62TestDatabase.create(directory.resolve("sdrtrunk.sqlite"));
        Object originalKey=Files.readAttributes(database,java.nio.file.attribute.BasicFileAttributes.class).fileKey();
        long eventCount;
        try(var source=DriverManager.getConnection("jdbc:sqlite:" + database);var query=source.createStatement();
            var rows=query.executeQuery("SELECT count(*) FROM receiver_activity_event"))
        { assertTrue(rows.next());eventCount=rows.getLong(1);assertTrue(eventCount>0); }
        ApplicationDatabaseMigrator.migrateInPlace(database,null);
        ConfigurationHistoryDatabase.validatePair(database);
        Path history=ActivityDatabasePath.forConfiguration(database);
        assertEquals(originalKey,Files.readAttributes(history,java.nio.file.attribute.BasicFileAttributes.class).fileKey());
        try(var connection=DriverManager.getConnection("jdbc:sqlite:" + history);var query=connection.createStatement())
        {
            try(var rows=query.executeQuery("SELECT count(*) FROM receiver_activity_event"))
            { assertTrue(rows.next());assertEquals(eventCount,rows.getLong(1)); }
            try(var rows=query.executeQuery("SELECT count(*) FROM sqlite_master WHERE name='configuration_channel'"))
            { assertTrue(rows.next());assertEquals(0,rows.getInt(1)); }
            try(var rows=query.executeQuery("PRAGMA quick_check")) { assertTrue(rows.next());assertEquals("ok",rows.getString(1)); }
            try(var rows=query.executeQuery("PRAGMA foreign_key_check")) { assertFalse(rows.next()); }
        }
        SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(database);
    }

    @Test void freshPairAndMigrationHaveSameExactRoleSchemas() throws Exception
    {
        Path fresh=directory.resolve("fresh.sqlite");SdrTrunkDatabaseStartup.createGlobalDatabase(fresh);
        Path migrated=Format63TestDatabase.create(directory.resolve("migrated.sqlite"));
        ConfigurationHistoryDatabase.validatePair(fresh);ConfigurationHistoryDatabase.validatePair(migrated);
    }

    @Test void refusesMissingOrMismatchedCompanionsWithoutCreatingReplacement() throws Exception
    {
        Path first=directory.resolve("first.sqlite"),second=directory.resolve("second.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(first);SdrTrunkDatabaseStartup.createGlobalDatabase(second);
        Files.delete(ActivityDatabasePath.forConfiguration(first));
        assertThrows(java.io.IOException.class,() -> ConfigurationHistoryDatabase.validatePair(first));
        assertFalse(Files.exists(ActivityDatabasePath.forConfiguration(first)));
        Files.copy(ActivityDatabasePath.forConfiguration(second),ActivityDatabasePath.forConfiguration(first));
        assertThrows(java.sql.SQLException.class,() -> ConfigurationHistoryDatabase.validatePair(first));
    }

    @Test void rollbackAndActivationInterruptionsAreRecoverableWithoutAHistoryCopy() throws Exception
    {
        Path rollback=Format62TestDatabase.create(directory.resolve("rollback.sqlite"));
        assertThrows(IllegalStateException.class,() -> ApplicationDatabaseMigrator.migrateInPlace(rollback,phase -> {
            if(phase.equals("Validating the updated schema and configuration")) throw new IllegalStateException("injected before commit");
        }));
        try(var source=DriverManager.getConnection("jdbc:sqlite:" + rollback))
        { assertEquals(62,DatabaseFormatCatalog.inspect(source).version()); }
        ApplicationDatabaseMigrator.migrateInPlace(rollback,null);
        ConfigurationHistoryDatabase.validatePair(rollback);

        for(String phase: java.util.List.of("before-history-rename","after-history-rename","before-configuration-rename","after-configuration-rename"))
        {
            Path database=Format62TestDatabase.create(directory.resolve(phase + ".sqlite"));
            try(AutoCloseable ignored=Format62To63DatabaseMigration.observeActivation(actual -> {
                if(phase.equals(actual)) throw new java.io.IOException("injected activation interruption");
            }))
            { assertThrows(java.io.IOException.class,() -> ApplicationDatabaseMigrator.migrateInPlace(database,null)); }
            assertThrows(java.io.IOException.class,() -> ConfigurationHistoryDatabase.validatePair(database));
            ApplicationDatabaseMigrator.recoverSplitTransition(database);
            ConfigurationHistoryDatabase.validatePair(database);
            assertFalse(Files.exists(ActivityDatabasePath.splitReceipt(database)));
            assertFalse(Files.exists(ActivityDatabasePath.configurationStage(database)));
        }
    }

    @Test void configurationOnlyRetryCompletesCommittedActivationWithoutResettingHistoryAgain() throws Exception
    {
        Path root=directory.resolve("configuration-only-retry");
        Path database=Format62TestDatabase.create(io.github.dsheirer.database.SdrTrunkDatabasePath.getDatabasePath(root));
        var service=ApplicationMigrationServiceTestSupport.createInProcess();
        try(AutoCloseable ignored=Format62To63DatabaseMigration.observeActivation(phase -> {
            if(phase.equals("after-history-rename")) throw new java.io.IOException("injected committed activation interruption");
        }))
        {
            assertThrows(java.io.IOException.class,() -> service.migrateCurrent(root,false,MigrationContent.CONFIGURATION_ONLY,null));
        }
        try(var history=DriverManager.getConnection("jdbc:sqlite:" + ActivityDatabasePath.forConfiguration(database));
            var statement=history.createStatement())
        {
            statement.executeUpdate("INSERT INTO statistics_status(key,value,updated_at_ms) VALUES('after-commit-proof','retained',1)");
        }
        var result=service.migrateCurrent(root,false,MigrationContent.CONFIGURATION_ONLY,null);
        assertEquals(MigrationContent.CONFIGURATION_ONLY,result.sourcePlan().content());
        assertTrue(result.helperOutput().contains("activation complete"));
        ConfigurationHistoryDatabase.validatePair(database);
        try(var history=DriverManager.getConnection("jdbc:sqlite:" + ActivityDatabasePath.forConfiguration(database));
            var statement=history.createStatement();var rows=statement.executeQuery("SELECT value FROM statistics_status WHERE key='after-commit-proof'"))
        { assertTrue(rows.next());assertEquals("retained",rows.getString(1)); }
    }

    @Test void completedRenameRecoveryRefusesAReplacedHistoryFile() throws Exception
    {
        Path database = Format62TestDatabase.create(directory.resolve("replaced-history.sqlite"));
        try(AutoCloseable ignored = Format62To63DatabaseMigration.observeActivation(phase -> {
            if(phase.equals("after-configuration-rename"))
            {
                throw new java.io.IOException("injected final activation interruption");
            }
        }))
        {
            assertThrows(java.io.IOException.class, () -> ApplicationDatabaseMigrator.migrateInPlace(database, null));
        }
        Path history = ActivityDatabasePath.forConfiguration(database);
        org.junit.jupiter.api.Assumptions.assumeTrue(
            Files.readAttributes(history, java.nio.file.attribute.BasicFileAttributes.class).fileKey() != null);
        Path substitute = directory.resolve("substitute.sqlite");
        Files.copy(history, substitute);
        Files.move(substitute, history, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        java.io.IOException refusal = assertThrows(java.io.IOException.class,
            () -> ApplicationDatabaseMigrator.recoverSplitTransition(database));

        assertTrue(refusal.getMessage().contains("source file was replaced"));
        assertTrue(Files.isRegularFile(database));
        assertTrue(Files.isRegularFile(history));
        assertTrue(Files.isRegularFile(ActivityDatabasePath.splitReceipt(database)));
    }
}
