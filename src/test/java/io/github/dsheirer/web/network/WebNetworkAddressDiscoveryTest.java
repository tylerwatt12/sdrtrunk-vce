/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.web.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class WebNetworkAddressDiscoveryTest
{
    @Test
    void retainsOnlyUsableAddressesOnActiveNonLoopbackInterfaces() throws Exception
    {
        WebNetworkAddressDiscovery.InterfaceSnapshot ethernet = snapshot("en0", "Ethernet", true, false,
            "0.0.0.0", "127.0.0.1", "169.254.10.20", "224.0.0.1", "192.168.10.25",
            "::", "::1", "fe80::1", "ff02::1", "fd00::25");
        WebNetworkAddressDiscovery.InterfaceSnapshot inactive = snapshot("en1", "Disconnected", false, false,
            "10.0.0.10");
        WebNetworkAddressDiscovery.InterfaceSnapshot loopback = snapshot("lo0", "Loopback", true, true,
            "10.0.0.11");

        List<WebNetworkAddressDiscovery.DiscoveredAddress> discovered =
            WebNetworkAddressDiscovery.select(List.of(inactive, loopback, ethernet));

        assertEquals(List.of("192.168.10.25", "fd00:0:0:0:0:0:0:25"),
            discovered.stream().map(WebNetworkAddressDiscovery.DiscoveredAddress::hostAddress).toList());
        assertEquals("en0", discovered.getFirst().interfaceName());
        assertEquals("Ethernet", discovered.getFirst().interfaceDisplayName());
    }

    @Test
    void ordersIpv4FirstAndRemainsDeterministicAcrossInputOrder() throws Exception
    {
        WebNetworkAddressDiscovery.InterfaceSnapshot tailscale = snapshot("utun9", "Tailscale", true, false,
            "2001:db8::20", "100.64.0.20");
        WebNetworkAddressDiscovery.InterfaceSnapshot ethernet = snapshot("en0", "Ethernet", true, false,
            "2001:db8::10", "192.168.1.20", "10.0.0.5");
        List<WebNetworkAddressDiscovery.InterfaceSnapshot> reversed = new ArrayList<>(List.of(ethernet, tailscale));
        Collections.reverse(reversed);

        List<WebNetworkAddressDiscovery.DiscoveredAddress> first =
            WebNetworkAddressDiscovery.select(List.of(ethernet, tailscale));
        List<WebNetworkAddressDiscovery.DiscoveredAddress> second = WebNetworkAddressDiscovery.select(reversed);

        assertEquals(first, second);
        assertEquals(List.of("10.0.0.5", "192.168.1.20", "100.64.0.20",
                "2001:db8:0:0:0:0:0:10", "2001:db8:0:0:0:0:0:20"),
            first.stream().map(WebNetworkAddressDiscovery.DiscoveredAddress::hostAddress).toList());
        assertTrue(first.subList(0, 3).stream().noneMatch(address -> address.address() instanceof Inet6Address));
        assertTrue(first.subList(3, 5).stream().allMatch(address -> address.address() instanceof Inet6Address));
    }

    @Test
    void formatsIpv4Ipv6AndScopedIpv6Urls() throws Exception
    {
        WebNetworkAddressDiscovery.DiscoveredAddress ipv4 = discovered("en0", "Ethernet", "192.168.50.12");
        WebNetworkAddressDiscovery.DiscoveredAddress ipv6 = discovered("en0", "Ethernet", "2001:db8::42");
        InetAddress unscoped = InetAddress.getByName("2001:db8::43");
        Inet6Address scoped = Inet6Address.getByAddress(null, unscoped.getAddress(), 7);
        WebNetworkAddressDiscovery.DiscoveredAddress scopedIpv6 =
            new WebNetworkAddressDiscovery.DiscoveredAddress("utun7", "VPN", scoped);

        assertEquals("192.168.50.12", ipv4.urlHost());
        assertEquals("https://192.168.50.12:8443/", ipv4.url("HTTPS", 8443));
        assertEquals("[2001:db8:0:0:0:0:0:42]", ipv6.urlHost());
        assertEquals("http://[2001:db8:0:0:0:0:0:42]:8090/", ipv6.url("http", 8090));
        assertEquals("[2001:db8:0:0:0:0:0:43%257]", scopedIpv6.urlHost());
        assertEquals("https://[2001:db8:0:0:0:0:0:43%257]:9443/", scopedIpv6.url("https", 9443));
    }

    @Test
    void rejectsInvalidUrlArguments() throws Exception
    {
        WebNetworkAddressDiscovery.DiscoveredAddress address = discovered("en0", "Ethernet", "192.168.1.10");

        assertThrows(IllegalArgumentException.class, () -> address.url("ftp", 8090));
        assertThrows(IllegalArgumentException.class, () -> address.url("https", 0));
        assertThrows(IllegalArgumentException.class, () -> address.url("https", 65_536));
    }

    private static WebNetworkAddressDiscovery.InterfaceSnapshot snapshot(String name, String displayName,
                                                                         boolean up, boolean loopback,
                                                                         String... addresses) throws Exception
    {
        List<InetAddress> parsed = new ArrayList<>();

        for(String address: addresses)
        {
            parsed.add(InetAddress.getByName(address));
        }

        return new WebNetworkAddressDiscovery.InterfaceSnapshot(name, displayName, up, loopback, parsed);
    }

    private static WebNetworkAddressDiscovery.DiscoveredAddress discovered(String name, String displayName,
                                                                            String address) throws Exception
    {
        return new WebNetworkAddressDiscovery.DiscoveredAddress(name, displayName, InetAddress.getByName(address));
    }
}
