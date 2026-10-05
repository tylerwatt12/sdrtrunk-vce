/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Adds the Live calls sorting choice without changing other personal settings. */
final class Format35To36DatabaseMigration implements DatabaseMigrationStep
{
    @Override public String id() { return "format-35-to-36"; }
    @Override public String description() { return "Add personal Live calls sorting"; }
    @Override public int sourceVersion() { return 35; }
    @Override public int targetVersion() { return 36; }

    @Override
    public List<DatabaseMigrationEffect> declaredEffects()
    {
        return declaredEffects(true);
    }

    @Override
    public List<DatabaseMigrationEffect> declaredEffects(boolean selectedSourceStep)
    {
        return effects(DatabaseMigrationEffect.UNKNOWN_COUNT, DatabaseMigrationEffect.UNKNOWN_COUNT,
            DatabaseMigrationEffect.UNKNOWN_COUNT, DatabaseMigrationEffect.UNKNOWN_COUNT, List.of(), true, selectedSourceStep);
    }

    @Override
    public List<DatabaseMigrationEffect> validateSource(Connection connection) throws SQLException
    {
        UserInspection inspection = inspect(connection);
        return effects(inspection.updates().size(), inspection.defaultedUsers(), inspection.rebasedRevisions(), inspection.resetLayouts(),
            inspectRepairs(connection), false, true);
    }

    @Override
    public void migrate(Connection connection) throws SQLException
    {
        migrateAndReport(connection, true);
    }

    @Override
    public List<DatabaseMigrationEffect> migrateAndReport(Connection connection) throws SQLException
    {
        return migrateAndReport(connection, true);
    }

    @Override
    public List<DatabaseMigrationEffect> migrateAndReport(Connection connection, boolean selectedSourceStep)
        throws SQLException
    {
        requireSource(connection);
        //Inspect the bounded layout fallback before any component repairs.
        UserInspection initial = inspect(connection);
        List<DatabaseMigrationEffect> repairs = selectedSourceStep ? repair(connection) : List.of();
        UserInspection inspection = selectedSourceStep ? inspect(connection) : initial;
        long updatedAt = Math.max(1, System.currentTimeMillis());
        try(var statement = connection.prepareStatement("""
            UPDATE web_user SET preferences_json=?, preferences_revision=?, updated_at_ms=? WHERE id=?
            """))
        {
            for(UserUpdate update: inspection.updates())
            {
                statement.setString(1, update.json());
                statement.setLong(2, update.revision());
                statement.setLong(3, updatedAt);
                statement.setLong(4, update.id());
                if(statement.executeUpdate() != 1)
                {
                    throw new SQLException("Web user changed during the Live sorting preference migration");
                }
            }
        }
        return effects(inspection.updates().size(), inspection.defaultedUsers(), inspection.rebasedRevisions(), inspection.resetLayouts(),
            repairs, false,
            selectedSourceStep);
    }

    private static void requireSource(Connection connection) throws SQLException
    {
        if(DatabaseFormatCatalog.inspectForMigration(connection).version() != 35)
        {
            throw new SQLException("Migration step format-35-to-36 requires exact source format 35");
        }
    }

    private static UserInspection inspect(Connection connection) throws SQLException
    {
        requireSource(connection);
        List<UserUpdate> updates = new ArrayList<>();
        long defaultedUsers = 0;
        long rebasedRevisions = 0;
        long resetLayouts = 0;
        try(var query = connection.createStatement(); ResultSet rows = query.executeQuery("""
            SELECT id,
                   CASE WHEN typeof(preferences_json)='text'
                              AND length(CAST(preferences_json AS BLOB)) <= 131072
                        THEN preferences_json END AS preferences_json,
                   CASE WHEN typeof(preferences_revision)='integer'
                        THEN preferences_revision END AS preferences_revision
            FROM web_user ORDER BY id
            """))
        {
            while(rows.next())
            {
                long id = rows.getLong("id");
                long revision = rows.getLong("preferences_revision");
                boolean revisionMissing = rows.wasNull();
                boolean incrementable = !revisionMissing && revision > 0 && revision < Long.MAX_VALUE - 2;
                String target;
                String source = rows.getString("preferences_json");
                boolean usableSource;
                try
                {
                    Format35WebUserPreferencesCodec.validate(source);
                    usableSource = true;
                }
                catch(IOException | RuntimeException invalid)
                {
                    usableSource = false;
                }
                if(usableSource)
                {
                    try
                    {
                        var migrated = Format36WebUserPreferencesCodec.migrateToBoundedFormat36(source);
                        target = migrated.json();
                        resetLayouts += migrated.resetLayouts();
                    }
                    catch(IOException | RuntimeException conversionFailure)
                    {
                        throw new SQLException("Personal preferences cannot be converted within the version-10 storage bound",
                            conversionFailure);
                    }
                }
                else
                {
                    try
                    {
                        target = Format36WebUserPreferencesCodec.defaults();
                    }
                    catch(IOException defaultsInvalid)
                    {
                        throw new SQLException("Unable to create default version-10 browser preferences",
                            defaultsInvalid);
                    }
                    defaultedUsers++;
                }
                if(!incrementable) rebasedRevisions++;
                updates.add(new UserUpdate(id, target, incrementable ? revision + 1 : 1));
            }
        }
        return new UserInspection(List.copyOf(updates), defaultedUsers, rebasedRevisions, resetLayouts);
    }

    private static List<DatabaseMigrationEffect> inspectRepairs(Connection connection) throws SQLException
    {
        CurrentDatabaseBestEffortRepair.requireOnlyRepairableForeignKeys(connection);
        return repairEffects(ApplicationDatabaseMigrator.inspectCurrentPortablePreferenceRepairs(connection),
            CurrentDatabaseAdministrativeRepair.inspect(connection, false),
            CurrentDatabaseBestEffortRepair.inspect(connection), CurrentDatabaseDerivedStateRepair.inspect(connection));
    }

    private static List<DatabaseMigrationEffect> repair(Connection connection) throws SQLException
    {
        CurrentDatabaseBestEffortRepair.requireOnlyRepairableForeignKeys(connection);
        return repairEffects(ApplicationDatabaseMigrator.repairCurrentPortablePreferences(connection),
            CurrentDatabaseAdministrativeRepair.repair(connection, false),
            CurrentDatabaseBestEffortRepair.repair(connection), CurrentDatabaseDerivedStateRepair.repair(connection));
    }

    private static List<DatabaseMigrationEffect> repairEffects(long portablePreferences,
        CurrentDatabaseAdministrativeRepair.Inspection administrative,
        CurrentDatabaseBestEffortRepair.Inspection configuration, CurrentDatabaseDerivedStateRepair.Inspection derived)
    {
        List<DatabaseMigrationEffect> effects = new ArrayList<>();
        effects.add(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.RESET,
            "unusable portable preference components", portablePreferences,
            "Preserve valid portable settings and default only malformed or obsolete components"));
        effects.addAll(CurrentDatabaseAdministrativeRepair.effects(administrative));
        effects.addAll(CurrentDatabaseBestEffortRepair.effects(configuration));
        effects.addAll(CurrentDatabaseDerivedStateRepair.effects(derived));
        return List.copyOf(effects);
    }

    private static List<DatabaseMigrationEffect> effects(long users, long defaultedUsers, long rebasedRevisions,
        long resetLayouts,
        List<DatabaseMigrationEffect> repairs, boolean declared, boolean includeRepairs)
    {
        List<DatabaseMigrationEffect> effects = new ArrayList<>();
        effects.add(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
            "personal Live calls sorting", users,
            "Preserve all usable version-9 personal preferences, add order-appeared sorting for active-only views or LCN for views with idle rows, " +
                "and increment each preference revision"));
        effects.add(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
            "unusable per-user browser preferences", defaultedUsers,
            "Default only malformed or oversized preference documents to version 10"));
        effects.add(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
            "unusable per-user preference revisions", rebasedRevisions,
            "Rebase an invalid or exhausted preference revision while preserving its usable personal settings"));
        effects.add(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.RESET,
            "saved table layouts exceeding the upgraded personal-preference storage bound", resetLayouts,
            "Remove only enough cached table layouts to fit the added sorting choice; preserve all other personal settings, " +
                "accounts, credentials and receiver configuration"));
        if(includeRepairs)
        {
            List<DatabaseMigrationEffect> described = declared ? repairEffects(
                DatabaseMigrationEffect.UNKNOWN_COUNT, CurrentDatabaseAdministrativeRepair.Inspection.none(),
                CurrentDatabaseBestEffortRepair.Inspection.none(), CurrentDatabaseDerivedStateRepair.Inspection.none()) :
                repairs;
            for(DatabaseMigrationEffect effect: described)
            {
                effects.add(declared ? new DatabaseMigrationEffect(effect.kind(), effect.subject(),
                    DatabaseMigrationEffect.UNKNOWN_COUNT, effect.detail()) : effect);
            }
        }
        return List.copyOf(effects);
    }

    private record UserUpdate(long id, String json, long revision) {}
    private record UserInspection(List<UserUpdate> updates, long defaultedUsers, long rebasedRevisions,
                                  long resetLayouts) {}
}
