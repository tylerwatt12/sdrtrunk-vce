/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatsAliasResolverTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void resolvesNxdnAndConventionalP25OnlyFromTheAssignedAliasList() throws Exception
    {
        Path database = mTemporaryFolder.resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO alias_list (id, name, system_name, family)
                VALUES (1, 'NXDN County', 'NXDN County', 'NXDN'),
                       (2, 'NXDN Other', 'NXDN Other', 'NXDN'),
                       (3, 'P25 Conventional', 'P25 Conventional', 'P25')
                """);
            statement.executeUpdate("""
                INSERT INTO alias (
                    id, alias_list_id, name, group_name, matcher_type, protocol, value, wacn, p25_system_id
                ) VALUES
                    (1, 1, 'NXDN Dispatch', 'Dispatch', 'TALKGROUP', 'NXDN', 91, NULL, NULL),
                    (2, 1, 'NXDN Unit', 'Units', 'RADIO_ID', 'NXDN', 123, NULL, NULL),
                    (3, 2, 'Wrong NXDN Dispatch', 'Other', 'TALKGROUP', 'NXDN', 91, NULL, NULL),
                    (4, 3, 'P25 Local', 'Local', 'TALKGROUP', 'APCO25', 101, NULL, NULL),
                    (5, 3, 'P25 Unit', 'Units', 'RADIO_ID', 'APCO25', 456, NULL, NULL),
                    (6, 3, 'Qualified Only', 'Qualified', 'P25_FULLY_QUALIFIED_TALKGROUP',
                        'APCO25', 102, 0xBEE00, 0x348)
                """);

            StatsAliasResolver resolver = new StatsAliasResolver();
            List<Map<String,Object>> nxdnTalkgroups = rows(
                row("NXDN County", 91), row("NXDN Other", 91));
            List<Map<String,Object>> nxdnRadios = rows(row("NXDN County", 123));
            List<Map<String,Object>> p25Talkgroups = rows(
                row("P25 Conventional", 101), row("P25 Conventional", 102));
            List<Map<String,Object>> p25Radios = rows(row("P25 Conventional", 456));

            resolver.enrichNxdnTalkgroups(connection, nxdnTalkgroups, "identity_id", "alias_");
            resolver.enrichNxdnRadios(connection, nxdnRadios, "identity_id", "alias_");
            resolver.enrichP25ConventionalTalkgroups(connection, p25Talkgroups, "identity_id", "alias_");
            resolver.enrichP25ConventionalRadios(connection, p25Radios, "identity_id", "alias_");

            assertEquals("NXDN Dispatch", nxdnTalkgroups.get(0).get("alias_name"));
            assertEquals("Wrong NXDN Dispatch", nxdnTalkgroups.get(1).get("alias_name"));
            assertEquals("NXDN Unit", nxdnRadios.getFirst().get("alias_name"));
            assertEquals("P25 Local", p25Talkgroups.getFirst().get("alias_name"));
            assertFalse(p25Talkgroups.get(1).containsKey("alias_name"),
                "A conventional receiver cannot validate a fully-qualified P25 rule");
            assertEquals("P25 Unit", p25Radios.getFirst().get("alias_name"));
        }
    }

    @SafeVarargs
    private static List<Map<String,Object>> rows(Map<String,Object>... rows)
    {
        return new ArrayList<>(List.of(rows));
    }

    private static Map<String,Object> row(String aliasList, int identityId)
    {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("alias_list_name", aliasList);
        row.put("identity_id", identityId);
        return row;
    }
}
