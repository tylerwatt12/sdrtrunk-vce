/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.web.network;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.IDN;
import java.net.SocketException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Current local names and reachable IPv4 addresses used by the app-managed HTTPS certificate.
 */
public record WebCertificateIdentity(String commonName, List<String> subjectAlternativeNames,
                                     List<WebNetworkAddressDiscovery.DiscoveredAddress> networkAddresses)
{
    private static final int MAXIMUM_SUBJECT_ALTERNATIVE_NAMES = 32;

    public WebCertificateIdentity
    {
        commonName = requireName(commonName);
        subjectAlternativeNames = List.copyOf(Objects.requireNonNull(subjectAlternativeNames,
            "Certificate names cannot be null"));
        networkAddresses = List.copyOf(Objects.requireNonNull(networkAddresses,
            "Network addresses cannot be null"));

        if(subjectAlternativeNames.isEmpty() ||
            subjectAlternativeNames.size() > MAXIMUM_SUBJECT_ALTERNATIVE_NAMES)
        {
            throw new IllegalArgumentException("Automatic certificate must have between 1 and 32 names");
        }
    }

    /**
     * Discovers the current computer name plus usable LAN and VPN addresses.
     */
    public static WebCertificateIdentity discover() throws SocketException
    {
        return create(discoverComputerName(), WebNetworkAddressDiscovery.discover());
    }

    /**
     * Stable minimum identity that an existing automatic certificate must cover before it can be reused. Additional
     * discovered IPv4 addresses are included when a certificate is generated, but a newly appearing secondary VPN
     * address does not silently rotate an otherwise usable certificate.
     */
    public List<String> requiredSubjectAlternativeNames()
    {
        LinkedHashSet<String> required = new LinkedHashSet<>();
        required.add(commonName);
        required.add("localhost");
        required.add("127.0.0.1");
        if(!networkAddresses.isEmpty())
        {
            required.add(withoutIpv6Scope(networkAddresses.getFirst().hostAddress()));
        }

        return List.copyOf(required);
    }

    static WebCertificateIdentity create(String computerName,
                                         List<WebNetworkAddressDiscovery.DiscoveredAddress> addresses)
    {
        String commonName = normalizeComputerName(computerName);
        List<WebNetworkAddressDiscovery.DiscoveredAddress> discovered = Objects.requireNonNull(addresses,
            "Network addresses cannot be null").stream()
            .filter(address -> address.address() instanceof Inet4Address).toList();
        LinkedHashSet<String> names = new LinkedHashSet<>();
        names.add(commonName);
        names.add("localhost");
        names.add("127.0.0.1");

        for(WebNetworkAddressDiscovery.DiscoveredAddress address: discovered)
        {
            if(names.size() >= MAXIMUM_SUBJECT_ALTERNATIVE_NAMES)
            {
                break;
            }

            names.add(withoutIpv6Scope(address.hostAddress()));
        }

        return new WebCertificateIdentity(commonName, List.copyOf(names), discovered);
    }

    private static String discoverComputerName()
    {
        try
        {
            return InetAddress.getLocalHost().getHostName();
        }
        catch(Exception exception)
        {
            return "localhost";
        }
    }

    private static String normalizeComputerName(String name)
    {
        if(name == null || name.isBlank())
        {
            return "localhost";
        }

        String normalized = name.strip();
        normalized = normalized.endsWith(".") && normalized.length() > 1 ?
            normalized.substring(0, normalized.length() - 1) : normalized;

        if(normalized.indexOf(':') >= 0)
        {
            return "localhost";
        }

        if(normalized.chars().allMatch(character -> Character.isDigit(character) || character == '.'))
        {
            try
            {
                InetAddress address = InetAddress.ofLiteral(normalized);
                return address instanceof Inet4Address ? address.getHostAddress() : "localhost";
            }
            catch(IllegalArgumentException exception)
            {
                return "localhost";
            }
        }

        try
        {
            String ascii = IDN.toASCII(normalized, IDN.USE_STD3_ASCII_RULES);
            return ascii.isBlank() ? "localhost" : ascii;
        }
        catch(IllegalArgumentException exception)
        {
            return "localhost";
        }
    }

    private static String withoutIpv6Scope(String address)
    {
        int scope = address.indexOf('%');
        return scope >= 0 ? address.substring(0, scope) : address;
    }

    private static String requireName(String name)
    {
        String normalized = Objects.requireNonNull(name, "Certificate common name cannot be null").strip();

        if(normalized.isEmpty())
        {
            throw new IllegalArgumentException("Certificate common name cannot be blank");
        }

        return normalized;
    }
}
