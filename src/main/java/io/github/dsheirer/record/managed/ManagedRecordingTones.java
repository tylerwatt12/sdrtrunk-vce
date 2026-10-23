/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.record.managed;

import io.github.dsheirer.audio.tone.CompletedToneSequence;
import io.github.dsheirer.audio.tone.ToneSummaryPayload;
import io.github.dsheirer.module.decode.DecoderType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Optional native-winner tone facts stored alongside a recording; access codes remain independent. */
public final class ManagedRecordingTones
{
    private ManagedRecordingTones() {}

    static void insert(Connection connection, long callId, CompletedToneSequence sequence) throws SQLException
    {
        if(sequence == null || sequence.tones().isEmpty()) return;
        DecoderType decoder = sequence.callLegSource().decoderType();
        Integer phase = decoder == DecoderType.P25_PHASE1 || decoder == DecoderType.P25_CONVENTIONAL ? Integer.valueOf(1) :
            decoder == DecoderType.P25_PHASE2 ? Integer.valueOf(2) : null;
        try(PreparedStatement statement = connection.prepareStatement("INSERT INTO recording_tone_sequence VALUES(?,?,?,?,?,?,?,?)"))
        {
            statement.setLong(1,callId); statement.setInt(2,sequence.source().code()); statement.setInt(3,sequence.method().code());
            statement.setObject(4,phase); statement.setLong(5,sequence.callStartTimestamp());
            statement.setObject(6,sequence.firstVoiceSampleTimestamp() > 0 ? sequence.firstVoiceSampleTimestamp() : null);
            statement.setLong(7,sequence.completedTimestamp()); statement.setInt(8,sequence.tones().size()); statement.executeUpdate();
        }
        try(PreparedStatement statement = connection.prepareStatement("INSERT INTO recording_tone VALUES(?,?,?,?,?,?,?,?)"))
        {
            int ordinal = 0;
            for(CompletedToneSequence.Element tone: sequence.tones())
            {
                statement.setLong(1,callId); statement.setInt(2,ordinal++);
                statement.setObject(3,tone.frequenciesMilliHertz().isEmpty() ? null : tone.frequenciesMilliHertz().getFirst());
                statement.setObject(4,tone.frequenciesMilliHertz().size() < 2 ? null : tone.frequenciesMilliHertz().get(1));
                statement.setString(5,tone.codecSymbol()); statement.setObject(6,tone.offsetMilliseconds());
                statement.setLong(7,tone.durationMilliseconds());
                statement.setObject(8,tone.confidence() != null ? Math.round(tone.confidence() * 1_000_000) : null);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /** Read the selected recordings together; each sequence retains its own ordinal and integrity checks. */
    static Map<Long,Map<String,Object>> read(Connection connection, List<Long> callIds) throws SQLException
    {
        if(callIds.isEmpty()) return Map.of();
        Map<Long,Sequence> sequences = new HashMap<>();
        String placeholders = String.join(",",java.util.Collections.nCopies(callIds.size(),"?"));
        try(PreparedStatement statement = connection.prepareStatement(
            "SELECT context.*,tone.ordinal,tone.frequency_1_millihz,tone.frequency_2_millihz," +
            "tone.codec_symbol,tone.offset_ms,tone.duration_ms,tone.confidence_ppm " +
            "FROM recording_tone_sequence context LEFT JOIN recording_tone tone ON tone.call_id=context.call_id " +
            "WHERE context.call_id IN (" + placeholders + ") ORDER BY context.call_id,tone.ordinal"))
        {
            int parameter = 1;
            for(long callId: callIds) statement.setLong(parameter++,callId);
            try(ResultSet row = statement.executeQuery())
            {
                while(row.next())
                {
                    long id = row.getLong("call_id");
                    Sequence sequence = sequences.get(id);
                    if(sequence == null)
                    {
                        Long phase = nullableLong(row,"phase");
                        sequence = new Sequence(row.getInt("source_code"),row.getInt("method_code"),
                            phase != null ? Math.toIntExact(phase) : null,row.getLong("call_started_at_ms"),
                            nullableLong(row,"first_voice_sample_at_ms"),row.getLong("completed_at_ms"),
                            row.getInt("tone_count"),new ArrayList<>());
                        sequences.put(id,sequence);
                    }
                    if(row.getObject("ordinal") != null) sequence.add(row);
                }
            }
        }
        Map<Long,Map<String,Object>> summaries = new HashMap<>();
        for(var entry: sequences.entrySet()) summaries.put(entry.getKey(),entry.getValue().summary());
        return summaries;
    }

    private record Sequence(int source, int method, Integer phase, long start, Long first, long end,
                            int expected, List<CompletedToneSequence.Element> tones)
    {
        private void add(ResultSet row) throws SQLException
        {
            if(row.getInt("ordinal") != tones.size() || tones.size() >= CompletedToneSequence.MAXIMUM_TONES)
                throw new SQLException("Recording tone sequence is incomplete");
            List<Integer> frequencies = new ArrayList<>(2);
            Long f1 = nullableLong(row,"frequency_1_millihz"), f2 = nullableLong(row,"frequency_2_millihz");
            if(f1 != null) frequencies.add(Math.toIntExact(f1));
            if(f2 != null) frequencies.add(Math.toIntExact(f2));
            Long confidence = nullableLong(row,"confidence_ppm");
            tones.add(new CompletedToneSequence.Element(frequencies,row.getString("codec_symbol"),
                nullableLong(row,"offset_ms"),row.getLong("duration_ms"),
                confidence != null ? confidence / 1_000_000. : null));
        }

        private Map<String,Object> summary() throws SQLException
        {
            if(tones.size() != expected) throw new SQLException("Recording tone sequence is incomplete");
            return ToneSummaryPayload.summary(source,method,phase,start,first,end,tones);
        }
    }

    private static Long nullableLong(java.sql.ResultSet row, String column) throws SQLException
    {
        long value = row.getLong(column); return row.wasNull() ? null : value;
    }
}
