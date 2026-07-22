/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.web.tls;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * Validated in-memory server certificate chain and matching private key.
 */
public record TlsMaterial(PrivateKey privateKey, List<X509Certificate> certificateChain)
{
    private static final String KEY_ALIAS = "sdrtrunk-web";

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
}
