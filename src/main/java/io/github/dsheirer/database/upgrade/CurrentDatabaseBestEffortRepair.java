/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.database.upgrade;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.audio.broadcast.BroadcastConfiguration;
import io.github.dsheirer.configuration.ChannelConfigurationPolicy;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.database.configuration.ConfigurationChannelProjection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Bounded same-format repairs for independently recoverable current-schema configuration components. */
final class CurrentDatabaseBestEffortRepair
{
    static final String STEP_ID = "repair-current-configuration-relationships";
    private static final int MAXIMUM_CONFIGURATION_JSON_BYTES = 4_194_304;
    private static final int MAXIMUM_ALIAS_LIST_NAME_BYTES = 4_096;
    private static final int MAXIMUM_SCAN_LIST_NAME_BYTES = 4_096;
    private static final int MAXIMUM_SCAN_LIST_DESCRIPTION_BYTES = 8_192;
    private static final int MAXIMUM_ALIAS_TEXT_BYTES = 4_194_304;
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final List<String> CHANNEL_ROW_OWNED_JSON_FIELDS = List.of(
        "configurationId", "system", "site", "name", "aliasListId", "aliasListName", "radioResolveId",
        "radresGuid", "radres_guid", "autoStart", "enabled", "autoStartOrder", "order", "channelType");
    private static final Set<String> REPAIRABLE_FOREIGN_KEY_TABLES = Set.of(
        "alias", "alias_broadcast_channel", "alias_list_unmatched_talkgroup_stream", "alias_scan_list_membership",
        "alias_list_unmatched_talkgroup_scan_list_membership", "configuration_channel");

    private CurrentDatabaseBestEffortRepair()
    {
    }

    static Inspection inspect(Connection connection) throws SQLException
    {
        return analyze(connection).inspection();
    }

    private static Analysis analyze(Connection connection) throws SQLException
    {
        AliasListInspection aliasLists = inspectAliasLists(connection);
        ScanListInspection scanLists = inspectScanLists(connection);
        ChannelInspection channels = inspectChannels(connection);
        ProviderInspection providers = inspectProviders(connection);
        AliasInspection aliases = inspectAliases(connection, Set.copyOf(aliasLists.invalidRowIds()));
        StreamRouteInspection streamRoutes = inspectStreamRoutes(connection, providers,
            Set.copyOf(aliases.invalidRowIds()), Set.copyOf(aliasLists.invalidRowIds()));
        MembershipInspection memberships = inspectMemberships(connection, Set.copyOf(aliases.invalidRowIds()),
            Set.copyOf(aliasLists.invalidRowIds()), Set.copyOf(scanLists.invalidRowIds()),
            scanLists.rows().stream().map(ScanListRow::id).collect(java.util.stream.Collectors.toSet()));
        GeneratedDefaultMembershipRecovery recoveredMemberships = memberships.recovery();
        long droppedMemberships = memberships.droppedRows();
        long dropped = Math.addExact(streamRoutes.droppedRows(), droppedMemberships);
        Set<Long> clearedChannelAliasLists = inspectUnusableChannelAliasLists(connection,
            Set.copyOf(channels.invalidRowIds()),
            channels.repairs(), aliasLists.invalidRowIds());
        Inspection inspection = new Inspection(dropped, streamRoutes.repairedRows(), clearedChannelAliasLists.size(),
            channels.invalidRowIds().size(),
            channels.repairs().size(),
            providers.invalidRowIds().size(), providers.repairs().size(),
            aliases.invalidRowIds().size(), aliasLists.invalidRowIds().size(),
            scanLists.invalidRowIds().size(), aliasLists.nameRepairs().size(),
            aliasLists.defaultedPolicies().size(), scanLists.nameRepairs().size(),
            scanLists.defaultedRows().size(), scanLists.defaultSelectionRepair(),
            recoveredMemberships.sourceRows(), aliases.defaultedAliasRows());
        return new Analysis(inspection, channels, providers, streamRoutes, Set.copyOf(clearedChannelAliasLists),
            aliases, aliasLists, scanLists, memberships);
    }

    static void requireOnlyRepairableForeignKeys(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("PRAGMA foreign_key_check"))
        {
            while(rows.next())
            {
                String table = rows.getString(1);
                if(!REPAIRABLE_FOREIGN_KEY_TABLES.contains(table) &&
                    !CurrentDatabaseDerivedStateRepair.isReproducibleTable(table))
                {
                    throw new SQLException("Current database contains a damaged relationship outside the bounded " +
                        "configuration-link repair set; table " + table + " must be recovered from a safety backup");
                }
            }
        }
    }

    static Inspection repair(Connection connection) throws SQLException
    {
        Analysis analysis = analyze(connection);
        Inspection before = analysis.inspection();
        if(!before.requiresRepair())
        {
            return before;
        }

        deleteRows(connection, "alias_broadcast_channel", analysis.streamRoutes().aliasRoutes().invalidRowIds());
        deleteRows(connection, "alias_list_unmatched_talkgroup_stream",
            analysis.streamRoutes().unmatchedRoutes().invalidRowIds());
        deleteMembershipRows(connection, "alias_scan_list_membership", "alias_id",
            analysis.memberships().aliasRowsToDelete());
        deleteMembershipRows(connection, "alias_list_unmatched_talkgroup_scan_list_membership", "alias_list_id",
            analysis.memberships().unmatchedRowsToDelete());
        deleteRows(connection, "configuration_broadcast_stream", analysis.providers().invalidRowIds());
        applyProviderRepairs(connection, analysis.providers().repairs());
        applyStreamRouteRepairs(connection, "alias_broadcast_channel",
            analysis.streamRoutes().aliasRoutes().repairs());
        applyStreamRouteRepairs(connection, "alias_list_unmatched_talkgroup_stream",
            analysis.streamRoutes().unmatchedRoutes().repairs());
        deleteRows(connection, "configuration_channel", analysis.channels().invalidRowIds());
        applyChannelRepairs(connection, analysis.channels().repairs(), analysis.clearedChannelAliasLists());
        clearUnusableChannelAliasLists(connection, analysis.clearedChannelAliasLists());
        deleteRows(connection, "alias", analysis.aliases().invalidRowIds());
        deleteRows(connection, "alias_list", analysis.aliasLists().invalidRowIds());
        deleteRows(connection, "scan_list", analysis.scanLists().invalidRowIds());

        applyAliasFieldDefaults(connection, analysis.aliases());
        applyAliasListRepairs(connection, analysis.aliasLists());
        long defaultScanListId = applyScanListRepairs(connection, analysis.scanLists());
        applyRecoveredDefaultMemberships(connection, defaultScanListId, analysis.memberships().recovery());

        Inspection remaining = inspect(connection);
        if(remaining.requiresRepair())
        {
            throw new SQLException("Bounded current-format relationship repair did not remove every targeted row");
        }
        return before;
    }

    static DatabaseMigrationChain.StepPreflight preflight(Inspection inspection)
    {
        return new DatabaseMigrationChain.StepPreflight(STEP_ID,
            "Repair unusable current-format configuration items independently",
            DatabaseFormatCatalog.CURRENT_VERSION, DatabaseFormatCatalog.CURRENT_VERSION, effects(inspection));
    }

    static List<DatabaseMigrationEffect> effects(Inspection inspection)
    {
        return List.of(
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DROP,
                "unusable saved channel rows", inspection.droppedChannels(),
                "Keep usable channels and discard only channel documents that cannot be loaded safely; derived " +
                    "receiver rows owned by a discarded channel follow it through schema cascades"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
                "recoverable saved channel values", inspection.repairedChannels(),
                "Keep each decodable active channel and rebuild only stale technical identities, row-owned values, " +
                    "and JSON projections"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DROP,
                "unusable broadcast provider rows", inspection.droppedBroadcastProviders(),
                "Keep usable providers and discard only provider documents that cannot be loaded safely"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
                "recoverable broadcast provider values", inspection.repairedBroadcastProviders(),
                "Keep each decodable provider and repair only its technical identity, ordering, redundant legacy " +
                    "fields, or a missing name"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DROP,
                "unusable Alias rows", inspection.droppedAliases(),
                "Keep usable Aliases and discard only rows with a missing owner or an invalid matcher; owned " +
                    "routes and memberships follow the Alias"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
                "Aliases with recoverable values", inspection.defaultedAliasRows(),
                "Keep the Alias and repair only a missing display name, stale inactive matcher fields, or malformed " +
                    "optional appearance, streaming, and recording values"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DROP,
                "unusable Alias List rows", inspection.droppedAliasLists(),
                "Keep usable Alias Lists and discard only definitions with an unusable identity, name, or family; " +
                    "owned Aliases and links follow the definition"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
                "Alias List names separated after normalization", inspection.renamedAliasLists(),
                "Trim usable names and deterministically suffix only names that otherwise collide after trimming"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
                "invalid Alias List unmatched-talkgroup policies", inspection.defaultedAliasListPolicies(),
                "Keep the Alias List and default only its unusable record flag"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DROP,
                "unusable scan-list rows", inspection.droppedScanLists(),
                "Keep usable scan lists and discard only definitions with an unusable identity or name; owned " +
                    "memberships follow the definition"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
                "scan-list names separated after normalization", inspection.renamedScanLists(),
                "Strip usable names and deterministically suffix only names that otherwise collide after stripping"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
                "recoverable scan-list fields", inspection.defaultedScanLists(),
                "Keep each usable scan list while defaulting only malformed ordering, description, or visibility"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
                "Default scan-list selection after configuration repair", inspection.repairedDefaultScanList(),
                "Preserve usable scan lists and restore exactly one published Default selection"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
                "scan-list memberships recovered into Default",
                inspection.remappedScanListMemberships(),
                "Keep routing intent for surviving Aliases and Alias Lists when a referenced scan list is unusable; " +
                    "coalesce duplicate owner routes onto the selected Default"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
                "broadcast routes with recoverable provider identities", inspection.repairedBroadcastRoutes(),
                "Canonicalize a route's provider UUID while preserving its Alias or Alias List owner"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DROP,
                "orphaned stream and scan-list relationship rows", inspection.droppedRelationships(),
                "Remove only links whose Alias, Alias List, scan list, or broadcast provider no longer exists"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
                "channels with an unusable Alias List", inspection.clearedChannelAliasLists(),
                "Keep each channel and clear only a missing, malformed, or incompatible Alias List assignment"));
    }

    private static AliasListInspection inspectAliasLists(Connection connection) throws SQLException
    {
        List<Long> invalidRows = new ArrayList<>();
        List<ConfigurationNameRepair.Candidate> names = new ArrayList<>();
        Map<Long,String> originals = new LinkedHashMap<>();
        Set<Long> defaultedPolicies = new LinkedHashSet<>();
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT rowid AS physical_rowid, id, typeof(name) AS name_type,
                   length(CAST(name AS BLOB)) AS name_bytes,
                   CASE WHEN typeof(name)='text' AND length(CAST(name AS BLOB))<=? THEN name END AS safe_name,
                   typeof(family) AS family_type,
                   CASE WHEN typeof(family)='text' AND length(CAST(family AS BLOB))<=32
                        THEN family END AS safe_family,
                   unmatched_talkgroup_record_enabled
            FROM alias_list ORDER BY id
            """))
        {
            statement.setInt(1, MAXIMUM_ALIAS_LIST_NAME_BYTES);
            try(ResultSet rows = statement.executeQuery())
            {
                while(rows.next())
                {
                    long rowId = rows.getLong("physical_rowid");
                    try
                    {
                        long id = requirePositiveId(rows.getObject("id"));
                        String name = "text".equals(rows.getString("name_type")) &&
                            rows.getLong("name_bytes") <= MAXIMUM_ALIAS_LIST_NAME_BYTES ?
                            rows.getString("safe_name") : null;
                        String originalName = name;
                        if(name == null || name.isBlank())
                        {
                            name = "Recovered Alias List " + id;
                        }
                        String family = requiredBoundedText(rows, "safe_family", "family_type", null, 32);
                        AliasListFamily.valueOf(family);
                        if(!booleanInteger(rows.getObject("unmatched_talkgroup_record_enabled")))
                        {
                            defaultedPolicies.add(rowId);
                        }
                        names.add(new ConfigurationNameRepair.Candidate(id, name));
                        originals.put(id, originalName);
                    }
                    catch(RuntimeException exception)
                    {
                        invalidRows.add(rowId);
                    }
                }
            }
        }

        Map<Long,String> desiredNames = ConfigurationNameRepair.plan(names, 25, false);
        Map<Long,String> nameRepairs = changedNames(originals, desiredNames);
        return new AliasListInspection(List.copyOf(invalidRows), Map.copyOf(nameRepairs),
            Set.copyOf(defaultedPolicies));
    }

    private static ScanListInspection inspectScanLists(Connection connection) throws SQLException
    {
        List<Long> invalidRows = new ArrayList<>();
        List<ScanListRow> accepted = new ArrayList<>();
        List<ConfigurationNameRepair.Candidate> names = new ArrayList<>();
        Map<Long,String> originals = new LinkedHashMap<>();
        Set<Long> defaultedRows = new LinkedHashSet<>();
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT rowid AS physical_rowid, id, sort_order,
                   typeof(name) AS name_type, length(CAST(name AS BLOB)) AS name_bytes,
                   CASE WHEN typeof(name)='text' AND length(CAST(name AS BLOB))<=? THEN name END AS safe_name,
                   typeof(description) AS description_type,
                   length(CAST(description AS BLOB)) AS description_bytes,
                   CASE WHEN typeof(description)='text' AND length(CAST(description AS BLOB))<=?
                        THEN description END AS safe_description,
                   published, is_default
            FROM scan_list ORDER BY id
            """))
        {
            statement.setInt(1, MAXIMUM_SCAN_LIST_NAME_BYTES);
            statement.setInt(2, MAXIMUM_SCAN_LIST_DESCRIPTION_BYTES);
            try(ResultSet rows = statement.executeQuery())
            {
                while(rows.next())
                {
                    long rowId = rows.getLong("physical_rowid");
                    try
                    {
                        long id = requirePositiveId(rows.getObject("id"));
                        String name = "text".equals(rows.getString("name_type")) &&
                            rows.getLong("name_bytes") <= MAXIMUM_SCAN_LIST_NAME_BYTES ?
                            rows.getString("safe_name") : null;
                        String originalName = name;
                        boolean recoveredName = name == null || name.strip().isBlank();
                        if(recoveredName)
                        {
                            name = "Recovered Scan List " + id;
                        }

                        boolean defaulted = recoveredName;
                        int sortOrder;
                        if(nonNegativeInt(rows.getObject("sort_order")))
                        {
                            sortOrder = ((Number)rows.getObject("sort_order")).intValue();
                        }
                        else
                        {
                            sortOrder = accepted.size();
                            defaulted = true;
                        }

                        String description = null;
                        String descriptionType = rows.getString("description_type");
                        if(!"null".equals(descriptionType))
                        {
                            if("text".equals(descriptionType))
                            {
                                long bytes = rows.getLong("description_bytes");
                                String candidate = bytes <= MAXIMUM_SCAN_LIST_DESCRIPTION_BYTES ?
                                    rows.getString("safe_description") : null;
                                String normalized = candidate != null ? candidate.strip() : null;
                                if(normalized != null && !normalized.isEmpty() &&
                                    ConfigurationNameRepair.codePointLength(normalized) <= 1_000)
                                {
                                    description = candidate;
                                }
                                else
                                {
                                    defaulted = true;
                                }
                            }
                            else
                            {
                                defaulted = true;
                            }
                        }

                        boolean published;
                        if(booleanInteger(rows.getObject("published")))
                        {
                            published = ((Number)rows.getObject("published")).intValue() == 1;
                        }
                        else
                        {
                            published = true;
                            defaulted = true;
                        }
                        boolean isDefault;
                        if(booleanInteger(rows.getObject("is_default")))
                        {
                            isDefault = ((Number)rows.getObject("is_default")).intValue() == 1;
                        }
                        else
                        {
                            isDefault = false;
                            defaulted = true;
                        }
                        if(isDefault && !published)
                        {
                            published = true;
                            defaulted = true;
                        }
                        if(defaulted)
                        {
                            defaultedRows.add(id);
                        }
                        accepted.add(new ScanListRow(id, sortOrder, name, description, published, isDefault));
                        names.add(new ConfigurationNameRepair.Candidate(id, name));
                        originals.put(id, originalName);
                    }
                    catch(RuntimeException exception)
                    {
                        invalidRows.add(rowId);
                    }
                }
            }
        }

        Map<Long,String> desiredNames = ConfigurationNameRepair.plan(names, 100, true);
        Map<Long,String> nameRepairs = changedNames(originals, desiredNames);
        List<ScanListRow> desired = new ArrayList<>();
        for(ScanListRow row: accepted)
        {
            desired.add(row.withName(desiredNames.get(row.id())));
        }

        int defaultCount = 0;
        for(ScanListRow row: desired)
        {
            defaultCount += row.isDefault() ? 1 : 0;
        }
        boolean generateDefault = false;
        long defaultSelectionRepair = defaultCount == 1 ? 0 : 1;
        if(defaultCount == 0)
        {
            int namedDefault = -1;
            for(int index = 0; index < desired.size(); index++)
            {
                if("Default".equalsIgnoreCase(desired.get(index).name()))
                {
                    namedDefault = index;
                    break;
                }
            }
            if(namedDefault >= 0)
            {
                ScanListRow row = desired.get(namedDefault);
                desired.set(namedDefault, row.withDefault(true));
            }
            else
            {
                generateDefault = true;
            }
        }
        else if(defaultCount > 1)
        {
            long selectedId = desired.stream().filter(row -> "Default".equalsIgnoreCase(row.name()))
                .mapToLong(ScanListRow::id).findFirst()
                .orElse(desired.stream().filter(ScanListRow::isDefault).mapToLong(ScanListRow::id)
                    .findFirst().orElseThrow());
            for(int index = 0; index < desired.size(); index++)
            {
                ScanListRow row = desired.get(index);
                desired.set(index, row.withDefault(row.id() == selectedId));
            }
        }

        return new ScanListInspection(List.copyOf(invalidRows), List.copyOf(desired),
            Map.copyOf(nameRepairs), Set.copyOf(defaultedRows), defaultSelectionRepair, generateDefault);
    }

    private static Map<Long,String> changedNames(Map<Long,String> originals, Map<Long,String> desired)
    {
        Map<Long,String> changed = new LinkedHashMap<>();
        for(Map.Entry<Long,String> entry: desired.entrySet())
        {
            if(!entry.getValue().equals(originals.get(entry.getKey())))
            {
                changed.put(entry.getKey(), entry.getValue());
            }
        }
        return changed;
    }

    private static MembershipInspection inspectMemberships(Connection connection, Set<Long> invalidAliasRows,
                                                            Set<Long> invalidAliasListRows,
                                                            Set<Long> invalidScanListRows,
                                                            Set<Long> retainedScanLists) throws SQLException
    {
        Set<Long> aliasIds = new LinkedHashSet<>();
        Set<Long> aliasListIds = new LinkedHashSet<>();
        MembershipTableInspection aliasMemberships = inspectMembershipTable(connection,
            "alias_scan_list_membership", "alias_id", "alias", invalidAliasRows, invalidScanListRows,
            retainedScanLists, aliasIds);
        MembershipTableInspection unmatchedMemberships = inspectMembershipTable(connection,
            "alias_list_unmatched_talkgroup_scan_list_membership", "alias_list_id", "alias_list",
            invalidAliasListRows, invalidScanListRows, retainedScanLists, aliasListIds);
        long recoveredRows = Math.addExact(aliasMemberships.recoveredRows(), unmatchedMemberships.recoveredRows());
        GeneratedDefaultMembershipRecovery recovery = new GeneratedDefaultMembershipRecovery(Set.copyOf(aliasIds),
            Set.copyOf(aliasListIds), recoveredRows);
        return new MembershipInspection(aliasMemberships.rowsToDelete(), unmatchedMemberships.rowsToDelete(),
            recovery);
    }

    private static MembershipTableInspection inspectMembershipTable(Connection connection, String membershipTable,
                                                                     String ownerColumn, String ownerTable,
                                                                     Set<Long> invalidOwnerRows,
                                                                     Set<Long> invalidScanListRows,
                                                                     Set<Long> retainedScanLists,
                                                                     Set<Long> recoveredOwnerIds)
        throws SQLException
    {
        List<MembershipRowKey> rowsToDelete = new ArrayList<>();
        long recoveredRows = 0;
        String sql = "SELECT membership." + ownerColumn + " AS owner_id, " +
            "typeof(membership." + ownerColumn + ") AS owner_id_type, membership.scan_list_id, " +
            "typeof(membership.scan_list_id) AS scan_list_id_type, owner.rowid AS owner_rowid, " +
            "target.rowid AS target_rowid FROM " + membershipTable + " membership " +
            "LEFT JOIN " + ownerTable + " owner ON owner.id=membership." + ownerColumn + " " +
            "LEFT JOIN scan_list target ON target.id=membership.scan_list_id " +
            "ORDER BY membership." + ownerColumn + ", membership.scan_list_id";
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql))
        {
            while(rows.next())
            {
                Object ownerId = rows.getObject("owner_id");
                Object scanListId = rows.getObject("scan_list_id");
                long ownerRowId = rows.getLong("owner_rowid");
                boolean ownerMissing = rows.wasNull();
                long targetRowId = rows.getLong("target_rowid");
                boolean targetMissing = rows.wasNull();
                boolean ownerUsable = positiveInteger(ownerId) && !ownerMissing &&
                    !invalidOwnerRows.contains(ownerRowId);
                boolean positiveStoredScanListId = integer(scanListId) && ((Number)scanListId).longValue() > 0;
                long scanList = positiveStoredScanListId ? ((Number)scanListId).longValue() : 0;
                boolean targetUsable = positiveInteger(scanListId) && !targetMissing &&
                    !invalidScanListRows.contains(targetRowId) && retainedScanLists.contains(scanList);
                if(ownerUsable && targetUsable)
                {
                    continue;
                }

                rowsToDelete.add(new MembershipRowKey(ownerId, scanListId));
                if(ownerUsable && positiveStoredScanListId && !retainedScanLists.contains(scanList))
                {
                    recoveredOwnerIds.add(((Number)ownerId).longValue());
                    recoveredRows = Math.addExact(recoveredRows, 1);
                }
            }
        }
        return new MembershipTableInspection(List.copyOf(rowsToDelete), recoveredRows);
    }

    private static ChannelInspection inspectChannels(Connection connection) throws SQLException
    {
        List<Long> invalid = new ArrayList<>();
        Map<Long,ChannelRepair> repairs = new LinkedHashMap<>();
        Set<String> reservedConfigurationIds = reservedCanonicalIds(connection, "configuration_channel");
        Set<String> retainedConfigurationIds = new LinkedHashSet<>();
        String sql = """
            SELECT rowid AS physical_rowid, id,
                   typeof(configuration_id) AS configuration_id_type,
                   length(CAST(configuration_id AS BLOB)) AS configuration_id_bytes,
                   CASE WHEN typeof(configuration_id)='text' AND length(CAST(configuration_id AS BLOB))<=128
                        THEN configuration_id END AS configuration_id,
                   typeof(channel_kind) AS channel_kind_type,
                   length(CAST(channel_kind AS BLOB)) AS channel_kind_bytes,
                   CASE WHEN typeof(channel_kind)='text' AND length(CAST(channel_kind AS BLOB))<=64
                        THEN channel_kind END AS channel_kind,
                   typeof(sort_order) AS sort_order_type,
                   CASE WHEN typeof(sort_order)='integer' THEN sort_order END AS sort_order,
                   typeof(system_name) AS system_name_type,
                   length(CAST(system_name AS BLOB)) AS system_name_bytes,
                   CASE WHEN typeof(system_name)='text' AND length(CAST(system_name AS BLOB))<=%1$d
                        THEN system_name END AS system_name,
                   typeof(site_name) AS site_name_type,
                   length(CAST(site_name AS BLOB)) AS site_name_bytes,
                   CASE WHEN typeof(site_name)='text' AND length(CAST(site_name AS BLOB))<=%1$d
                        THEN site_name END AS site_name,
                   typeof(name) AS name_type, length(CAST(name AS BLOB)) AS name_bytes,
                   CASE WHEN typeof(name)='text' AND length(CAST(name AS BLOB))<=%1$d
                        THEN name END AS name,
                   alias_list_id,
                   typeof(radioresolve_id) AS radioresolve_id_type,
                   length(CAST(radioresolve_id AS BLOB)) AS radioresolve_id_bytes,
                   CASE WHEN typeof(radioresolve_id)='text' AND length(CAST(radioresolve_id AS BLOB))<=128
                        THEN radioresolve_id END AS radioresolve_id,
                   typeof(auto_start) AS auto_start_type,
                   CASE WHEN typeof(auto_start)='integer' THEN auto_start END AS auto_start,
                   typeof(auto_start_order) AS auto_start_order_type,
                   CASE WHEN typeof(auto_start_order)='integer' THEN auto_start_order END AS auto_start_order,
                   typeof(decoder_type) AS decoder_type_type,
                   length(CAST(decoder_type AS BLOB)) AS decoder_type_bytes,
                   CASE WHEN typeof(decoder_type)='text' AND length(CAST(decoder_type AS BLOB))<=64
                        THEN decoder_type END AS decoder_type,
                   typeof(address_domain_code) AS address_domain_code_type,
                   CASE WHEN typeof(address_domain_code)='integer' THEN address_domain_code END AS address_domain_code,
                   typeof(primary_frequency_hz) AS primary_frequency_hz_type,
                   CASE WHEN typeof(primary_frequency_hz)='integer' THEN primary_frequency_hz END AS primary_frequency_hz,
                   typeof(config_json) AS config_json_type,
                   length(CAST(config_json AS BLOB)) AS config_json_bytes,
                   CASE WHEN typeof(config_json)='text' AND length(CAST(config_json AS BLOB)) <= %1$d
                        THEN config_json END AS config_json
            FROM configuration_channel ORDER BY rowid
            """.formatted(MAXIMUM_CONFIGURATION_JSON_BYTES);
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql))
        {
            int sourceOrder = 0;
            while(rows.next())
            {
                sourceOrder++;
                long rowId = rows.getLong("physical_rowid");
                try
                {
                    ChannelRepair repair = inspectChannel(rows, sourceOrder - 1, reservedConfigurationIds,
                        retainedConfigurationIds);
                    if(repair != null)
                    {
                        repairs.put(rowId, repair);
                    }
                }
                catch(IOException | RuntimeException ignored)
                {
                    invalid.add(rowId);
                }
            }
        }
        return new ChannelInspection(List.copyOf(invalid), Map.copyOf(repairs));
    }

    private static ChannelRepair inspectChannel(ResultSet row, int fallbackSortOrder,
                                                Set<String> reservedConfigurationIds,
                                                Set<String> retainedConfigurationIds)
        throws SQLException, IOException
    {
        if(!positiveInteger(row.getObject("id")))
        {
            throw new IOException("Saved channel has an unusable identity");
        }
        long rowId = ((Number)row.getObject("id")).longValue();
        String storedConfigurationId = boundedRequiredText(row, "configuration_id", 128) ?
            row.getString("configuration_id") : null;
        String configurationId = tryCanonicalUuid(storedConfigurationId);
        if(configurationId == null || retainedConfigurationIds.contains(configurationId))
        {
            configurationId = deterministicUuid("current-channel", rowId, reservedConfigurationIds,
                retainedConfigurationIds);
        }

        if(!"text".equals(row.getString("config_json_type")))
        {
            throw new IOException("Saved channel configuration is not text");
        }
        long payloadBytes = row.getLong("config_json_bytes");
        if(row.wasNull() || payloadBytes > MAXIMUM_CONFIGURATION_JSON_BYTES)
        {
            throw new IOException("Saved channel configuration exceeds its storage bound");
        }
        String payloadText = row.getString("config_json");
        if(payloadText == null)
        {
            throw new IOException("Saved channel configuration is unavailable");
        }
        JsonNode parsed = MAPPER.readTree(payloadText);
        if(parsed == null || !parsed.isObject() || !typedObject(parsed.get("decodeConfiguration")) ||
            !typedObject(parsed.get("sourceConfiguration")))
        {
            throw new IOException("Saved channel has no usable decode or source configuration");
        }

        boolean repaired = !Objects.equals(storedConfigurationId, configurationId);
        for(String field: CHANNEL_ROW_OWNED_JSON_FIELDS)
        {
            if(((com.fasterxml.jackson.databind.node.ObjectNode)parsed).remove(field) != null)
            {
                repaired = true;
            }
        }

        ChannelConfigurationSalvage.Result channelSalvage = ChannelConfigurationSalvage.decode(MAPPER,
            (com.fasterxml.jackson.databind.node.ObjectNode)parsed, "Saved channel");
        Channel channel = channelSalvage.channel();
        parsed = channelSalvage.payload();
        repaired |= channelSalvage.defaultedComponents() > 0;
        channel.setConfigurationId(configurationId);
        if(!ChannelConfigurationPolicy.isActive(channel))
        {
            throw new IOException("Saved channel is not active and supported");
        }

        String channelKind = ChannelConfigurationPolicy.requireChannelKind(channel).name();
        ConfigurationChannelProjection projection = ConfigurationChannelProjection.from(channel);
        int sortOrder = nonNegativeInt(row.getObject("sort_order")) ?
            ((Number)row.getObject("sort_order")).intValue() : fallbackSortOrder;
        String systemName = boundedNullableText(row, "system_name", MAXIMUM_CONFIGURATION_JSON_BYTES) ?
            row.getString("system_name") : null;
        String siteName = boundedNullableText(row, "site_name", MAXIMUM_CONFIGURATION_JSON_BYTES) ?
            row.getString("site_name") : null;
        String name = boundedNullableText(row, "name", MAXIMUM_CONFIGURATION_JSON_BYTES) ?
            row.getString("name") : null;
        String storedRadioResolveId = boundedNullableText(row, "radioresolve_id", 128) ?
            row.getString("radioresolve_id") : null;
        String radioResolveId = tryCanonicalUuid(storedRadioResolveId);
        boolean autoStart = booleanInteger(row.getObject("auto_start")) &&
            ((Number)row.getObject("auto_start")).intValue() == 1;
        Integer autoStartOrder = nullableInt(row.getObject("auto_start_order")) ?
            (row.getObject("auto_start_order") == null ? null :
                ((Number)row.getObject("auto_start_order")).intValue()) : null;
        boolean validAutoStartOrder = "null".equals(row.getString("auto_start_order_type")) ||
            "integer".equals(row.getString("auto_start_order_type")) &&
                nullableInt(row.getObject("auto_start_order"));
        boolean validPrimaryFrequency = "null".equals(row.getString("primary_frequency_hz_type")) ||
            "integer".equals(row.getString("primary_frequency_hz_type")) &&
                positiveNullableInteger(row.getObject("primary_frequency_hz"));

        repaired |= !nonNegativeInt(row.getObject("sort_order")) ||
            !boundedNullableText(row, "system_name", MAXIMUM_CONFIGURATION_JSON_BYTES) ||
            !boundedNullableText(row, "site_name", MAXIMUM_CONFIGURATION_JSON_BYTES) ||
            !boundedNullableText(row, "name", MAXIMUM_CONFIGURATION_JSON_BYTES) ||
            !boundedNullableText(row, "radioresolve_id", 128) ||
            !Objects.equals(storedRadioResolveId, radioResolveId) ||
            !booleanInteger(row.getObject("auto_start")) || !validAutoStartOrder || !validPrimaryFrequency ||
            !Objects.equals(channelKind, row.getObject("channel_kind")) ||
            !Objects.equals(projection.decoderType(), row.getObject("decoder_type")) ||
            !Objects.equals(projection.addressDomainCode(), integerValue(row.getObject("address_domain_code"))) ||
            !Objects.equals(projection.primaryFrequencyHz(), longValue(row.getObject("primary_frequency_hz")));

        retainedConfigurationIds.add(configurationId);
        if(!repaired)
        {
            return null;
        }
        return new ChannelRepair(configurationId, sortOrder, systemName, siteName, name, radioResolveId, autoStart,
            autoStartOrder, channelKind, projection, MAPPER.writeValueAsString(parsed));
    }

    private static ProviderInspection inspectProviders(Connection connection) throws SQLException
    {
        List<Long> invalidRowIds = new ArrayList<>();
        Map<Long,ProviderRepair> repairs = new LinkedHashMap<>();
        List<ProviderIdentity> retainedIdentities = new ArrayList<>();
        Set<String> reservedConfigurationIds = reservedCanonicalIds(connection,
            "configuration_broadcast_stream");
        Set<String> retainedConfigurationIds = new LinkedHashSet<>();
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT rowid AS physical_rowid, id,
                   typeof(configuration_id) AS configuration_id_type,
                   length(CAST(configuration_id AS BLOB)) AS configuration_id_bytes,
                   CASE WHEN typeof(configuration_id)='text' AND length(CAST(configuration_id AS BLOB))<=128
                        THEN configuration_id END AS configuration_id,
                   typeof(sort_order) AS sort_order_type,
                   CASE WHEN typeof(sort_order)='integer' THEN sort_order END AS sort_order,
                   typeof(config_json) AS config_json_type,
                   length(CAST(config_json AS BLOB)) AS config_json_bytes,
                   CASE WHEN typeof(config_json)='text' AND length(CAST(config_json AS BLOB)) <= ?
                        THEN config_json END AS config_json
            FROM configuration_broadcast_stream ORDER BY rowid
            """))
        {
            statement.setInt(1, MAXIMUM_CONFIGURATION_JSON_BYTES);
            try(ResultSet rows = statement.executeQuery())
            {
                int sourceOrder = 0;
                while(rows.next())
                {
                    sourceOrder++;
                    long rowId = rows.getLong("physical_rowid");
                    try
                    {
                        ProviderPlan provider = inspectProvider(rows, sourceOrder - 1, reservedConfigurationIds,
                            retainedConfigurationIds);
                        retainedIdentities.add(provider.identity());
                        if(provider.repair() != null)
                        {
                            repairs.put(rowId, provider.repair());
                        }
                    }
                    catch(IOException | RuntimeException ignored)
                    {
                        invalidRowIds.add(rowId);
                    }
                }
            }
        }
        Map<String,String> exactTargets = new LinkedHashMap<>();
        Map<String,String> canonicalTargets = new LinkedHashMap<>();
        Set<String> ambiguousCanonicalSources = new LinkedHashSet<>();
        for(ProviderIdentity identity: retainedIdentities)
        {
            if(identity.storedId() != null)
            {
                exactTargets.put(identity.storedId(), identity.targetId());
            }
            if(identity.canonicalSourceId() != null)
            {
                String previous = canonicalTargets.putIfAbsent(identity.canonicalSourceId(), identity.targetId());
                if(previous != null && !previous.equals(identity.targetId()))
                {
                    ambiguousCanonicalSources.add(identity.canonicalSourceId());
                }
            }
        }
        ambiguousCanonicalSources.forEach(canonicalTargets::remove);
        return new ProviderInspection(List.copyOf(invalidRowIds), Map.copyOf(repairs),
            Map.copyOf(exactTargets), Map.copyOf(canonicalTargets));
    }

    private static ProviderPlan inspectProvider(ResultSet row, int fallbackSortOrder,
                                                Set<String> reservedConfigurationIds,
                                                Set<String> retainedConfigurationIds)
        throws SQLException, IOException
    {
        if(!positiveInteger(row.getObject("id")))
        {
            throw new IOException("Broadcast provider has an unusable identity");
        }
        long rowId = ((Number)row.getObject("id")).longValue();
        String storedConfigurationId = "text".equals(row.getString("configuration_id_type")) &&
            row.getLong("configuration_id_bytes") <= 128 ? row.getString("configuration_id") : null;
        String canonicalSourceId = tryCanonicalUuid(storedConfigurationId);
        String configurationId = canonicalSourceId;
        if(configurationId == null || retainedConfigurationIds.contains(configurationId))
        {
            configurationId = deterministicUuid("current-broadcast-provider", rowId, reservedConfigurationIds,
                retainedConfigurationIds);
        }
        if(!"text".equals(row.getString("config_json_type")))
        {
            throw new IOException("Broadcast provider configuration is not text");
        }
        long payloadBytes = row.getLong("config_json_bytes");
        if(row.wasNull() || payloadBytes > MAXIMUM_CONFIGURATION_JSON_BYTES)
        {
            throw new IOException("Broadcast provider configuration exceeds its storage bound");
        }
        String payloadText = row.getString("config_json");
        if(payloadText == null)
        {
            throw new IOException("Broadcast provider configuration is unavailable");
        }
        JsonNode parsed = MAPPER.readTree(payloadText);
        if(parsed == null || !parsed.isObject() || !parsed.hasNonNull("type") ||
            !parsed.get("type").isTextual() || parsed.get("type").textValue().isBlank())
        {
            throw new IOException("Broadcast provider has no supported type");
        }
        com.fasterxml.jackson.databind.node.ObjectNode payload = (com.fasterxml.jackson.databind.node.ObjectNode)parsed;
        boolean repaired = !Objects.equals(storedConfigurationId, configurationId);
        repaired |= payload.remove("configurationId") != null;
        repaired |= payload.remove("aliasListName") != null;
        JsonNode name = payload.get("name");
        if(name == null || !name.isTextual() || name.textValue().isBlank())
        {
            payload.put("name", "Recovered Broadcast Provider " + ((Number)row.getObject("id")).longValue());
            repaired = true;
        }
        BroadcastConfiguration provider = MAPPER.treeToValue(payload, BroadcastConfiguration.class);
        provider.setConfigurationId(configurationId);
        if(provider.isConfigurationIdPersistenceRequired() || provider.getBroadcastServerType() == null)
        {
            throw new IOException("Broadcast provider type or identity is unusable");
        }
        int sortOrder = nonNegativeInt(row.getObject("sort_order")) ?
            ((Number)row.getObject("sort_order")).intValue() : fallbackSortOrder;
        repaired |= !nonNegativeInt(row.getObject("sort_order"));
        retainedConfigurationIds.add(configurationId);
        ProviderRepair repair = repaired ? new ProviderRepair(configurationId, sortOrder,
            MAPPER.writeValueAsString(payload)) : null;
        return new ProviderPlan(new ProviderIdentity(storedConfigurationId, canonicalSourceId, configurationId),
            repair);
    }

    private static AliasInspection inspectAliases(Connection connection, Set<Long> invalidAliasListRows)
        throws SQLException
    {
        List<Long> invalid = new ArrayList<>();
        Map<Long,AliasRepair> repairs = new LinkedHashMap<>();
        String sql = """
            SELECT alias.rowid AS physical_rowid, owner.rowid AS owner_rowid,
                   typeof(alias.id) AS id_type,
                   CASE WHEN typeof(alias.id)='integer' THEN alias.id END AS id,
                   typeof(alias.alias_list_id) AS alias_list_id_type,
                   CASE WHEN typeof(alias.alias_list_id)='integer' THEN alias.alias_list_id END AS alias_list_id,
                   typeof(alias.name) AS name_type, length(CAST(alias.name AS BLOB)) AS name_bytes,
                   CASE WHEN typeof(alias.name)='text' AND length(CAST(alias.name AS BLOB))<=%1$d
                        THEN alias.name END AS name,
                   typeof(alias.description) AS description_type,
                   length(CAST(alias.description AS BLOB)) AS description_bytes,
                   CASE WHEN typeof(alias.description)='text' AND length(CAST(alias.description AS BLOB))<=%1$d
                        THEN alias.description END AS description,
                   typeof(alias.group_name) AS group_name_type,
                   length(CAST(alias.group_name AS BLOB)) AS group_name_bytes,
                   CASE WHEN typeof(alias.group_name)='text' AND length(CAST(alias.group_name AS BLOB))<=%1$d
                        THEN alias.group_name END AS group_name,
                   typeof(alias.color) AS color_type,
                   CASE WHEN typeof(alias.color)='integer' THEN alias.color END AS color,
                   typeof(alias.icon_name) AS icon_name_type,
                   length(CAST(alias.icon_name AS BLOB)) AS icon_name_bytes,
                   CASE WHEN typeof(alias.icon_name)='text' AND length(CAST(alias.icon_name AS BLOB))<=%1$d
                        THEN alias.icon_name END AS icon_name,
                   typeof(alias.stream_as_talkgroup) AS stream_as_talkgroup_type,
                   CASE WHEN typeof(alias.stream_as_talkgroup)='integer'
                        THEN alias.stream_as_talkgroup END AS stream_as_talkgroup,
                   typeof(alias.record_enabled) AS record_enabled_type,
                   CASE WHEN typeof(alias.record_enabled)='integer'
                        THEN alias.record_enabled END AS record_enabled,
                   typeof(alias.matcher_type) AS matcher_type_type,
                   CASE WHEN typeof(alias.matcher_type)='text' AND length(CAST(alias.matcher_type AS BLOB))<=64
                        THEN alias.matcher_type END AS matcher_type,
                   typeof(alias.protocol) AS protocol_type,
                   length(CAST(alias.protocol AS BLOB)) AS protocol_bytes,
                   CASE WHEN typeof(alias.protocol)='text' AND length(CAST(alias.protocol AS BLOB))<=64
                        THEN alias.protocol END AS protocol,
                   typeof(alias.value) AS value_type,
                   CASE WHEN typeof(alias.value)='integer' THEN alias.value END AS value,
                   typeof(alias.min_value) AS min_value_type,
                   CASE WHEN typeof(alias.min_value)='integer' THEN alias.min_value END AS min_value,
                   typeof(alias.max_value) AS max_value_type,
                   CASE WHEN typeof(alias.max_value)='integer' THEN alias.max_value END AS max_value,
                   typeof(alias.text_value) AS text_value_type,
                   length(CAST(alias.text_value AS BLOB)) AS text_value_bytes,
                   CASE WHEN typeof(alias.text_value)='text' AND length(CAST(alias.text_value AS BLOB))<=%1$d
                        THEN alias.text_value END AS text_value,
                   typeof(alias.numeric_value) AS numeric_value_type,
                   CASE WHEN typeof(alias.numeric_value)='integer' THEN alias.numeric_value END AS numeric_value,
                   typeof(alias.tone_sequence) AS tone_sequence_type,
                   length(CAST(alias.tone_sequence AS BLOB)) AS tone_sequence_bytes,
                   CASE WHEN typeof(alias.tone_sequence)='text'
                                  AND length(CAST(alias.tone_sequence AS BLOB))<=%1$d
                        THEN alias.tone_sequence END AS tone_sequence,
                   CASE WHEN typeof(owner.family)='text' AND length(CAST(owner.family AS BLOB))<=32
                        THEN owner.family END AS family
            FROM alias
            LEFT JOIN alias_list owner ON owner.id=alias.alias_list_id
            ORDER BY alias.rowid
            """.formatted(MAXIMUM_ALIAS_TEXT_BYTES);
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql))
        {
            while(rows.next())
            {
                long rowId = rows.getLong("physical_rowid");
                try
                {
                    long ownerRowId = rows.getLong("owner_rowid");
                    if(rows.wasNull() || invalidAliasListRows.contains(ownerRowId))
                    {
                        throw new IOException("Alias owner is unusable");
                    }
                    long aliasId = requirePositiveId(rows.getObject("id"));
                    String name = boundedNonBlankText(rows, "name") ? rows.getString("name") :
                        "Recovered Alias " + aliasId;
                    Format14To15DatabaseMigration.AliasMatcherFields matcher = requireValidAlias(rows);
                    AliasOptionalFields optionalFields = aliasOptionalFields(rows);
                    if(!boundedNonBlankText(rows, "name") || matcher.defaultedFieldCount() > 0 ||
                        optionalFields.defaultedFieldCount() > 0)
                    {
                        repairs.put(rowId, new AliasRepair(name, optionalFields, matcher));
                    }
                }
                catch(IOException | RuntimeException ignored)
                {
                    invalid.add(rowId);
                }
            }
        }
        return new AliasInspection(List.copyOf(invalid), Map.copyOf(repairs), repairs.size());
    }

    private static Format14To15DatabaseMigration.AliasMatcherFields requireValidAlias(ResultSet row)
        throws SQLException, IOException
    {
        if(!positiveInteger(row.getObject("id")) || !positiveInteger(row.getObject("alias_list_id")) ||
            !"text".equals(row.getString("matcher_type_type")))
        {
            throw new IOException("Alias has an unusable required identity or matcher field");
        }

        Object storedFamily = row.getObject("family");
        if(!(storedFamily instanceof String familyText))
        {
            throw new IOException("Alias has an unusable family");
        }
        AliasListFamily family;
        try
        {
            family = AliasListFamily.valueOf(familyText);
        }
        catch(IllegalArgumentException ignored)
        {
            throw new IOException("Alias has an unusable family");
        }
        return Format14To15DatabaseMigration.inspectAliasMatcher(row, family, "Saved Alias");
    }

    private static AliasOptionalFields aliasOptionalFields(ResultSet row) throws SQLException
    {
        boolean defaultDescription = !boundedNullableText(row, "description");
        boolean defaultGroupName = !boundedNullableText(row, "group_name");
        boolean defaultColor = !intValue(row.getObject("color"));
        boolean defaultIconName = !boundedNullableText(row, "icon_name");
        boolean defaultStreamAs = !positiveNullableInt(row.getObject("stream_as_talkgroup"));
        boolean defaultRecordEnabled = !booleanInteger(row.getObject("record_enabled"));
        long defaultedFields = (defaultDescription ? 1 : 0) + (defaultGroupName ? 1 : 0) +
            (defaultColor ? 1 : 0) + (defaultIconName ? 1 : 0) + (defaultStreamAs ? 1 : 0) +
            (defaultRecordEnabled ? 1 : 0);
        return new AliasOptionalFields(defaultDescription ? null : row.getString("description"),
            defaultGroupName ? null : row.getString("group_name"),
            defaultColor ? 0 : ((Number)row.getObject("color")).intValue(),
            defaultIconName ? null : row.getString("icon_name"),
            defaultStreamAs ? null : (row.getObject("stream_as_talkgroup") == null ? null :
                ((Number)row.getObject("stream_as_talkgroup")).intValue()),
            !defaultRecordEnabled && ((Number)row.getObject("record_enabled")).intValue() == 1,
            defaultedFields);
    }

    private static Set<Long> inspectUnusableChannelAliasLists(Connection connection, Set<Long> invalidChannelRows,
                                                               Map<Long,ChannelRepair> channelRepairs,
                                                               List<Long> invalidAliasListRows) throws SQLException
    {
        Set<Long> rowIds = new LinkedHashSet<>();
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("""
            SELECT channel.rowid AS physical_rowid, channel.alias_list_id, typeof(channel.alias_list_id) AS id_type,
                   channel.decoder_type, target.family, target.rowid AS target_rowid
            FROM configuration_channel channel
            LEFT JOIN alias_list target ON target.id=channel.alias_list_id
            WHERE channel.alias_list_id IS NOT NULL
            """))
        {
            while(rows.next())
            {
                long rowId = rows.getLong("physical_rowid");
                long targetRowId = rows.getLong("target_rowid");
                boolean targetInvalid = !rows.wasNull() && invalidAliasListRows.contains(targetRowId);
                ChannelRepair channelRepair = channelRepairs.get(rowId);
                String decoderType = channelRepair != null ? channelRepair.projection().decoderType() :
                    rows.getString("decoder_type");
                if(!invalidChannelRows.contains(rowId) &&
                    (!"integer".equals(rows.getString("id_type")) || rows.getLong("alias_list_id") <= 0 ||
                        targetInvalid || !compatible(rows.getString("family"), decoderType)))
                {
                    rowIds.add(rowId);
                }
            }
        }
        return Set.copyOf(rowIds);
    }

    private static boolean compatible(String family, String decoderType)
    {
        if(family == null || decoderType == null)
        {
            return false;
        }
        return switch(family)
        {
            case "P25" -> Set.of("P25_PHASE1", "P25_PHASE2", "P25_CONVENTIONAL").contains(decoderType);
            case "DMR" -> "DMR".equals(decoderType);
            case "NXDN" -> "NXDN".equals(decoderType);
            case "NBFM" -> "AM".equals(decoderType) || "NBFM".equals(decoderType);
            default -> false;
        };
    }

    private static StreamRouteInspection inspectStreamRoutes(Connection connection, ProviderInspection providers,
                                                              Set<Long> invalidAliasRows,
                                                              Set<Long> invalidAliasListRows)
        throws SQLException
    {
        RouteTableInspection aliasRoutes = inspectStreamRouteTable(connection, "alias_broadcast_channel",
            "alias_id", "alias", invalidAliasRows, providers);
        RouteTableInspection unmatchedRoutes = inspectStreamRouteTable(connection,
            "alias_list_unmatched_talkgroup_stream", "alias_list_id", "alias_list", invalidAliasListRows,
            providers);
        return new StreamRouteInspection(aliasRoutes, unmatchedRoutes);
    }

    private static RouteTableInspection inspectStreamRouteTable(Connection connection, String table,
                                                               String ownerColumn, String ownerTable,
                                                               Set<Long> invalidOwnerRows,
                                                               ProviderInspection providers) throws SQLException
    {
        List<Long> invalidRows = new ArrayList<>();
        Map<Long,String> repairs = new LinkedHashMap<>();
        Set<String> retainedRelationships = new LinkedHashSet<>();
        String sql = """
            SELECT route.rowid AS physical_rowid, route.id, typeof(route.id) AS id_type,
                   route.%1$s AS owner_id, typeof(route.%1$s) AS owner_id_type,
                   CASE WHEN typeof(route.broadcast_configuration_id)='text'
                              AND length(CAST(route.broadcast_configuration_id AS BLOB))<=128
                        THEN route.broadcast_configuration_id END AS provider_id,
                   typeof(route.broadcast_configuration_id) AS provider_id_type,
                   length(CAST(route.broadcast_configuration_id AS BLOB)) AS provider_id_bytes,
                   owner.rowid AS owner_rowid
            FROM %2$s route
            LEFT JOIN %3$s owner ON owner.id=route.%1$s
            ORDER BY route.rowid
            """.formatted(ownerColumn, table, ownerTable);
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql))
        {
            while(rows.next())
            {
                long rowId = rows.getLong("physical_rowid");
                Object ownerId = rows.getObject("owner_id");
                String storedProviderId = "text".equals(rows.getString("provider_id_type")) &&
                    rows.getLong("provider_id_bytes") <= 128 ? rows.getString("provider_id") : null;
                String targetProviderId = providers.resolveRouteTarget(storedProviderId);
                long ownerRowId = rows.getLong("owner_rowid");
                boolean ownerMissing = rows.wasNull();
                boolean ownerInvalid = !ownerMissing && invalidOwnerRows.contains(ownerRowId);
                if(!positiveInteger(rows.getObject("id")) || !positiveInteger(ownerId) || ownerMissing ||
                    ownerInvalid || targetProviderId == null)
                {
                    invalidRows.add(rowId);
                    continue;
                }

                String relationship = ((Number)ownerId).longValue() + "\u0000" + targetProviderId;
                if(!retainedRelationships.add(relationship))
                {
                    //Two differently-spelled legacy UUIDs can collapse onto the same current relationship.
                    //Keep the first stable row and discard only the redundant duplicate.
                    invalidRows.add(rowId);
                }
                else if(!targetProviderId.equals(storedProviderId))
                {
                    repairs.put(rowId, targetProviderId);
                }
            }
        }
        return new RouteTableInspection(List.copyOf(invalidRows), Map.copyOf(repairs));
    }

    private static void clearUnusableChannelAliasLists(Connection connection, Set<Long> channelRowIds)
        throws SQLException
    {
        if(channelRowIds.isEmpty())
        {
            return;
        }
        try(PreparedStatement statement = connection.prepareStatement(
            "UPDATE configuration_channel SET alias_list_id=NULL WHERE rowid=?"))
        {
            for(long rowId: channelRowIds)
            {
                statement.setLong(1, rowId);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void applyChannelRepairs(Connection connection, Map<Long,ChannelRepair> repairs,
                                            Set<Long> clearedAliasLists)
        throws SQLException
    {
        Map<Long,String> targetConfigurationIds = new LinkedHashMap<>();
        repairs.forEach((rowId, repair) -> targetConfigurationIds.put(rowId, repair.configurationId()));
        applyTemporaryConfigurationIds(connection, "configuration_channel", targetConfigurationIds);
        try(PreparedStatement statement = connection.prepareStatement("""
            UPDATE configuration_channel
            SET configuration_id=?, sort_order=?, system_name=?, site_name=?, name=?, radioresolve_id=?,
                auto_start=?, auto_start_order=?,
                alias_list_id=CASE WHEN ?<>0 THEN NULL ELSE alias_list_id END,
                channel_kind=?, decoder_type=?, address_domain_code=?, primary_frequency_hz=?, config_json=?
            WHERE rowid=?
            """))
        {
            for(Map.Entry<Long,ChannelRepair> entry: repairs.entrySet())
            {
                ChannelRepair repair = entry.getValue();
                statement.setString(1, repair.configurationId());
                statement.setInt(2, repair.sortOrder());
                statement.setString(3, repair.systemName());
                statement.setString(4, repair.siteName());
                statement.setString(5, repair.name());
                statement.setString(6, repair.radioResolveId());
                statement.setInt(7, repair.autoStart() ? 1 : 0);
                if(repair.autoStartOrder() == null)
                {
                    statement.setNull(8, java.sql.Types.INTEGER);
                }
                else
                {
                    statement.setInt(8, repair.autoStartOrder());
                }
                statement.setInt(9, clearedAliasLists.contains(entry.getKey()) ? 1 : 0);
                statement.setString(10, repair.channelKind());
                repair.projection().bind(statement, 11);
                statement.setString(14, repair.payload());
                statement.setLong(15, entry.getKey());
                if(statement.executeUpdate() != 1)
                {
                    throw new SQLException("Accepted saved channel changed during current-format repair");
                }
            }
        }
    }

    private static void applyProviderRepairs(Connection connection, Map<Long,ProviderRepair> repairs)
        throws SQLException
    {
        Map<Long,String> targetConfigurationIds = new LinkedHashMap<>();
        repairs.forEach((rowId, repair) -> targetConfigurationIds.put(rowId, repair.configurationId()));
        applyTemporaryConfigurationIds(connection, "configuration_broadcast_stream", targetConfigurationIds);
        try(PreparedStatement statement = connection.prepareStatement("""
            UPDATE configuration_broadcast_stream
            SET configuration_id=?, sort_order=?, config_json=? WHERE rowid=?
            """))
        {
            for(Map.Entry<Long,ProviderRepair> entry: repairs.entrySet())
            {
                statement.setString(1, entry.getValue().configurationId());
                statement.setInt(2, entry.getValue().sortOrder());
                statement.setString(3, entry.getValue().payload());
                statement.setLong(4, entry.getKey());
                if(statement.executeUpdate() != 1)
                {
                    throw new SQLException("Accepted broadcast provider changed during current-format repair");
                }
            }
        }
    }

    /** Moves colliding technical identities aside before applying their unique final canonical assignments. */
    private static void applyTemporaryConfigurationIds(Connection connection, String table,
                                                       Map<Long,String> targetIds) throws SQLException
    {
        if(targetIds.isEmpty())
        {
            return;
        }

        Map<Long,String> currentIds = new LinkedHashMap<>();
        Set<String> occupied = new LinkedHashSet<>(targetIds.values());
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
            "SELECT rowid, configuration_id FROM " + table))
        {
            while(rows.next())
            {
                Object stored = rows.getObject(2);
                if(stored instanceof String text)
                {
                    currentIds.put(rows.getLong(1), text);
                    occupied.add(text);
                }
            }
        }

        Map<Long,String> temporaryIds = new LinkedHashMap<>();
        for(Map.Entry<Long,String> entry: targetIds.entrySet())
        {
            if(Objects.equals(currentIds.get(entry.getKey()), entry.getValue()))
            {
                continue;
            }
            String temporary = deterministicUuid("temporary-" + table, entry.getKey(), occupied, Set.of());
            occupied.add(temporary);
            temporaryIds.put(entry.getKey(), temporary);
        }

        try(PreparedStatement statement = connection.prepareStatement(
            "UPDATE " + table + " SET configuration_id=? WHERE rowid=?"))
        {
            for(Map.Entry<Long,String> entry: temporaryIds.entrySet())
            {
                statement.setString(1, entry.getValue());
                statement.setLong(2, entry.getKey());
                if(statement.executeUpdate() != 1)
                {
                    throw new SQLException("Accepted configuration identity changed during current-format repair");
                }
            }
        }
    }

    private static void applyStreamRouteRepairs(Connection connection, String table, Map<Long,String> repairs)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement(
            "UPDATE " + table + " SET broadcast_configuration_id=? WHERE rowid=?"))
        {
            for(Map.Entry<Long,String> entry: repairs.entrySet())
            {
                statement.setString(1, entry.getValue());
                statement.setLong(2, entry.getKey());
                if(statement.executeUpdate() != 1)
                {
                    throw new SQLException("Accepted broadcast route changed during current-format repair");
                }
            }
        }
    }

    private static void applyAliasFieldDefaults(Connection connection, AliasInspection inspection)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            UPDATE alias SET name=?, description=?, group_name=?, color=?, icon_name=?, stream_as_talkgroup=?,
                             record_enabled=?, matcher_type=?, protocol=?, value=?, min_value=?, max_value=?,
                             text_value=?, numeric_value=?, tone_sequence=?
            WHERE rowid=?
            """))
        {
            for(Map.Entry<Long,AliasRepair> entry: inspection.repairs().entrySet())
            {
                AliasRepair repair = entry.getValue();
                AliasOptionalFields fields = repair.optionalFields();
                Format14To15DatabaseMigration.AliasMatcherFields matcher = repair.matcher();
                statement.setString(1, repair.name());
                statement.setString(2, fields.description());
                statement.setString(3, fields.groupName());
                statement.setInt(4, fields.color());
                statement.setString(5, fields.iconName());
                if(fields.streamAsTalkgroup() == null)
                {
                    statement.setNull(6, java.sql.Types.INTEGER);
                }
                else
                {
                    statement.setInt(6, fields.streamAsTalkgroup());
                }
                statement.setInt(7, fields.recordEnabled() ? 1 : 0);
                statement.setString(8, matcher.matcherType());
                statement.setString(9, matcher.protocol());
                setNullableInteger(statement, 10, matcher.value());
                setNullableInteger(statement, 11, matcher.minimum());
                setNullableInteger(statement, 12, matcher.maximum());
                statement.setString(13, matcher.textValue());
                setNullableInteger(statement, 14, matcher.numericValue());
                statement.setString(15, matcher.toneSequence());
                statement.setLong(16, entry.getKey());
                if(statement.executeUpdate() != 1)
                {
                    throw new SQLException("Accepted Alias changed during current-format repair");
                }
            }
        }
    }

    private static void applyAliasListRepairs(Connection connection, AliasListInspection inspection)
        throws SQLException
    {
        Map<Long,String> temporaryNames = temporaryNamePlan(connection, "alias_list",
            inspection.nameRepairs(), 25);
        try(PreparedStatement statement = connection.prepareStatement("""
            UPDATE alias_list
            SET name=?, unmatched_talkgroup_record_enabled=
                CASE WHEN ?<>0 THEN 0 ELSE unmatched_talkgroup_record_enabled END
            WHERE id=?
            """))
        {
            for(Map.Entry<Long,String> entry: temporaryNames.entrySet())
            {
                statement.setString(1, entry.getValue());
                statement.setInt(2, inspection.defaultedPolicies().contains(entry.getKey()) ? 1 : 0);
                statement.setLong(3, entry.getKey());
                if(statement.executeUpdate() != 1)
                {
                    throw new SQLException("Accepted Alias List changed during current-format repair");
                }
            }
        }
        try(PreparedStatement statement = connection.prepareStatement("UPDATE alias_list SET name=? WHERE id=?"))
        {
            for(Map.Entry<Long,String> entry: inspection.nameRepairs().entrySet())
            {
                statement.setString(1, entry.getValue());
                statement.setLong(2, entry.getKey());
                if(statement.executeUpdate() != 1)
                {
                    throw new SQLException("Accepted Alias List changed during current-format repair");
                }
            }
        }
        try(PreparedStatement statement = connection.prepareStatement(
            "UPDATE alias_list SET unmatched_talkgroup_record_enabled=0 WHERE rowid=?"))
        {
            for(long rowId: inspection.defaultedPolicies())
            {
                if(inspection.nameRepairs().containsKey(rowId))
                {
                    continue;
                }
                statement.setLong(1, rowId);
                if(statement.executeUpdate() != 1)
                {
                    throw new SQLException("Accepted Alias List changed during current-format repair");
                }
            }
        }
    }

    private static long applyScanListRepairs(Connection connection, ScanListInspection inspection)
        throws SQLException
    {
        Map<Long,ScanListRow> rowsById = inspection.rows().stream()
            .collect(java.util.stream.Collectors.toMap(ScanListRow::id, row -> row));
        Map<Long,String> temporaryNames = temporaryNamePlan(connection, "scan_list",
            inspection.nameRepairs(), 100);
        try(PreparedStatement statement = connection.prepareStatement("""
            UPDATE scan_list SET name=?, sort_order=?, description=?, published=?, is_default=? WHERE id=?
            """))
        {
            for(Map.Entry<Long,String> entry: temporaryNames.entrySet())
            {
                ScanListRow row = rowsById.get(entry.getKey());
                if(row == null)
                {
                    throw new SQLException("Accepted scan-list name repair has no row plan");
                }
                statement.setString(1, entry.getValue());
                statement.setInt(2, row.sortOrder());
                statement.setString(3, row.description());
                statement.setInt(4, row.published() ? 1 : 0);
                statement.setInt(5, row.isDefault() ? 1 : 0);
                statement.setLong(6, row.id());
                if(statement.executeUpdate() != 1)
                {
                    throw new SQLException("Accepted scan list changed during current-format repair");
                }
            }
        }
        try(PreparedStatement statement = connection.prepareStatement("""
            UPDATE scan_list
            SET name=?, sort_order=?, description=?, published=?, is_default=?
            WHERE id=?
            """))
        {
            for(ScanListRow row: inspection.rows())
            {
                statement.setString(1, row.name());
                statement.setInt(2, row.sortOrder());
                statement.setString(3, row.description());
                statement.setInt(4, row.published() ? 1 : 0);
                statement.setInt(5, row.isDefault() ? 1 : 0);
                statement.setLong(6, row.id());
                if(statement.executeUpdate() != 1)
                {
                    throw new SQLException("Accepted scan list changed during current-format repair");
                }
            }
        }
        if(inspection.generateDefault())
        {
            long generatedDefaultId = allocateGeneratedDefaultScanListId(connection);
            try(PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO scan_list(id, sort_order, name, description, published, is_default)
                VALUES (?, 0, 'Default', NULL, 1, 1)
                """))
            {
                statement.setLong(1, generatedDefaultId);
                statement.executeUpdate();
            }
            return generatedDefaultId;
        }
        return inspection.rows().stream().filter(ScanListRow::isDefault).mapToLong(ScanListRow::id)
            .findFirst().orElseThrow(() -> new SQLException("Current-format scan-list repair has no Default target"));
    }

    private static long allocateGeneratedDefaultScanListId(Connection connection) throws SQLException
    {
        long candidate = 1;
        try(Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("SELECT id FROM scan_list ORDER BY id"))
        {
            while(rows.next())
            {
                long storedId = rows.getLong(1);
                if(storedId == candidate)
                {
                    candidate++;
                }
                else if(storedId > candidate)
                {
                    break;
                }
            }
        }
        if(candidate >= SqliteIdentityRepair.JSON_SAFE_INTEGER_MAXIMUM)
        {
            throw new SQLException("Unable to allocate a safe repaired Default scan-list identifier");
        }
        return candidate;
    }

    private static void applyRecoveredDefaultMemberships(Connection connection, long defaultScanListId,
                                                          GeneratedDefaultMembershipRecovery recovery)
        throws SQLException
    {
        if(recovery.sourceRows() == 0)
        {
            return;
        }
        insertGeneratedDefaultMemberships(connection, "alias_scan_list_membership", "alias_id",
            recovery.aliasIds(), defaultScanListId);
        insertGeneratedDefaultMemberships(connection, "alias_list_unmatched_talkgroup_scan_list_membership",
            "alias_list_id", recovery.aliasListIds(), defaultScanListId);
    }

    private static void insertGeneratedDefaultMemberships(Connection connection, String table, String ownerColumn,
                                                            Set<Long> ownerIds, long generatedDefaultId)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement(
            "INSERT OR IGNORE INTO " + table + "(" + ownerColumn + ", scan_list_id) VALUES (?, ?)"))
        {
            for(long ownerId: ownerIds)
            {
                statement.setLong(1, ownerId);
                statement.setLong(2, generatedDefaultId);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static Map<Long,String> temporaryNamePlan(Connection connection, String table,
                                                      Map<Long,String> repairs, int maximumLength)
        throws SQLException
    {
        if(repairs.isEmpty())
        {
            return Map.of();
        }
        List<Map.Entry<Long,String>> ordered = repairs.entrySet().stream()
            .sorted(Map.Entry.comparingByKey()).toList();
        Set<String> occupied = new LinkedHashSet<>();
        try(Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("SELECT name FROM " + table))
        {
            while(rows.next())
            {
                occupied.add(ConfigurationNameRepair.normalize(rows.getString(1)));
            }
        }
        repairs.values().forEach(name -> occupied.add(ConfigurationNameRepair.normalize(name)));

        Map<Long,String> temporaryNames = new LinkedHashMap<>();
        for(Map.Entry<Long,String> entry: ordered)
        {
            int attempt = 0;
            String temporary;
            do
            {
                String suffix = attempt++ == 0 ? "" : "-" + attempt;
                String identifier = Long.toUnsignedString(entry.getKey(), 36);
                temporary = "~m" + identifier.substring(Math.max(0,
                    identifier.length() - Math.max(1, maximumLength - 2 - suffix.length()))) + suffix;
            }
            while(temporary.length() > maximumLength ||
                !occupied.add(ConfigurationNameRepair.normalize(temporary)));
            temporaryNames.put(entry.getKey(), temporary);
        }
        return Map.copyOf(temporaryNames);
    }

    private static void deleteRows(Connection connection, String table, List<Long> rowIds) throws SQLException
    {
        if(rowIds.isEmpty())
        {
            return;
        }
        try(PreparedStatement statement = connection.prepareStatement("DELETE FROM " + table + " WHERE rowid=?"))
        {
            for(long rowId: rowIds)
            {
                statement.setLong(1, rowId);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void deleteMembershipRows(Connection connection, String table, String ownerColumn,
                                             List<MembershipRowKey> rows) throws SQLException
    {
        if(rows.isEmpty())
        {
            return;
        }
        try(PreparedStatement statement = connection.prepareStatement(
            "DELETE FROM " + table + " WHERE " + ownerColumn + "=? AND scan_list_id=?"))
        {
            for(MembershipRowKey row: rows)
            {
                statement.setObject(1, row.ownerId());
                statement.setObject(2, row.scanListId());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void setNullableInteger(PreparedStatement statement, int index, Integer value)
        throws SQLException
    {
        if(value == null)
        {
            statement.setNull(index, java.sql.Types.INTEGER);
        }
        else
        {
            statement.setInt(index, value);
        }
    }

    private static boolean typedObject(JsonNode value)
    {
        return value != null && value.isObject() && value.hasNonNull("type") && value.get("type").isTextual() &&
            !value.get("type").textValue().isBlank();
    }

    private static Set<String> reservedCanonicalIds(Connection connection, String table) throws SQLException
    {
        Set<String> reserved = new LinkedHashSet<>();
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("""
            SELECT CASE WHEN typeof(configuration_id)='text'
                              AND length(CAST(configuration_id AS BLOB))<=128
                        THEN configuration_id END AS configuration_id
            FROM %s
            """.formatted(table)))
        {
            while(rows.next())
            {
                String canonical = tryCanonicalUuid(rows.getObject("configuration_id"));
                if(canonical != null)
                {
                    reserved.add(canonical);
                }
            }
        }
        return Set.copyOf(reserved);
    }

    private static String deterministicUuid(String component, long rowId, Set<String> reserved,
                                            Set<String> retained)
    {
        int attempt = 0;
        while(true)
        {
            String seed = component + ':' + rowId + ':' + attempt++;
            String candidate = UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
            if(!reserved.contains(candidate) && !retained.contains(candidate))
            {
                return candidate;
            }
        }
    }

    private static String tryCanonicalUuid(Object value)
    {
        if(!(value instanceof String text))
        {
            return null;
        }
        try
        {
            return UUID.fromString(text.strip()).toString();
        }
        catch(IllegalArgumentException ignored)
        {
            return null;
        }
    }

    private static boolean integer(Object value)
    {
        return value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long;
    }

    private static Integer integerValue(Object value)
    {
        return intValue(value) ? ((Number)value).intValue() : null;
    }

    private static Long longValue(Object value)
    {
        return integer(value) ? ((Number)value).longValue() : null;
    }

    private static boolean positiveInteger(Object value)
    {
        return integer(value) && ((Number)value).longValue() > 0 &&
            ((Number)value).longValue() < SqliteIdentityRepair.JSON_SAFE_INTEGER_MAXIMUM;
    }

    private static boolean positiveNullableInteger(Object value)
    {
        return value == null || positiveInteger(value);
    }

    private static boolean positiveNullableInt(Object value)
    {
        return value == null || positiveInteger(value) && ((Number)value).longValue() <= 0xFFFFFF;
    }

    private static boolean intValue(Object value)
    {
        return integer(value) && ((Number)value).longValue() >= Integer.MIN_VALUE &&
            ((Number)value).longValue() <= Integer.MAX_VALUE;
    }

    private static boolean nonNegativeInt(Object value)
    {
        return integer(value) && ((Number)value).longValue() >= 0 &&
            ((Number)value).longValue() <= Integer.MAX_VALUE;
    }

    private static boolean nullableInt(Object value)
    {
        return value == null || integer(value) && ((Number)value).longValue() >= Integer.MIN_VALUE &&
            ((Number)value).longValue() <= Integer.MAX_VALUE;
    }

    private static boolean booleanInteger(Object value)
    {
        return integer(value) && (((Number)value).longValue() == 0 || ((Number)value).longValue() == 1);
    }

    private static long requirePositiveId(Object value)
    {
        if(!positiveInteger(value))
        {
            throw new IllegalArgumentException("Configuration row requires a positive integer identity");
        }
        return ((Number)value).longValue();
    }

    private static String requiredBoundedText(ResultSet row, String valueColumn, String typeColumn,
                                              String bytesColumn, int maximumBytes) throws SQLException
    {
        if(!"text".equals(row.getString(typeColumn)))
        {
            throw new IllegalArgumentException("Configuration text has the wrong SQLite storage type");
        }
        if(bytesColumn != null)
        {
            long bytes = row.getLong(bytesColumn);
            if(row.wasNull() || bytes > maximumBytes)
            {
                throw new IllegalArgumentException("Configuration text is too large");
            }
        }
        String value = row.getString(valueColumn);
        if(value == null)
        {
            throw new IllegalArgumentException("Configuration text is unavailable");
        }
        return value;
    }

    private static boolean boundedNonBlankText(ResultSet row, String column) throws SQLException
    {
        return "text".equals(row.getString(column + "_type")) &&
            boundedBytes(row, column, MAXIMUM_ALIAS_TEXT_BYTES) && row.getString(column) != null &&
            !row.getString(column).isBlank();
    }

    private static boolean boundedNullableText(ResultSet row, String column) throws SQLException
    {
        return boundedNullableText(row, column, MAXIMUM_ALIAS_TEXT_BYTES);
    }

    private static boolean boundedNullableText(ResultSet row, String column, int maximumBytes) throws SQLException
    {
        String type = row.getString(column + "_type");
        return "null".equals(type) || "text".equals(type) && boundedBytes(row, column, maximumBytes) &&
            row.getString(column) != null;
    }

    private static boolean boundedRequiredText(ResultSet row, String column, int maximumBytes) throws SQLException
    {
        return "text".equals(row.getString(column + "_type")) &&
            boundedBytes(row, column, maximumBytes) && row.getString(column) != null;
    }

    private static boolean boundedBytes(ResultSet row, String column, int maximumBytes) throws SQLException
    {
        long bytes = row.getLong(column + "_bytes");
        return !row.wasNull() && bytes <= maximumBytes;
    }

    private static boolean addressDomain(Object value)
    {
        return integer(value) && ((Number)value).longValue() >= 0 && ((Number)value).longValue() <= 2;
    }

    private static long scalar(Connection connection, String sql) throws SQLException
    {
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            if(!resultSet.next())
            {
                throw new SQLException("Current-format relationship repair count returned no result");
            }
            return resultSet.getLong(1);
        }
    }

    record Inspection(long droppedRelationships, long repairedBroadcastRoutes, long clearedChannelAliasLists,
                      long droppedChannels, long repairedChannels, long droppedBroadcastProviders,
                      long repairedBroadcastProviders,
                      long droppedAliases, long droppedAliasLists,
                      long droppedScanLists, long renamedAliasLists, long defaultedAliasListPolicies,
                      long renamedScanLists, long defaultedScanLists, long repairedDefaultScanList,
                      long remappedScanListMemberships, long defaultedAliasRows)
    {
        static Inspection none()
        {
            return new Inspection(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        boolean requiresRepair()
        {
            return droppedRelationships > 0 || repairedBroadcastRoutes > 0 || clearedChannelAliasLists > 0 ||
                droppedChannels > 0 ||
                repairedChannels > 0 ||
                droppedBroadcastProviders > 0 || repairedBroadcastProviders > 0 ||
                droppedAliases > 0 || droppedAliasLists > 0 ||
                droppedScanLists > 0 || renamedAliasLists > 0 || defaultedAliasListPolicies > 0 ||
                renamedScanLists > 0 || defaultedScanLists > 0 || repairedDefaultScanList > 0 ||
                remappedScanListMemberships > 0 || defaultedAliasRows > 0;
        }
    }

    private record ProviderInspection(List<Long> invalidRowIds, Map<Long,ProviderRepair> repairs,
                                      Map<String,String> exactRouteTargets,
                                      Map<String,String> canonicalRouteTargets)
    {
        private String resolveRouteTarget(String storedId)
        {
            if(storedId == null)
            {
                return null;
            }
            String exact = exactRouteTargets.get(storedId);
            if(exact != null)
            {
                return exact;
            }
            String canonical = tryCanonicalUuid(storedId);
            return canonical == null ? null : canonicalRouteTargets.get(canonical);
        }
    }

    private record Analysis(Inspection inspection, ChannelInspection channels, ProviderInspection providers,
                            StreamRouteInspection streamRoutes, Set<Long> clearedChannelAliasLists,
                            AliasInspection aliases, AliasListInspection aliasLists,
                            ScanListInspection scanLists, MembershipInspection memberships)
    {
    }

    private record ChannelInspection(List<Long> invalidRowIds, Map<Long,ChannelRepair> repairs)
    {
    }

    private record ChannelRepair(String configurationId, int sortOrder, String systemName, String siteName, String name,
                                 String radioResolveId, boolean autoStart, Integer autoStartOrder,
                                 String channelKind, ConfigurationChannelProjection projection, String payload)
    {
    }

    private record ProviderRepair(String configurationId, int sortOrder, String payload)
    {
    }

    private record ProviderPlan(ProviderIdentity identity, ProviderRepair repair)
    {
    }

    private record ProviderIdentity(String storedId, String canonicalSourceId, String targetId)
    {
    }

    private record RouteTableInspection(List<Long> invalidRowIds, Map<Long,String> repairs)
    {
        private long repairedRows()
        {
            return repairs.size();
        }
    }

    private record StreamRouteInspection(RouteTableInspection aliasRoutes, RouteTableInspection unmatchedRoutes)
    {
        private long droppedRows()
        {
            return Math.addExact(aliasRoutes.invalidRowIds().size(), unmatchedRoutes.invalidRowIds().size());
        }

        private long repairedRows()
        {
            return Math.addExact(aliasRoutes.repairedRows(), unmatchedRoutes.repairedRows());
        }
    }

    private record GeneratedDefaultMembershipRecovery(Set<Long> aliasIds, Set<Long> aliasListIds, long sourceRows)
    {
        private static GeneratedDefaultMembershipRecovery none()
        {
            return new GeneratedDefaultMembershipRecovery(Set.of(), Set.of(), 0);
        }
    }

    private record MembershipRowKey(Object ownerId, Object scanListId)
    {
    }

    private record MembershipTableInspection(List<MembershipRowKey> rowsToDelete, long recoveredRows)
    {
    }

    private record MembershipInspection(List<MembershipRowKey> aliasRowsToDelete,
                                        List<MembershipRowKey> unmatchedRowsToDelete,
                                        GeneratedDefaultMembershipRecovery recovery)
    {
        private long droppedRows()
        {
            return Math.subtractExact(Math.addExact(aliasRowsToDelete.size(), unmatchedRowsToDelete.size()),
                recovery.sourceRows());
        }
    }

    private record AliasListInspection(List<Long> invalidRowIds, Map<Long,String> nameRepairs,
                                       Set<Long> defaultedPolicies)
    {
    }

    private record AliasInspection(List<Long> invalidRowIds, Map<Long,AliasRepair> repairs,
                                   long defaultedAliasRows)
    {
    }

    private record AliasRepair(String name, AliasOptionalFields optionalFields,
                               Format14To15DatabaseMigration.AliasMatcherFields matcher)
    {
    }

    private record AliasOptionalFields(String description, String groupName, int color, String iconName,
                                       Integer streamAsTalkgroup, boolean recordEnabled, long defaultedFieldCount)
    {
    }

    private record ScanListInspection(List<Long> invalidRowIds, List<ScanListRow> rows,
                                      Map<Long,String> nameRepairs, Set<Long> defaultedRows,
                                      long defaultSelectionRepair, boolean generateDefault)
    {
    }

    private record ScanListRow(long id, int sortOrder, String name, String description, boolean published,
                               boolean isDefault)
    {
        private ScanListRow withName(String name)
        {
            return new ScanListRow(id, sortOrder, name, description, published, isDefault);
        }

        private ScanListRow withDefault(boolean selected)
        {
            return new ScanListRow(id, sortOrder, name, description, selected || published, selected);
        }
    }
}
