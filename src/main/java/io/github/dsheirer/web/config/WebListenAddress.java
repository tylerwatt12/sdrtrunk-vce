/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.config;

import java.net.IDN;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Canonical listen host and TCP port for the embedded web application.
 *
 * <p>Parsing is deliberately DNS-free.  Hostname resolution is performed only when a caller explicitly invokes
 * {@link #resolveBindHost()}.</p>
 */
public final class WebListenAddress
{
    public static final int DEFAULT_PORT = 8090;
    private static final int MAXIMUM_HOST_CHARACTERS = 253;
    private static final Pattern DECIMAL_ADDRESS = Pattern.compile("[0-9.]+");
    private static final Pattern DECIMAL_OCTET = Pattern.compile("[0-9]{1,3}");
    private static final Pattern HOST_LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
    public static final WebListenAddress DEFAULT = parse("127.0.0.1:" + DEFAULT_PORT);

    private final String mHost;
    private final int mPort;
    private final boolean mIpv6Literal;

    private WebListenAddress(String host, int port, boolean ipv6Literal)
    {
        mHost = host;
        mPort = port;
        mIpv6Literal = ipv6Literal;
    }

    /**
     * Parses a hostname or IPv4 {@code host:port}, or a bracketed IPv6 {@code [address]:port}.
     */
    public static WebListenAddress parse(String value)
    {
        if(value == null || value.isBlank() || !value.equals(value.strip()))
        {
            throw invalid("Listen address cannot be blank or surrounded by whitespace");
        }

        if(value.contains("://") || containsPathOrUserInformation(value))
        {
            throw invalid("Listen address must contain only a host and port");
        }

        if(value.charAt(0) == '[')
        {
            return parseIpv6(value);
        }

        int separator = value.indexOf(':');

        if(separator < 0 || separator != value.lastIndexOf(':'))
        {
            throw invalid("Listen address requires one port separator; IPv6 addresses must be bracketed");
        }

        String host = value.substring(0, separator);
        int port = parsePort(value.substring(separator + 1));
        return new WebListenAddress(canonicalHost(host), port, false);
    }

    public static WebListenAddress defaults()
    {
        return DEFAULT;
    }

    public String host()
    {
        return mHost;
    }

    public int port()
    {
        return mPort;
    }

    public boolean isIpv6Literal()
    {
        return mIpv6Literal;
    }

    /**
     * Explicitly resolves the validated bind host.  Unlike {@link #parse(String)}, this method may perform DNS.
     */
    public InetAddress resolveBindHost() throws UnknownHostException
    {
        return InetAddress.getByName(mHost);
    }

    @Override
    public String toString()
    {
        return mIpv6Literal ? "[" + mHost + "]:" + mPort : mHost + ":" + mPort;
    }

    @Override
    public boolean equals(Object object)
    {
        return this == object || object instanceof WebListenAddress other && mPort == other.mPort &&
            mIpv6Literal == other.mIpv6Literal && mHost.equals(other.mHost);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(mHost, mPort, mIpv6Literal);
    }

    private static WebListenAddress parseIpv6(String value)
    {
        int closingBracket = value.indexOf(']');

        if(closingBracket < 0 || closingBracket + 1 >= value.length() ||
            value.charAt(closingBracket + 1) != ':' || value.indexOf('[', 1) >= 0 ||
            value.indexOf(']', closingBracket + 1) >= 0)
        {
            throw invalid("Bracketed IPv6 listen address is malformed");
        }

        String literal = value.substring(1, closingBracket);

        if(literal.isBlank() || literal.indexOf('%') >= 0)
        {
            throw invalid("IPv6 listen address is missing or contains an unsupported scope identifier");
        }

        InetAddress address;

        try
        {
            // InetAddress.ofLiteral parses numeric text only and never performs a name-service lookup.
            address = InetAddress.ofLiteral(literal);
        }
        catch(IllegalArgumentException exception)
        {
            throw invalid("IPv6 listen address is invalid", exception);
        }

        if(!(address instanceof Inet6Address))
        {
            throw invalid("Only an IPv6 literal may use bracketed listen-address syntax");
        }

        int port = parsePort(value.substring(closingBracket + 2));
        return new WebListenAddress(canonicalIpv6(address.getAddress()), port, true);
    }

    private static String canonicalHost(String host)
    {
        if(host.isBlank() || host.indexOf('[') >= 0 || host.indexOf(']') >= 0)
        {
            throw invalid("Listen host is missing or malformed");
        }

        if(DECIMAL_ADDRESS.matcher(host).matches())
        {
            return canonicalIpv4(host);
        }

        String candidate = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        String ascii;

        try
        {
            ascii = IDN.toASCII(candidate, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        }
        catch(IllegalArgumentException exception)
        {
            throw invalid("Listen hostname is invalid", exception);
        }

        if(ascii.isBlank() || ascii.length() > MAXIMUM_HOST_CHARACTERS)
        {
            throw invalid("Listen hostname length is invalid");
        }

        for(String label: ascii.split("\\.", -1))
        {
            if(label.length() > 63 || !HOST_LABEL.matcher(label).matches())
            {
                throw invalid("Listen hostname contains an invalid label");
            }
        }

        return ascii;
    }

    private static String canonicalIpv4(String host)
    {
        String[] octets = host.split("\\.", -1);

        if(octets.length != 4)
        {
            throw invalid("IPv4 listen address must contain four decimal octets");
        }

        StringBuilder canonical = new StringBuilder();

        for(String octet: octets)
        {
            if(!DECIMAL_OCTET.matcher(octet).matches() || octet.length() > 1 && octet.charAt(0) == '0')
            {
                throw invalid("IPv4 listen address contains an invalid or ambiguous octet");
            }

            int number = Integer.parseInt(octet);

            if(number > 255)
            {
                throw invalid("IPv4 listen address octet is outside 0-255");
            }

            if(!canonical.isEmpty())
            {
                canonical.append('.');
            }

            canonical.append(number);
        }

        return canonical.toString();
    }

    private static String canonicalIpv6(byte[] bytes)
    {
        int[] words = new int[8];

        for(int index = 0; index < words.length; index++)
        {
            words[index] = (bytes[index * 2] & 0xFF) << 8 | bytes[index * 2 + 1] & 0xFF;
        }

        int longestStart = -1;
        int longestLength = 0;

        for(int index = 0; index < words.length;)
        {
            if(words[index] != 0)
            {
                index++;
                continue;
            }

            int start = index;

            while(index < words.length && words[index] == 0)
            {
                index++;
            }

            int length = index - start;

            if(length >= 2 && length > longestLength)
            {
                longestStart = start;
                longestLength = length;
            }
        }

        StringBuilder canonical = new StringBuilder();

        for(int index = 0; index < words.length;)
        {
            if(index == longestStart)
            {
                canonical.append("::");
                index += longestLength;
                continue;
            }

            if(!canonical.isEmpty() && canonical.charAt(canonical.length() - 1) != ':')
            {
                canonical.append(':');
            }

            canonical.append(Integer.toHexString(words[index++]));
        }

        return canonical.toString();
    }

    private static int parsePort(String portText)
    {
        if(portText.isEmpty() || portText.length() > 5)
        {
            throw invalid("Listen port is missing or invalid");
        }

        for(int index = 0; index < portText.length(); index++)
        {
            char character = portText.charAt(index);

            if(character < '0' || character > '9')
            {
                throw invalid("Listen port must contain decimal digits only");
            }
        }

        int port = Integer.parseInt(portText);

        if(port < 1 || port > 65_535)
        {
            throw invalid("Listen port must be between 1 and 65535");
        }

        return port;
    }

    private static boolean containsPathOrUserInformation(String value)
    {
        return value.indexOf('/') >= 0 || value.indexOf('\\') >= 0 || value.indexOf('?') >= 0 ||
            value.indexOf('#') >= 0 || value.indexOf('@') >= 0;
    }

    private static IllegalArgumentException invalid(String message)
    {
        return new IllegalArgumentException(message);
    }

    private static IllegalArgumentException invalid(String message, Throwable cause)
    {
        return new IllegalArgumentException(message, cause);
    }
}
