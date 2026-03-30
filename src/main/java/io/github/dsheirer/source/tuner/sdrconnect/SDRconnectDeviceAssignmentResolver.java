/*
 * *****************************************************************************
 * Copyright (C) 2014-2025 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.source.tuner.sdrconnect;

import io.github.dsheirer.source.tuner.configuration.TunerConfiguration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

class SDRconnectDeviceAssignmentResolver
{
    Map<String, String> resolveConfiguredDeviceAssignments(List<TunerConfiguration> tunerConfigurations,
                                                           Map<String, SDRconnectEndpointReadiness> readinessByEndpoint)
    {
        Map<String, String> assignments = new HashMap<>();
        Map<String, Integer> representativePortsByHost =
            getRepresentativePortsByHost(tunerConfigurations, readinessByEndpoint);
        Map<String, List<SDRconnectDeviceSlot>> slotsByHost =
            getDeviceSlotsByHost(representativePortsByHost, readinessByEndpoint);
        Map<String, Set<SDRconnectDeviceSlot>> claimedSlotsByHost = new HashMap<>();

        assignExplicitConfiguredDevices(tunerConfigurations, slotsByHost, claimedSlotsByHost, assignments);
        assignBlankConfiguredDevices(tunerConfigurations, slotsByHost, claimedSlotsByHost, assignments);

        return assignments;
    }

    private Map<String, Integer> getRepresentativePortsByHost(List<TunerConfiguration> tunerConfigurations,
                                                              Map<String, SDRconnectEndpointReadiness> readinessByEndpoint)
    {
        Map<String, Integer> representativePortsByHost = new LinkedHashMap<>();

        for(TunerConfiguration tunerConfiguration: tunerConfigurations)
        {
            if(tunerConfiguration instanceof SDRconnectTunerConfiguration sdrconnectConfig)
            {
                String endpointKey = getSDRconnectEndpointKey(sdrconnectConfig.getHost(), sdrconnectConfig.getPort());
                SDRconnectEndpointReadiness readiness = readinessByEndpoint.get(endpointKey);

                if(readiness != null && readiness.isReady())
                {
                    representativePortsByHost.putIfAbsent(sdrconnectConfig.getHost(), sdrconnectConfig.getPort());
                }
            }
        }

        return representativePortsByHost;
    }

    private Map<String, List<SDRconnectDeviceSlot>> getDeviceSlotsByHost(Map<String, Integer> representativePortsByHost,
                                                                         Map<String, SDRconnectEndpointReadiness> readinessByEndpoint)
    {
        Map<String, List<SDRconnectDeviceSlot>> slotsByHost = new HashMap<>();

        for(Map.Entry<String, Integer> representative : representativePortsByHost.entrySet())
        {
            String endpointKey = getSDRconnectEndpointKey(representative.getKey(), representative.getValue());
            SDRconnectEndpointReadiness readiness = readinessByEndpoint.get(endpointKey);
            String validDevices = readiness != null ? readiness.getValidDevices() : null;

            if(validDevices != null && !validDevices.isBlank())
            {
                slotsByHost.put(representative.getKey(), parseSDRconnectDeviceSlots(validDevices));
            }
        }

        return slotsByHost;
    }

    private void assignExplicitConfiguredDevices(List<TunerConfiguration> tunerConfigurations,
                                                 Map<String, List<SDRconnectDeviceSlot>> slotsByHost,
                                                 Map<String, Set<SDRconnectDeviceSlot>> claimedSlotsByHost,
                                                 Map<String, String> assignments)
    {
        for(TunerConfiguration tunerConfiguration: tunerConfigurations)
        {
            if(!(tunerConfiguration instanceof SDRconnectTunerConfiguration sdrconnectConfig) ||
                sdrconnectConfig.getDeviceName() == null || sdrconnectConfig.getDeviceName().isBlank())
            {
                continue;
            }

            SDRconnectDeviceSlot slot = findMatchingDeviceSlot(slotsByHost.get(sdrconnectConfig.getHost()),
                sdrconnectConfig.getDeviceName());

            if(slot != null)
            {
                assignments.put(sdrconnectConfig.getUniqueID(),
                    slot.getPreferredSelection(sdrconnectConfig.getDeviceName()));
                claimedSlotsByHost.computeIfAbsent(sdrconnectConfig.getHost(), key -> new HashSet<>()).add(slot);
            }
        }
    }

    private void assignBlankConfiguredDevices(List<TunerConfiguration> tunerConfigurations,
                                              Map<String, List<SDRconnectDeviceSlot>> slotsByHost,
                                              Map<String, Set<SDRconnectDeviceSlot>> claimedSlotsByHost,
                                              Map<String, String> assignments)
    {
        for(TunerConfiguration tunerConfiguration: tunerConfigurations)
        {
            if(tunerConfiguration instanceof SDRconnectTunerConfiguration sdrconnectConfig &&
                (sdrconnectConfig.getDeviceName() == null || sdrconnectConfig.getDeviceName().isBlank()))
            {
                List<SDRconnectDeviceSlot> slots = slotsByHost.get(sdrconnectConfig.getHost());

                if(slots != null)
                {
                    Set<SDRconnectDeviceSlot> claimed =
                        claimedSlotsByHost.computeIfAbsent(sdrconnectConfig.getHost(), key -> new HashSet<>());
                    SDRconnectDeviceSlot slot = findFirstUnclaimedSlot(slots, claimed);

                    if(slot != null)
                    {
                        assignments.put(sdrconnectConfig.getUniqueID(), slot.getPreferredSelection(null));
                        claimed.add(slot);
                    }
                }
            }
        }
    }

    private SDRconnectDeviceSlot findFirstUnclaimedSlot(List<SDRconnectDeviceSlot> slots,
                                                        Set<SDRconnectDeviceSlot> claimed)
    {
        for(SDRconnectDeviceSlot slot : slots)
        {
            if(!claimed.contains(slot))
            {
                return slot;
            }
        }

        return null;
    }

    private String getSDRconnectEndpointKey(String host, int port)
    {
        return host + ":" + port;
    }

    /**
     * Parses the advertised SDRconnect devices into assignable slots, pairing serial-based and friendly-name aliases
     * when SDRconnect exposes both in the same ordered list.
     *
     * Note: the serial/named pairing is positional. This relies on SDRconnect currently advertising the serial-based
     * groups and friendly-name groups in the same physical-device order.
     */
    private List<SDRconnectDeviceSlot> parseSDRconnectDeviceSlots(String validDevices)
    {
        Map<String, List<String>> groupedEntries = new LinkedHashMap<>();

        for(String entry : validDevices.split(","))
        {
            String trimmed = entry.trim();

            if(trimmed.isEmpty() || "IQ File".equalsIgnoreCase(trimmed))
            {
                continue;
            }

            String deviceKey = trimmed.replaceFirst("\\s+\\([^)]*\\)$", "");
            groupedEntries.computeIfAbsent(deviceKey, key -> new ArrayList<>()).add(trimmed);
        }

        List<Map.Entry<String, List<String>>> serialGroups = new ArrayList<>();
        List<Map.Entry<String, List<String>>> namedGroups = new ArrayList<>();

        for(Map.Entry<String, List<String>> entry : groupedEntries.entrySet())
        {
            if(entry.getKey().matches(".*\\(\\d+\\)$"))
            {
                serialGroups.add(entry);
            }
            else
            {
                namedGroups.add(entry);
            }
        }

        List<SDRconnectDeviceSlot> slots = new ArrayList<>();

        if(!serialGroups.isEmpty() && serialGroups.size() == namedGroups.size())
        {
            for(int x = 0; x < serialGroups.size(); x++)
            {
                SDRconnectDeviceSlot slot = new SDRconnectDeviceSlot();
                slot.addAlias(serialGroups.get(x).getKey());
                slot.addEntries(serialGroups.get(x).getValue());
                slot.addAlias(namedGroups.get(x).getKey());
                slot.addEntries(namedGroups.get(x).getValue());
                slots.add(slot);
            }
        }
        else
        {
            for(Map.Entry<String, List<String>> entry : groupedEntries.entrySet())
            {
                SDRconnectDeviceSlot slot = new SDRconnectDeviceSlot();
                slot.addAlias(entry.getKey());
                slot.addEntries(entry.getValue());
                slots.add(slot);
            }
        }

        return slots;
    }

    private SDRconnectDeviceSlot findMatchingDeviceSlot(List<SDRconnectDeviceSlot> slots, String selector)
    {
        if(slots == null || selector == null || selector.isBlank())
        {
            return null;
        }

        SDRconnectDeviceSlot fallback = null;

        for(SDRconnectDeviceSlot slot : slots)
        {
            if(slot.matches(selector))
            {
                if(fallback == null)
                {
                    fallback = slot;
                }

                if(slot.prefersDefaultNetworkMode(selector))
                {
                    return slot;
                }
            }
        }

        return fallback;
    }

    private static class SDRconnectDeviceSlot
    {
        private final LinkedHashSet<String> mAliases = new LinkedHashSet<>();
        private final LinkedHashSet<String> mAdvertisedEntries = new LinkedHashSet<>();

        private void addAlias(String alias)
        {
            if(alias != null && !alias.isBlank())
            {
                mAliases.add(alias.trim());
            }
        }

        private void addEntries(List<String> entries)
        {
            for(String entry : entries)
            {
                if(entry != null && !entry.isBlank())
                {
                    mAdvertisedEntries.add(entry.trim());
                }
            }
        }

        private boolean matches(String selector)
        {
            if(selector == null || selector.isBlank())
            {
                return false;
            }

            String normalizedSelector = selector.trim().toLowerCase();

            for(String alias : mAliases)
            {
                if(alias.equalsIgnoreCase(selector) || alias.toLowerCase().contains(normalizedSelector))
                {
                    return true;
                }
            }

            for(String entry : mAdvertisedEntries)
            {
                if(entry.equalsIgnoreCase(selector) || entry.toLowerCase().contains(normalizedSelector))
                {
                    return true;
                }
            }

            return false;
        }

        private boolean prefersDefaultNetworkMode(String selector)
        {
            return getPreferredSelection(selector).toLowerCase()
                .contains(SDRconnectTunerController.DEFAULT_NETWORK_MODE.toLowerCase());
        }

        private String getPreferredSelection(String selector)
        {
            if(selector != null && !selector.isBlank())
            {
                String matchedSelection = findMatchedSelection(selector);

                if(matchedSelection != null)
                {
                    return matchedSelection;
                }
            }

            String namedFallback = findNamedPreferredSelection();

            if(namedFallback != null)
            {
                return namedFallback;
            }

            String defaultModeSelection = findDefaultNetworkModeSelection(mAdvertisedEntries);

            if(defaultModeSelection != null)
            {
                return defaultModeSelection;
            }

            return getFirstAdvertisedEntry();
        }

        private String findMatchedSelection(String selector)
        {
            String matchedEntry = null;
            String normalizedSelector = selector.trim().toLowerCase();

            for(String entry : mAdvertisedEntries)
            {
                if(entry.equalsIgnoreCase(selector) || entry.toLowerCase().contains(normalizedSelector))
                {
                    if(matchedEntry == null)
                    {
                        matchedEntry = entry;
                    }

                    if(prefersDefaultNetworkModeEntry(entry))
                    {
                        return entry;
                    }
                }
            }

            return matchedEntry;
        }

        private String findNamedPreferredSelection()
        {
            String namedFallback = null;

            for(String entry : mAdvertisedEntries)
            {
                if(!isSerialBasedEntry(entry))
                {
                    if(namedFallback == null)
                    {
                        namedFallback = entry;
                    }

                    if(prefersDefaultNetworkModeEntry(entry))
                    {
                        return entry;
                    }
                }
            }

            return namedFallback;
        }

        private String findDefaultNetworkModeSelection(Collection<String> entries)
        {
            for(String entry : entries)
            {
                if(prefersDefaultNetworkModeEntry(entry))
                {
                    return entry;
                }
            }

            return null;
        }

        private boolean prefersDefaultNetworkModeEntry(String entry)
        {
            return entry.toLowerCase().contains(SDRconnectTunerController.DEFAULT_NETWORK_MODE.toLowerCase());
        }

        private boolean isSerialBasedEntry(String entry)
        {
            return entry.matches(".*\\(\\d+\\)\\s+\\([^)]*\\)$");
        }

        private String getFirstAdvertisedEntry()
        {
            return mAdvertisedEntries.iterator().next();
        }
    }
}
