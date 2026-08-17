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

import io.github.dsheirer.rrapi.request.GetCountryInfo;
import io.github.dsheirer.rrapi.request.GetCountryList;
import io.github.dsheirer.rrapi.request.GetCountyInfo;
import io.github.dsheirer.rrapi.request.GetStateInfo;
import io.github.dsheirer.rrapi.request.GetUserData;
import io.github.dsheirer.rrapi.request.SearchStateFrequency;
import io.github.dsheirer.rrapi.response.GetCountryInfoResponse;
import io.github.dsheirer.rrapi.response.GetCountryListResponse;
import io.github.dsheirer.rrapi.response.GetCountyInfoResponse;
import io.github.dsheirer.rrapi.response.GetStateInfoResponse;
import io.github.dsheirer.rrapi.response.GetUserDataResponse;
import io.github.dsheirer.rrapi.response.SearchFrequencyResponse;
import io.github.dsheirer.rrapi.type.CountryInfo;
import io.github.dsheirer.rrapi.type.CountyInfo;
import io.github.dsheirer.rrapi.type.StateInfo;
import io.github.dsheirer.rrapi.type.UserInfo;
import java.util.ArrayList;
import java.util.List;

/**
 * Adapter for the native RadioReference XML models using SDRTrunk's HTTPS-only transport.
 */
final class RrapiRadioReferenceGateway implements RadioReferenceGateway
{
    static final String APPLICATION_KEY = "88969092";
    private SecureRadioReferenceSoapClient mClient;

    RrapiRadioReferenceGateway(String userName, char[] password) throws RadioReferenceGatewayException
    {
        mClient = SecureRadioReferenceSoapClient.production(userName, password);
    }

    RrapiRadioReferenceGateway(SecureRadioReferenceSoapClient client)
    {
        mClient = client;
    }

    @Override
    public Account account() throws RadioReferenceGatewayException
    {
        try
        {
            GetUserDataResponse response = client().execute(GetUserData::create, GetUserDataResponse.class);
            UserInfo userInfo = response.getUserInfo();
            return userInfo == null ? null : new Account(userInfo.getUserName(), userInfo.getExpirationDate());
        }
        catch(RuntimeException exception)
        {
            throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.UNAVAILABLE);
        }
    }

    @Override
    public List<Country> countries() throws RadioReferenceGatewayException
    {
        try
        {
            List<Country> countries = new ArrayList<>();
            GetCountryListResponse response =
                client().execute(GetCountryList::create, GetCountryListResponse.class);

            if(response.getCountries() != null)
            {
                for(io.github.dsheirer.rrapi.type.Country country: response.getCountries())
                {
                    countries.add(new Country(country.getCountryId(), country.getName(), country.getCountryCode()));
                }
            }

            return countries;
        }
        catch(RuntimeException exception)
        {
            throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.UNAVAILABLE);
        }
    }

    @Override
    public CountryDirectory country(int countryId) throws RadioReferenceGatewayException
    {
        try
        {
            GetCountryInfoResponse response =
                client().execute(authorization -> GetCountryInfo.create(authorization, countryId),
                    GetCountryInfoResponse.class);
            CountryInfo info = response.getCountryInfo();

            if(info == null)
            {
                throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.UNAVAILABLE);
            }

            List<State> states = new ArrayList<>();

            if(info.getStates() != null)
            {
                for(io.github.dsheirer.rrapi.type.State state: info.getStates())
                {
                    states.add(new State(state.getStateId(), state.getName(), state.getStateCode()));
                }
            }

            return new CountryDirectory(new Country(info.getCountryId(), info.getName(), info.getCountryCode()),
                states, agencies(info.getAgencies()));
        }
        catch(RuntimeException exception)
        {
            throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.UNAVAILABLE);
        }
    }

    @Override
    public StateDirectory state(int stateId) throws RadioReferenceGatewayException
    {
        try
        {
            GetStateInfoResponse response =
                client().execute(authorization -> GetStateInfo.create(authorization, stateId),
                    GetStateInfoResponse.class);
            StateInfo info = response.getStateInfo();

            if(info == null)
            {
                throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.UNAVAILABLE);
            }

            List<County> counties = new ArrayList<>();

            if(info.getCounties() != null)
            {
                for(io.github.dsheirer.rrapi.type.County county: info.getCounties())
                {
                    counties.add(new County(county.getCountyId(), county.getName(), county.getCountyHeader()));
                }
            }

            return new StateDirectory(new State(info.getStateId(), info.getName(), info.getStateEntityType()),
                counties, systems(info.getSystems()), agencies(info.getAgencies()));
        }
        catch(RuntimeException exception)
        {
            throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.UNAVAILABLE);
        }
    }

    @Override
    public CountyDirectory county(int countyId) throws RadioReferenceGatewayException
    {
        try
        {
            GetCountyInfoResponse response =
                client().execute(authorization -> GetCountyInfo.create(authorization, countyId),
                    GetCountyInfoResponse.class);
            CountyInfo info = response.getCountyInfo();

            if(info == null)
            {
                throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.UNAVAILABLE);
            }

            return new CountyDirectory(new County(info.getCountyId(), info.getName(), info.getHeader()),
                systems(info.getSystems()), agencies(info.getAgencies()));
        }
        catch(RuntimeException exception)
        {
            throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.UNAVAILABLE);
        }
    }

    @Override
    public List<FrequencyResult> searchStateFrequencies(int stateId, double frequencyMHz)
        throws RadioReferenceGatewayException
    {
        try
        {
            SearchFrequencyResponse response = client().execute(
                authorization -> SearchStateFrequency.create(authorization, stateId, frequencyMHz),
                SearchFrequencyResponse.class);
            List<FrequencyResult> results = new ArrayList<>();

            if(response.getResults() != null)
            {
                for(io.github.dsheirer.rrapi.type.SearchFrequencyResult result: response.getResults())
                {
                    List<String> tags = new ArrayList<>();

                    if(result.getTags() != null)
                    {
                        result.getTags().stream().filter(java.util.Objects::nonNull)
                            .map(io.github.dsheirer.rrapi.type.Tag::getDescription)
                            .filter(java.util.Objects::nonNull).forEach(tags::add);
                    }

                    results.add(new FrequencyResult(result.getDownlink(), result.getUplink(), result.getCallsign(),
                        result.getDescription(), result.getAlpha(), result.getTone(), result.getColorCode(),
                        result.getTalkgroup(), result.getSlot(), result.getMode(), result.getClassification(), tags,
                        result.getSubCategoryId(), result.getStateId(), result.getAgencyId(), result.getCountyId()));
                }
            }

            return results;
        }
        catch(RuntimeException exception)
        {
            throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.UNAVAILABLE);
        }
    }

    private SecureRadioReferenceSoapClient client() throws RadioReferenceGatewayException
    {
        SecureRadioReferenceSoapClient client = mClient;

        if(client == null)
        {
            throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.UNAVAILABLE);
        }

        return client;
    }

    private static List<Agency> agencies(List<io.github.dsheirer.rrapi.type.Agency> source)
    {
        List<Agency> agencies = new ArrayList<>();

        if(source != null)
        {
            for(io.github.dsheirer.rrapi.type.Agency agency: source)
            {
                agencies.add(new Agency(agency.getAgencyId(), agency.getName(), agency.getType()));
            }
        }

        return agencies;
    }

    private static List<TrunkedSystem> systems(List<io.github.dsheirer.rrapi.type.System> source)
    {
        List<TrunkedSystem> systems = new ArrayList<>();

        if(source != null)
        {
            for(io.github.dsheirer.rrapi.type.System system: source)
            {
                systems.add(new TrunkedSystem(system.getSystemId(), system.getName(), system.getCity(),
                    system.getTypeId(), system.getFlavorId(), system.getVoiceId()));
            }
        }

        return systems;
    }

    @Override
    public void close()
    {
        SecureRadioReferenceSoapClient client = mClient;
        mClient = null;

        if(client != null)
        {
            client.close();
        }
    }
}
