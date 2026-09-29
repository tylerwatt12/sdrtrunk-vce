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

import java.util.LinkedHashMap;
import java.util.Map;

/** Exact DDL for Managed Recordings catalogs. Existing files are validated only at startup. */
public final class ManagedRecordingSchema
{
    public static final int APPLICATION_ID = 0x56434552; // VCER
    public static final int CURRENT_FORMAT_VERSION = 3;
    public static final String TRANSCRIPTION_STATUS_COLUMN = "transcription_status TEXT NOT NULL " +
        "DEFAULT 'pending' CHECK(transcription_status IN('pending','complete','failed'))";
    private static final Map<String,String> FORMAT_ONE_DDL = formatOneSchema();
    private static final Map<String,String> FORMAT_TWO_DDL = formatTwoSchema();
    static final Map<String,String> DDL = formatThreeSchema();

    private ManagedRecordingSchema() {}

    /** Frozen prior signatures and the current signature for the dedicated catalog migrator. */
    public static Map<String,String> ddlForFormat(int version)
    {
        return switch(version)
        {
            case 1 -> FORMAT_ONE_DDL;
            case 2 -> FORMAT_TWO_DDL;
            case 3 -> DDL;
            default -> throw new IllegalArgumentException("Unsupported managed recordings catalog format: " + version);
        };
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
