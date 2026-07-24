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

import io.github.dsheirer.rrapi.RadioReferenceException;
import io.github.dsheirer.rrapi.RadioReferenceService;
import io.github.dsheirer.rrapi.response.Fault;
import io.github.dsheirer.rrapi.type.AuthorizationInformation;
import io.github.dsheirer.rrapi.type.CountryInfo;
import io.github.dsheirer.rrapi.type.CountyInfo;
import io.github.dsheirer.rrapi.type.StateInfo;
import io.github.dsheirer.rrapi.type.UserInfo;
import java.util.ArrayList;
import java.util.List;

/**
 * Adapter for the native RadioReference API client.  This adapter performs no caching.
 */
final class RrapiRadioReferenceGateway implements RadioReferenceGateway
{
    private static final String APPLICATION_KEY = "88969092";
    private RadioReferenceService mService;

    RrapiRadioReferenceGateway(String userName, char[] password) throws RadioReferenceGatewayException
    {
        try
        {
            AuthorizationInformation authorization =
                new AuthorizationInformation(APPLICATION_KEY, userName, new String(password));
            mService = new RadioReferenceService(authorization);
        }
        catch(RadioReferenceException exception)
        {
            throw sanitized(exception);
        }
        catch(RuntimeException exception)
        {
            throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.UNAVAILABLE);
        }
    }

    @Override
    public Account account() throws RadioReferenceGatewayException
    {
        try
        {
            UserInfo userInfo = service().getUserInfo();
            return userInfo == null ? null : new Account(userInfo.getUserName(), userInfo.getExpirationDate());
        }
        catch(RadioReferenceException exception)
        {
            throw sanitized(exception);
        }
    }

    @Override
    public List<Country> countries() throws RadioReferenceGatewayException
    {
        try
        {
            List<Country> countries = new ArrayList<>();

            for(io.github.dsheirer.rrapi.type.Country country: service().getCountries())
            {
                countries.add(new Country(country.getCountryId(), country.getName(), country.getCountryCode()));
            }

            return countries;
        }
        catch(RadioReferenceException | RuntimeException exception)
        {
            throw sanitized(exception);
        }
    }

    @Override
    public CountryDirectory country(int countryId) throws RadioReferenceGatewayException
    {
        try
        {
            CountryInfo info = service().getCountryInfo(countryId);

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
        catch(RadioReferenceException | RuntimeException exception)
        {
            throw sanitized(exception);
        }
    }

    @Override
    public StateDirectory state(int stateId) throws RadioReferenceGatewayException
    {
        try
        {
            StateInfo info = service().getStateInfo(stateId);

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
        catch(RadioReferenceException | RuntimeException exception)
        {
            throw sanitized(exception);
        }
    }

    @Override
    public CountyDirectory county(int countyId) throws RadioReferenceGatewayException
    {
        try
        {
            CountyInfo info = service().getCountyInfo(countyId);

            if(info == null)
            {
                throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.UNAVAILABLE);
            }

            return new CountyDirectory(new County(info.getCountyId(), info.getName(), info.getHeader()),
                systems(info.getSystems()), agencies(info.getAgencies()));
        }
        catch(RadioReferenceException | RuntimeException exception)
        {
            throw sanitized(exception);
        }
    }

    private RadioReferenceService service() throws RadioReferenceGatewayException
    {
        RadioReferenceService service = mService;

        if(service == null)
        {
            throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.UNAVAILABLE);
        }

        return service;
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

    private static RadioReferenceGatewayException sanitized(Exception exception)
    {
        if(exception instanceof RadioReferenceException radioReferenceException &&
            radioReferenceException.hasFault())
        {
            Fault fault = radioReferenceException.getFault();

            if(fault != null && "AUTH".equalsIgnoreCase(fault.getFaultCode()))
            {
                return new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.INVALID_CREDENTIALS);
            }
        }

        return new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.UNAVAILABLE);
    }

    @Override
    public void close()
    {
        mService = null;
    }
}
