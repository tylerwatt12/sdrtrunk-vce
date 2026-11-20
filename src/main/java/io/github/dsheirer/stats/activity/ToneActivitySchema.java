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
    private static final String FORMAT_50_SEQUENCE_SQL = """
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
    private static final String SEQUENCE_SQL = FORMAT_50_SEQUENCE_SQL
        .replace("method_code IN (1,2)", "method_code IN (1,2,3)")
        .replace("CHECK(source_code=method_code)",
            "CHECK((source_code=1 AND method_code IN (1,3)) OR (source_code=2 AND method_code=2))");
    private static final String CURRENT_SEQUENCE_SQL = SEQUENCE_SQL
        .replace("method_code IN (1,2,3)", "method_code IN (1,2,3,4)")
        .replace("method_code IN (1,3)", "method_code IN (1,3,4)");
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

    /** Frozen format-50/51 definition for the historical adjacent step. */
    public static void upgradeFrom49(Connection connection) throws SQLException
    {
        addToneHistory(connection, FORMAT_50_SEQUENCE_SQL);
    }

    /** Fresh current schema admits the same methods as the adjacent format-54-to-55 step. */
    public static void createCurrent(Connection connection) throws SQLException
    {
        addToneHistory(connection, CURRENT_SEQUENCE_SQL);
    }

    private static void addToneHistory(Connection connection, String sequenceSql) throws SQLException
    {
        // Refuse an unsafe populated call before changing its view; empty fresh creation needs no migrator lock.
        ActivityMessageSchemaMigration.requirePopulatedBoundary(connection, "receiver_activity_event");
        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DROP VIEW receiver_activity_event_resolved");
            ReceiverActivitySchema.upgradeToneEventConstraints(connection);
            ReceiverActivitySchema.createFormat50ResolvedView(connection);
            statement.executeUpdate(sequenceSql);
            statement.executeUpdate(TONE_SQL);
        }
    }

    /** Preserve legacy raw/encoded rows; only admit the explicit new prospective nominal PCM method. */
    public static void upgradeFrom51(Connection connection) throws SQLException
    {
        ActivityMessageSchemaMigration.replaceTableDefinition(connection, "main", "receiver_activity_tone_sequence",
            FORMAT_50_SEQUENCE_SQL, SEQUENCE_SQL);
    }

    /** Adjacent Application Migrator step; retained tone rows keep their existing parent and method. */
    public static void upgradeFrom54(Connection connection) throws SQLException
    {
        ActivityMessageSchemaMigration.replaceTableDefinition(connection, "main", "receiver_activity_tone_sequence",
            SEQUENCE_SQL, CURRENT_SEQUENCE_SQL);
    }

    public static void validate(Connection connection) throws SQLException
    {
        SqliteSchemaValidator.validateDefinitions(connection, List.of(
            new SqliteSchemaValidator.Definition("table", "receiver_activity_tone_sequence", CURRENT_SEQUENCE_SQL),
            new SqliteSchemaValidator.Definition("table", "receiver_activity_tone", TONE_SQL)));
    }

    /** New evidence annotates an exact existing call. No synthetic Activity event is created. */
    static boolean record(Connection connection, ReceiverActivityRecords.CompletedToneObservation observation,
                          boolean detailedHistoryEnabled, Long id) throws SQLException
    {
        if(!detailedHistoryEnabled || id == null || observation.sequence().tones().isEmpty()) return false;
        try(PreparedStatement parent = connection.prepareStatement("SELECT 1 FROM receiver_activity_event WHERE id=? AND action_code=4"))
        {
            parent.setLong(1, id);
            try(var rows = parent.executeQuery()) { if(!rows.next()) return false; }
        }
        CompletedToneSequence sequence = observation.sequence();
        int firstOrdinal = 0;
        long offsetShift = 0;
        boolean knownOffsetShift = true;
        try(PreparedStatement existing = connection.prepareStatement("SELECT source_code,method_code,tone_count,first_voice_sample_at_ms,completed_at_ms FROM receiver_activity_tone_sequence WHERE event_id=?"))
        {
            existing.setLong(1, id);
            try(var rows = existing.executeQuery())
            {
                if(rows.next())
                {
                    firstOrdinal = rows.getInt(3);
                    if(rows.getInt(1) != sequence.source().code() || rows.getInt(2) != sequence.method().code() ||
                        firstOrdinal + sequence.tones().size() > CompletedToneSequence.MAXIMUM_TONES ||
                        sequence.completedTimestamp() < rows.getLong(5)) return false;
                    Number firstVoice = (Number)rows.getObject(4);
                    knownOffsetShift = firstVoice != null && sequence.firstVoiceSampleTimestamp() > 0;
                    if(knownOffsetShift) offsetShift = sequence.firstVoiceSampleTimestamp() - firstVoice.longValue();
                }
            }
        }
        DecoderType decoder = sequence.callLegSource().decoderType();
        Integer phase = decoder == DecoderType.P25_PHASE1 || decoder == DecoderType.P25_CONVENTIONAL ? Integer.valueOf(1) :
            decoder == DecoderType.P25_PHASE2 ? Integer.valueOf(2) : null;
        if(firstOrdinal > 0)
        {
            try(PreparedStatement update = connection.prepareStatement("UPDATE receiver_activity_tone_sequence SET completed_at_ms=?,tone_count=? WHERE event_id=?"))
            {
                update.setLong(1, sequence.completedTimestamp()); update.setInt(2, firstOrdinal + sequence.tones().size());
                update.setLong(3, id); update.executeUpdate();
            }
        }
        else try(PreparedStatement statement = connection.prepareStatement("INSERT INTO receiver_activity_tone_sequence VALUES(?,?,?,?,?,?,?,?)"))
        {
            statement.setLong(1, id); statement.setInt(2, sequence.source().code()); statement.setInt(3, sequence.method().code());
            optionalLong(statement, 4, phase != null ? phase.longValue() : null);
            statement.setLong(5, sequence.callStartTimestamp());
            optionalLong(statement, 6, sequence.firstVoiceSampleTimestamp() > 0 ? sequence.firstVoiceSampleTimestamp() : null);
            statement.setLong(7, sequence.completedTimestamp()); statement.setInt(8, sequence.tones().size()); statement.executeUpdate();
        }
        try(PreparedStatement statement = connection.prepareStatement("INSERT INTO receiver_activity_tone VALUES(?,?,?,?,?,?,?,?)"))
        {
            int ordinal = firstOrdinal;
            for(CompletedToneSequence.Element tone: sequence.tones())
            {
                statement.setLong(1, id); statement.setInt(2, ordinal++);
                optionalLong(statement, 3, tone.frequenciesMilliHertz().isEmpty() ? null : tone.frequenciesMilliHertz().getFirst().longValue());
                optionalLong(statement, 4, tone.frequenciesMilliHertz().size() < 2 ? null : tone.frequenciesMilliHertz().get(1).longValue());
                statement.setString(5, tone.codecSymbol()); optionalLong(statement, 6,
                    knownOffsetShift && tone.offsetMilliseconds() != null ? tone.offsetMilliseconds() + offsetShift : null);
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
        for(int start = 0; start < rows.size(); start += 500)
            enrichBoundedPage(connection, rows.subList(start, Math.min(start + 500, rows.size())));
    }

    private static void enrichBoundedPage(Connection connection, List<Map<String,Object>> rows) throws SQLException
    {
        List<Long> ids = rows.stream().map(row -> ((Number)row.get("id")).longValue()).toList();
        if(ids.isEmpty()) return;
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

}
