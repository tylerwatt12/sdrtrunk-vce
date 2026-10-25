/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.ActivityDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ConfigurationHistoryReplacementTest
{
    @TempDir Path directory;
    @Test void failedPairReplacementRestoresItsSupportedMonolithicBackup() throws Exception
    {
        Path root=directory.resolve("profile");
        Path current=Format62TestDatabase.create(SdrTrunkDatabasePath.getDatabasePath(root));
        String originalDigest=dataDigest(current);
        Path selected=directory.resolve("selected.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(selected);
        ApplicationMigrationService service=new ApplicationMigrationService(SqliteDatabaseSnapshot::create,
            ApplicationMigrationServiceTestSupport::runMigratorInProcess,promoted -> {
                throw new SQLException("injected paired replacement validation failure");
            });
        SQLException failure=assertThrows(SQLException.class,() -> service.replaceCurrentDatabase(selected,root,null));
        assertTrue(failure.getMessage().contains("injected paired replacement"));
        assertFalse(Files.exists(ActivityDatabasePath.forConfiguration(current)));
        try(var connection=DriverManager.getConnection("jdbc:sqlite:" + current))
        { assertEquals(62,DatabaseFormatCatalog.inspect(connection).version()); }
        assertEquals(originalDigest,dataDigest(current));
    }
    private static String dataDigest(Path database) throws Exception
    {
        var tables=new java.util.ArrayList<String>();
        var data=new java.util.ArrayList<String>();
        try(var connection=DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            try(var query=connection.createStatement();var rows=query.executeQuery(
                "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name"))
            { while(rows.next()) tables.add(rows.getString(1)); }
            for(String table:tables)
            {
                var contents=new java.util.ArrayList<String>();
                try(var query=connection.createStatement();var rows=query.executeQuery("SELECT * FROM \"" + table + "\""))
                {
                    while(rows.next())
                    {
                        var values=new java.util.ArrayList<Object>();
                        for(int column=1;column<=rows.getMetaData().getColumnCount();column++)
                        {
                            Object value=rows.getObject(column);
                            values.add(value instanceof byte[] bytes ? java.util.HexFormat.of().formatHex(bytes) : value);
                        }
                        contents.add(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(values));
                    }
                }
                java.util.Collections.sort(contents);data.add(table + contents);
            }
        }
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
