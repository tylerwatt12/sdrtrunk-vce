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
package io.github.dsheirer.service.radioreference;

import java.util.List;

/**
 * JavaFX-independent boundary around the RadioReference client library.
 *
 * <p>The snapshots are compact, immutable projections of directory and exact-frequency search data.
 * {@link DetailReference} carries the native object identity that a later importer slice can use to load system sites,
 * frequencies and talkgroups without changing the directory contract.</p>
 */
public interface RadioReferenceGateway extends AutoCloseable
{
    Account account() throws RadioReferenceGatewayException;

    List<Country> countries() throws RadioReferenceGatewayException;

    CountryDirectory country(int countryId) throws RadioReferenceGatewayException;

    StateDirectory state(int stateId) throws RadioReferenceGatewayException;

    CountyDirectory county(int countyId) throws RadioReferenceGatewayException;

    List<FrequencyResult> searchStateFrequencies(int stateId, double frequencyMHz)
        throws RadioReferenceGatewayException;

    @Override
    void close();

    record Account(String userName, String expiration)
    {
    }

    record Country(int id, String name, String code)
    {
    }

    record State(int id, String name, String code)
    {
    }

    record County(int id, String name, String header)
    {
    }

    record Agency(int id, String name, int type)
    {
    }

    record TrunkedSystem(int id, String name, String city, int typeId, int flavorId, int voiceId)
    {
    }

    record FrequencyResult(double downlinkMHz, double uplinkMHz, String callsign, String description,
                           String alpha, String tone, String colorCode, String talkgroup, String slot,
                           String mode, String classification, List<String> tags, int subCategoryId,
                           int stateId, int agencyId, int countyId)
    {
        public FrequencyResult
        {
            tags = immutable(tags);
        }
    }

    record CountryDirectory(Country country, List<State> states, List<Agency> agencies)
    {
        public CountryDirectory
        {
            states = immutable(states);
            agencies = immutable(agencies);
        }
    }

    record StateDirectory(State state, List<County> counties, List<TrunkedSystem> systems, List<Agency> agencies)
    {
        public StateDirectory
        {
            counties = immutable(counties);
            systems = immutable(systems);
            agencies = immutable(agencies);
        }
    }

    record CountyDirectory(County county, List<TrunkedSystem> systems, List<Agency> agencies)
    {
        public CountyDirectory
        {
            systems = immutable(systems);
            agencies = immutable(agencies);
        }
    }

    record DetailReference(DetailKind kind, int id)
    {
    }

    enum DetailKind
    {
        TRUNKED_SYSTEM,
        AGENCY,
        COUNTY
    }

    private static <T> List<T> immutable(List<T> items)
    {
        return items == null ? List.of() : List.copyOf(items);
    }
}
