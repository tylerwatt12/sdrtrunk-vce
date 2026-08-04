/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.web.tls;

import java.io.IOException;
import java.net.IDN;
import java.net.InetAddress;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.security.auth.x500.X500Principal;

/**
 * Validated in-memory server certificate chain and matching private key.
 */
public record TlsMaterial(PrivateKey privateKey, List<X509Certificate> certificateChain)
{
    private static final String KEY_ALIAS = "sdrtrunk-web";
    private static final int DNS_SUBJECT_ALTERNATIVE_NAME = 2;
    private static final int IP_SUBJECT_ALTERNATIVE_NAME = 7;
    private static final int MAXIMUM_PREVIEW_SAN_COUNT = 64;
    private static final int MAXIMUM_PREVIEW_TEXT_CHARACTERS = 512;

    public TlsMaterial
    {
        Objects.requireNonNull(privateKey, "Private key cannot be null");
        Objects.requireNonNull(certificateChain, "Certificate chain cannot be null");
        certificateChain = List.copyOf(certificateChain);

        if(certificateChain.isEmpty())
        {
            throw new IllegalArgumentException("Certificate chain cannot be empty");
        }

        if(certificateChain.stream().anyMatch(Objects::isNull))
        {
            throw new IllegalArgumentException("Certificate chain cannot contain null certificates");
        }
    }

    public X509Certificate leafCertificate()
    {
        return certificateChain.getFirst();
    }

    /**
     * Bounded RFC 2253 subject name suitable for a certificate preview.
     */
    public String subjectDisplayName()
    {
        return boundedDisplayText(leafCertificate().getSubjectX500Principal().getName(X500Principal.RFC2253));
    }

    /**
     * Bounded RFC 2253 issuer name suitable for a certificate preview.
     */
    public String issuerDisplayName()
    {
        return boundedDisplayText(leafCertificate().getIssuerX500Principal().getName(X500Principal.RFC2253));
    }

    public Instant notBefore()
    {
        return leafCertificate().getNotBefore().toInstant();
    }

    public Instant notAfter()
    {
        return leafCertificate().getNotAfter().toInstant();
    }

    /**
     * Returns a bounded list of human-readable SAN values. Hostname coverage uses only DNS and IP SANs and fails
     * closed if the certificate's SAN extension cannot be parsed.
     */
    public List<String> subjectAlternativeNames()
    {
        List<SubjectAlternativeName> entries = subjectAlternativeNameEntries();
        List<String> values = new ArrayList<>(entries.size());

        for(SubjectAlternativeName entry: entries)
        {
            String label = switch(entry.type())
            {
                case DNS_SUBJECT_ALTERNATIVE_NAME -> "DNS:";
                case IP_SUBJECT_ALTERNATIVE_NAME -> "IP:";
                case 1 -> "EMAIL:";
                case 6 -> "URI:";
                case 8 -> "RID:";
                default -> "TYPE" + entry.type() + ":";
            };
            values.add(boundedDisplayText(label + entry.value()));
        }

        return List.copyOf(values);
    }

    /**
     * Cryptographically identifies a self-signed leaf certificate.
     */
    public boolean isSelfSigned()
    {
        X509Certificate leaf = leafCertificate();

        if(!leaf.getSubjectX500Principal().equals(leaf.getIssuerX500Principal()))
        {
            return false;
        }

        try
        {
            leaf.verify(leaf.getPublicKey());
            return true;
        }
        catch(GeneralSecurityException exception)
        {
            return false;
        }
    }

    /**
     * Indicates whether a DNS name or literal IP address is covered by the leaf certificate's SAN extension. DNS
     * wildcards are accepted only as the complete left-most label and match exactly one label. The common name is
     * intentionally not used as a fallback.
     */
    public boolean coversHost(String host)
    {
        String candidate = normalizeHostInput(host);

        if(candidate == null)
        {
            return false;
        }

        boolean addressCandidate = resemblesLiteralAddress(candidate);
        byte[] candidateAddress = addressCandidate ? literalAddress(candidate) : null;

        if(addressCandidate && candidateAddress == null)
        {
            return false;
        }

        for(SubjectAlternativeName entry: subjectAlternativeNameEntries(Integer.MAX_VALUE))
        {
            if(candidateAddress != null && entry.type() == IP_SUBJECT_ALTERNATIVE_NAME)
            {
                byte[] sanAddress = literalAddress(entry.value());

                if(sanAddress != null && Arrays.equals(candidateAddress, sanAddress))
                {
                    return true;
                }
            }
            else if(candidateAddress == null && entry.type() == DNS_SUBJECT_ALTERNATIVE_NAME &&
                dnsNameMatches(candidate, entry.value()))
            {
                return true;
            }
        }

        return false;
    }

    /**
     * SHA-256 fingerprint of the leaf certificate, formatted as uppercase colon-separated hexadecimal.
     */
    public String leafSha256Fingerprint() throws GeneralSecurityException
    {
        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(leafCertificate().getEncoded());
            return HexFormat.ofDelimiter(":").withUpperCase().formatHex(digest);
        }
        catch(CertificateEncodingException exception)
        {
            throw new GeneralSecurityException("Unable to fingerprint the TLS certificate", exception);
        }
    }

    /**
     * Builds a server SSL context without persisting a keystore password. The managed PEM key is placed into a
     * temporary in-memory PKCS#12 keystore using a random, short-lived password that is cleared before returning.
     */
    public SSLContext createServerSslContext() throws GeneralSecurityException
    {
        char[] password = ephemeralPassword();

        try
        {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");

            try
            {
                keyStore.load(null, null);
            }
            catch(IOException exception)
            {
                throw new GeneralSecurityException("Unable to initialize an in-memory TLS keystore", exception);
            }

            Certificate[] chain = certificateChain.toArray(Certificate[]::new);
            keyStore.setKeyEntry(KEY_ALIAS, privateKey, password, chain);
            KeyManagerFactory keyManagerFactory =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(keyStore, password);
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(keyManagerFactory.getKeyManagers(), null, new SecureRandom());
            return sslContext;
        }
        finally
        {
            Arrays.fill(password, '\0');
        }
    }

    private static char[] ephemeralPassword() throws GeneralSecurityException
    {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(128);
        SecretKey key = generator.generateKey();
        return HexFormat.of().formatHex(key.getEncoded()).toCharArray();
    }

    private List<SubjectAlternativeName> subjectAlternativeNameEntries()
    {
        return subjectAlternativeNameEntries(MAXIMUM_PREVIEW_SAN_COUNT);
    }

    private List<SubjectAlternativeName> subjectAlternativeNameEntries(int maximumEntries)
    {
        Collection<List<?>> supplied;

        try
        {
            supplied = leafCertificate().getSubjectAlternativeNames();
        }
        catch(CertificateParsingException exception)
        {
            return List.of();
        }

        if(supplied == null || supplied.isEmpty())
        {
            return List.of();
        }

        List<SubjectAlternativeName> entries = new ArrayList<>(
            Math.min(supplied.size(), maximumEntries));
        int inspected = 0;

        for(List<?> entry: supplied)
        {
            if(inspected++ >= maximumEntries)
            {
                break;
            }

            if(entry == null || entry.size() < 2 || !(entry.get(0) instanceof Integer type))
            {
                continue;
            }

            String value = subjectAlternativeNameValue(type, entry.get(1));

            if(value != null && !value.isBlank())
            {
                entries.add(new SubjectAlternativeName(type, boundedDisplayText(value)));
            }
        }

        return List.copyOf(entries);
    }

    private static String subjectAlternativeNameValue(int type, Object supplied)
    {
        if(supplied instanceof String value)
        {
            return value;
        }

        if(type == IP_SUBJECT_ALTERNATIVE_NAME && supplied instanceof byte[] address)
        {
            try
            {
                return InetAddress.getByAddress(address).getHostAddress();
            }
            catch(IllegalArgumentException | java.net.UnknownHostException exception)
            {
                return null;
            }
        }

        return null;
    }

    private static String normalizeHostInput(String supplied)
    {
        if(supplied == null || supplied.isBlank() || supplied.length() > MAXIMUM_PREVIEW_TEXT_CHARACTERS)
        {
            return null;
        }

        String candidate = supplied.strip();

        if(candidate.startsWith("["))
        {
            int closingBracket = candidate.indexOf(']');

            if(closingBracket < 0)
            {
                return null;
            }

            String suffix = candidate.substring(closingBracket + 1);

            if(!suffix.isEmpty() && !suffix.matches(":\\d{1,5}"))
            {
                return null;
            }

            candidate = candidate.substring(1, closingBracket);
        }
        else
        {
            int colon = candidate.lastIndexOf(':');

            if(colon > 0 && candidate.indexOf(':') == colon && candidate.substring(colon + 1).matches("\\d{1,5}"))
            {
                candidate = candidate.substring(0, colon);
            }
        }

        return candidate.isBlank() ? null : candidate;
    }

    private static byte[] literalAddress(String supplied)
    {
        int scope = supplied.indexOf('%');

        if(scope >= 0)
        {
            supplied = supplied.substring(0, scope);
        }

        if(!resemblesLiteralAddress(supplied))
        {
            return null;
        }

        try
        {
            return InetAddress.ofLiteral(supplied).getAddress();
        }
        catch(IllegalArgumentException exception)
        {
            return null;
        }
    }

    private static boolean resemblesLiteralAddress(String supplied)
    {
        return supplied.indexOf(':') >= 0 ||
            supplied.chars().allMatch(character -> Character.isDigit(character) || character == '.');
    }

    private static boolean dnsNameMatches(String host, String suppliedPattern)
    {
        String normalizedHost = normalizeDnsName(host);

        if(normalizedHost == null)
        {
            return false;
        }

        String pattern = suppliedPattern.strip();

        if(pattern.startsWith("*."))
        {
            String suffix = normalizeDnsName(pattern.substring(2));

            if(suffix == null)
            {
                return false;
            }

            int firstDot = normalizedHost.indexOf('.');
            return firstDot > 0 && normalizedHost.substring(firstDot + 1).equals(suffix);
        }

        String normalizedPattern = normalizeDnsName(pattern);
        return normalizedPattern != null && normalizedHost.equals(normalizedPattern);
    }

    private static String normalizeDnsName(String supplied)
    {
        try
        {
            String candidate = supplied;

            if(candidate.endsWith("."))
            {
                candidate = candidate.substring(0, candidate.length() - 1);
            }

            String normalized = IDN.toASCII(candidate, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            return normalized.isBlank() || normalized.length() > 253 || normalized.contains("*") ? null : normalized;
        }
        catch(IllegalArgumentException exception)
        {
            return null;
        }
    }

    private static String boundedDisplayText(String supplied)
    {
        return supplied.length() <= MAXIMUM_PREVIEW_TEXT_CHARACTERS ? supplied :
            supplied.substring(0, MAXIMUM_PREVIEW_TEXT_CHARACTERS - 1) + '\u2026';
    }

    private record SubjectAlternativeName(int type, String value)
    {
    }
}
