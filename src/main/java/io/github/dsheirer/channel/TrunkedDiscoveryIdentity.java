/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.channel;

import io.github.dsheirer.module.decode.traffic.RadioSystemKey;
import io.github.dsheirer.stats.TrunkedDiscoveryEvidence;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Native serving-site identity confirmed by discovery, owned by the saved channel. */
public record TrunkedDiscoveryIdentity(String protocolId, String variant, TrunkedDiscoveryEvidence.Identity identity)
{
    public TrunkedDiscoveryIdentity
    {
        if(protocolId == null || variant == null || identity == null || identity.p25() != null ||
            identity.site() == null || identity.site() < 0 || identity.siteKey() == null)
            throw invalid();
        if(identity.colorCode() != null && (identity.colorCode() < 0 || identity.colorCode() > 15) ||
            identity.ran() != null && (identity.ran() < 0 || identity.ran() > 63)) throw invalid();
        boolean nativeSystem;
        String nativeKey;
        if("dmr".equals(protocolId))
        {
            nativeSystem = Set.of("TIER_III", "CAPACITY_MAX", "HYTERA_TIER_III").contains(variant);
            if(!nativeSystem && !Set.of("CAPACITY_PLUS", "CONNECT_PLUS").contains(variant)) throw invalid();
            nativeKey = RadioSystemKey.dmrTier3(identity.model(), identity.network());
            if(nativeSystem && !Objects.equals(identity.network(), identity.system())) throw invalid();
        }
        else if("nxdn".equals(protocolId))
        {
            nativeSystem = Set.of("TYPE_C", "TYPE_C_4800", "TYPE_C_9600").contains(variant);
            if(!nativeSystem && !"TYPE_D".equals(variant)) throw invalid();
            nativeKey = RadioSystemKey.nxdnTypeC(identity.category(), identity.system());
        }
        else throw invalid();
        int maximumSite;
        if("dmr".equals(protocolId))
            maximumSite = nativeSystem ? switch(identity.model() != null ? identity.model().toLowerCase(Locale.ROOT) : "")
            {
                case "tiny" -> 7; case "small" -> 31; case "large" -> 255; case "huge" -> 1023; default -> -1;
            } : "CAPACITY_PLUS".equals(variant) ? 31 : 255;
        else
            maximumSite = nativeSystem ? switch(identity.category() != null ? identity.category().toLowerCase(Locale.ROOT) : "")
            {
                case "global" -> 4095; case "regional" -> 255; case "local" -> 31; default -> -1;
            } : 31;
        if(identity.site() > maximumSite) throw invalid();
        if(nativeSystem)
        {
            if(nativeKey == null || !nativeKey.equals(identity.radioSystemKey()) ||
                !(nativeKey + ":site:" + identity.site()).equals(identity.siteKey())) throw invalid();
        }
        else
        {
            String scope = protocolId + ':' + variant.toLowerCase(Locale.ROOT).replace('_', '-') + ":frequency:";
            String key = identity.siteKey().toLowerCase(Locale.ROOT).replace('_', '-');
            String suffix = ":site:" + identity.site();
            if(identity.radioSystemKey() != null || !key.startsWith(scope) || !key.endsWith(suffix) ||
                !identity.siteKey().contains(":frequency:") || !identity.siteKey().endsWith(suffix)) throw invalid();
            try
            {
                if(Long.parseLong(key.substring(scope.length(), key.length() - suffix.length())) <= 0) throw invalid();
            }
            catch(IndexOutOfBoundsException | NumberFormatException malformed) { throw invalid(); }
        }
    }

    private static IllegalArgumentException invalid()
    {
        return new IllegalArgumentException("A consistent DMR or NXDN serving-site identity is required");
    }

    public static TrunkedDiscoveryIdentity from(TrunkedDiscoveryEvidence evidence)
    {
        if(evidence == null || !evidence.verified())
            throw new IllegalArgumentException("A confirmed trunked serving-site identity is required");
        return new TrunkedDiscoveryIdentity(evidence.protocolId(), evidence.variant(), evidence.identity());
    }

    /** Reject a valid identity attached to a different decoder, conventional source, or local carrier. */
    public void validateFor(io.github.dsheirer.controller.channel.Channel channel)
    {
        if(io.github.dsheirer.configuration.ChannelConfigurationPolicy.requireChannelKind(channel) !=
            io.github.dsheirer.configuration.ChannelConfigurationPolicy.ChannelKind.TRUNKED) throw invalid();
        var decoder = channel.getDecodeConfiguration();
        if("dmr".equals(protocolId) != (decoder instanceof io.github.dsheirer.module.decode.dmr.DecodeConfigDMR) ||
            "nxdn".equals(protocolId) != (decoder instanceof io.github.dsheirer.module.decode.nxdn.DecodeConfigNXDN)) throw invalid();
        if(decoder instanceof io.github.dsheirer.module.decode.nxdn.DecodeConfigNXDN nxdn)
        {
            String mode = nxdn.getTransmissionMode().name();
            if("TYPE_D".equals(variant) != "TYPE_D".equals(mode) ||
                "TYPE_C_4800".equals(variant) && !"M4800".equals(mode) ||
                "TYPE_C_9600".equals(variant) && !"M9600".equals(mode)) throw invalid();
        }
        if(identity.radioSystemKey() == null)
        {
            String key = identity.siteKey();
            long frequency = Long.parseLong(key.substring(key.indexOf(":frequency:") + 11, key.lastIndexOf(":site:")));
            var source = channel.getSourceConfiguration();
            boolean matches = source instanceof io.github.dsheirer.source.config.SourceConfigTuner tuner &&
                tuner.getFrequency() == frequency ||
                source instanceof io.github.dsheirer.source.config.SourceConfigTunerMultipleFrequency multiple &&
                multiple.getFrequencies().contains(frequency);
            if(!matches) throw invalid();
        }
    }

    public boolean matches(TrunkedDiscoveryEvidence evidence, boolean siteOnly)
    {
        if(!protocolId.equals(evidence.protocolId())) return false;
        var wanted = evidence.identity();
        if(identity.radioSystemKey() == null && (!variant.equals(evidence.variant()) ||
            !compatible(wanted.network(), identity.network()) || !compatible(wanted.system(), identity.system()) ||
            !compatible(wanted.integrator(), identity.integrator()) || !compatible(wanted.ran(), identity.ran()) ||
            !compatible(wanted.colorCode(), identity.colorCode()))) return false;
        if(siteOnly) return wanted.siteKey().equals(identity.siteKey()) &&
            Objects.equals(wanted.radioSystemKey(), identity.radioSystemKey()) &&
            compatible(wanted.ran(), identity.ran()) && compatible(wanted.colorCode(), identity.colorCode());
        return wanted.radioSystemKey() != null ? wanted.radioSystemKey().equals(identity.radioSystemKey()) :
            wanted.siteKey().equals(identity.siteKey()) && variant.equals(evidence.variant());
    }

    private static boolean compatible(Integer first, Integer second)
    {
        return first == null || second == null || first.equals(second);
    }
}
