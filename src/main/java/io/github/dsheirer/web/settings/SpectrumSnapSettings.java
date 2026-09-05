/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.settings;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.database.SdrTrunkDatabase;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

/** One bounded receiver-wide country selection for the code-owned spectrum-snap catalog. */
public record SpectrumSnapSettings(long revision, String countryCode)
{
    public static final String KEY = "spectrum_snap_country";
    public static final String DEFAULT_COUNTRY_CODE = "US";
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public SpectrumSnapSettings
    {
        if(revision < 1)
        {
            throw new IllegalArgumentException("Spectrum-snap settings revision must be positive");
        }
        countryCode = SpectrumSnapPresetCatalog.requireCountry(countryCode).code();
    }

    public static SpectrumSnapSettings defaults()
    {
        return new SpectrumSnapSettings(1, DEFAULT_COUNTRY_CODE);
    }

    public String encode()
    {
        JsonNode root = JSON.createObjectNode().put("revision", revision).put("country_code", countryCode);
        return root.toString();
    }

    public static SpectrumSnapSettings decode(String encoded) throws SQLException
    {
        try
        {
            if(encoded == null || encoded.length() > 256)
            {
                throw new IllegalArgumentException();
            }
            JsonNode root = JSON.readTree(encoded);
            if(!root.isObject() || root.size() != 2 || !root.path("revision").canConvertToLong() ||
                !root.path("country_code").isTextual())
            {
                throw new IllegalArgumentException();
            }
            return new SpectrumSnapSettings(root.get("revision").longValue(), root.get("country_code").textValue());
        }
        catch(Exception exception)
        {
            throw new SQLException("Invalid spectrum-snap country selection; the saved profile was not changed.",
                exception);
        }
    }

    public static SpectrumSnapSettings read(Connection connection) throws SQLException
    {
        try(var query = connection.prepareStatement(
            "SELECT settings_json FROM application_settings WHERE key=?"))
        {
            query.setString(1, KEY);
            try(var rows = query.executeQuery())
            {
                if(!rows.next())
                {
                    throw new SQLException("Missing spectrum-snap country selection. Run the Application Migrator.");
                }
                return decode(rows.getString(1));
            }
        }
    }

    public static SpectrumSnapSettings read(Path database) throws IOException, SQLException
    {
        try(Connection connection = SdrTrunkDatabase.open(database))
        {
            return read(connection);
        }
    }

    public static void write(Connection connection, SpectrumSnapSettings settings) throws SQLException
    {
        try(var update = connection.prepareStatement("""
            INSERT INTO application_settings(key, settings_json, updated_at_ms) VALUES(?, ?, ?)
            ON CONFLICT(key) DO UPDATE SET settings_json=excluded.settings_json, updated_at_ms=excluded.updated_at_ms
            """))
        {
            update.setString(1, KEY);
            update.setString(2, settings.encode());
            update.setLong(3, System.currentTimeMillis());
            update.executeUpdate();
        }
    }
}
