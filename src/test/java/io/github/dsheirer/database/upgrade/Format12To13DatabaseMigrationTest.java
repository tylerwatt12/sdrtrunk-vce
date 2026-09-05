package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.gui.setup.SetupProgress;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class Format12To13DatabaseMigrationTest
{
    @TempDir Path temporary;

    @Test void populatedPredecessorPreservesSettingsAndCreatesBoundedCompletedRecord() throws Exception
    {
        Path path=Format12TestDatabase.create(temporary.resolve("previous.sqlite"));
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+path))
        {
            String before=settings(connection);
            var step=new Format12To13DatabaseMigration();
            assertEquals(1,step.validateSource(connection).getFirst().affectedRows());
            assertEquals(before,settings(connection));
            connection.setAutoCommit(false);
            DatabaseMigrationChain.migrate(connection);
            connection.rollback();
            assertEquals(12,DatabaseFormatCatalog.inspect(connection).version());
            assertEquals(before,settings(connection));
            assertThrows(SQLException.class,()->SetupProgress.read(connection));
            DatabaseMigrationChain.migrate(connection);
            connection.commit();
            assertEquals(14,DatabaseFormatCatalog.requireCurrent(connection).version());
            assertEquals(before,settings(connection));
            assertTrue(SetupProgress.read(connection).isComplete());
        }
        SdrTrunkDatabaseStartup.validateGlobalDatabase(path);
    }

    @Test void freshProfileIsIncompleteAndMissingRecordIsNotSilentlyRepaired() throws Exception
    {
        Path path=temporary.resolve("fresh.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(path);
        assertFalse(SetupProgress.read(path).isComplete());
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+path);var statement=connection.createStatement())
        {
            statement.executeUpdate("DELETE FROM application_settings WHERE key='setup_wizard'");
        }
        assertThrows(SQLException.class,()->SdrTrunkDatabaseStartup.validateGlobalDatabase(path));
    }

    private String settings(java.sql.Connection connection) throws Exception
    {
        try(var statement=connection.createStatement(); var rows=statement.executeQuery("SELECT group_concat(key || ':' || settings_json, '|') FROM (SELECT * FROM application_settings WHERE key NOT IN ('setup_wizard', 'spectrum_snap_country') ORDER BY key)"))
        { rows.next(); return rows.getString(1); }
    }
}
