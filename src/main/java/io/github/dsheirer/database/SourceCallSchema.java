/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database;

/** Shared persisted shape of an exact source call reference; it never identifies a call by time or radio fields. */
public final class SourceCallSchema
{
    private SourceCallSchema() {}

    public static final String COLUMN_DEFINITION = """
        source_call_uuid TEXT CHECK(source_call_uuid IS NULL OR
            (typeof(source_call_uuid) = 'text' AND length(source_call_uuid) = 36
                AND substr(source_call_uuid,9,1) = '-' AND substr(source_call_uuid,14,1) = '-'
                AND substr(source_call_uuid,19,1) = '-' AND substr(source_call_uuid,24,1) = '-'
                AND length(replace(source_call_uuid,'-','')) = 32
                AND replace(source_call_uuid,'-','') NOT GLOB '*[^0-9a-f]*'))
        """.strip();
}
