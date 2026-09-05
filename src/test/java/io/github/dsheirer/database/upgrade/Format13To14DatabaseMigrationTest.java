package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.dsheirer.web.settings.SpectrumSnapSettings;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format13To14DatabaseMigrationTest
{
    @TempDir Path temporary;

    @Test
    void populatedPredecessorGetsExplicitUnitedStatesSelectionAndRollsBackCleanly() throws Exception
    {
        Path path = Format13TestDatabase.create(temporary.resolve("previous.sqlite"));
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + path))
        {
            assertThrows(SQLException.class, () -> SpectrumSnapSettings.read(connection));
            Format13To14DatabaseMigration step = new Format13To14DatabaseMigration();
            assertEquals(1, step.validateSource(connection).getFirst().affectedRows());

            connection.setAutoCommit(false);
            step.migrate(connection);
            DatabaseFormatCatalog.stamp(connection, 14);
            connection.rollback();
            assertEquals(13, DatabaseFormatCatalog.inspect(connection).version());
            assertThrows(SQLException.class, () -> SpectrumSnapSettings.read(connection));

            step.migrate(connection);
            DatabaseFormatCatalog.stamp(connection, 14);
            connection.commit();
            assertEquals(14, DatabaseFormatCatalog.inspect(connection).version());
            SpectrumSnapSettings selected = SpectrumSnapSettings.read(connection);
            assertEquals(1, selected.revision());
            assertEquals("US", selected.countryCode());
        }
    }

    @Test
    void rejectsUnexpectedPreexistingSelection() throws Exception
    {
        Path path = Format13TestDatabase.create(temporary.resolve("unexpected.sqlite"));
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + path))
        {
            SpectrumSnapSettings.write(connection, SpectrumSnapSettings.defaults());
            assertThrows(SQLException.class, () -> new Format13To14DatabaseMigration().validateSource(connection));
        }
    }
}
