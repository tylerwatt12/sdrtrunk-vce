package io.github.dsheirer.stats;

import io.github.dsheirer.database.SdrTrunkDatabase;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Connection-local read models joining independent configuration and retained history. */
public final class StatsHistoryContext
{
    private StatsHistoryContext() {}

    /**
     * Names the configuration subscriber dictionary explicitly. Its integer keys are private to configuration;
     * matching a history subscriber always uses its complete natural identity instead.
     */
    public static void installSubscriberContext(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement(); ResultSet views = statement.executeQuery(
            "SELECT count(*) FROM sqlite_temp_schema WHERE name IN ('configured_p25_subscriber_identity','configured_channel')"))
        {
            if(views.next() && views.getInt(1) == 2) return;
        }
        boolean attached = configurationAttached(connection);
        try(Statement statement = connection.createStatement())
        {
            statement.execute("CREATE TEMP VIEW IF NOT EXISTS configured_p25_subscriber_identity AS SELECT * FROM " +
                (attached ? "configuration." : "main.") + "p25_subscriber_identity");
            statement.execute("CREATE TEMP VIEW IF NOT EXISTS configured_channel AS SELECT * FROM " +
                (attached ? "configuration." : "main.") + "configuration_channel");
        }
    }

    public static boolean configurationAttached(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement(); ResultSet databases =
            statement.executeQuery("PRAGMA database_list"))
        {
            while(databases.next())
            {
                if("configuration".equals(databases.getString("name"))) return true;
            }
        }
        return false;
    }

    /** Retained channel drilldowns remain readable after their live configuration has been removed. */
    public static void installHistoricalChannels(Connection connection) throws SQLException
    {
        if(!configurationAttached(connection)) return;
        try(Statement statement = connection.createStatement())
        {
            statement.execute("""
                CREATE TEMP VIEW configuration_channel AS
                SELECT configured.id, configured.configuration_id, configured.channel_kind, configured.sort_order,
                    configured.system_name, configured.site_name, configured.name, configured.alias_list_id,
                    configured.decoder_type, configured.address_domain_code, configured.primary_frequency_hz,
                    configured.config_json, reset.reset_at_ms AS analog_activity_reset_ms
                FROM configuration.configuration_channel configured
                LEFT JOIN main.receiver_channel_statistics_reset reset ON reset.configuration_id=configured.configuration_id
                UNION ALL
                SELECT channel.id, channel.configuration_id, channel.channel_kind, channel.id,
                    channel.system_name, channel.site_name, channel.name, channel.alias_list_id,
                    channel.decoder_type, channel.address_domain_code, channel.primary_frequency_hz,
                    CASE WHEN channel.assigned_talkgroup IS NULL THEN '{}'
                        ELSE json_object('decodeConfiguration',json_object('talkgroup',channel.assigned_talkgroup)) END,
                    reset.reset_at_ms
                FROM main.receiver_channel channel
                LEFT JOIN main.receiver_channel_statistics_reset reset ON reset.configuration_id=channel.configuration_id
                WHERE NOT EXISTS(SELECT 1 FROM configuration.configuration_channel configured
                    WHERE configured.configuration_id=channel.configuration_id)
                """);
        }
        io.github.dsheirer.stats.activity.ReceiverActivitySchema.installConfigurationReadView(connection);
    }

    static Connection openRead(Path configuration) throws SQLException
    {
        try { return SdrTrunkDatabase.openHistoryRead(configuration); }
        catch(java.io.IOException unavailable) { throw new SQLException("Saved activity is unavailable",unavailable); }
    }

    static String sameSubscriber(String configuredId, String historicalId)
    {
        return configuredId + "=(SELECT configured_subscriber.id FROM p25_subscriber_identity history_subscriber " +
            "JOIN configured_p25_subscriber_identity configured_subscriber " +
            "ON configured_subscriber.home_wacn=history_subscriber.home_wacn " +
            "AND configured_subscriber.home_system_id=history_subscriber.home_system_id " +
            "AND configured_subscriber.subscriber_id=history_subscriber.subscriber_id " +
            "WHERE history_subscriber.id=" + historicalId + ")";
    }
}
