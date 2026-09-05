/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.settings;

import io.github.dsheirer.database.SdrTrunkDatabase;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/** Transactional owner for the receiver-wide spectrum-snap country selection. */
public final class SpectrumSnapSettingsService
{
    private final Path mDatabasePath;

    public SpectrumSnapSettingsService(Path databasePath)
    {
        mDatabasePath = Objects.requireNonNull(databasePath).toAbsolutePath().normalize();
    }

    public Snapshot snapshot() throws IOException, SQLException
    {
        return snapshot(SpectrumSnapSettings.read(mDatabasePath));
    }

    public synchronized ReplaceResult replace(long expectedRevision, String countryCode) throws IOException, SQLException
    {
        if(expectedRevision < 1)
        {
            throw new IllegalArgumentException("Expected spectrum-snap revision must be positive");
        }
        String normalizedCountry = SpectrumSnapPresetCatalog.requireCountry(countryCode).code();
        try(Connection connection = SdrTrunkDatabase.open(mDatabasePath))
        {
            connection.setAutoCommit(false);
            try
            {
                SpectrumSnapSettings current = SpectrumSnapSettings.read(connection);
                if(current.revision() != expectedRevision)
                {
                    connection.rollback();
                    return new ReplaceResult(false, snapshot(current));
                }
                SpectrumSnapSettings updated = new SpectrumSnapSettings(
                    Math.incrementExact(current.revision()), normalizedCountry);
                SpectrumSnapSettings.write(connection, updated);
                connection.commit();
                return new ReplaceResult(true, snapshot(updated));
            }
            catch(SQLException | RuntimeException exception)
            {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static Snapshot snapshot(SpectrumSnapSettings settings)
    {
        SpectrumSnapPresetCatalog.Country country =
            SpectrumSnapPresetCatalog.requireCountry(settings.countryCode());
        return new Snapshot(settings.revision(), country.code(), country.label(),
            SpectrumSnapPresetCatalog.countries(), country.scopes());
    }

    public record Snapshot(long revision, String countryCode, String countryLabel,
                           List<SpectrumSnapPresetCatalog.CountrySummary> countries,
                           List<SpectrumSnapPresetCatalog.Scope> scopes)
    {
        public Snapshot
        {
            countries = List.copyOf(countries);
            scopes = List.copyOf(scopes);
        }
    }

    public record ReplaceResult(boolean updated, Snapshot snapshot)
    {
    }
}
