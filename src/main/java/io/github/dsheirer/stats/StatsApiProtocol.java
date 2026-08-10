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

package io.github.dsheirer.stats;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One API-facing protocol registry.  Database codes and decoder enum names stay behind this boundary.
 */
enum StatsApiProtocol
{
    P25("p25"),
    DMR("dmr"),
    NXDN("nxdn"),
    NBFM("nbfm"),
    ARS("ars"),
    CELLOCATOR("cellocator"),
    DCS("dcs"),
    FLEETSYNC("fleetsync"),
    IPV4("ipv4"),
    LOJACK("lojack"),
    LRRP("lrrp"),
    MDC1200("mdc1200"),
    TAIT1200("tait1200"),
    UDP("udp"),
    UNKNOWN("unknown");

    private static final List<Map.Entry<Integer,String>> NXDN_SERVICES = List.of(
        Map.entry(0x8000, "multi_site"),
        Map.entry(0x4000, "multi_system"),
        Map.entry(0x2000, "location_registration"),
        Map.entry(0x1000, "group_registration"),
        Map.entry(0x0800, "authentication"),
        Map.entry(0x0400, "composite_control_channel"),
        Map.entry(0x0200, "voice_call"),
        Map.entry(0x0100, "data_call"),
        Map.entry(0x0080, "short_data_call"),
        Map.entry(0x0040, "status_call_and_remote_control"),
        Map.entry(0x0020, "pstn_network"),
        Map.entry(0x0010, "ip_network"));

    private final String mWireName;

    StatsApiProtocol(String wireName)
    {
        mWireName = wireName;
    }

    String wireName()
    {
        return mWireName;
    }

    static StatsApiProtocol fromCode(long code)
    {
        return switch((int)code)
        {
            case 1, 2 -> P25;
            case 3 -> DMR;
            case 4 -> NXDN;
            case 10 -> NBFM;
            default -> UNKNOWN;
        };
    }

    static StatsApiProtocol fromName(String name)
    {
        String normalized = name != null ? name.strip().toUpperCase(Locale.ROOT)
            .replace('-', '_').replace(' ', '_') : "";
        return switch(normalized)
        {
            case "P25", "APCO25", "APCO_25", "APCO25_PHASE2", "APCO_25_P2", "P25_PHASE_1",
                "P25_PHASE_2" -> P25;
            case "DMR" -> DMR;
            case "NXDN" -> NXDN;
            case "NBFM" -> NBFM;
            case "ARS" -> ARS;
            case "CELLOCATOR" -> CELLOCATOR;
            case "DCS" -> DCS;
            case "FLEETSYNC" -> FLEETSYNC;
            case "IPV4" -> IPV4;
            case "LOJACK" -> LOJACK;
            case "LRRP" -> LRRP;
            case "MDC1200", "MDC_1200" -> MDC1200;
            case "TAIT1200", "TAIT_1200" -> TAIT1200;
            case "UDP" -> UDP;
            default -> UNKNOWN;
        };
    }

    String scopeKind(long code)
    {
        return switch((int)code)
        {
            case 1 -> "linked_system";
            case 2 -> "receiver_context";
            default -> "unknown";
        };
    }

    String addressDomain(long code)
    {
        if(this != NXDN)
        {
            return "standard";
        }

        return switch((int)code)
        {
            case 1 -> "nxdn_type_c";
            case 2 -> "nxdn_type_d";
            default -> "standard";
        };
    }

    String variant(long code)
    {
        return switch(this)
        {
            case DMR -> switch((int)code)
            {
                case 1 -> "tier_iii";
                case 2 -> "connect_plus";
                case 3 -> "capacity_max";
                case 4 -> "hytera_tier_iii";
                case 5 -> "capacity_plus";
                default -> "unknown";
            };
            case NXDN -> switch((int)code)
            {
                case 1 -> "type_c";
                case 2 -> "type_d";
                default -> "unknown";
            };
            default -> "unknown";
        };
    }

    String variant(String value)
    {
        String normalized = value != null ? value.strip().toLowerCase(Locale.ROOT)
            .replace('-', '_').replace(' ', '_') : "";

        return switch(this)
        {
            case P25 -> switch(normalized)
            {
                case "p25_phase_1", "p25_phase1", "p25_1", "apco25", "apco_25" -> "phase_1";
                case "p25_phase_2", "p25_phase2", "p25_2", "apco25_phase2", "apco_25_p2" -> "phase_2";
                default -> "unknown";
            };
            case DMR -> switch(normalized)
            {
                case "tier_iii", "connect_plus", "capacity_max", "hytera_tier_iii", "capacity_plus" ->
                    normalized;
                default -> "unknown";
            };
            case NXDN -> switch(normalized)
            {
                case "type_c", "type_d" -> normalized;
                default -> "unknown";
            };
            default -> "unknown";
        };
    }

    String siteClassification(long code)
    {
        return switch(this)
        {
            case DMR -> switch((int)code)
            {
                case 1 -> "tiny";
                case 2 -> "small";
                case 3 -> "large";
                case 4 -> "huge";
                default -> "unknown";
            };
            case NXDN -> switch((int)code)
            {
                case 1 -> "global";
                case 2 -> "regional";
                case 3 -> "local";
                case 4 -> "type_d";
                case 5 -> "reserved";
                default -> "unknown";
            };
            default -> "unknown";
        };
    }

    String brand(long code)
    {
        if(this != DMR)
        {
            return "unknown";
        }

        return switch((int)code)
        {
            case 1 -> "dmr_tier_iii";
            case 2 -> "motorola_connect_plus";
            case 3 -> "motorola_capacity_max";
            case 4 -> "hytera_tier_iii";
            case 5 -> "motorola_capacity_plus";
            default -> "unknown";
        };
    }

    String operatingMode(long code)
    {
        return switch(this)
        {
            case DMR -> switch((int)code)
            {
                case 1 -> "open_system";
                case 2 -> "advantage";
                default -> "unknown";
            };
            case NXDN -> switch((int)code)
            {
                case 1 -> "idle";
                case 2 -> "free";
                case 3 -> "halted_cwid";
                default -> "unknown";
            };
            default -> "unknown";
        };
    }

    String channelType(long code)
    {
        if(this != DMR)
        {
            return "unknown";
        }

        return switch((int)code)
        {
            case 1 -> "control";
            case 2 -> "traffic";
            default -> "unknown";
        };
    }

    List<String> services(long flags)
    {
        if(this != NXDN)
        {
            return List.of();
        }

        return NXDN_SERVICES.stream().filter(entry -> (flags & entry.getKey()) != 0)
            .map(Map.Entry::getValue).toList();
    }

    Map<String,Boolean> systemCapabilities()
    {
        boolean trunked = this == P25 || this == DMR || this == NXDN;
        Map<String,Boolean> capabilities = new LinkedHashMap<>();
        capabilities.put("sites", trunked);
        capabilities.put("group_identities", trunked);
        capabilities.put("radios", trunked);
        capabilities.put("activity", trunked);
        capabilities.put("talker_aliases", trunked);
        capabilities.put("current_affiliations", this == P25);
        capabilities.put("patch_groups", this == P25);
        return Map.copyOf(capabilities);
    }

    Map<String,Boolean> siteCapabilities()
    {
        boolean trunked = this == P25 || this == DMR || this == NXDN;
        Map<String,Boolean> capabilities = new LinkedHashMap<>();
        capabilities.put("channels", trunked);
        capabilities.put("group_identities", trunked);
        capabilities.put("neighbors", trunked);
        capabilities.put("quality", trunked);
        capabilities.put("activity", trunked);
        capabilities.put("frequency_bands", this == P25);
        capabilities.put("patch_groups", this == P25);
        return Map.copyOf(capabilities);
    }

    Map<String,Boolean> groupIdentityCapabilities(boolean patchGroup)
    {
        Map<String,Boolean> capabilities = new LinkedHashMap<>(systemCapabilities());
        capabilities.put("current_affiliations", this == P25 && !patchGroup);
        return Map.copyOf(capabilities);
    }

    Map<String,Boolean> conventionalCapabilities()
    {
        Map<String,Boolean> capabilities = new LinkedHashMap<>();
        capabilities.put("group_identities", this == DMR);
        capabilities.put("radios", this == DMR);
        capabilities.put("activity", true);
        return Map.copyOf(capabilities);
    }
}
