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
import io.github.dsheirer.rrapi.request.GetAgencyInfo;
import io.github.dsheirer.rrapi.request.GetFlavors;
import io.github.dsheirer.rrapi.request.GetModes;
import io.github.dsheirer.rrapi.request.GetSites;
import io.github.dsheirer.rrapi.request.GetStateInfo;
import io.github.dsheirer.rrapi.request.GetSubCategoryFrequenciesRequest;
import io.github.dsheirer.rrapi.request.GetSystemInformation;
import io.github.dsheirer.rrapi.request.GetTalkgroupCategories;
import io.github.dsheirer.rrapi.request.GetTalkgroups;
import io.github.dsheirer.rrapi.request.GetTypes;
import io.github.dsheirer.rrapi.request.GetUserData;
import io.github.dsheirer.rrapi.request.GetUserFeedBroadcasts;
import io.github.dsheirer.rrapi.request.GetVoices;
import io.github.dsheirer.rrapi.response.GetCountryInfoResponse;
import io.github.dsheirer.rrapi.response.GetCountryListResponse;
import io.github.dsheirer.rrapi.response.GetCountyInfoResponse;
import io.github.dsheirer.rrapi.response.GetAgencyInfoResponse;
import io.github.dsheirer.rrapi.response.GetFlavorsResponse;
import io.github.dsheirer.rrapi.response.GetModesResponse;
import io.github.dsheirer.rrapi.response.GetSitesResponse;
import io.github.dsheirer.rrapi.response.GetStateInfoResponse;
import io.github.dsheirer.rrapi.response.GetSubCategoryFrequenciesResponse;
import io.github.dsheirer.rrapi.response.GetSystemInformationResponse;
import io.github.dsheirer.rrapi.response.GetTalkgroupCategoriesResponse;
import io.github.dsheirer.rrapi.response.GetTalkgroupsResponse;
import io.github.dsheirer.rrapi.response.GetTypesResponse;
import io.github.dsheirer.rrapi.response.GetUserDataResponse;
import io.github.dsheirer.rrapi.response.GetUserFeedBroadcastsResponse;
import io.github.dsheirer.rrapi.response.GetVoicesResponse;
import io.github.dsheirer.rrapi.response.SearchFrequencyResponse;
import io.github.dsheirer.rrapi.type.CountryInfo;
import io.github.dsheirer.rrapi.type.AgencyInfo;
import io.github.dsheirer.rrapi.type.CountyInfo;
import io.github.dsheirer.rrapi.type.StateInfo;
import io.github.dsheirer.rrapi.type.UserInfo;
import io.github.dsheirer.service.radioreference.RadioReferenceSourceCache.Source;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Adapter for the native RadioReference XML models using SDRTrunk's HTTPS-only transport.
 */
final class RrapiRadioReferenceGateway implements RadioReferenceGateway
{
    static final String APPLICATION_KEY = "88969092";
    private volatile SecureRadioReferenceSoapClient mClient;
    private final RadioReferenceSourceCache mSources = new RadioReferenceSourceCache(64, 16L * 1024 * 1024,
        java.time.Duration.ofMinutes(5), RrapiRadioReferenceGateway::sourceWeight, System::nanoTime);

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
    public Map<Integer,String> systemTypes() throws RadioReferenceGatewayException
    {
        return systemTypes(false);
    }

    private Map<Integer,String> systemTypes(boolean refresh) throws RadioReferenceGatewayException
    {
        return mSources.get(Source.SYSTEM_TYPES, 0, refresh, () -> {
            try
            {
                Map<Integer,String> types = new LinkedHashMap<>();
                GetTypesResponse response = client().execute(GetTypes::create, GetTypesResponse.class);
                if(response.getTypes() != null)
                {
                    for(io.github.dsheirer.rrapi.type.Type type: response.getTypes())
                        if(type != null && type.getTypeId() > 0) types.put(type.getTypeId(), text(type.getName()));
                }
                return Map.copyOf(types);
            }
            catch(RuntimeException exception)
            {
                throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.UNAVAILABLE);
            }
        });
    }

    @Override
    public List<UserFeed> userFeeds() throws RadioReferenceGatewayException
    {
        try
        {
            GetUserFeedBroadcastsResponse response =
                client().execute(GetUserFeedBroadcasts::create, GetUserFeedBroadcastsResponse.class);
            List<UserFeed> feeds = new ArrayList<>();

            if(response.getUserFeedBroadcasts() != null)
            {
                for(io.github.dsheirer.rrapi.type.UserFeedBroadcast feed: response.getUserFeedBroadcasts())
                {
                    if(feed != null)
                    {
                        feeds.add(new UserFeed(feed.getFeedId(), text(feed.getDescription()),
                            text(feed.getHostname()), text(feed.getPort()), text(feed.getMount()),
                            secret(feed.getPassword())));
                    }
                }
            }

            return feeds;
        }
        catch(RuntimeException exception)
        {
            throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.UNAVAILABLE);
        }
    }

    @Override
    public List<Country> countries() throws RadioReferenceGatewayException
    {
        return mSources.get(Source.COUNTRIES, 0, false, () -> List.copyOf(loadCountries()));
    }

    private List<Country> loadCountries() throws RadioReferenceGatewayException
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
        return mSources.get(Source.COUNTRY, countryId, false, () -> loadCountry(countryId));
    }

    private CountryDirectory loadCountry(int countryId) throws RadioReferenceGatewayException
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
            if(info.getCountryId() != countryId)
                throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.INVALID_LOCATION);

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
        return mSources.get(Source.STATE, stateId, false, () -> loadState(stateId));
    }

    private StateDirectory loadState(int stateId) throws RadioReferenceGatewayException
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
            if(info.getStateId() != stateId)
                throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.INVALID_LOCATION);

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
        return countySource(countyId).directory();
    }

    @Override
    public List<FrequencyResult> searchStateFrequencies(int stateId, double frequencyMHz)
        throws RadioReferenceGatewayException
    {
        try
        {
            SearchFrequencyResponse response = client().searchStateFrequencies(stateId, frequencyMHz);
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

                    //The dependency exposes the SOAP search result's "sid" field as getStateId(), but the API
                    //contract defines sid as the trunked system ID.  Preserve that structural identity here.
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

    @Override
    public List<TrunkedSystem> p25SystemsBySystemId(int systemId) throws RadioReferenceGatewayException
    {
        try
        {
            return systems(client().p25SystemsBySystemId(systemId).getSystems());
        }
        catch(RuntimeException exception)
        {
            throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.UNAVAILABLE);
        }
    }

    @Override
    public List<Mode> modes() throws RadioReferenceGatewayException
    {
        return mSources.get(Source.MODES, 0, false, () -> List.copyOf(loadModes()));
    }

    private List<Mode> loadModes() throws RadioReferenceGatewayException
    {
        try
        {
            GetModesResponse response = client().execute(GetModes::create, GetModesResponse.class);
            List<Mode> modes = new ArrayList<>();

            if(response.getModes() != null)
            {
                for(io.github.dsheirer.rrapi.type.Mode mode: response.getModes())
                {
                    if(mode != null) modes.add(new Mode(mode.getModeId(), text(mode.getName())));
                }
            }

            return modes;
        }
        catch(RuntimeException exception)
        {
            throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.UNAVAILABLE);
        }
    }

    @Override
    public List<Site> sites(int systemId) throws RadioReferenceGatewayException
    {
        return trunkedSiteDetails(systemId).stream().map(site -> new Site(site.id(), site.systemId(), site.number(),
            site.name(), site.countyId(), site.channels().stream().map(channel -> new SiteChannel(
                channel.frequencyHz() / 1_000_000.0, channel.use(), channel.primaryControl(), channel.alternateControl()))
                .toList())).toList();
    }

    @Override
    public List<FrequencyCategory> agencyFrequencyCategories(int agencyId) throws RadioReferenceGatewayException
    {
        return agencySource(agencyId).categories();
    }

    @Override
    public List<FrequencyCategory> countyFrequencyCategories(int countyId) throws RadioReferenceGatewayException
    {
        return countySource(countyId).categories();
    }

    @Override
    public ConventionalCatalog conventionalCatalog(DetailKind ownerKind, int ownerId)
        throws RadioReferenceGatewayException
    {
        if(ownerKind == DetailKind.COUNTY)
        {
            CountySource county = countySource(ownerId);
            return new ConventionalCatalog(county.directory().county().name(), "", "", county.categories());
        }
        if(ownerKind == DetailKind.AGENCY)
        {
            AgencySource agency = agencySource(ownerId);
            String county = agency.countyId() > 0 ? countySource(agency.countyId()).directory().county().name() : "";
            return new ConventionalCatalog(county, agency.name(), "", agency.categories());
        }
        throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.INVALID_LOCATION);
    }

    private AgencySource agencySource(int agencyId) throws RadioReferenceGatewayException
    {
        return mSources.get(Source.AGENCY, agencyId, false, () -> {
            GetAgencyInfoResponse response = client().execute(
                authorization -> GetAgencyInfo.create(authorization, agencyId), GetAgencyInfoResponse.class);
            AgencyInfo info = response.getAgencyInfo();
            if(info == null || info.getAgencyId() != agencyId)
                throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.INVALID_LOCATION);
            return new AgencySource(info.getName(), info.getCountyId(), List.copyOf(categories(info.getCategories())));
        });
    }

    private CountySource countySource(int countyId) throws RadioReferenceGatewayException
    {
        return mSources.get(Source.COUNTY, countyId, false, () -> {
            GetCountyInfoResponse response = client().execute(
                authorization -> GetCountyInfo.create(authorization, countyId), GetCountyInfoResponse.class);
            CountyInfo info = response.getCountyInfo();
            if(info == null || info.getCountyId() != countyId)
                throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.INVALID_LOCATION);
            return new CountySource(new CountyDirectory(new County(info.getCountyId(), info.getName(), info.getHeader()),
                systems(info.getSystems()), agencies(info.getAgencies())), List.copyOf(categories(info.getCategories())));
        });
    }

    private record AgencySource(String name, int countyId, List<FrequencyCategory> categories) {}
    private record CountySource(CountyDirectory directory, List<FrequencyCategory> categories) {}

    @Override
    public TrunkedSystemDetails trunkedSystemDetails(int systemId) throws RadioReferenceGatewayException
    {
        return trunkedSystemDetails(systemId, false);
    }

    @Override
    public TrunkedSystemDetails trunkedSystemDetails(int systemId, boolean refresh) throws RadioReferenceGatewayException
    {
        return mSources.get(Source.SYSTEM, systemId, refresh, () -> loadTrunkedSystemDetails(systemId, refresh));
    }

    private TrunkedSystemDetails loadTrunkedSystemDetails(int systemId, boolean refresh) throws RadioReferenceGatewayException
    {
        try
        {
            GetSystemInformationResponse response = client().execute(
                authorization -> GetSystemInformation.create(authorization, systemId),
                GetSystemInformationResponse.class, SecureRadioReferenceService.SYSTEM_INFORMATION_REQUEST_TIMEOUT);
            io.github.dsheirer.rrapi.type.SystemInformation info = response.getSystemInformation();

            if(info == null)
                throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.UNAVAILABLE);

            String type = systemTypes(refresh).getOrDefault(info.getTypeId(), "");
            String flavor = mSources.get(Source.FLAVOR, info.getFlavorId(), refresh, () -> firstName(client().execute(
                authorization -> GetFlavors.create(authorization, info.getFlavorId()), GetFlavorsResponse.class)
                .getFlavors()));
            String voice = mSources.get(Source.VOICE, info.getVoiceId(), refresh, () -> firstName(client().execute(
                authorization -> GetVoices.create(authorization, info.getVoiceId()), GetVoicesResponse.class)
                .getVoices()));
            String wacn = "";
            String nativeSystemId = "";
            List<RadioNetworkIdentity> networks = new ArrayList<>();

            if(info.getRadioNetworks() != null)
            {
                for(io.github.dsheirer.rrapi.type.RadioNetwork network: info.getRadioNetworks())
                {
                    if(network != null)
                    {
                        networks.add(new RadioNetworkIdentity(text(network.getWacn()), text(network.getSystemId()),
                            text(network.getModel())));
                    }
                }
                if(!networks.isEmpty())
                {
                    wacn = networks.getFirst().wacn();
                    nativeSystemId = networks.getFirst().systemId();
                }
            }

            return new TrunkedSystemDetails(systemId, text(info.getName()), text(info.getCity()), type, flavor,
                voice, wacn, nativeSystemId, networks);
        }
        catch(RuntimeException exception)
        {
            throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.UNAVAILABLE);
        }
    }

    @Override
    public List<TrunkedSiteDetails> trunkedSiteDetails(int systemId) throws RadioReferenceGatewayException
    {
        return trunkedSiteDetails(systemId, false);
    }

    @Override
    public List<TrunkedSiteDetails> trunkedSiteDetails(int systemId, boolean refresh) throws RadioReferenceGatewayException
    {
        return mSources.get(Source.SITES, systemId, refresh, () -> List.copyOf(loadTrunkedSiteDetails(systemId)));
    }

    private List<TrunkedSiteDetails> loadTrunkedSiteDetails(int systemId) throws RadioReferenceGatewayException
    {
        try
        {
            GetSitesResponse response = client().execute(authorization -> GetSites.create(authorization, systemId),
                GetSitesResponse.class, SecureRadioReferenceService.SITES_REQUEST_TIMEOUT);
            List<TrunkedSiteDetails> sites = new ArrayList<>();

            if(response.getSites() != null)
            {
                for(io.github.dsheirer.rrapi.type.Site site: response.getSites())
                {
                    if(site == null)
                    {
                        continue;
                    }

                    List<TrunkedSiteChannel> channels = new ArrayList<>();

                    if(site.getSiteFrequencies() != null)
                    {
                        for(io.github.dsheirer.rrapi.type.SiteFrequency frequency: site.getSiteFrequencies())
                        {
                            if(frequency != null && Double.isFinite(frequency.getFrequency()) &&
                                frequency.getFrequency() > 0)
                            {
                                channels.add(new TrunkedSiteChannel(
                                    Math.round(frequency.getFrequency() * 1_000_000.0),
                                    frequency.getLogicalChannelNumber(), text(frequency.getChannelId()),
                                    text(frequency.getUse()), text(frequency.getColorCode()),
                                    frequency.isPrimaryControlChannel(), frequency.isAlternateControlChannel()));
                            }
                        }
                    }

                    sites.add(new TrunkedSiteDetails(site.getSiteId(), site.getSystemId(), site.getSiteNumber(),
                        text(site.getDescription()), site.getCountyId(), site.getZoneNumber(), site.getRfss(),
                        text(site.getNac()), site.getRan(), text(site.getModulation()),
                        site.getTdmaControlChannel() != 0, channels));
                }
            }

            return sites;
        }
        catch(RuntimeException exception)
        {
            throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.UNAVAILABLE);
        }
    }

    @Override
    public List<RemoteTalkgroup> talkgroups(int systemId) throws RadioReferenceGatewayException
    {
        try
        {
            GetTalkgroupsResponse response = client().execute(
                authorization -> GetTalkgroups.create(authorization, systemId), GetTalkgroupsResponse.class,
                SecureRadioReferenceService.TALKGROUPS_REQUEST_TIMEOUT);
            List<RemoteTalkgroup> talkgroups = new ArrayList<>();

            if(response.getTalkgroups() != null)
            {
                for(io.github.dsheirer.rrapi.type.Talkgroup talkgroup: response.getTalkgroups())
                {
                    if(talkgroup == null)
                    {
                        continue;
                    }

                    List<String> tags = new ArrayList<>();

                    if(talkgroup.getTags() != null)
                    {
                        for(io.github.dsheirer.rrapi.type.Tag tag: talkgroup.getTags())
                        {
                            if(tag != null && tag.getDescription() != null)
                            {
                                tags.add(tag.getDescription());
                            }
                        }
                    }

                    talkgroups.add(new RemoteTalkgroup(talkgroup.getTalkgroupId(), talkgroup.getDecimalValue(),
                        text(talkgroup.getAlphaTag()), text(talkgroup.getDescription()), text(talkgroup.getMode()),
                        talkgroup.getEncryptionState(), talkgroup.getTalkgroupCategoryId(), tags));
                }
            }

            return talkgroups;
        }
        catch(RuntimeException exception)
        {
            throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.UNAVAILABLE);
        }
    }

    @Override
    public List<RemoteTalkgroupCategory> talkgroupCategories(int systemId)
        throws RadioReferenceGatewayException
    {
        try
        {
            GetTalkgroupCategoriesResponse response = client().execute(
                authorization -> GetTalkgroupCategories.create(authorization, systemId),
                GetTalkgroupCategoriesResponse.class,
                SecureRadioReferenceService.TALKGROUP_CATEGORIES_REQUEST_TIMEOUT);
            List<RemoteTalkgroupCategory> categories = new ArrayList<>();

            if(response.getTalkgroupCategories() != null)
            {
                for(io.github.dsheirer.rrapi.type.TalkgroupCategory category: response.getTalkgroupCategories())
                {
                    if(category != null)
                    {
                        categories.add(new RemoteTalkgroupCategory(category.getTalkgroupCategoryId(),
                            category.getSystemId(), text(category.getName())));
                    }
                }
            }

            return categories;
        }
        catch(RuntimeException exception)
        {
            throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.UNAVAILABLE);
        }
    }

    @Override
    public List<ConventionalFrequency> subcategoryFrequencies(int subCategoryId) throws RadioReferenceGatewayException
    {
        return mSources.get(Source.SUBCATEGORY, subCategoryId, false, () -> List.copyOf(loadSubcategoryFrequencies(subCategoryId)));
    }

    private List<ConventionalFrequency> loadSubcategoryFrequencies(int subCategoryId)
        throws RadioReferenceGatewayException
    {
        try
        {
            GetSubCategoryFrequenciesResponse response = client().execute(
                authorization -> GetSubCategoryFrequenciesRequest.create(authorization, subCategoryId),
                GetSubCategoryFrequenciesResponse.class);
            Map<Integer,String> modes = new LinkedHashMap<>();
            for(Mode mode: modes()) modes.put(mode.id(), mode.name());

            List<ConventionalFrequency> frequencies = new ArrayList<>();

            if(response.getFrequencies() != null)
            {
                for(io.github.dsheirer.rrapi.type.Frequency frequency: response.getFrequencies())
                {
                    if(frequency == null || !Double.isFinite(frequency.getDownlink()) ||
                        frequency.getDownlink() <= 0)
                    {
                        continue;
                    }

                    List<String> tags = new ArrayList<>();

                    if(frequency.getTags() != null)
                    {
                        frequency.getTags().stream().filter(Objects::nonNull)
                            .map(io.github.dsheirer.rrapi.type.Tag::getDescription).filter(Objects::nonNull)
                            .forEach(tags::add);
                    }

                    Long uplink = Double.isFinite(frequency.getUplink()) && frequency.getUplink() > 0 ?
                        Math.round(frequency.getUplink() * 1_000_000.0) : null;
                    frequencies.add(new ConventionalFrequency(frequency.getFrequencyId(),
                        Math.round(frequency.getDownlink() * 1_000_000.0), uplink, text(frequency.getCallsign()),
                        text(frequency.getDescription()), text(frequency.getAlphaTag()), text(frequency.getTone()),
                        text(frequency.getColorCode()), text(frequency.getTalkgroup()), text(frequency.getSlot()),
                        modeName(frequency.getMode(), modes), frequency.getEncryption(),
                        text(frequency.getClassification()), tags, frequency.getSubCategoryId()));
                }
            }

            return frequencies;
        }
        catch(RuntimeException exception)
        {
            throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.UNAVAILABLE);
        }
    }

    private static long sourceWeight(Object value)
    {
        if(value == null) return 8;
        if(value instanceof String text) return 32L + 2L * text.length();
        if(value instanceof Number || value instanceof Boolean || value instanceof Enum<?>) return 16;
        if(value instanceof AgencySource agency) return 32 + sourceWeight(agency.name()) + sourceWeight(agency.categories());
        if(value instanceof CountySource county) return 32 + sourceWeight(county.directory()) + sourceWeight(county.categories());
        if(value instanceof java.util.Collection<?> items)
            return 32 + items.stream().mapToLong(item -> 8 + sourceWeight(item)).sum();
        if(value instanceof Map<?,?> items)
            return 32 + items.entrySet().stream().mapToLong(item -> 32 + sourceWeight(item.getKey()) + sourceWeight(item.getValue())).sum();
        if(value instanceof Record)
        {
            long bytes = 32;
            try
            {
                for(var field: value.getClass().getRecordComponents()) bytes += sourceWeight(field.getAccessor().invoke(value));
                return bytes;
            }
            catch(ReflectiveOperationException exception) { throw new IllegalArgumentException("Unsupported RadioReference source projection"); }
        }
        throw new IllegalArgumentException("Unsupported RadioReference source projection");
    }

    private static String firstName(List<?> values)
    {
        if(values == null || values.isEmpty() || values.getFirst() == null)
        {
            return "";
        }

        Object value = values.getFirst();

        if(value instanceof io.github.dsheirer.rrapi.type.Type type)
        {
            return text(type.getName());
        }
        else if(value instanceof io.github.dsheirer.rrapi.type.Flavor flavor)
        {
            return text(flavor.getName());
        }
        else if(value instanceof io.github.dsheirer.rrapi.type.Voice voice)
        {
            return text(voice.getName());
        }

        return "";
    }

    private static String modeName(String raw, Map<Integer,String> modes)
    {
        String value = text(raw);

        try
        {
            return modes.getOrDefault(Integer.parseInt(value), value);
        }
        catch(NumberFormatException exception)
        {
            return value;
        }
    }

    private static String text(String value)
    {
        return value == null ? "" : value.strip();
    }

    private static String secret(String value)
    {
        return value == null ? "" : value;
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

    private static List<FrequencyCategory> categories(List<io.github.dsheirer.rrapi.type.Category> source)
    {
        List<FrequencyCategory> categories = new ArrayList<>();

        if(source != null)
        {
            for(io.github.dsheirer.rrapi.type.Category category: source)
            {
                if(category != null && category.getSubCategories() != null)
                {
                    for(io.github.dsheirer.rrapi.type.SubCategory subCategory: category.getSubCategories())
                    {
                        if(subCategory != null)
                        {
                            categories.add(new FrequencyCategory(subCategory.getSubCategoryId(), category.getName(),
                                subCategory.getName()));
                        }
                    }
                }
            }
        }

        return categories;
    }

    private static List<TrunkedSystem> systems(List<io.github.dsheirer.rrapi.type.System> source)
    {
        List<TrunkedSystem> systems = new ArrayList<>();

        if(source != null)
        {
            for(io.github.dsheirer.rrapi.type.System system: source)
            {
                systems.add(new TrunkedSystem(system.getSystemId(), system.getName(), system.getCity(),
                    system.getTypeId(), system.getFlavorId(), system.getVoiceId(),
                    system.getLastUpdated() == null ? 0 : system.getLastUpdated().getTime()));
            }
        }

        return systems;
    }

    @Override
    public void close()
    {
        SecureRadioReferenceSoapClient client = mClient;
        mClient = null;
        mSources.close();

        if(client != null)
        {
            client.close();
        }
    }
}
