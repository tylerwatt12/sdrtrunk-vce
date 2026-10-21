/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.record.managed;

import io.github.dsheirer.audio.tone.CompletedToneSequence;
import io.github.dsheirer.audio.tone.ToneSummaryPayload;
import io.github.dsheirer.module.decode.DecoderType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
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

    /** Indexed reads are bounded to the returned recording, with no inference from filenames or old access codes. */
    static Map<String,Object> read(Connection connection, long callId) throws SQLException
    {
        try(PreparedStatement context = connection.prepareStatement("SELECT * FROM recording_tone_sequence WHERE call_id=?"))
        {
            context.setLong(1,callId);
            try(var row = context.executeQuery())
            {
                if(!row.next()) return null;
                int source = row.getInt("source_code"), method = row.getInt("method_code"), expected = row.getInt("tone_count");
                int rawPhase = row.getInt("phase"); Integer phase = row.wasNull() ? null : rawPhase;
                long start = row.getLong("call_started_at_ms"), end = row.getLong("completed_at_ms");
                Long first = nullableLong(row,"first_voice_sample_at_ms");
                List<CompletedToneSequence.Element> tones = new ArrayList<>();
                try(PreparedStatement statement = connection.prepareStatement("SELECT * FROM recording_tone WHERE call_id=? ORDER BY ordinal"))
                {
                    statement.setLong(1,callId);
                    try(var values = statement.executeQuery())
                    {
                        while(values.next())
                        {
                            if(values.getInt("ordinal") != tones.size() || tones.size() >= CompletedToneSequence.MAXIMUM_TONES)
                                throw new SQLException("Recording tone sequence is incomplete");
                            List<Integer> frequencies = new ArrayList<>(2);
                            Long f1 = nullableLong(values,"frequency_1_millihz"), f2 = nullableLong(values,"frequency_2_millihz");
                            if(f1 != null) frequencies.add(Math.toIntExact(f1));
                            if(f2 != null) frequencies.add(Math.toIntExact(f2));
                            Long confidence = nullableLong(values,"confidence_ppm");
                            tones.add(new CompletedToneSequence.Element(frequencies,values.getString("codec_symbol"),
                                nullableLong(values,"offset_ms"),values.getLong("duration_ms"),
                                confidence != null ? confidence / 1_000_000. : null));
                        }
                    }
                }
                if(tones.size() != expected) throw new SQLException("Recording tone sequence is incomplete");
                return ToneSummaryPayload.summary(source,method,phase,start,first,end,tones);
            }
        }
    }

    private static Long nullableLong(java.sql.ResultSet row, String column) throws SQLException
    {
        long value = row.getLong(column); return row.wasNull() ? null : value;
    }
}
