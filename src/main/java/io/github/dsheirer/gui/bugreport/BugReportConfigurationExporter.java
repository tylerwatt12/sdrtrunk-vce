/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.gui.bugreport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.dsheirer.database.SdrTrunkDatabase;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only exporter for the configuration portions of the portable SQLite database. The raw database is never copied.
 */
public class BugReportConfigurationExporter
{
    private static final List<String> CONFIGURATION_TABLES = List.of(
        "database_metadata",
        "alias_list",
        "alias",
        "alias_broadcast_channel",
        "configuration_channel",
        "configuration_channel_map",
        "configuration_broadcast_stream",
        "application_settings",
        "application_icons"
    );
    private final ObjectMapper mObjectMapper;
    private final BugReportRedactor mRedactor;

    public BugReportConfigurationExporter(ObjectMapper objectMapper, BugReportRedactor redactor)
    {
        mObjectMapper = objectMapper;
        mRedactor = redactor;
    }

    public Map<String,List<Map<String,Object>>> export(Path databasePath) throws IOException, SQLException
    {
        Map<String,List<Map<String,Object>>> snapshot = new LinkedHashMap<>();

        try(Connection connection = SdrTrunkDatabase.open(databasePath))
        {
            connection.setAutoCommit(false);

            try
            {
                for(String table: CONFIGURATION_TABLES)
                {
                    snapshot.put(table, exportTable(connection, table));
                }
            }
            finally
            {
                connection.rollback();
            }
        }

        return snapshot;
    }

    private List<Map<String,Object>> exportTable(Connection connection, String table) throws SQLException
    {
        List<Map<String,Object>> rows = new ArrayList<>();

        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT * FROM " + table))
        {
            ResultSetMetaData metadata = resultSet.getMetaData();

            while(resultSet.next())
            {
                Map<String,Object> row = new LinkedHashMap<>();

                for(int column = 1; column <= metadata.getColumnCount(); column++)
                {
                    String name = metadata.getColumnLabel(column);
                    Object value = resultSet.getObject(column);
                    row.put(name, sanitize(name, value));
                }

                rows.add(row);
            }
        }

        return rows;
    }

    private Object sanitize(String fieldName, Object value)
    {
        if(value instanceof String text)
        {
            if(fieldName.endsWith("_json"))
            {
                try
                {
                    JsonNode json = mObjectMapper.readTree(text);
                    return mRedactor.redact(json);
                }
                catch(Exception e)
                {
                    ObjectNode invalidJson = mObjectMapper.createObjectNode();
                    invalidJson.put("export_error", "Stored JSON could not be parsed");
                    invalidJson.put("raw_value", "<omitted because it could not be safely inspected>");
                    return invalidJson;
                }
            }

            return mRedactor.redactFieldValue(fieldName, text);
        }

        if(value instanceof byte[] bytes)
        {
            return "<binary data omitted: " + bytes.length + " bytes>";
        }

        return value;
    }
}
