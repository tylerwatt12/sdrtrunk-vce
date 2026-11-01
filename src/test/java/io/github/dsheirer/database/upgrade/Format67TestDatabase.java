/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import java.nio.file.Path;
import java.sql.DriverManager;

/** Populated format-67 configuration, derived through the frozen populated format-66 adjacent transition. */
public final class Format67TestDatabase
{
    private Format67TestDatabase() {}

    /** Frozen single-destination document for source fixtures; never serialize the current Settings record as legacy data. */
    static String remoteSettingsJson(io.github.dsheirer.remote.RemoteLinkSettingsStore.Settings settings)
        throws java.io.IOException
    {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var document = mapper.createObjectNode();
        document.put("revision", settings.revision());
        document.put("listenerEnabled", settings.listenerEnabled());
        document.put("bindAddress", settings.bindAddress());
        document.put("listenPort", settings.listenPort());
        document.set("trustedSenders", mapper.valueToTree(settings.trustedSenders()));
        document.set("outbound", mapper.valueToTree(settings.outbound()));
        return mapper.writeValueAsString(document);
    }

    public static Path create(Path database) throws Exception
    {
        Format66TestDatabase.create(database);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            new Format66To67DatabaseMigration().migrate(connection);
            DatabaseFormatCatalog.stamp(connection, 67);
            connection.commit();
        }
        return database;
    }
}
