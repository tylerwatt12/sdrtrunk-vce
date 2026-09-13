/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Deterministic large Alias Activity fixture shared by read-path and streaming-export performance tests.
 * Fixture construction is intentionally separate from every measured interval.  The retained compact activity and
 * its format-20 read model contain the same deterministic call, signaling, state, and timestamp distributions.
 */
public final class AliasActivityRepresentativeTestDatabase
{
    public static final int EXACT_ALIAS_COUNT = 100_000;
    public static final int MORE_THAN_ALIAS_COUNT = 100_001;
    public static final long ALIAS_LIST_ID = 9_901;
    public static final long ACTIVITY_ROW_LIMIT = 100_000;
    private static final long BASE_TIMESTAMP_MS = 1_700_000_000_000L;
    private static final long UPDATED_AT_MS = 1_800_000_000_000L;

    private AliasActivityRepresentativeTestDatabase()
    {
    }

    public static Fixture createExact(Path database) throws Exception
    {
        return create(database, EXACT_ALIAS_COUNT);
    }

    public static Fixture createMoreThan(Path database) throws Exception
    {
        return create(database, MORE_THAN_ALIAS_COUNT);
    }

    public static Fixture create(Path database, int aliasCount) throws Exception
    {
        if(aliasCount < EXACT_ALIAS_COUNT)
        {
            throw new IllegalArgumentException("Representative Alias Activity fixture requires at least 100,000 aliases");
        }

        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            connection.setAutoCommit(false);
            try
            {
                insertCoverage(statement);
                insertAliases(statement, aliasCount);
                insertRetainedActivity(statement, aliasCount);
                insertActivitySummary(statement, aliasCount);
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
            statement.execute("PRAGMA optimize");

            long retainedRows = number(statement,
                "SELECT count(*) FROM radio_system_identity_summary WHERE radio_system_id=" + ALIAS_LIST_ID);
            long summaryRows = number(statement,
                "SELECT count(*) FROM alias_activity_summary WHERE alias_list_id=" + ALIAS_LIST_ID);
            if(summaryRows != aliasCount || retainedRows < 75_000)
            {
                throw new IllegalStateException("Representative fixture is incomplete: aliases=" + aliasCount +
                    ", summaries=" + summaryRows + ", retained activity=" + retainedRows);
            }
            return new Fixture(database, ALIAS_LIST_ID, aliasCount, retainedRows);
        }
    }

    private static void insertCoverage(Statement statement) throws Exception
    {
        statement.executeUpdate("""
            INSERT INTO alias_list(id,name,family,unmatched_talkgroup_record_enabled)
            VALUES(9901,'Representative 100k DMR','DMR',0)
            """);
        statement.executeUpdate("""
            INSERT INTO configuration_channel(
                configuration_id,channel_kind,sort_order,system_name,site_name,name,alias_list_id,
                decoder_type,address_domain_code,primary_frequency_hz,config_json
            ) VALUES
                ('99010000-0000-4000-8000-000000000001','TRUNKED',9901,'Representative DMR','North',
                    'North Control',9901,'DMR',0,451012500,
                    '{"decodeConfiguration":{"type":"decodeConfigDMR","channelMode":"TRUNKED"},' ||
                    '"sourceConfiguration":{"type":"sourceConfigTuner","frequency":451012500}}'),
                ('99010000-0000-4000-8000-000000000002','TRUNKED',9902,'Representative DMR','South',
                    'South Control',9901,'DMR',0,452012500,
                    '{"decodeConfiguration":{"type":"decodeConfigDMR","channelMode":"TRUNKED"},' ||
                    '"sourceConfiguration":{"type":"sourceConfigTuner","frequency":452012500}}')
            """);
        statement.executeUpdate("""
            INSERT INTO radio_system(
                id,system_key,protocol_code,address_domain_code,dmr_model_code,dmr_network_id,
                first_seen_ms,last_seen_ms
            ) VALUES(9901,'dmr:tier3:small:42',3,0,2,42,1700000000000,1700100000000)
            """);
        statement.executeUpdate("""
            INSERT INTO receiver_channel(
                id,configuration_id,first_seen_ms,last_seen_ms,radio_system_id,radio_system_assigned_at_ms
            ) VALUES
                (9901,'99010000-0000-4000-8000-000000000001',1700000000000,1700100000000,9901,
                    1700000000000),
                (9902,'99010000-0000-4000-8000-000000000002',1700000000000,1700100000000,9901,
                    1700000000000)
            """);
    }

    private static void insertAliases(Statement statement, int aliasCount) throws Exception
    {
        statement.executeUpdate("""
            WITH RECURSIVE sequence(value) AS (
                VALUES(1) UNION ALL SELECT value + 1 FROM sequence WHERE value < %1$d
            )
            INSERT INTO alias(
                id,alias_list_id,name,description,group_name,color,stream_as_talkgroup,record_enabled,
                matcher_type,protocol,value,min_value,max_value,numeric_value
            )
            SELECT 1000000 + value,9901,printf('Alias %%06d',value),
                'Representative description ' || value,
                CASE value %% 4 WHEN 0 THEN 'Dispatch' WHEN 1 THEN 'Fire'
                    WHEN 2 THEN 'Police' ELSE 'Utilities' END,
                value %% 16,CASE WHEN value %% 17=0 THEN value ELSE NULL END,
                CASE WHEN value %% 7=0 THEN 1 ELSE 0 END,
                CASE
                    WHEN value %% 997=0 THEN 'UNIT_STATUS'
                    WHEN value <= 40000 THEN 'TALKGROUP'
                    WHEN value <= 50000 THEN 'TALKGROUP_RANGE'
                    WHEN value <= 90000 THEN 'RADIO_ID'
                    ELSE 'RADIO_ID_RANGE'
                END,
                CASE WHEN value %% 997=0 THEN NULL ELSE 'DMR' END,
                CASE
                    WHEN value %% 997=0 THEN NULL
                    WHEN value <= 40000 THEN value
                    WHEN value > 50000 AND value <= 90000 THEN value - 50000
                    ELSE NULL
                END,
                CASE
                    WHEN value %% 997=0 THEN NULL
                    WHEN value > 40000 AND value <= 50000 THEN 100000 + (value - 40001) * 10
                    WHEN value > 90000 THEN 500000 + (value - 90001) * 10
                    ELSE NULL
                END,
                CASE
                    WHEN value %% 997=0 THEN NULL
                    WHEN value > 40000 AND value <= 50000 THEN 100004 + (value - 40001) * 10
                    WHEN value > 90000 THEN 500004 + (value - 90001) * 10
                    ELSE NULL
                END,
                CASE WHEN value %% 997=0 THEN value %% 256 ELSE NULL END
            FROM sequence
            """.formatted(aliasCount));
    }

    private static void insertRetainedActivity(Statement statement, int aliasCount) throws Exception
    {
        int maximumActivityAlias = Math.min(aliasCount, (int)ACTIVITY_ROW_LIMIT);
        statement.executeUpdate("""
            WITH RECURSIVE sequence(value) AS (
                VALUES(1) UNION ALL SELECT value + 1 FROM sequence WHERE value < %1$d
            ), observed(value) AS (
                SELECT value FROM sequence WHERE value %% 5<>0 AND value %% 997<>0
            )
            INSERT INTO radio_system_identity_summary(
                id,radio_system_id,identity_kind_code,identity_id,first_seen_ms,last_seen_ms,
                logical_call_count,recorded_output_count,streamed_output_count,
                encrypted_logical_call_count,grant_count,join_count,emergency_count,register_count,
                logout_count,denial_count,data_count
            )
            SELECT 2000000 + value,9901,CASE WHEN value <= 50000 THEN 1 ELSE 2 END,
                CASE
                    WHEN value <= 40000 THEN value
                    WHEN value <= 50000 THEN 100002 + (value - 40001) * 10
                    WHEN value <= 90000 THEN value - 50000
                    ELSE 500002 + (value - 90001) * 10
                END,
                %2$d + value * 1000,%2$d + value * 1000 + (value %% 3600) * 1000,
                CASE WHEN value %% 10=1 THEN 0 ELSE 1 + (value * 17) %% 1000 END,
                CASE WHEN value %% 10=1 THEN 0 ELSE value %% 7 END,
                CASE WHEN value %% 10=1 THEN 0 ELSE value %% 5 END,
                CASE WHEN value %% 10=1 THEN 0 ELSE value %% 3 END,
                CASE WHEN value %% 10=1 THEN 0 ELSE 1 + value %% 11 END,
                CASE WHEN value %% 10=1 THEN 0 ELSE 1 + value %% 13 END,
                CASE WHEN value %% 10=1 THEN 0 ELSE 1 + value %% 3 END,
                CASE WHEN value %% 10=1 THEN 0 ELSE 1 + value %% 5 END,
                CASE WHEN value %% 10=1 THEN 0 ELSE 1 + value %% 7 END,
                CASE WHEN value %% 10=1 THEN 0 ELSE 1 + value %% 17 END,
                CASE WHEN value %% 10=1 THEN 0 ELSE 1 + value %% 19 END
            FROM observed
            """.formatted(maximumActivityAlias, BASE_TIMESTAMP_MS));
    }

    private static void insertActivitySummary(Statement statement, int aliasCount) throws Exception
    {
        statement.executeUpdate("""
            WITH RECURSIVE sequence(value) AS (
                VALUES(1) UNION ALL SELECT value + 1 FROM sequence WHERE value < %1$d
            ), classified AS (
                SELECT value,value %% 997=0 AS unsupported,
                    value <= %2$d AND value %% 5<>0 AND value %% 997<>0 AS observed,
                    value <= %2$d AND value %% 10=1 AND value %% 997<>0 AS unused_observed
                FROM sequence
            )
            INSERT INTO alias_activity_summary(
                alias_id,alias_list_id,protocol_code,metrics_state,
                logical_call_count,recorded_logical_call_count,stream_submitted_logical_call_count,
                encrypted_logical_call_count,grant_observation_count,join_observation_count,
                emergency_observation_count,register_observation_count,logout_observation_count,
                denial_observation_count,data_observation_count,other_signaling_observation_count,
                signaling_observation_count,first_evidence_ms,last_evidence_ms,updated_at_ms
            )
            SELECT 1000000 + value,9901,
                CASE WHEN unsupported THEN 0 ELSE 3 END,
                CASE WHEN unsupported THEN 'unsupported' WHEN observed THEN 'observed'
                    ELSE 'not_collected' END,
                CASE WHEN unsupported THEN NULL WHEN NOT observed OR unused_observed THEN 0
                    ELSE 1 + (value * 17) %% 1000 END,
                CASE WHEN unsupported THEN NULL WHEN NOT observed OR unused_observed THEN 0 ELSE value %% 7 END,
                CASE WHEN unsupported THEN NULL WHEN NOT observed OR unused_observed THEN 0 ELSE value %% 5 END,
                CASE WHEN unsupported THEN NULL WHEN NOT observed OR unused_observed THEN 0 ELSE value %% 3 END,
                CASE WHEN unsupported THEN NULL WHEN NOT observed OR unused_observed THEN 0 ELSE 1 + value %% 11 END,
                CASE WHEN unsupported THEN NULL WHEN NOT observed OR unused_observed THEN 0 ELSE 1 + value %% 13 END,
                CASE WHEN unsupported THEN NULL WHEN NOT observed OR unused_observed THEN 0 ELSE 1 + value %% 3 END,
                CASE WHEN unsupported THEN NULL WHEN NOT observed OR unused_observed THEN 0 ELSE 1 + value %% 5 END,
                CASE WHEN unsupported THEN NULL WHEN NOT observed OR unused_observed THEN 0 ELSE 1 + value %% 7 END,
                CASE WHEN unsupported THEN NULL WHEN NOT observed OR unused_observed THEN 0 ELSE 1 + value %% 17 END,
                CASE WHEN unsupported THEN NULL WHEN NOT observed OR unused_observed THEN 0 ELSE 1 + value %% 19 END,
                CASE WHEN unsupported THEN NULL ELSE 0 END,
                CASE WHEN unsupported THEN NULL WHEN NOT observed OR unused_observed THEN 0 ELSE
                    (1 + value %% 11) + (1 + value %% 13) + (1 + value %% 3) +
                    (1 + value %% 5) + (1 + value %% 7) + (1 + value %% 17) + (1 + value %% 19) END,
                CASE WHEN observed THEN %3$d + value * 1000 ELSE NULL END,
                CASE WHEN observed THEN %3$d + value * 1000 + (value %% 3600) * 1000 ELSE NULL END,
                %4$d
            FROM classified
            """.formatted(aliasCount, ACTIVITY_ROW_LIMIT, BASE_TIMESTAMP_MS, UPDATED_AT_MS));
    }

    private static long number(Statement statement, String sql) throws Exception
    {
        try(ResultSet rows = statement.executeQuery(sql))
        {
            return rows.next() ? rows.getLong(1) : 0;
        }
    }

    public record Fixture(Path database, long aliasListId, int aliasCount, long retainedActivityRows)
    {
    }
}
