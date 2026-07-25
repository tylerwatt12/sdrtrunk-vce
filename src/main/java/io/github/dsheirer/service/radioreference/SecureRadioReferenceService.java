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
import io.github.dsheirer.rrapi.request.GetAgencyInfo;
import io.github.dsheirer.rrapi.request.GetCountryInfo;
import io.github.dsheirer.rrapi.request.GetCountryList;
import io.github.dsheirer.rrapi.request.GetCountyInfo;
import io.github.dsheirer.rrapi.request.GetFlavors;
import io.github.dsheirer.rrapi.request.GetModes;
import io.github.dsheirer.rrapi.request.GetSites;
import io.github.dsheirer.rrapi.request.GetStateInfo;
import io.github.dsheirer.rrapi.request.GetSubCategoryFrequenciesRequest;
import io.github.dsheirer.rrapi.request.GetSystemInformation;
import io.github.dsheirer.rrapi.request.GetTags;
import io.github.dsheirer.rrapi.request.GetTalkgroupCategories;
import io.github.dsheirer.rrapi.request.GetTalkgroups;
import io.github.dsheirer.rrapi.request.GetTypes;
import io.github.dsheirer.rrapi.request.GetUserData;
import io.github.dsheirer.rrapi.request.GetUserFeedBroadcasts;
import io.github.dsheirer.rrapi.request.GetVoices;
import io.github.dsheirer.rrapi.request.RequestEnvelope;
import io.github.dsheirer.rrapi.response.Fault;
import io.github.dsheirer.rrapi.response.GetAgencyInfoResponse;
import io.github.dsheirer.rrapi.response.GetCountryInfoResponse;
import io.github.dsheirer.rrapi.response.GetCountryListResponse;
import io.github.dsheirer.rrapi.response.GetCountyInfoResponse;
import io.github.dsheirer.rrapi.response.GetFlavorsResponse;
import io.github.dsheirer.rrapi.response.GetModesResponse;
import io.github.dsheirer.rrapi.response.GetSitesResponse;
import io.github.dsheirer.rrapi.response.GetStateInfoResponse;
import io.github.dsheirer.rrapi.response.GetSubCategoryFrequenciesResponse;
import io.github.dsheirer.rrapi.response.GetSystemInformationResponse;
import io.github.dsheirer.rrapi.response.GetTagsResponse;
import io.github.dsheirer.rrapi.response.GetTalkgroupCategoriesResponse;
import io.github.dsheirer.rrapi.response.GetTalkgroupsResponse;
import io.github.dsheirer.rrapi.response.GetTypesResponse;
import io.github.dsheirer.rrapi.response.GetUserDataResponse;
import io.github.dsheirer.rrapi.response.GetUserFeedBroadcastsResponse;
import io.github.dsheirer.rrapi.response.GetVoicesResponse;
import io.github.dsheirer.rrapi.response.ResponseBody;
import io.github.dsheirer.rrapi.type.Agency;
import io.github.dsheirer.rrapi.type.AgencyInfo;
import io.github.dsheirer.rrapi.type.AuthorizationInformation;
import io.github.dsheirer.rrapi.type.Country;
import io.github.dsheirer.rrapi.type.CountryInfo;
import io.github.dsheirer.rrapi.type.CountyInfo;
import io.github.dsheirer.rrapi.type.Flavor;
import io.github.dsheirer.rrapi.type.Frequency;
import io.github.dsheirer.rrapi.type.Mode;
import io.github.dsheirer.rrapi.type.Site;
import io.github.dsheirer.rrapi.type.StateInfo;
import io.github.dsheirer.rrapi.type.SystemInformation;
import io.github.dsheirer.rrapi.type.Tag;
import io.github.dsheirer.rrapi.type.Talkgroup;
import io.github.dsheirer.rrapi.type.TalkgroupCategory;
import io.github.dsheirer.rrapi.type.TalkgroupRequestFilter;
import io.github.dsheirer.rrapi.type.Type;
import io.github.dsheirer.rrapi.type.UserFeedBroadcast;
import io.github.dsheirer.rrapi.type.UserInfo;
import io.github.dsheirer.rrapi.type.Voice;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * HTTPS-only compatibility service for the RadioReference views that still consume the native API models.
 *
 * <p>The radio-reference-api dependency remains useful for its request, response and data models, but its service
 * class has a compiled plaintext endpoint.  Keeping transport in this application-owned service prevents both the
 * desktop importer and new web workflows from using that endpoint.</p>
 */
public class SecureRadioReferenceService implements AutoCloseable
{
    /*
     * System detail responses vary substantially in size. These endpoint budgets remain finite, but allow large
     * statewide systems to complete without weakening the short default used by account and directory requests.
     */
    static final Duration SYSTEM_INFORMATION_REQUEST_TIMEOUT = Duration.ofSeconds(20);
    static final Duration SITES_REQUEST_TIMEOUT = Duration.ofSeconds(45);
    static final Duration TALKGROUPS_REQUEST_TIMEOUT = Duration.ofSeconds(60);
    static final Duration TALKGROUP_CATEGORIES_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private SecureRadioReferenceSoapClient mClient;
    private SecureRadioReferenceSoapClient mRetiredClient;
    private int mActiveRequests;
    private Map<Integer,Flavor> mFlavors;
    private Map<Integer,Mode> mModes;
    private Map<Integer,Tag> mTags;
    private Map<Integer,Type> mTypes;
    private Map<Integer,Voice> mVoices;

    public SecureRadioReferenceService(AuthorizationInformation authorizationInformation)
        throws RadioReferenceException
    {
        if(authorizationInformation == null || authorizationInformation.getUserName() == null ||
            authorizationInformation.getPassword() == null)
        {
            throw new RadioReferenceException("Authorization information cannot be null");
        }

        char[] password = authorizationInformation.getPassword().toCharArray();

        try
        {
            mClient = SecureRadioReferenceSoapClient.production(authorizationInformation.getUserName(), password);
        }
        catch(RadioReferenceGatewayException exception)
        {
            throw legacy(exception);
        }
        finally
        {
            Arrays.fill(password, '\0');
        }
    }

    SecureRadioReferenceService(SecureRadioReferenceSoapClient client)
    {
        mClient = Objects.requireNonNull(client);
    }

    public UserInfo getUserInfo() throws RadioReferenceException
    {
        return execute(GetUserData::create, GetUserDataResponse.class).getUserInfo();
    }

    public List<UserFeedBroadcast> getUserFeeds() throws RadioReferenceException
    {
        return immutable(execute(GetUserFeedBroadcasts::create,
            GetUserFeedBroadcastsResponse.class).getUserFeedBroadcasts());
    }

    public Country getCountry(int countryId) throws RadioReferenceException
    {
        return getCountries().stream()
            .filter(country -> country.getCountryId() == countryId)
            .findFirst()
            .orElseThrow(() -> new RadioReferenceException("RadioReference country is unavailable"));
    }

    public List<Country> getCountries() throws RadioReferenceException
    {
        return immutable(execute(GetCountryList::create, GetCountryListResponse.class).getCountries());
    }

    public CountryInfo getCountryInfo(Country country) throws RadioReferenceException
    {
        if(country == null)
        {
            throw new RadioReferenceException("RadioReference country is unavailable");
        }

        return getCountryInfo(country.getCountryId());
    }

    public CountryInfo getCountryInfo(int countryId) throws RadioReferenceException
    {
        return execute(authorization -> GetCountryInfo.create(authorization, countryId),
            GetCountryInfoResponse.class).getCountryInfo();
    }

    public AgencyInfo getAgencyInfo(Agency agency) throws RadioReferenceException
    {
        if(agency == null)
        {
            throw new RadioReferenceException("RadioReference agency is unavailable");
        }

        return getAgencyInfo(agency.getAgencyId());
    }

    public AgencyInfo getAgencyInfo(int agencyId) throws RadioReferenceException
    {
        return execute(authorization -> GetAgencyInfo.create(authorization, agencyId),
            GetAgencyInfoResponse.class).getAgencyInfo();
    }

    public StateInfo getStateInfo(int stateId) throws RadioReferenceException
    {
        return execute(authorization -> GetStateInfo.create(authorization, stateId),
            GetStateInfoResponse.class).getStateInfo();
    }

    public CountyInfo getCountyInfo(int countyId) throws RadioReferenceException
    {
        return execute(authorization -> GetCountyInfo.create(authorization, countyId),
            GetCountyInfoResponse.class).getCountyInfo();
    }

    public SystemInformation getSystemInformation(int systemId) throws RadioReferenceException
    {
        return execute(authorization -> GetSystemInformation.create(authorization, systemId),
            GetSystemInformationResponse.class, SYSTEM_INFORMATION_REQUEST_TIMEOUT).getSystemInformation();
    }

    public List<Site> getSites(int systemId) throws RadioReferenceException
    {
        return immutable(execute(authorization -> GetSites.create(authorization, systemId),
            GetSitesResponse.class, SITES_REQUEST_TIMEOUT).getSites());
    }

    public List<Talkgroup> getTalkgroups(int systemId) throws RadioReferenceException
    {
        return immutable(execute(authorization -> GetTalkgroups.create(authorization, systemId),
            GetTalkgroupsResponse.class, TALKGROUPS_REQUEST_TIMEOUT).getTalkgroups());
    }

    public List<Talkgroup> getTalkgroups(TalkgroupRequestFilter filter) throws RadioReferenceException
    {
        if(filter == null)
        {
            throw new RadioReferenceException("RadioReference talkgroup filter is unavailable");
        }

        return immutable(execute(authorization -> GetTalkgroups.create(authorization, filter),
            GetTalkgroupsResponse.class, TALKGROUPS_REQUEST_TIMEOUT).getTalkgroups());
    }

    public List<TalkgroupCategory> getTalkgroupCategories(int systemId) throws RadioReferenceException
    {
        return immutable(execute(authorization -> GetTalkgroupCategories.create(authorization, systemId),
            GetTalkgroupCategoriesResponse.class, TALKGROUP_CATEGORIES_REQUEST_TIMEOUT).getTalkgroupCategories());
    }

    public synchronized Map<Integer,Flavor> getFlavorsMap() throws RadioReferenceException
    {
        if(mFlavors == null)
        {
            mFlavors = index(execute(GetFlavors::create, GetFlavorsResponse.class).getFlavors(),
                Flavor::getFlavorId);
        }

        return mFlavors;
    }

    public Flavor getFlavor(int flavorId) throws RadioReferenceException
    {
        return getFlavorsMap().get(flavorId);
    }

    public synchronized Map<Integer,Mode> getModesMap() throws RadioReferenceException
    {
        if(mModes == null)
        {
            mModes = index(execute(GetModes::create, GetModesResponse.class).getModes(), Mode::getModeId);
        }

        return mModes;
    }

    public Mode getMode(int modeId) throws RadioReferenceException
    {
        return getModesMap().get(modeId);
    }

    public synchronized Map<Integer,Tag> getTagsMap() throws RadioReferenceException
    {
        if(mTags == null)
        {
            mTags = index(execute(GetTags::create, GetTagsResponse.class).getTags(), Tag::getTagId);
        }

        return mTags;
    }

    public synchronized void clearTagMap()
    {
        mTags = null;
    }

    public Tag getTag(int tagId) throws RadioReferenceException
    {
        return getTagsMap().get(tagId);
    }

    public synchronized Map<Integer,Type> getTypesMap() throws RadioReferenceException
    {
        if(mTypes == null)
        {
            mTypes = index(execute(GetTypes::create, GetTypesResponse.class).getTypes(), Type::getTypeId);
        }

        return mTypes;
    }

    public Type getType(int typeId) throws RadioReferenceException
    {
        return getTypesMap().get(typeId);
    }

    public synchronized Map<Integer,Voice> getVoicesMap() throws RadioReferenceException
    {
        if(mVoices == null)
        {
            mVoices = index(execute(GetVoices::create, GetVoicesResponse.class).getVoices(), Voice::getVoiceId);
        }

        return mVoices;
    }

    public Voice getVoice(int voiceId) throws RadioReferenceException
    {
        return getVoicesMap().get(voiceId);
    }

    public List<Frequency> getSubCategoryFrequencies(int subCategoryId) throws RadioReferenceException
    {
        return immutable(execute(authorization ->
                GetSubCategoryFrequenciesRequest.create(authorization, subCategoryId),
            GetSubCategoryFrequenciesResponse.class).getFrequencies());
    }

    private <T extends ResponseBody> T execute(Function<AuthorizationInformation,RequestEnvelope> requestFactory,
                                               Class<T> responseType) throws RadioReferenceException
    {
        return execute(requestFactory, responseType, null);
    }

    private <T extends ResponseBody> T execute(Function<AuthorizationInformation,RequestEnvelope> requestFactory,
                                               Class<T> responseType, Duration requestTimeout)
        throws RadioReferenceException
    {
        SecureRadioReferenceSoapClient client;

        synchronized(this)
        {
            client = mClient;

            if(client == null)
            {
                throw new RadioReferenceException("RadioReference service is closed");
            }

            mActiveRequests++;
        }

        try
        {
            return requestTimeout == null ? client.execute(requestFactory, responseType) :
                client.execute(requestFactory, responseType, requestTimeout);
        }
        catch(RadioReferenceGatewayException exception)
        {
            throw legacy(exception);
        }
        finally
        {
            releaseRequest();
        }
    }

    private static RadioReferenceException legacy(RadioReferenceGatewayException exception)
    {
        if(exception.kind() == RadioReferenceGatewayException.Kind.INVALID_CREDENTIALS)
        {
            Fault fault = new Fault();
            fault.setFaultCode("AUTH");
            return new RadioReferenceException("RadioReference rejected the credentials", 401, fault);
        }

        return new RadioReferenceException(exception.getMessage(), exception);
    }

    private static <T> List<T> immutable(List<T> items)
    {
        return items == null ? List.of() : List.copyOf(items);
    }

    private static <T> Map<Integer,T> index(List<T> items, ToIntFunction<T> key)
    {
        Map<Integer,T> indexed = new LinkedHashMap<>();

        if(items != null)
        {
            for(T item: items)
            {
                if(item != null)
                {
                    indexed.put(key.applyAsInt(item), item);
                }
            }
        }

        return Collections.unmodifiableMap(indexed);
    }

    private void releaseRequest()
    {
        SecureRadioReferenceSoapClient retiredClient = null;

        synchronized(this)
        {
            mActiveRequests--;

            if(mActiveRequests == 0 && mRetiredClient != null)
            {
                retiredClient = mRetiredClient;
                mRetiredClient = null;
            }
        }

        if(retiredClient != null)
        {
            retiredClient.close();
        }
    }

    @Override
    public void close()
    {
        SecureRadioReferenceSoapClient clientToClose = null;

        synchronized(this)
        {
            SecureRadioReferenceSoapClient client = mClient;
            mClient = null;
            mFlavors = null;
            mModes = null;
            mTags = null;
            mTypes = null;
            mVoices = null;

            if(client != null)
            {
                if(mActiveRequests == 0)
                {
                    clientToClose = client;
                }
                else
                {
                    mRetiredClient = client;
                }
            }
        }

        if(clientToClose != null)
        {
            clientToClose.close();
        }
    }
}
