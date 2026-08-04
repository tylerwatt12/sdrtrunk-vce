/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.web.network;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Discovers numeric addresses that another computer can use to reach the embedded web server.
 *
 * <p>All active physical and virtual interfaces are considered so LAN and VPN addresses are both retained. Loopback,
 * wildcard, multicast, and link-local addresses are excluded because they are either not remotely usable or require
 * additional interface-scope information that does not make a portable browser URL.</p>
 */
public final class WebNetworkAddressDiscovery
{
    private static final Comparator<DiscoveredAddress> PREFERRED_ORDER =
        Comparator.comparingInt(WebNetworkAddressDiscovery::addressFamilyPreference)
            .thenComparing(DiscoveredAddress::interfaceDisplayName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(DiscoveredAddress::interfaceDisplayName)
            .thenComparing(DiscoveredAddress::interfaceName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(DiscoveredAddress::interfaceName)
            .thenComparing(DiscoveredAddress::address, WebNetworkAddressDiscovery::compareAddresses)
            .thenComparing(DiscoveredAddress::hostAddress);

    private WebNetworkAddressDiscovery()
    {
    }

    /**
     * Discovers usable LAN and VPN addresses. IPv4 addresses are returned before IPv6 addresses, with stable ordering
     * by interface display name, interface name, and numeric address inside each family.
     */
    public static List<DiscoveredAddress> discover() throws SocketException
    {
        Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();

        if(networkInterfaces == null)
        {
            return List.of();
        }

        List<InterfaceSnapshot> snapshots = new ArrayList<>();

        while(networkInterfaces.hasMoreElements())
        {
            NetworkInterface networkInterface = networkInterfaces.nextElement();
            snapshots.add(new InterfaceSnapshot(networkInterface.getName(), networkInterface.getDisplayName(),
                networkInterface.isUp(), networkInterface.isLoopback(),
                Collections.list(networkInterface.getInetAddresses())));
        }

        return select(snapshots);
    }

    /**
     * Pure selection stage kept package-visible for deterministic unit tests without depending on host interfaces.
     */
    static List<DiscoveredAddress> select(Collection<InterfaceSnapshot> interfaces)
    {
        Objects.requireNonNull(interfaces, "Interface snapshots cannot be null");
        List<DiscoveredAddress> selected = new ArrayList<>();

        for(InterfaceSnapshot networkInterface: interfaces)
        {
            if(networkInterface == null || !networkInterface.up() || networkInterface.loopback())
            {
                continue;
            }

            for(InetAddress address: networkInterface.addresses())
            {
                if(isUsable(address))
                {
                    selected.add(new DiscoveredAddress(networkInterface.name(), networkInterface.displayName(),
                        address));
                }
            }
        }

        selected.sort(PREFERRED_ORDER);
        return List.copyOf(selected);
    }

    static boolean isUsable(InetAddress address)
    {
        return (address instanceof Inet4Address || address instanceof Inet6Address) &&
            !address.isAnyLocalAddress() && !address.isLoopbackAddress() && !address.isMulticastAddress() &&
            !address.isLinkLocalAddress();
    }

    private static int addressFamilyPreference(DiscoveredAddress address)
    {
        return address.address() instanceof Inet4Address ? 0 : 1;
    }

    private static int compareAddresses(InetAddress first, InetAddress second)
    {
        byte[] left = first.getAddress();
        byte[] right = second.getAddress();
        int lengthComparison = Integer.compare(left.length, right.length);

        if(lengthComparison != 0)
        {
            return lengthComparison;
        }

        for(int index = 0; index < left.length; index++)
        {
            int comparison = Integer.compare(Byte.toUnsignedInt(left[index]), Byte.toUnsignedInt(right[index]));

            if(comparison != 0)
            {
                return comparison;
            }
        }

        return 0;
    }

    record InterfaceSnapshot(String name, String displayName, boolean up, boolean loopback,
                             List<InetAddress> addresses)
    {
        InterfaceSnapshot
        {
            name = requireInterfaceName(name);
            displayName = displayName == null || displayName.isBlank() ? name : displayName.strip();
            addresses = List.copyOf(Objects.requireNonNull(addresses, "Interface addresses cannot be null"));
        }
    }

    /**
     * A usable numeric address and the interface that owns it.
     */
    public record DiscoveredAddress(String interfaceName, String interfaceDisplayName, InetAddress address)
    {
        public DiscoveredAddress
        {
            interfaceName = requireInterfaceName(interfaceName);
            interfaceDisplayName = interfaceDisplayName == null || interfaceDisplayName.isBlank() ? interfaceName :
                interfaceDisplayName.strip();
            address = Objects.requireNonNull(address, "Network address cannot be null");

            if(!isUsable(address))
            {
                throw new IllegalArgumentException("Network address is not a usable LAN or VPN address");
            }
        }

        /**
         * Canonical numeric host address without URI brackets or escaping.
         */
        public String hostAddress()
        {
            return address.getHostAddress();
        }

        /**
         * Numeric host ready for use in a URL authority. IPv6 literals are bracketed and a scope delimiter, when
         * present, is escaped according to URI zone-identifier syntax.
         */
        public String urlHost()
        {
            String host = hostAddress();
            return address instanceof Inet6Address ? "[" + host.replace("%", "%25") + "]" : host;
        }

        /**
         * Root HTTP or HTTPS URL for this address and port.
         */
        public String url(String scheme, int port)
        {
            String normalizedScheme = Objects.requireNonNull(scheme, "URL scheme cannot be null").strip()
                .toLowerCase(Locale.ROOT);

            if(!normalizedScheme.equals("http") && !normalizedScheme.equals("https"))
            {
                throw new IllegalArgumentException("URL scheme must be HTTP or HTTPS");
            }

            if(port < 1 || port > 65_535)
            {
                throw new IllegalArgumentException("URL port must be between 1 and 65535");
            }

            return normalizedScheme + "://" + urlHost() + ":" + port + "/";
        }
    }

    private static String requireInterfaceName(String name)
    {
        String normalized = Objects.requireNonNull(name, "Interface name cannot be null").strip();

        if(normalized.isEmpty())
        {
            throw new IllegalArgumentException("Interface name cannot be blank");
        }

        return normalized;
    }
}
