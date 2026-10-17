/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.record.managed;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/** Exact DDL for Managed Recordings catalogs. Existing files are validated only at startup. */
public final class ManagedRecordingSchema
{
    public static final int APPLICATION_ID = 0x56434552; // VCER
    public static final int CURRENT_FORMAT_VERSION = 11;
    public static final String TRANSCRIPTION_STATUS_COLUMN = "transcription_status TEXT NOT NULL " +
        "DEFAULT 'pending' CHECK(transcription_status IN('pending','complete','failed'))";
    private static final Map<String,String> FORMAT_ONE_DDL = formatOneSchema();
    private static final Map<String,String> FORMAT_TWO_DDL = formatTwoSchema();
    private static final Map<String,String> FORMAT_THREE_DDL = formatThreeSchema();
    private static final Map<String,String> FORMAT_FOUR_DDL = formatFourSchema();
    private static final Map<String,String> FORMAT_FIVE_DDL = formatFiveSchema();
    private static final Map<String,String> FORMAT_SIX_DDL = formatSixSchema();
    private static final Map<String,String> FORMAT_SEVEN_DDL = formatSevenSchema();
    private static final Map<String,String> FORMAT_EIGHT_DDL = formatEightSchema();
    private static final Map<String,String> FORMAT_NINE_DDL = formatNineSchema();
    // Frozen format 10 extends nominal tone identities without changing their stored representation.
    private static final Map<String,String> FORMAT_TEN_DDL = FORMAT_NINE_DDL;
    static final Map<String,String> DDL = formatElevenSchema();

    private ManagedRecordingSchema() {}

    /** Frozen prior signatures and the current signature for the dedicated catalog migrator. */
    public static Map<String,String> ddlForFormat(int version)
    {
        return switch(version)
        {
            case 1 -> FORMAT_ONE_DDL;
            case 2 -> FORMAT_TWO_DDL;
            case 3 -> FORMAT_THREE_DDL;
            case 4 -> FORMAT_FOUR_DDL;
            case 5 -> FORMAT_FIVE_DDL;
            case 6 -> FORMAT_SIX_DDL;
            case 7 -> FORMAT_SEVEN_DDL;
            case 8 -> FORMAT_EIGHT_DDL;
            case 9 -> FORMAT_NINE_DDL;
            case 10 -> FORMAT_TEN_DDL;
            case 11 -> DDL;
            default -> throw new IllegalArgumentException("Unsupported managed recordings catalog format: " + version);
        };
    }

    /** FTS5 creates its registered shadow tables itself; they remain part of exact read-only admission. */
    public static java.util.List<String> creationStatements()
    {
        return DDL.entrySet().stream().filter(entry -> !isTranscriptIndexShadow(entry.getKey()))
            .map(Map.Entry::getValue).toList();
    }

    private static boolean isTranscriptIndexShadow(String name)
    {
        return java.util.Set.of("recording_transcript_fts_config", "recording_transcript_fts_data",
            "recording_transcript_fts_docsize", "recording_transcript_fts_idx").contains(name);
    }

    private static Map<String,String> formatEightSchema()
    {
        Map<String,String> schema = new LinkedHashMap<>(FORMAT_SEVEN_DDL);
        schema.put("recording_transcript_fts", "CREATE VIRTUAL TABLE recording_transcript_fts USING " +
            "fts5(text,content='recording_transcript',content_rowid='call_id',tokenize='unicode61')");
        schema.put("recording_transcript_fts_config", "CREATE TABLE 'recording_transcript_fts_config'(k PRIMARY KEY, v) WITHOUT ROWID");
        schema.put("recording_transcript_fts_data", "CREATE TABLE 'recording_transcript_fts_data'(id INTEGER PRIMARY KEY, block BLOB)");
        schema.put("recording_transcript_fts_docsize", "CREATE TABLE 'recording_transcript_fts_docsize'(id INTEGER PRIMARY KEY, sz BLOB)");
        schema.put("recording_transcript_fts_idx", "CREATE TABLE 'recording_transcript_fts_idx'(segid, term, pgno, PRIMARY KEY(segid, term)) WITHOUT ROWID");
        schema.put("recording_transcript_fts_insert", "CREATE TRIGGER recording_transcript_fts_insert AFTER INSERT ON recording_transcript BEGIN " +
            "INSERT INTO recording_transcript_fts(rowid,text) VALUES(new.call_id,new.text); END");
        schema.put("recording_transcript_fts_delete", "CREATE TRIGGER recording_transcript_fts_delete AFTER DELETE ON recording_transcript BEGIN " +
            "INSERT INTO recording_transcript_fts(recording_transcript_fts,rowid,text) VALUES('delete',old.call_id,old.text); END");
        schema.put("recording_transcript_fts_update", "CREATE TRIGGER recording_transcript_fts_update AFTER UPDATE ON recording_transcript BEGIN " +
            "INSERT INTO recording_transcript_fts(recording_transcript_fts,rowid,text) VALUES('delete',old.call_id,old.text); " +
            "INSERT INTO recording_transcript_fts(rowid,text) VALUES(new.call_id,new.text); END");
        return java.util.Collections.unmodifiableMap(schema);
    }

    private static Map<String,String> formatNineSchema()
    {
        Map<String,String> schema = new LinkedHashMap<>(FORMAT_EIGHT_DDL);
        schema.put("recording_tone_sequence", FORMAT_EIGHT_DDL.get("recording_tone_sequence")
            .replace("method_code IN(1,2,3)", "method_code IN(1,2,3,4)")
            .replace("method_code IN(1,3)", "method_code IN(1,3,4)"));
        return java.util.Collections.unmodifiableMap(schema);
    }

    /** Exact prospective source-call relationships; no cross-database row IDs or historical guessing. */
    private static Map<String,String> formatElevenSchema()
    {
        Map<String,String> schema = new LinkedHashMap<>(FORMAT_TEN_DDL);
        schema.put("recording_source", "CREATE TABLE recording_source (" +
            "call_id INTEGER NOT NULL REFERENCES recording_call(id) ON DELETE CASCADE," +
            "source_call_uuid TEXT NOT NULL CHECK(length(source_call_uuid)=36 AND " +
                "substr(source_call_uuid,9,1)='-' AND substr(source_call_uuid,14,1)='-' AND " +
                "substr(source_call_uuid,19,1)='-' AND substr(source_call_uuid,24,1)='-' AND " +
                "length(replace(source_call_uuid,'-',''))=32 AND " +
                "replace(source_call_uuid,'-','') NOT GLOB '*[^0-9a-f]*')," +
            "PRIMARY KEY(call_id,source_call_uuid)) WITHOUT ROWID, STRICT");
        schema.put("idx_recording_source_uuid", "CREATE INDEX idx_recording_source_uuid " +
            "ON recording_source(source_call_uuid,call_id)");
        return java.util.Collections.unmodifiableMap(schema);
    }

    /** Exact, read-only admission shared by normal startup and the bundled catalog migrator. */
    public static void validate(Connection connection, int version) throws SQLException
    {
        if(pragmaInt(connection, "application_id") != APPLICATION_ID ||
            pragmaInt(connection, "user_version") != version)
            throw new SQLException("Unrecognized managed recordings catalog identity or version.");
        Map<String,String> actual = new LinkedHashMap<>();
        try(Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("SELECT name,sql FROM sqlite_master " +
                "WHERE type IN ('table','index','view','trigger') AND name NOT GLOB 'sqlite_*' ORDER BY name"))
        {
            while(rows.next()) actual.put(rows.getString(1), rows.getString(2));
        }
        if(!actual.equals(ddlForFormat(version)))
            throw new SQLException("Managed recordings catalog schema is unknown or partially migrated.");
        try(Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("SELECT format_version,call_count,total_bytes FROM catalog_metadata WHERE id=1"))
        {
            if(!rows.next() || rows.getInt(1) != version || rows.getLong(2) < 0 || rows.getLong(3) < 0 || rows.next())
                throw new SQLException("Managed recordings catalog metadata is invalid.");
        }
    }

    private static int pragmaInt(Connection connection, String name) throws SQLException
    {
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("PRAGMA " + name))
        {
            if(!rows.next()) throw new SQLException("Missing SQLite " + name + " pragma.");
            return rows.getInt(1);
        }
    }

    private static Map<String,String> formatTwoSchema()
    {
        Map<String,String> schema = new LinkedHashMap<>(FORMAT_ONE_DDL);
        schema.put("recording_transcript", "CREATE TABLE recording_transcript (" +
            "call_id INTEGER PRIMARY KEY REFERENCES recording_call(id) ON DELETE CASCADE," +
            "text TEXT NOT NULL,stored_at_ms INTEGER NOT NULL CHECK(stored_at_ms>=0)) STRICT");
        return java.util.Collections.unmodifiableMap(schema);
    }

    private static Map<String,String> formatThreeSchema()
    {
        Map<String,String> schema = new LinkedHashMap<>(FORMAT_TWO_DDL);
        // SQLite's ADD COLUMN inserts the new definition before the table CHECK. Match that exact spelling so
        // fresh catalogs and migrated catalogs share one signature.
        schema.put("recording_call", FORMAT_TWO_DDL.get("recording_call").replace(
            ",CHECK(start_ms", ", " + TRANSCRIPTION_STATUS_COLUMN + ",CHECK(start_ms"));
        schema.put("idx_recording_call_transcription_pending",
            "CREATE INDEX idx_recording_call_transcription_pending " +
                "ON recording_call(id,duration_ms) WHERE transcription_status='pending'");
        return java.util.Collections.unmodifiableMap(schema);
    }

    private static Map<String,String> formatFourSchema()
    {
        Map<String,String> schema = new LinkedHashMap<>(FORMAT_THREE_DDL);
        // Frozen format 4: do not obtain historical constraints from the current tone creator.
        schema.put("recording_tone_sequence", "CREATE TABLE recording_tone_sequence (" +
            "call_id INTEGER PRIMARY KEY REFERENCES recording_call(id) ON DELETE CASCADE," +
            "source_code INTEGER NOT NULL CHECK(source_code IN(1,2))," +
            "method_code INTEGER NOT NULL CHECK(method_code IN(1,2))," +
            "phase INTEGER CHECK(phase IS NULL OR phase IN(1,2))," +
            "call_started_at_ms INTEGER NOT NULL CHECK(call_started_at_ms>0)," +
            "first_voice_sample_at_ms INTEGER CHECK(first_voice_sample_at_ms IS NULL OR first_voice_sample_at_ms>0)," +
            "completed_at_ms INTEGER NOT NULL CHECK(completed_at_ms>=call_started_at_ms)," +
            "tone_count INTEGER NOT NULL CHECK(tone_count BETWEEN 1 AND 32)," +
            "CHECK(source_code=method_code)) STRICT");
        schema.put("recording_tone", "CREATE TABLE recording_tone (" +
            "call_id INTEGER NOT NULL REFERENCES recording_tone_sequence(call_id) ON DELETE CASCADE," +
            "ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 0 AND 31)," +
            "frequency_1_millihz INTEGER CHECK(frequency_1_millihz IS NULL OR frequency_1_millihz BETWEEN 1 AND 8000000)," +
            "frequency_2_millihz INTEGER CHECK(frequency_2_millihz IS NULL OR frequency_2_millihz BETWEEN 1 AND 8000000)," +
            "codec_symbol TEXT CHECK(codec_symbol IS NULL OR (length(codec_symbol) BETWEEN 1 AND 64 AND codec_symbol NOT GLOB '*[^A-Z0-9_]*'))," +
            "offset_ms INTEGER CHECK(offset_ms IS NULL OR offset_ms>=0)," +
            "duration_ms INTEGER NOT NULL CHECK(duration_ms>0)," +
            "confidence_ppm INTEGER CHECK(confidence_ppm IS NULL OR confidence_ppm BETWEEN 0 AND 1000000)," +
            "PRIMARY KEY(call_id,ordinal)," +
            "CHECK(frequency_2_millihz IS NULL OR frequency_1_millihz IS NOT NULL)," +
            "CHECK(frequency_1_millihz IS NOT NULL OR codec_symbol IS NOT NULL)) WITHOUT ROWID, STRICT");
        return java.util.Collections.unmodifiableMap(schema);
    }

    private static Map<String,String> formatFiveSchema()
    {
        // Frozen format 5 must not follow future changes to the current tone creator.
        Map<String,String> schema = new LinkedHashMap<>(FORMAT_FOUR_DDL);
        schema.put("recording_tone_sequence", FORMAT_FOUR_DDL.get("recording_tone_sequence")
            .replace("method_code IN(1,2)", "method_code IN(1,2,3)")
            .replace("CHECK(source_code=method_code)",
                "CHECK((source_code=1 AND method_code IN(1,3)) OR (source_code=2 AND method_code=2))"));
        return java.util.Collections.unmodifiableMap(schema);
    }

    /** Ordered lookup paths for qualified identities and the existing categorical recording filters. */
    private static Map<String,String> formatSixSchema()
    {
        Map<String,String> schema = new LinkedHashMap<>(FORMAT_FIVE_DDL);
        schema.put("idx_recording_call_unowned_time", "CREATE INDEX idx_recording_call_unowned_time " +
            "ON recording_call(start_ms DESC,id DESC) WHERE system_id IS NULL");
        schema.put("idx_recording_call_source_home_time", "CREATE INDEX idx_recording_call_source_home_time " +
            "ON recording_call(source_home_wacn,source_home_system,source_home_id,start_ms DESC,id DESC,system_id) " +
            "WHERE source_home_id IS NOT NULL");
        schema.put("idx_recording_call_target_home_time", "CREATE INDEX idx_recording_call_target_home_time " +
            "ON recording_call(target_home_wacn,target_home_system,target_home_id,start_ms DESC,id DESC,call_type,system_id) " +
            "WHERE target_home_id IS NOT NULL");
        schema.put("idx_recording_patch_member_home_lookup", "CREATE INDEX idx_recording_patch_member_home_lookup " +
            "ON recording_patch_member(kind,home_wacn,home_system,home_id,start_ms DESC,call_id DESC,local_id)");
        schema.put("idx_recording_call_protocol_time", "CREATE INDEX idx_recording_call_protocol_time " +
            "ON recording_call(protocol,start_ms DESC,id DESC)");
        schema.put("idx_recording_call_call_type_time", "CREATE INDEX idx_recording_call_call_type_time " +
            "ON recording_call(call_type,start_ms DESC,id DESC)");
        schema.put("idx_recording_call_voice_type_time", "CREATE INDEX idx_recording_call_voice_type_time " +
            "ON recording_call(voice_type,start_ms DESC,id DESC)");
        return java.util.Collections.unmodifiableMap(schema);
    }

    /** Captured auxiliary identifiers retain their own protocol and value independently of the audio decoder. */
    private static Map<String,String> formatSevenSchema()
    {
        Map<String,String> schema = new LinkedHashMap<>(FORMAT_SIX_DDL);
        schema.put("recording_identifier", "CREATE TABLE recording_identifier (" +
            "call_id INTEGER NOT NULL REFERENCES recording_call(id) ON DELETE CASCADE," +
            "protocol TEXT NOT NULL CHECK(protocol IN('DCS','FLEETSYNC','MDC1200','LOJACK','TAIT1200'))," +
            "kind TEXT NOT NULL CHECK(kind IN('radio','tone','function'))," +
            "role TEXT NOT NULL CHECK(role IN('source','destination','other'))," +
            "value TEXT NOT NULL CHECK(length(value) BETWEEN 1 AND 128 AND length(trim(value))>0 AND instr(value,char(0))=0)," +
            "start_ms INTEGER NOT NULL CHECK(start_ms>=0)," +
            "PRIMARY KEY(call_id,protocol,kind,role,value)," +
            "CHECK((protocol='DCS' AND kind='tone') OR " +
                "(protocol IN('FLEETSYNC','MDC1200','TAIT1200') AND kind='radio') OR " +
                "(protocol='LOJACK' AND kind IN('radio','function')))) WITHOUT ROWID, STRICT");
        schema.put("idx_recording_identifier_lookup", "CREATE INDEX idx_recording_identifier_lookup " +
            "ON recording_identifier(protocol,kind,value,start_ms DESC,call_id DESC,role)");
        return java.util.Collections.unmodifiableMap(schema);
    }

    /** The original format 1 DDL must remain byte-for-byte stable for exact legacy admission. */
    private static Map<String,String> formatOneSchema()
    {
        Map<String,String> schema = new LinkedHashMap<>();
        schema.put("catalog_metadata", "CREATE TABLE catalog_metadata (" +
            "id INTEGER PRIMARY KEY CHECK(id=1),format_version INTEGER NOT NULL," +
            "call_count INTEGER NOT NULL CHECK(call_count>=0)," +
            "total_bytes INTEGER NOT NULL CHECK(total_bytes>=0)) STRICT");
        schema.put("recording_system", "CREATE TABLE recording_system (" +
            "id INTEGER PRIMARY KEY,system_key TEXT NOT NULL UNIQUE) STRICT");
        schema.put("recording_channel", "CREATE TABLE recording_channel (" +
            "id INTEGER PRIMARY KEY,channel_uuid TEXT NOT NULL UNIQUE) STRICT");
        schema.put("recording_site", "CREATE TABLE recording_site (" +
            "id INTEGER PRIMARY KEY,wacn INTEGER NOT NULL,system_id INTEGER NOT NULL," +
            "rfss INTEGER NOT NULL,site_id INTEGER NOT NULL," +
            "UNIQUE(wacn,system_id,rfss,site_id)) STRICT");
        schema.put("recording_system_site", "CREATE TABLE recording_system_site (" +
            "system_id INTEGER NOT NULL REFERENCES recording_system(id)," +
            "site_id INTEGER NOT NULL REFERENCES recording_site(id)," +
            "PRIMARY KEY(system_id,site_id)) WITHOUT ROWID, STRICT");
        schema.put("recording_call", "CREATE TABLE recording_call (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT,start_ms INTEGER NOT NULL,end_ms INTEGER NOT NULL," +
            "duration_ms INTEGER NOT NULL,relative_path TEXT NOT NULL UNIQUE,size_bytes INTEGER NOT NULL," +
            "system_id INTEGER REFERENCES recording_system(id)," +
            "channel_id INTEGER REFERENCES recording_channel(id),alias_list_id INTEGER," +
            "protocol INTEGER NOT NULL,call_type INTEGER NOT NULL,voice_type INTEGER NOT NULL," +
            "source_id INTEGER,source_home_wacn INTEGER,source_home_system INTEGER,source_home_id INTEGER," +
            "target_id INTEGER,target_home_wacn INTEGER,target_home_system INTEGER,target_home_id INTEGER," +
            "frequency_hz INTEGER,timeslot INTEGER,nac INTEGER,tone_kind INTEGER,tone TEXT," +
            "winner_site_id INTEGER REFERENCES recording_site(id)," +
            "CHECK(start_ms>=0 AND end_ms>=start_ms AND duration_ms>=0 AND size_bytes>0)) STRICT");
        schema.put("recording_call_site", "CREATE TABLE recording_call_site (" +
            "call_id INTEGER NOT NULL REFERENCES recording_call(id) ON DELETE CASCADE," +
            "site_id INTEGER NOT NULL REFERENCES recording_site(id)," +
            "start_ms INTEGER NOT NULL," +
            "PRIMARY KEY(call_id,site_id)) WITHOUT ROWID, STRICT");
        schema.put("recording_patch_member", "CREATE TABLE recording_patch_member (" +
            "call_id INTEGER NOT NULL REFERENCES recording_call(id) ON DELETE CASCADE," +
            "kind INTEGER NOT NULL,local_id INTEGER NOT NULL,home_wacn INTEGER NOT NULL," +
            "home_system INTEGER NOT NULL,home_id INTEGER NOT NULL,start_ms INTEGER NOT NULL," +
            "PRIMARY KEY(call_id,kind,local_id,home_wacn,home_system,home_id)) WITHOUT ROWID, STRICT");
        schema.put("idx_recording_call_time", "CREATE INDEX idx_recording_call_time " +
            "ON recording_call(start_ms DESC,id DESC)");
        schema.put("idx_recording_call_system_time", "CREATE INDEX idx_recording_call_system_time " +
            "ON recording_call(system_id,start_ms DESC,id DESC) WHERE system_id IS NOT NULL");
        schema.put("idx_recording_call_channel_time", "CREATE INDEX idx_recording_call_channel_time " +
            "ON recording_call(channel_id,start_ms DESC,id DESC) WHERE channel_id IS NOT NULL");
        schema.put("idx_recording_call_alias_list_time", "CREATE INDEX idx_recording_call_alias_list_time " +
            "ON recording_call(alias_list_id,start_ms DESC,id DESC) WHERE alias_list_id IS NOT NULL");
        schema.put("idx_recording_call_target_time", "CREATE INDEX idx_recording_call_target_time " +
            "ON recording_call(target_id,start_ms DESC,id DESC) WHERE target_id IS NOT NULL");
        schema.put("idx_recording_call_source_time", "CREATE INDEX idx_recording_call_source_time " +
            "ON recording_call(source_id,start_ms DESC,id DESC) WHERE source_id IS NOT NULL");
        schema.put("idx_recording_call_frequency_time", "CREATE INDEX idx_recording_call_frequency_time " +
            "ON recording_call(frequency_hz,start_ms DESC,id DESC) WHERE frequency_hz IS NOT NULL");
        schema.put("idx_recording_call_site_lookup", "CREATE INDEX idx_recording_call_site_lookup " +
            "ON recording_call_site(site_id,start_ms DESC,call_id DESC)");
        schema.put("idx_recording_patch_member_lookup", "CREATE INDEX idx_recording_patch_member_lookup " +
            "ON recording_patch_member(kind,local_id,start_ms DESC,call_id DESC)");
        return java.util.Collections.unmodifiableMap(schema);
    }
}
