/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.SqliteSchemaValidator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format19To20DatabaseMigrationTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void preservesConfigurationAndBackfillsRetainedExactRangeAndMultisiteActivity() throws Exception
    {
        Path database = Format19TestDatabase.create(mTemporaryFolder.resolve("format-19.sqlite"));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            seedRetainedActivity(statement);
            long aliases = number(statement, "SELECT count(*) FROM alias");
            long aliasLists = number(statement, "SELECT count(*) FROM alias_list");
            long channels = number(statement, "SELECT count(*) FROM configuration_channel");
            long systems = number(statement, "SELECT count(*) FROM radio_system");
            long identities = number(statement, "SELECT count(*) FROM radio_system_identity_summary");

            DatabaseMigrationChain.PreflightReport preflight = DatabaseMigrationChain.validateSource(connection,
                DatabaseFormatCatalog.inspectForMigration(connection));
            assertEquals(2, preflight.steps().size());
            assertEquals("format-19-to-20", preflight.steps().getFirst().id());
            assertEquals(DatabaseMigrationEffect.UNKNOWN_COUNT,
                preflight.steps().getFirst().effects().getFirst().affectedRows());
            assertEquals(DatabaseMigrationEffect.Kind.PRESERVE,
                preflight.steps().getFirst().effects().getFirst().kind());
            assertEquals(aliases, new Format19To20DatabaseMigration().validateSource(connection)
                .getFirst().affectedRows());

            connection.setAutoCommit(false);
            DatabaseMigrationChain.MigrationReport report;
            try
            {
                report = DatabaseMigrationChain.migrate(connection);
                connection.commit();
            }
            catch(Exception exception)
            {
                connection.rollback();
                throw exception;
            }
            finally
            {
                connection.setAutoCommit(true);
            }

            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION, report.target().version());
            assertEquals("format-20-to-21", report.steps().getLast().id());
            assertEquals(aliases, number(statement, "SELECT count(*) FROM alias"));
            assertEquals(aliases, number(statement, "SELECT count(*) FROM alias_activity_summary"));
            assertEquals(aliasLists, number(statement, "SELECT count(*) FROM alias_list"));
            assertEquals(channels, number(statement, "SELECT count(*) FROM configuration_channel"));
            assertEquals(systems, number(statement, "SELECT count(*) FROM radio_system"));
            assertEquals(identities, number(statement, "SELECT count(*) FROM radio_system_identity_summary"));

            assertUnobservedSummary(statement, 9101);
            assertSummary(statement, 9102, "observed", 11, 4, 5, 2, 2, 6, 2_000, 6_000);
            assertSummary(statement, 9103, "observed", 13, 6, 7, 3, 3, 7, 3_000, 7_000);
            assertSummary(statement, 9106, "observed", 7, 2, 3, 1, 5, 49, 1_000, 5_000);
            assertSummary(statement, 9301, "observed", 7, 2, 3, 1, 5, 49, 1_000, 5_000);
            assertEquals(100, number(statement, """
                SELECT other_signaling_observation_count
                FROM alias_activity_summary WHERE alias_id=9106
                """), "CONTINUE observations remain part of the retained other-signaling metric");

            assertEquals("not_collected", text(statement,
                "SELECT metrics_state FROM alias_activity_summary WHERE alias_id=9104"));
            assertEquals(0, number(statement,
                "SELECT logical_call_count FROM alias_activity_summary WHERE alias_id=9104"));
            assertNull(nullableNumber(statement,
                "SELECT first_evidence_ms FROM alias_activity_summary WHERE alias_id=9104"));
            assertEquals("unsupported", text(statement,
                "SELECT metrics_state FROM alias_activity_summary WHERE alias_id=9105"));
            assertNull(nullableNumber(statement,
                "SELECT logical_call_count FROM alias_activity_summary WHERE alias_id=9105"));

            assertEquals(List.of("idx_alias_activity_calls", "idx_alias_activity_last_seen",
                "idx_alias_activity_signaling"), indexNames(statement));
            assertTrue(plan(statement, """
                SELECT alias_id FROM alias_activity_summary
                WHERE alias_list_id=9001 ORDER BY logical_call_count DESC, alias_id LIMIT 100
                """).contains("idx_alias_activity_calls"));
            assertTrue(plan(statement, """
                SELECT alias_id FROM alias_activity_summary
                WHERE alias_list_id=9001 ORDER BY signaling_observation_count DESC, alias_id LIMIT 100
                """).contains("idx_alias_activity_signaling"));
            assertTrue(plan(statement, """
                SELECT alias_id FROM alias_activity_summary
                WHERE alias_list_id=9001 ORDER BY last_evidence_ms DESC, alias_id LIMIT 100
                """).contains("idx_alias_activity_last_seen"));
            assertTrue(plan(statement, """
                SELECT id FROM alias
                WHERE alias_list_id=9001 AND matcher_type='TALKGROUP_RANGE'
                  AND protocol='DMR' AND min_value<=250 AND max_value>=250
                ORDER BY min_value DESC,max_value DESC,id DESC LIMIT 1
                """).contains("idx_alias_activity_talkgroup_range"));
            assertTrue(plan(statement, """
                SELECT id FROM alias
                WHERE alias_list_id=9001 AND matcher_type='RADIO_ID_RANGE'
                  AND protocol='DMR' AND min_value<=550 AND max_value>=550
                ORDER BY min_value DESC,max_value DESC,id DESC LIMIT 1
                """).contains("idx_alias_activity_radio_range"));
            assertTrue(plan(statement, """
                SELECT id FROM alias WHERE alias_list_id=9001
                ORDER BY matcher_type,id LIMIT 100
                """).contains("idx_alias_activity_matcher_sort"));
            assertTrue(plan(statement, """
                SELECT id FROM alias WHERE alias_list_id=9001
                ORDER BY CASE
                    WHEN matcher_type IN ('TALKGROUP', 'TALKGROUP_RANGE') THEN 'talkgroup'
                    WHEN matcher_type IN ('RADIO_ID', 'RADIO_ID_RANGE') THEN 'radio'
                    ELSE 'other' END,id LIMIT 100
                """).contains("idx_alias_activity_type_sort"));
            assertTrue(plan(statement, """
                SELECT id FROM alias WHERE alias_list_id=9001
                ORDER BY lower(coalesce(group_name,'')),id LIMIT 100
                """).contains("idx_alias_activity_group_sort"));
            assertTrue(plan(statement, """
                SELECT id FROM alias WHERE alias_list_id=9001
                ORDER BY CASE
                    WHEN matcher_type IN ('TALKGROUP_RANGE', 'RADIO_ID_RANGE')
                        THEN printf('%020d–%020d',min_value,max_value)
                    WHEN value IS NOT NULL THEN printf('%020d',value)
                    WHEN numeric_value IS NOT NULL THEN printf('%020d',numeric_value)
                    WHEN text_value IS NOT NULL THEN lower(text_value)
                    WHEN tone_sequence IS NOT NULL THEN lower(tone_sequence)
                    ELSE '' END,id LIMIT 100
                """).contains("idx_alias_activity_value_sort"));

            assertEquals("ok", text(statement, "PRAGMA integrity_check"));
            assertEquals(0, number(statement, "SELECT count(*) FROM pragma_foreign_key_check"));
            assertEquals(DatabaseFormatCatalog.current().fingerprint(),
                SqliteSchemaValidator.fingerprint(connection));
        }
    }

    @Test
    void dmrConventionalBackfillPreservesRetainedRoleAggregatesWithoutGuessing() throws Exception
    {
        assertConventionalPrivateBackfill("DMR", 0, 9_401, 9_411, 9_412,
            "94000000-0000-4000-8000-000000000001", "format-19-dmr-private.sqlite");
    }

    @Test
    void nxdnConventionalBackfillPreservesRetainedRoleAggregatesWithoutGuessing() throws Exception
    {
        assertConventionalPrivateBackfill("NXDN", 1, 9_501, 9_511, 9_512,
            "95000000-0000-4000-8000-000000000001", "format-19-nxdn-private.sqlite");
    }

    @Test
    void deferredAliasForeignKeyPreservesSummaryAcrossTransactionalSnapshotReplacement() throws Exception
    {
        Path database = Format20TestDatabase.create(mTemporaryFolder.resolve("format-20.sqlite"));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            long aliasId = number(statement, "SELECT alias_id FROM alias_activity_summary ORDER BY alias_id LIMIT 1");
            String aliasSql = text(statement, "SELECT sql FROM sqlite_schema WHERE type='table' AND name='alias'");
            String activitySql = text(statement,
                "SELECT sql FROM sqlite_schema WHERE type='table' AND name='alias_activity_summary'");
            assertTrue(activitySql.contains("ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED"));

            connection.setAutoCommit(false);
            try
            {
                statement.executeUpdate("CREATE TEMP TABLE alias_replacement AS SELECT * FROM alias");
                statement.executeUpdate("DELETE FROM alias");
                statement.executeUpdate("INSERT INTO alias SELECT * FROM alias_replacement");
                connection.commit();
            }
            catch(Exception exception)
            {
                connection.rollback();
                throw exception;
            }
            finally
            {
                connection.setAutoCommit(true);
            }

            assertTrue(aliasSql.startsWith("CREATE TABLE alias"));
            assertTrue(aliasId > 0);
            assertEquals(1, number(statement,
                "SELECT count(*) FROM alias_activity_summary WHERE alias_id=" + aliasId));
            assertEquals(0, number(statement, "SELECT count(*) FROM pragma_foreign_key_check"));
        }
    }

    @Test
    void normalStartupRejectsMissingSummaryWithoutRepairingIt() throws Exception
    {
        Path database = Format20TestDatabase.create(mTemporaryFolder.resolve("missing-summary.sqlite"));
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DROP TABLE alias_activity_summary");
        }

        assertThrows(java.sql.SQLException.class, () -> SdrTrunkDatabaseStartup.validateGlobalDatabase(database));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            assertEquals(0, number(statement, """
                SELECT count(*) FROM sqlite_schema
                WHERE type='table' AND name='alias_activity_summary'
                """));
        }
    }

    @Test
    void migratesMoreThanFiveHundredSourcesAndFourThousandOverlappingRangesWithoutRejectingConfiguration()
        throws Exception
    {
        Path database = Format19TestDatabase.create(mTemporaryFolder.resolve("format-19-overflow.sqlite"));
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            seedHighCardinalityRetainedActivity(statement);
            long aliasesBefore = number(statement, "SELECT count(*) FROM alias");

            connection.setAutoCommit(false);
            DatabaseMigrationChain.MigrationReport report;
            try
            {
                report = DatabaseMigrationChain.migrate(connection);
                connection.commit();
            }
            catch(Exception exception)
            {
                connection.rollback();
                throw exception;
            }
            finally
            {
                connection.setAutoCommit(true);
            }

            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION, report.target().version());
            assertEquals(aliasesBefore, number(statement, "SELECT count(*) FROM alias"));
            assertEquals(aliasesBefore, number(statement, "SELECT count(*) FROM alias_activity_summary"));
            assertSummary(statement, 1_504_097, "observed", 501, 0, 0, 0, 501, 501,
                1_001, 2_501);
            assertUnobservedSummary(statement, 1_500_001);
            assertEquals("ok", text(statement, "PRAGMA integrity_check"));
            assertEquals(0, number(statement, "SELECT count(*) FROM pragma_foreign_key_check"));
        }
    }

    private static void seedRetainedActivity(Statement statement) throws Exception
    {
        statement.executeUpdate("""
            INSERT INTO alias_list(id,name,family) VALUES
                (9001,'Format 20 DMR North','DMR'),
                (9002,'Format 20 DMR South','DMR')
            """);
        statement.executeUpdate("""
            INSERT INTO configuration_channel(
                configuration_id,channel_kind,sort_order,system_name,site_name,name,alias_list_id,
                decoder_type,address_domain_code,primary_frequency_hz,config_json
            ) VALUES
                ('90000000-0000-4000-8000-000000000001','TRUNKED',9001,'Migration DMR','North',
                    'North Control',9001,'DMR',0,451012500,
                    '{"decodeConfiguration":{"channelMode":"TRUNKED"}}'),
                ('90000000-0000-4000-8000-000000000002','TRUNKED',9002,'Migration DMR','South',
                    'South Control',9001,'DMR',0,452012500,
                    '{"decodeConfiguration":{"channelMode":"TRUNKED"}}'),
                ('90000000-0000-4000-8000-000000000003','TRUNKED',9003,'Migration DMR','East',
                    'East Control',9002,'DMR',0,453012500,
                    '{"decodeConfiguration":{"channelMode":"TRUNKED"}}')
            """);
        statement.executeUpdate("""
            INSERT INTO radio_system(
                id,system_key,configuration_id,protocol_code,address_domain_code,first_seen_ms,last_seen_ms
            ) VALUES(9001,'dmr:channel:90000000-0000-4000-8000-000000000001',
                '90000000-0000-4000-8000-000000000001',3,0,1000,7000)
            """);
        statement.executeUpdate("""
            INSERT INTO receiver_channel(
                id,configuration_id,first_seen_ms,last_seen_ms,radio_system_id,radio_system_assigned_at_ms
            ) VALUES
                (9001,'90000000-0000-4000-8000-000000000001',1000,7000,9001,1000),
                (9002,'90000000-0000-4000-8000-000000000002',1000,7000,9001,1000),
                (9003,'90000000-0000-4000-8000-000000000003',1000,7000,9001,1000)
            """);
        statement.executeUpdate("""
            INSERT INTO alias(
                id,alias_list_id,name,matcher_type,protocol,value,min_value,max_value,numeric_value
            ) VALUES
                (9101,9001,'Exact Dispatch','TALKGROUP','DMR',100,NULL,NULL,NULL),
                (9102,9001,'Range Dispatch','TALKGROUP_RANGE','DMR',NULL,200,299,NULL),
                (9103,9001,'Exact Radio','RADIO_ID','DMR',300,NULL,NULL,NULL),
                (9104,9001,'Unobserved','TALKGROUP','DMR',400,NULL,NULL,NULL),
                (9105,9001,'Unsupported Status','STATUS',NULL,NULL,NULL,NULL,1),
                (9106,9001,'Duplicate Exact Winner','TALKGROUP','DMR',100,NULL,NULL,NULL),
                (9301,9002,'Other Assigned List Exact','TALKGROUP','DMR',100,NULL,NULL,NULL)
            """);
        statement.executeUpdate("""
            INSERT INTO radio_system_identity_summary(
                id,radio_system_id,identity_kind_code,identity_id,first_seen_ms,last_seen_ms,
                logical_call_count,recorded_output_count,streamed_output_count,
                encrypted_logical_call_count,grant_count,join_count,emergency_count,register_count,
                logout_count,denial_count,data_count,continue_count
            ) VALUES
                (9201,9001,1,100,1000,5000,7,2,3,1,4,5,6,7,8,9,10,100),
                (9202,9001,1,250,2000,6000,11,4,5,2,1,2,0,0,0,0,3,0),
                (9203,9001,2,300,3000,7000,13,6,7,3,1,3,0,0,0,0,3,0)
            """);
    }

    private void assertConventionalPrivateBackfill(String protocol, int addressDomain, long channelId,
                                                   long exactAliasId, long rangeAliasId,
                                                   String configurationId, String filename) throws Exception
    {
        Path database = Format19TestDatabase.create(mTemporaryFolder.resolve(filename));
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            long aliasListId = channelId;
            statement.executeUpdate("""
                INSERT INTO alias_list(id,name,family) VALUES(%d,'Conventional %s','%s')
                """.formatted(aliasListId, protocol, protocol));
            statement.executeUpdate("""
                INSERT INTO configuration_channel(
                    configuration_id,channel_kind,sort_order,system_name,site_name,name,alias_list_id,
                    decoder_type,address_domain_code,primary_frequency_hz,config_json
                ) VALUES('%s','CONVENTIONAL',%d,'Conventional %s',NULL,'Local',%d,
                    '%s',%d,460025000,
                    '{"decodeConfiguration":{"channelMode":"CONVENTIONAL"}}')
                """.formatted(configurationId, channelId, protocol, aliasListId, protocol, addressDomain));
            statement.executeUpdate("""
                INSERT INTO receiver_channel(id,configuration_id,first_seen_ms,last_seen_ms)
                VALUES(%d,'%s',1000,20000)
                """.formatted(channelId, configurationId));
            statement.executeUpdate("""
                INSERT INTO alias(
                    id,alias_list_id,name,matcher_type,protocol,value,min_value,max_value
                ) VALUES
                    (%d,%d,'Self Radio','RADIO_ID','%s',501,NULL,NULL),
                    (%d,%d,'Radio Range','RADIO_ID_RANGE','%s',NULL,600,699)
                """.formatted(exactAliasId, aliasListId, protocol, rangeAliasId, aliasListId, protocol));
            statement.executeUpdate("""
                INSERT INTO conventional_call_identity_bucket(
                    channel_id,bucket_start_ms,identity_role_code,identity_kind_code,identity_id,
                    call_count,encrypted_count,recorded_count,streamed_count
                ) VALUES
                    (%1$d,3600000,1,2,501,1,1,1,1),
                    (%1$d,3600000,2,2,501,1,1,1,1),
                    (%1$d,7200000,1,2,501,1,0,1,0),
                    (%1$d,10800000,2,2,501,1,0,0,1),
                    (%1$d,14400000,1,2,601,1,1,1,1),
                    (%1$d,14400000,2,2,602,1,1,1,1)
                """.formatted(channelId));

            connection.setAutoCommit(false);
            try
            {
                DatabaseMigrationChain.migrate(connection);
                connection.commit();
            }
            catch(Exception exception)
            {
                connection.rollback();
                throw exception;
            }
            finally
            {
                connection.setAutoCommit(true);
            }

            //Format 19 retained source and destination roles separately.  They are intentionally summed because
            //the compact bucket cannot prove whether equal role rows came from one self-call or independent calls.
            assertSummary(statement, exactAliasId, "observed", 4, 3, 3, 2, 0, 0,
                3_600_000, 10_800_000);
            assertSummary(statement, rangeAliasId, "observed", 2, 2, 2, 2, 0, 0,
                14_400_000, 14_400_000);
            assertEquals("ok", text(statement, "PRAGMA integrity_check"));
            assertEquals(0, number(statement, "SELECT count(*) FROM pragma_foreign_key_check"));
        }
    }

    private static void seedHighCardinalityRetainedActivity(Statement statement) throws Exception
    {
        statement.executeUpdate("INSERT INTO alias_list(id,name,family) VALUES(9900,'Overflow DMR','DMR')");
        statement.executeUpdate("""
            WITH RECURSIVE sequence(value) AS (
                VALUES(1) UNION ALL SELECT value + 1 FROM sequence WHERE value < 4097
            )
            INSERT INTO alias(id,alias_list_id,name,matcher_type,protocol,min_value,max_value)
            SELECT 1500000 + value,9900,printf('Overlapping Range %04d',value),
                'TALKGROUP_RANGE','DMR',700,800
            FROM sequence
            """);
        statement.executeUpdate("""
            WITH RECURSIVE sequence(value) AS (
                VALUES(1) UNION ALL SELECT value + 1 FROM sequence WHERE value < 501
            )
            INSERT INTO configuration_channel(
                configuration_id,channel_kind,sort_order,system_name,site_name,name,alias_list_id,
                decoder_type,address_domain_code,primary_frequency_hz,config_json
            )
            SELECT printf('99000000-0000-4000-8000-%012d',value),'TRUNKED',10000 + value,
                'Overflow DMR',printf('Site %03d',value),printf('Control %03d',value),9900,
                'DMR',0,450000000 + value,
                '{"decodeConfiguration":{"channelMode":"TRUNKED"}}'
            FROM sequence
            """);
        statement.executeUpdate("""
            WITH RECURSIVE sequence(value) AS (
                VALUES(1) UNION ALL SELECT value + 1 FROM sequence WHERE value < 501
            )
            INSERT INTO radio_system(
                id,system_key,protocol_code,address_domain_code,dmr_model_code,dmr_network_id,
                first_seen_ms,last_seen_ms
            )
            SELECT 10000 + value,'dmr:tier3:tiny:' || value,3,0,1,value,1000 + value,2000 + value
            FROM sequence
            """);
        statement.executeUpdate("""
            WITH RECURSIVE sequence(value) AS (
                VALUES(1) UNION ALL SELECT value + 1 FROM sequence WHERE value < 501
            )
            INSERT INTO receiver_channel(
                id,configuration_id,first_seen_ms,last_seen_ms,radio_system_id,radio_system_assigned_at_ms
            )
            SELECT 20000 + value,printf('99000000-0000-4000-8000-%012d',value),
                1000 + value,2000 + value,10000 + value,1000 + value
            FROM sequence
            """);
        statement.executeUpdate("""
            WITH RECURSIVE sequence(value) AS (
                VALUES(1) UNION ALL SELECT value + 1 FROM sequence WHERE value < 501
            )
            INSERT INTO radio_system_identity_summary(
                id,radio_system_id,identity_kind_code,identity_id,first_seen_ms,last_seen_ms,
                logical_call_count,join_count
            )
            SELECT 30000 + value,10000 + value,1,777,1000 + value,2000 + value,1,1
            FROM sequence
            """);
    }

    private static void assertSummary(Statement statement, long aliasId, String state, long calls,
                                      long recorded, long streamed, long encrypted,
                                      long joins, long signaling, long first, long last) throws Exception
    {
        try(ResultSet rows = statement.executeQuery("""
            SELECT metrics_state,logical_call_count,
                recorded_logical_call_count,stream_submitted_logical_call_count,
                encrypted_logical_call_count,join_observation_count,signaling_observation_count,
                first_evidence_ms,last_evidence_ms
            FROM alias_activity_summary WHERE alias_id=%d
            """.formatted(aliasId)))
        {
            assertTrue(rows.next());
            assertEquals(state, rows.getString("metrics_state"));
            assertEquals(calls, rows.getLong("logical_call_count"));
            assertEquals(recorded, rows.getLong("recorded_logical_call_count"));
            assertEquals(streamed, rows.getLong("stream_submitted_logical_call_count"));
            assertEquals(encrypted, rows.getLong("encrypted_logical_call_count"));
            assertEquals(joins, rows.getLong("join_observation_count"));
            assertEquals(signaling, rows.getLong("signaling_observation_count"));
            assertEquals(first, rows.getLong("first_evidence_ms"));
            assertEquals(last, rows.getLong("last_evidence_ms"));
        }
    }

    private static void assertUnobservedSummary(Statement statement, long aliasId) throws Exception
    {
        assertEquals("not_collected", text(statement,
            "SELECT metrics_state FROM alias_activity_summary WHERE alias_id=" + aliasId));
        assertEquals(0, number(statement,
            "SELECT logical_call_count FROM alias_activity_summary WHERE alias_id=" + aliasId));
        assertNull(nullableNumber(statement,
            "SELECT first_evidence_ms FROM alias_activity_summary WHERE alias_id=" + aliasId));
    }

    private static List<String> indexNames(Statement statement) throws Exception
    {
        try(ResultSet rows = statement.executeQuery("""
            SELECT name FROM sqlite_schema
            WHERE type='index' AND tbl_name='alias_activity_summary' AND sql IS NOT NULL
            ORDER BY name
            """))
        {
            List<String> names = new java.util.ArrayList<>();
            while(rows.next())
            {
                names.add(rows.getString(1));
            }
            return names;
        }
    }

    private static String plan(Statement statement, String sql) throws Exception
    {
        try(ResultSet rows = statement.executeQuery("EXPLAIN QUERY PLAN " + sql))
        {
            StringBuilder plan = new StringBuilder();
            while(rows.next())
            {
                plan.append(rows.getString("detail")).append('\n');
            }
            return plan.toString();
        }
    }

    private static long number(Statement statement, String sql) throws Exception
    {
        try(ResultSet rows = statement.executeQuery(sql))
        {
            return rows.next() ? rows.getLong(1) : 0;
        }
    }

    private static Long nullableNumber(Statement statement, String sql) throws Exception
    {
        try(ResultSet rows = statement.executeQuery(sql))
        {
            if(!rows.next())
            {
                return null;
            }
            long value = rows.getLong(1);
            return rows.wasNull() ? null : value;
        }
    }

    private static String text(Statement statement, String sql) throws Exception
    {
        try(ResultSet rows = statement.executeQuery(sql))
        {
            return rows.next() ? rows.getString(1) : null;
        }
    }
}
