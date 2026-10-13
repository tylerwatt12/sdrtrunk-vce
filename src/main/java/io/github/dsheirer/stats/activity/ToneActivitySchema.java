/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats.activity;

import io.github.dsheirer.audio.tone.CompletedToneSequence;
import io.github.dsheirer.audio.tone.ToneSummaryPayload;
import io.github.dsheirer.database.SqliteSchemaValidator;
import io.github.dsheirer.module.decode.DecoderType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/** Explicit, optional complete-call tone evidence. All writes run on the Activity writer. */
public final class ToneActivitySchema
{
    private ToneActivitySchema() {}
    private static final String SEQUENCE_SQL = """
        CREATE TABLE receiver_activity_tone_sequence (
            event_id INTEGER PRIMARY KEY REFERENCES receiver_activity_event(id) ON DELETE CASCADE,
            source_code INTEGER NOT NULL CHECK(source_code IN (1,2)),
            method_code INTEGER NOT NULL CHECK(method_code IN (1,2)),
            phase INTEGER CHECK(phase IS NULL OR phase IN (1,2)),
            call_started_at_ms INTEGER NOT NULL CHECK(call_started_at_ms>0),
            first_voice_sample_at_ms INTEGER CHECK(first_voice_sample_at_ms IS NULL OR first_voice_sample_at_ms>0),
            completed_at_ms INTEGER NOT NULL CHECK(completed_at_ms>=call_started_at_ms),
            tone_count INTEGER NOT NULL CHECK(tone_count BETWEEN 1 AND 32),
            CHECK(source_code=method_code)
        ) STRICT
        """;
    private static final String TONE_SQL = """
        CREATE TABLE receiver_activity_tone (
            event_id INTEGER NOT NULL REFERENCES receiver_activity_tone_sequence(event_id) ON DELETE CASCADE,
            ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 0 AND 31),
            frequency_1_millihz INTEGER CHECK(frequency_1_millihz IS NULL OR frequency_1_millihz BETWEEN 1 AND 8000000),
            frequency_2_millihz INTEGER CHECK(frequency_2_millihz IS NULL OR frequency_2_millihz BETWEEN 1 AND 8000000),
            codec_symbol TEXT CHECK(codec_symbol IS NULL OR (length(codec_symbol) BETWEEN 1 AND 64 AND codec_symbol NOT GLOB '*[^A-Z0-9_]*')),
            offset_ms INTEGER CHECK(offset_ms IS NULL OR offset_ms>=0),
            duration_ms INTEGER NOT NULL CHECK(duration_ms>0),
            confidence_ppm INTEGER CHECK(confidence_ppm IS NULL OR confidence_ppm BETWEEN 0 AND 1000000),
            PRIMARY KEY(event_id,ordinal),
            CHECK(frequency_2_millihz IS NULL OR frequency_1_millihz IS NOT NULL),
            CHECK(frequency_1_millihz IS NOT NULL OR codec_symbol IS NOT NULL)
        ) WITHOUT ROWID, STRICT
        """;

    /** Fresh creation and the sole adjacent migrator use identical authoritative definitions. */
    public static void upgradeFrom49(Connection connection) throws SQLException
    {
        List<String> indexes = new ArrayList<>(); List<String[]> statistics = new ArrayList<>();
        List<IndexSample> samples = new ArrayList<>(); Long sequence;
        try(Statement statement = connection.createStatement())
        {
            //The owned migrator disables foreign keys before its one transaction.
            //Refuse a populated rebuild elsewhere rather than cascading history.
            try(var row = statement.executeQuery("SELECT (SELECT foreign_keys FROM pragma_foreign_keys), EXISTS(SELECT 1 FROM receiver_activity_event LIMIT 1)"))
            { row.next(); if(row.getInt(1) != 0 && row.getInt(2) != 0) throw new SQLException("Populated tone upgrade requires the Application Migrator boundary"); }
            //Preserve index creation order as well as definitions: SQLite uses it to resolve equal-cost plans.
            try(var rows = statement.executeQuery("SELECT sql FROM sqlite_schema WHERE type='index' AND tbl_name='receiver_activity_event' AND sql IS NOT NULL ORDER BY rowid"))
            { while(rows.next()) indexes.add(rows.getString(1)); }
            try(var exists = statement.executeQuery("SELECT 1 FROM sqlite_schema WHERE name='sqlite_stat1'"))
            {
                if(exists.next()) try(Statement stats = connection.createStatement();
                    var rows = stats.executeQuery("SELECT idx,stat FROM sqlite_stat1 WHERE tbl='receiver_activity_event'"))
                { while(rows.next()) statistics.add(new String[]{rows.getString(1), rows.getString(2)}); }
            }
            try(var exists = statement.executeQuery("SELECT 1 FROM sqlite_schema WHERE name='sqlite_stat4'"))
            {
                if(exists.next()) try(Statement stats = connection.createStatement();
                    var rows = stats.executeQuery("SELECT idx,neq,nlt,ndlt,sample FROM sqlite_stat4 WHERE tbl='receiver_activity_event'"))
                {
                    while(rows.next()) samples.add(new IndexSample(rows.getString(1), rows.getString(2),
                        rows.getString(3), rows.getString(4), rows.getBytes(5)));
                }
            }
            try(var row = statement.executeQuery("SELECT seq FROM sqlite_sequence WHERE name='receiver_activity_event'"))
            { sequence = row.next() ? row.getLong(1) : null; }
            statement.executeUpdate("DROP VIEW receiver_activity_event_resolved");
            ReceiverActivitySchema.createFormat50ReceiverActivityEventMigrationTable(connection);
            String columns = "id,channel_id,radio_system_id,observed_at_ms,action_code,event_type_code," +
                "source_observed_local_id,target_observed_local_id,target_kind_code,source_identity_summary_id," +
                "source_identity_kind_code,target_identity_summary_id,frequency_hz,lcn_band,lcn_number,timeslot," +
                "encrypted,encryption_algorithm_id,encryption_key_id,observed_nac,observed_rfss,observed_site," +
                "source_observed_working_id,target_observed_working_id";
            statement.executeUpdate("INSERT INTO receiver_activity_event_format50(" + columns + ") SELECT " + columns +
                " FROM receiver_activity_event");
            statement.executeUpdate("DROP TABLE receiver_activity_event");
            statement.executeUpdate("ALTER TABLE receiver_activity_event_format50 RENAME TO receiver_activity_event");
            for(String sql: indexes) statement.executeUpdate(sql);
            if(!statistics.isEmpty()) try(PreparedStatement restore = connection.prepareStatement(
                "INSERT INTO sqlite_stat1(tbl,idx,stat) VALUES('receiver_activity_event',?,?)"))
            {
                for(String[] row: statistics) { restore.setString(1, row[0]); restore.setString(2, row[1]); restore.addBatch(); }
                restore.executeBatch();
            }
            if(!samples.isEmpty()) try(PreparedStatement restore = connection.prepareStatement(
                "INSERT INTO sqlite_stat4(tbl,idx,neq,nlt,ndlt,sample) VALUES('receiver_activity_event',?,?,?,?,?)"))
            {
                for(IndexSample row: samples)
                {
                    restore.setString(1,row.index()); restore.setString(2,row.equal()); restore.setString(3,row.less());
                    restore.setString(4,row.distinct()); restore.setBytes(5,row.sample()); restore.addBatch();
                }
                restore.executeBatch();
            }
            if(sequence != null)
            {
                int restored = statement.executeUpdate("UPDATE sqlite_sequence SET seq=max(seq," + sequence + ") WHERE name='receiver_activity_event'");
                if(restored == 0) statement.executeUpdate("INSERT INTO sqlite_sequence(name,seq) VALUES('receiver_activity_event'," + sequence + ")");
            }
            else statement.executeUpdate("DELETE FROM sqlite_sequence WHERE name='receiver_activity_event' AND NOT EXISTS(SELECT 1 FROM receiver_activity_event)");
            ReceiverActivitySchema.createFormat50ResolvedView(connection);
            statement.executeUpdate(SEQUENCE_SQL); statement.executeUpdate(TONE_SQL);
            //Reload the preserved estimates without collecting new statistics or changing retained samples.
            if(!statistics.isEmpty() || !samples.isEmpty()) statement.execute("ANALYZE sqlite_schema");
        }
    }

    public static void validate(Connection connection) throws SQLException
    {
        SqliteSchemaValidator.validateDefinitions(connection, List.of(
            new SqliteSchemaValidator.Definition("table", "receiver_activity_tone_sequence", SEQUENCE_SQL),
            new SqliteSchemaValidator.Definition("table", "receiver_activity_tone", TONE_SQL)));
    }

    static boolean record(Connection connection, ReceiverActivityRecords.CompletedToneObservation observation,
                          boolean detailedHistoryEnabled) throws SQLException
    {
        if(!detailedHistoryEnabled || observation.sequence().tones().isEmpty()) return false;
        Long id = ReceiverActivitySchema.insertDetailedToneEvent(connection, observation.activity());
        if(id == null) return false;
        CompletedToneSequence sequence = observation.sequence();
        DecoderType decoder = sequence.callLegSource().decoderType();
        Integer phase = decoder == DecoderType.P25_PHASE1 || decoder == DecoderType.P25_CONVENTIONAL ? Integer.valueOf(1) :
            decoder == DecoderType.P25_PHASE2 ? Integer.valueOf(2) : null;
        try(PreparedStatement statement = connection.prepareStatement("INSERT INTO receiver_activity_tone_sequence VALUES(?,?,?,?,?,?,?,?)"))
        {
            statement.setLong(1, id); statement.setInt(2, sequence.source().code()); statement.setInt(3, sequence.method().code());
            optionalLong(statement, 4, phase != null ? phase.longValue() : null);
            statement.setLong(5, sequence.callStartTimestamp());
            optionalLong(statement, 6, sequence.firstVoiceSampleTimestamp() > 0 ? sequence.firstVoiceSampleTimestamp() : null);
            statement.setLong(7, sequence.completedTimestamp()); statement.setInt(8, sequence.tones().size()); statement.executeUpdate();
        }
        try(PreparedStatement statement = connection.prepareStatement("INSERT INTO receiver_activity_tone VALUES(?,?,?,?,?,?,?,?)"))
        {
            int ordinal = 0;
            for(CompletedToneSequence.Element tone: sequence.tones())
            {
                statement.setLong(1, id); statement.setInt(2, ordinal++);
                optionalLong(statement, 3, tone.frequenciesMilliHertz().isEmpty() ? null : tone.frequenciesMilliHertz().getFirst().longValue());
                optionalLong(statement, 4, tone.frequenciesMilliHertz().size() < 2 ? null : tone.frequenciesMilliHertz().get(1).longValue());
                statement.setString(5, tone.codecSymbol()); optionalLong(statement, 6, tone.offsetMilliseconds());
                statement.setLong(7, tone.durationMilliseconds());
                optionalLong(statement, 8, tone.confidence() != null ? Math.round(tone.confidence() * 1_000_000) : null);
                statement.addBatch();
            }
            statement.executeBatch();
        }
        return true;
    }

    private static void optionalLong(PreparedStatement statement, int column, Long value) throws SQLException
    { if(value == null) statement.setNull(column, Types.INTEGER); else statement.setLong(column, value); }

    /** Hydrate only the already limited page; primary-key seeks preserve its order and ownership. */
    public static void enrichPage(Connection connection, List<Map<String,Object>> rows) throws SQLException
    {
        List<Long> ids = rows.stream().filter(row -> "TONE".equals(row.get("event_type")))
            .map(row -> ((Number)row.get("id")).longValue()).toList();
        if(ids.isEmpty()) return;
        if(ids.size() > 500) throw new IllegalArgumentException("Tone page exceeds Activity bound");
        Map<Long,StoredSequence> summaries = new LinkedHashMap<>();
        Set<Long> incomplete = new HashSet<>();
        String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(","));
        try(PreparedStatement statement = connection.prepareStatement("SELECT event_id,source_code,method_code,phase,call_started_at_ms,first_voice_sample_at_ms,completed_at_ms,tone_count FROM receiver_activity_tone_sequence WHERE event_id IN (" + placeholders + ")"))
        {
            int column = 1; for(Long id: ids) statement.setLong(column++, id);
            try(var result = statement.executeQuery())
            {
                while(result.next())
                {
                    Number phase = (Number)result.getObject("phase"); Number first = (Number)result.getObject("first_voice_sample_at_ms");
                    summaries.put(result.getLong("event_id"), new StoredSequence(result.getInt("source_code"), result.getInt("method_code"),
                        phase != null ? phase.intValue() : null, result.getLong("call_started_at_ms"), first != null ? first.longValue() : null,
                        result.getLong("completed_at_ms"), result.getInt("tone_count"), new ArrayList<>()));
                }
            }
        }
        try(PreparedStatement statement = connection.prepareStatement("SELECT event_id,ordinal,frequency_1_millihz,frequency_2_millihz,codec_symbol,offset_ms,duration_ms,confidence_ppm FROM receiver_activity_tone WHERE event_id IN (" + placeholders + ") ORDER BY event_id,ordinal"))
        {
            int column = 1; for(Long id: ids) statement.setLong(column++, id);
            try(var result = statement.executeQuery())
            {
                while(result.next())
                {
                    StoredSequence summary = summaries.get(result.getLong("event_id"));
                    if(summary == null || summary.tones().size() >= CompletedToneSequence.MAXIMUM_TONES)
                        throw new SQLException("Stored tone context exceeds bound");
                    if(result.getInt("ordinal") != summary.tones().size()) incomplete.add(result.getLong("event_id"));
                    List<Integer> frequencies = new ArrayList<>();
                    for(String name: List.of("frequency_1_millihz", "frequency_2_millihz"))
                    { Object value = result.getObject(name); if(value instanceof Number number) frequencies.add(number.intValue()); }
                    Number offset = (Number)result.getObject("offset_ms"); Number confidence = (Number)result.getObject("confidence_ppm");
                    summary.tones().add(new CompletedToneSequence.Element(frequencies, result.getString("codec_symbol"),
                        offset != null ? offset.longValue() : null, result.getLong("duration_ms"), confidence != null ? confidence.doubleValue() / 1_000_000. : null));
                }
            }
        }
        for(Map<String,Object> row: rows)
        {
            StoredSequence summary = summaries.get(((Number)row.get("id")).longValue());
            if(summary != null)
            {
                //Child-first retention can leave an expired parent visible until its later bounded deletion.
                //Do not publish a partial sequence or fail unrelated rows in that already limited page.
                if(summary.count() != summary.tones().size() || incomplete.contains(((Number)row.get("id")).longValue())) continue;
                row.put("paging_tone_summary", ToneSummaryPayload.summary(summary.source(), summary.method(), summary.phase(),
                    summary.started(), summary.firstVoice(), summary.completed(), summary.tones()));
            }
        }
    }

    private record StoredSequence(int source, int method, Integer phase, long started, Long firstVoice, long completed,
                                  int count, List<CompletedToneSequence.Element> tones) { }
    private record IndexSample(String index, String equal, String less, String distinct, byte[] sample) { }

}
