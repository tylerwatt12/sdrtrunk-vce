/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.service.radioreference;

import io.github.dsheirer.service.radioreference.RadioReferenceDirectoryService.DiscoverySystem;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.RadioNetworkIdentity;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.TrunkedSiteChannel;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.TrunkedSiteDetails;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.TrunkedSystemDetails;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Optional directory enrichment shared by click-spectrum discovery and the trunked-system scanner.
 * Call only from a background discovery worker, never a decoder callback. A frequency hit is a candidate,
 * not identity evidence: an accepted match also requires the decoded system and site identity.
 */
public final class RadioReferenceDiscoveryResolver
{
    private static final String PROVENANCE = "radioreference-exact-frequency-and-on-air-identity";
    private final RadioReferenceDirectoryService mDirectory;

    public RadioReferenceDiscoveryResolver(RadioReferenceDirectoryService directory)
    {
        mDirectory = Objects.requireNonNull(directory);
    }

    public Result resolve(Integer stateId, Identity identity)
    {
        if(identity == null || !identity.stable())
        {
            return Result.manual("identity_required",
                "A stable on-air system and site identity is required for RadioReference matching.");
        }
        if(!mDirectory.status().authenticated())
        {
            return Result.manual("login_required", "Sign in to RadioReference to identify this system.");
        }
        if(!mDirectory.status().premium())
        {
            return Result.manual("premium_required", "A current RadioReference premium subscription is required.");
        }
        if(!identity.p25() && (stateId == null || stateId <= 0))
        {
            return Result.manual("location_required", "Select a RadioReference state to identify this system.");
        }
        try
        {
            return match(identity, identity.p25() ?
                mDirectory.p25DiscoverySystems(identity.system(), identity.frequencyHz(), stateId,
                    system -> protocolMatches(identity, system) && networkMatches(identity, system)) :
                mDirectory.discoverySystems(stateId, identity.frequencyHz(),
                    system -> protocolMatches(identity, system) && networkMatches(identity, system)));
        }
        catch(RadioReferenceDirectoryException exception)
        {
            return switch(exception.code())
            {
                case NOT_AUTHENTICATED, INVALID_CREDENTIALS -> Result.manual("login_required",
                    "Sign in to RadioReference to identify this system.");
                case PREMIUM_REQUIRED -> Result.manual("premium_required",
                    "A current RadioReference premium subscription is required.");
                case INVALID_REQUEST -> Result.manual("location_required",
                    "Select a valid RadioReference state to identify this system.");
                case RESULT_SET_TOO_LARGE -> identity.p25() && stateId == null ?
                    Result.manual("location_required", "Select a RadioReference state to narrow the system search.") :
                    Result.manual("ambiguous", "Too many RadioReference candidates; choose a system manually.");
                default -> Result.manual("unavailable",
                    "RadioReference could not be reached. Review the decoded identity and retry later.");
            };
        }
    }

    /** Pure matcher for deterministic tests and already loaded, bounded directory snapshots. */
    static Result match(Identity identity, List<DiscoverySystem> candidates)
    {
        if(identity == null || !identity.stable())
        {
            return Result.manual("identity_required",
                "A stable on-air system and site identity is required for RadioReference matching.");
        }
        Map<String,Match> matches = new LinkedHashMap<>();
        for(DiscoverySystem candidate: candidates)
        {
            TrunkedSystemDetails system = candidate.system();
            if(system.id() <= 0 || !protocolMatches(identity, system) || !networkMatches(identity, system))
            {
                continue;
            }
            for(TrunkedSiteDetails site: candidate.sites())
            {
                if(site.id() <= 0 || site.systemId() != system.id() || site.number() != identity.site() ||
                    identity.p25() && site.rfss() != identity.rfss() ||
                    identity.ran() != null && site.ran() > 0 && site.ran() != identity.ran())
                {
                    continue;
                }
                List<TrunkedSiteChannel> tunedChannels = site.channels().stream()
                    .filter(channel -> channel.frequencyHz() == identity.frequencyHz()).toList();
                if(tunedChannels.isEmpty() || !colorMatches(identity, tunedChannels))
                {
                    continue;
                }
                List<TrunkedSiteChannel> channels = site.channels().stream().filter(channel -> channel.frequencyHz() > 0 &&
                    channel.frequencyHz() <= 100_000_000_000L)
                    .distinct()
                    .sorted(Comparator.comparingLong(TrunkedSiteChannel::frequencyHz)
                        .thenComparingInt(TrunkedSiteChannel::logicalChannelNumber)
                        .thenComparing(TrunkedSiteChannel::channelId, Comparator.nullsFirst(Comparator.naturalOrder())))
                    .toList();
                matches.putIfAbsent(system.id() + ":" + site.id(), new Match(system.id(), site.id(),
                    text(system.name()), text(site.name()), channels,
                    "https://www.radioreference.com/db/sid/" + system.id(),
                    "https://www.radioreference.com/db/site/" + site.id()));
            }
        }
        if(matches.size() > 1)
        {
            return Result.manual("ambiguous",
                "Multiple RadioReference sites match the decoded identity and frequency; choose manually.");
        }
        if(matches.isEmpty())
        {
            return Result.manual("no_match",
                "No RadioReference site matches the decoded identity and frequency.");
        }
        return new Result("matched", "RadioReference system and site verified against the on-air identity.",
            matches.values().iterator().next(), PROVENANCE);
    }

    private static boolean protocolMatches(Identity identity, TrunkedSystemDetails system)
    {
        String type = normalized(system.type());
        if(identity.p25()) return type.equals("project 25");
        if(!type.equals(identity.protocolId())) return false;
        if(type.equals("dmr"))
        {
            String flavor = normalized(system.flavor());
            if(flavor.contains("capacity plus")) return false;
            if(flavor.contains("connect plus")) return identity.connectPlus();
            if(flavor.contains("tier iii") || flavor.contains("tier 3") || flavor.contains("capacity max"))
                return identity.tierThree();
        }
        if(type.equals("nxdn"))
        {
            String flavor = normalized(system.flavor()).replace('_', ' ').replace('-', ' ');
            if(flavor.contains("type d")) return false;
        }
        return true;
    }

    private static boolean networkMatches(Identity identity, TrunkedSystemDetails system)
    {
        List<RadioNetworkIdentity> networks = system.radioNetworks().isEmpty() ?
            List.of(new RadioNetworkIdentity(system.wacn(), system.systemId())) : system.radioNetworks();
        for(RadioNetworkIdentity network: networks)
        {
            Integer nativeSystem = identity.p25() ? number(network.systemId(), 16) : nativeNumber(network.systemId());
            if(!Objects.equals(nativeSystem, identity.system())) continue;
            String scope = normalized(network.model());
            if(!identity.p25() && !scope.isEmpty() && !scope.equals(identity.scope())) continue;
            if(!identity.p25() || Objects.equals(number(network.wacn(), 16), identity.wacn())) return true;
        }
        return false;
    }

    private static boolean colorMatches(Identity identity, List<TrunkedSiteChannel> channels)
    {
        if(identity.colorCode() == null) return true;
        boolean hasColor = false;
        for(TrunkedSiteChannel channel: channels)
        {
            Integer color = number(channel.colorCode(), 10);
            if(color != null)
            {
                hasColor = true;
                if(color.equals(identity.colorCode())) return true;
            }
        }
        return !hasColor;
    }

    private static Integer number(String raw, int radix)
    {
        String value = text(raw);
        if(value.isEmpty()) return null;
        if(radix == 16 && value.toLowerCase(Locale.ROOT).startsWith("0x")) value = value.substring(2);
        if(!value.matches(radix == 16 ? "[0-9a-fA-F]{1,8}" : "[0-9]{1,9}")) return null;
        try { return Integer.parseInt(value, radix); }
        catch(NumberFormatException exception) { return null; }
    }

    private static Integer nativeNumber(String raw)
    {
        String value = text(raw);
        if(value.toLowerCase(Locale.ROOT).startsWith("0x") || value.matches("[0-9a-fA-F]*[a-fA-F][0-9a-fA-F]*"))
            return number(value, 16);
        Integer decimal = number(value, 10);
        Integer hexadecimal = number(value, 16);
        // The SOAP schema declares sysid as string without a non-P25 radix. Do not guess from an RR display label.
        return Objects.equals(decimal, hexadecimal) ? decimal : null;
    }

    private static String text(String value) { return value == null ? "" : value.strip(); }
    private static String normalized(String value) { return text(value).toLowerCase(Locale.ROOT); }
    private static boolean bounded(Integer value, int maximum) { return value != null && value >= 0 && value <= maximum; }

    /** Native IDs are decoded integers; never pass an RR catalog ID as an on-air system ID. */
    public record Identity(String protocolId, String variant, long frequencyHz, Integer wacn, Integer system,
                           Integer rfss, Integer site, Integer colorCode, Integer ran, String scope)
    {
        public Identity
        {
            protocolId = normalized(protocolId);
            variant = normalized(variant).replace('_', ' ').replace('-', ' ');
            scope = normalized(scope);
        }
        public Identity(String protocolId, String variant, long frequencyHz, Integer wacn, Integer system,
                        Integer rfss, Integer site, Integer colorCode, Integer ran)
        {
            this(protocolId, variant, frequencyHz, wacn, system, rfss, site, colorCode, ran, null);
        }
        private boolean p25() { return protocolId.equals("p25-phase1") || protocolId.equals("p25-phase2"); }
        private boolean connectPlus() { return variant.contains("connect") && variant.contains("plus"); }
        private boolean tierThree() { return variant.equals("tier3") || variant.contains("tier iii") ||
            variant.contains("tier 3") || variant.contains("capacity max"); }
        public boolean stable()
        {
            if(frequencyHz <= 0 || frequencyHz > 100_000_000_000L ||
                colorCode != null && !bounded(colorCode, 15) || ran != null && !bounded(ran, 63)) return false;
            if(p25()) return bounded(wacn, 0xFFFFF) && bounded(system, 0xFFF) &&
                bounded(rfss, 255) && bounded(site, 255);
            if(protocolId.equals("dmr")) return (connectPlus() || tierThree()) &&
                bounded(system, 65535) && bounded(site, 65535);
            // Type-D and Capacity Plus have frequency-scoped identity only; do not invent a global system ID.
            return protocolId.equals("nxdn") && variant.contains("type c") && !variant.contains("type d") &&
                bounded(system, 131071) && bounded(site, 4095);
        }
    }

    public record Match(int rrSystemId, int rrSiteId, String systemName, String siteName,
                        List<TrunkedSiteChannel> channels, String url, String siteUrl)
    {
        public Match { channels = List.copyOf(channels); }
    }

    public record Result(String state, String message, Match match, String provenance)
    {
        public static Result manual(String state, String message) { return new Result(state, message, null, "on-air"); }
        public static Result manual() { return manual("manual", "Review the decoded system and site identity."); }
        public static Result pending() { return manual("pending", "Checking RadioReference system and site identity."); }
        public boolean matched() { return match != null && state.equals("matched"); }
    }
}
