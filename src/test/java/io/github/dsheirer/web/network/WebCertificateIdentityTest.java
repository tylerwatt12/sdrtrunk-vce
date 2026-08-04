/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.web.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WebCertificateIdentityTest
{
    @Test
    void includesComputerLoopbackAndDiscoveredAddresses() throws Exception
    {
        WebCertificateIdentity identity = WebCertificateIdentity.create("receiver.local.", List.of(
            address("en0", "Ethernet", "192.168.1.24"),
            address("utun0", "VPN", "fd00::24")));

        assertEquals("receiver.local", identity.commonName());
        assertEquals(List.of("receiver.local", "localhost", "127.0.0.1", "192.168.1.24"),
            identity.subjectAlternativeNames());
        assertEquals(List.of("receiver.local", "localhost", "127.0.0.1", "192.168.1.24"),
            identity.requiredSubjectAlternativeNames());
        assertEquals(List.of("192.168.1.24"),
            identity.networkAddresses().stream().map(
                WebNetworkAddressDiscovery.DiscoveredAddress::hostAddress).toList());
    }

    @Test
    void defaultsBlankComputerNameAndBoundsCertificateNames() throws Exception
    {
        List<WebNetworkAddressDiscovery.DiscoveredAddress> addresses = new ArrayList<>();

        for(int index = 1; index <= 40; index++)
        {
            addresses.add(address("vpn" + index, "VPN " + index, "10.10.0." + index));
        }

        WebCertificateIdentity identity = WebCertificateIdentity.create(" ", addresses);

        assertEquals("localhost", identity.commonName());
        assertEquals(32, identity.subjectAlternativeNames().size());
        assertTrue(identity.subjectAlternativeNames().contains("10.10.0.1"));
    }

    private static WebNetworkAddressDiscovery.DiscoveredAddress address(String interfaceName, String displayName,
                                                                         String address) throws Exception
    {
        return new WebNetworkAddressDiscovery.DiscoveredAddress(interfaceName, displayName,
            InetAddress.getByName(address));
    }
}
