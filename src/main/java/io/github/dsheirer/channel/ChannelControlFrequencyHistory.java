/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.channel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.sqlite.SQLiteConfig;

/** Bounded request-side lookup of retained, successfully decoded control frequencies for saved channels. */
final class ChannelControlFrequencyHistory
{
    static final int MAXIMUM_OBSERVATIONS = 256;
    static final String LAST_CONTROL_SQL = """
        SELECT frequency_hz, observed_at_ms, last_valid_decode_ms
        FROM trunked_control_channel_quality INDEXED BY idx_trunked_control_quality_channel_time
        WHERE channel_id = (SELECT id FROM receiver_channel WHERE configuration_id = ?)
        ORDER BY observed_at_ms DESC
        LIMIT ?
        """;

    private ChannelControlFrequencyHistory()
    {
    }

    static Map<String,Long> read(Path database, List<ChannelAdministrationService.ChannelSummary> channels)
    {
        List<ChannelAdministrationService.ChannelSummary> candidates = channels.stream()
            .filter(channel -> "TRUNKED".equals(channel.channelKind()) &&
                !"active-control".equals(channel.displayFrequencySource()) && !channel.frequenciesHz().isEmpty())
            .toList();
        if(candidates.isEmpty() || database == null || !Files.isRegularFile(database)) return Map.of();

        Map<String,Long> result = new HashMap<>();
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        config.setBusyTimeout(250);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database, config.toProperties());
            Statement settings = connection.createStatement())
        {
            settings.execute("PRAGMA query_only=ON");
            try(PreparedStatement query = connection.prepareStatement(LAST_CONTROL_SQL))
            {
                for(var channel: candidates)
                {
                    Long frequency = lastConfirmed(query, channel.configurationId(),
                        new HashSet<>(channel.frequenciesHz()));
                    if(frequency != null) result.put(channel.configurationId(), frequency);
                }
            }
        }
        catch(SQLException exception)
        {
            //This optional presentation hint must not prevent channel setup when history is unavailable.
            return Map.of();
        }
        return Map.copyOf(result);
    }

    static Long lastConfirmed(PreparedStatement query, String configurationId, Set<Long> configured)
        throws SQLException
    {
        query.setString(1, configurationId);
        query.setInt(2, MAXIMUM_OBSERVATIONS + 1);
        long newestDecode = 0;
        Long newestFrequency = null;
        int inspected = 0;
        try(ResultSet rows = query.executeQuery())
        {
            while(rows.next())
            {
                long observedAt = rows.getLong("observed_at_ms");
                //The schema requires last_valid_decode_ms <= observed_at_ms. Older observations cannot win.
                if(observedAt < newestDecode) return newestFrequency;
                if(inspected++ == MAXIMUM_OBSERVATIONS) return null;
                long frequency = rows.getLong("frequency_hz");
                long decodedAt = rows.getLong("last_valid_decode_ms");
                if(configured.contains(frequency) && decodedAt > 0 &&
                    (decodedAt > newestDecode || decodedAt == newestDecode &&
                        (newestFrequency == null || frequency < newestFrequency)))
                {
                    newestDecode = decodedAt;
                    newestFrequency = frequency;
                }
            }
        }
        return newestFrequency;
    }
}
