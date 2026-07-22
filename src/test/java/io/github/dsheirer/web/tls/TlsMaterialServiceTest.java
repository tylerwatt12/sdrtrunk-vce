/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.web.tls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TlsMaterialServiceTest
{
    @TempDir
    Path mTemporaryDirectory;

    @Test
    void generatesLoadsAndBuildsServerContextAtFixedManagedPaths() throws Exception
    {
        Path dataRoot = mTemporaryDirectory.resolve("portable-data");
        WebTlsMaterialService service = new WebTlsMaterialService(dataRoot);
        TlsMaterial generated = service.generateSelfSigned("bosgame",
            List.of("bosgame", "192.168.64.84", "127.0.0.1"));

        assertEquals(dataRoot.resolve("security/tls/certificate.pem").toAbsolutePath(),
            service.certificatePath());
        assertEquals(dataRoot.resolve("security/tls/private-key.pem").toAbsolutePath(),
            service.privateKeyPath());
        assertTrue(Files.isRegularFile(service.certificatePath()));
        assertTrue(Files.isRegularFile(service.privateKeyPath()));
        assertEquals("RSA", generated.privateKey().getAlgorithm());
        assertEquals(2_048, ((RSAPublicKey)generated.leafCertificate().getPublicKey()).getModulus().bitLength());
        assertEquals("SHA256withRSA", generated.leafCertificate().getSigAlgName());
        generated.leafCertificate().verify(generated.leafCertificate().getPublicKey());
        assertTrue(generated.leafCertificate().getNotAfter().toInstant().isAfter(Instant.now().plus(Duration.ofDays(300))));
        assertSanValues(generated.leafCertificate(), Set.of("bosgame", "192.168.64.84", "127.0.0.1"));

        TlsMaterial loaded = service.validateInstalledMaterial();
        assertEquals(generated.leafSha256Fingerprint(), loaded.leafSha256Fingerprint());
        assertEquals("TLS", loaded.createServerSslContext().getProtocol());
        assertFalse(Files.exists(service.certificatePath().getParent().resolve(".installing")));
        assertFalse(Files.exists(service.certificatePath().getParent().resolve(".certificate.pem.staging")));
        assertFalse(Files.exists(service.certificatePath().getParent().resolve(".private-key.pem.staging")));
    }

    @Test
    void localFacadeImportsOnlyACompleteValidatedPair() throws Exception
    {
        WebTlsMaterialService source = new WebTlsMaterialService(mTemporaryDirectory.resolve("source"));
        TlsMaterial expected = source.generateSelfSigned("receiver.test", List.of("receiver.test", "127.0.0.1"));
        WebTlsMaterialService target = new WebTlsMaterialService(mTemporaryDirectory.resolve("target"));

        TlsMaterial imported = target.importPem(source.certificatePath(), source.privateKeyPath());
        assertEquals(expected.leafSha256Fingerprint(), imported.leafSha256Fingerprint());
        assertEquals(expected.leafSha256Fingerprint(), target.validateInstalledMaterial().leafSha256Fingerprint());
    }

    @Test
    void pairedImportAcceptsAValidLeafFirstCertificateChain() throws Exception
    {
        GeneratedChain generated = generateCertificateChain();
        Path selectedCertificate = mTemporaryDirectory.resolve("selected-chain.pem");
        Path selectedKey = mTemporaryDirectory.resolve("selected-key.pem");
        Files.write(selectedCertificate, concatenate(
            encodePem("CERTIFICATE", generated.leaf().getEncoded()),
            encodePem("CERTIFICATE", generated.root().getEncoded())));
        Files.write(selectedKey, encodePem("PRIVATE KEY", generated.leafKeyPair().getPrivate().getEncoded()));

        TlsMaterialService service = new TlsMaterialService(mTemporaryDirectory.resolve("target"));
        TlsMaterial imported = service.importPem(selectedCertificate, selectedKey);
        assertEquals(2, imported.certificateChain().size());
        assertEquals(generated.leaf().getSubjectX500Principal(), imported.leafCertificate().getSubjectX500Principal());
        assertEquals(2, service.load().certificateChain().size());
    }

    @Test
    void mismatchIsRejectedBeforeAnExistingManagedPairChanges() throws Exception
    {
        TlsMaterialService first = new TlsMaterialService(mTemporaryDirectory.resolve("first"));
        TlsMaterialService second = new TlsMaterialService(mTemporaryDirectory.resolve("second"));
        TlsMaterialService target = new TlsMaterialService(mTemporaryDirectory.resolve("target"));
        first.generateSelfSigned(List.of("first.test"));
        second.generateSelfSigned(List.of("second.test"));
        TlsMaterial original = target.generateSelfSigned(List.of("target.test"));

        assertThrows(TlsMaterialException.class,
            () -> target.importPem(first.getCertificatePath(), second.getPrivateKeyPath()));
        assertEquals(original.leafSha256Fingerprint(), target.load().leafSha256Fingerprint());
        assertFalse(Files.exists(target.getCertificatePath().getParent().resolve(".installing")));
    }

    @Test
    void rejectsEncryptedOrPkcs1PrivateKeyEnvelopesAndOversizedInputs() throws Exception
    {
        WebTlsMaterialService source = new WebTlsMaterialService(mTemporaryDirectory.resolve("source"));
        source.generateSelfSigned("source.test", List.of("source.test"));
        String pkcs8 = Files.readString(source.privateKeyPath());
        Path wrongEnvelope = mTemporaryDirectory.resolve("wrong-envelope.pem");
        Files.writeString(wrongEnvelope, pkcs8.replace("PRIVATE KEY", "RSA PRIVATE KEY"));
        WebTlsMaterialService target = new WebTlsMaterialService(mTemporaryDirectory.resolve("target"));
        assertThrows(TlsMaterialException.class, () -> target.importPem(source.certificatePath(), wrongEnvelope));

        Path oversized = mTemporaryDirectory.resolve("oversized.pem");
        Files.write(oversized, new byte[TlsMaterialService.MAXIMUM_CERTIFICATE_PEM_BYTES + 1]);
        assertThrows(TlsMaterialException.class, () -> target.importPem(oversized, source.privateKeyPath()));
        assertFalse(Files.exists(target.certificatePath()));
        assertFalse(Files.exists(target.privateKeyPath()));
    }

    @Test
    void rejectsInvalidOrMissingSubjectAlternativeNames()
    {
        TlsMaterialService service = new TlsMaterialService(mTemporaryDirectory.resolve("target"));
        assertThrows(TlsMaterialException.class, () -> service.generateSelfSigned(List.of()));
        assertThrows(TlsMaterialException.class, () -> service.generateSelfSigned(List.of("999.1.2.3")));
        assertThrows(TlsMaterialException.class, () -> service.generateSelfSigned(List.of("bad_name")));
        assertFalse(Files.exists(service.getCertificatePath()));
        assertFalse(Files.exists(service.getPrivateKeyPath()));
    }

    @Test
    void interruptedInstallationMarkerFailsClosed() throws Exception
    {
        TlsMaterialService service = new TlsMaterialService(mTemporaryDirectory.resolve("target"));
        service.generateSelfSigned(List.of("target.test"));
        Files.writeString(service.getCertificatePath().getParent().resolve(".installing"), "1\n");
        assertFalse(service.isInstalled());
        assertThrows(TlsMaterialException.class, service::load);
    }

    private static GeneratedChain generateCertificateChain() throws Exception
    {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2_048);
        KeyPair rootKeys = generator.generateKeyPair();
        KeyPair leafKeys = generator.generateKeyPair();
        Instant now = Instant.now();
        Date notBefore = Date.from(now.minus(Duration.ofMinutes(5)));
        Date notAfter = Date.from(now.plus(Duration.ofDays(30)));
        X500Name rootName = new X500Name("CN=Test Root");
        X500Name leafName = new X500Name("CN=receiver.test");
        JcaX509v3CertificateBuilder rootBuilder = new JcaX509v3CertificateBuilder(rootName,
            BigInteger.valueOf(1), notBefore, notAfter, rootName, rootKeys.getPublic());
        rootBuilder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        rootBuilder.addExtension(Extension.keyUsage, true,
            new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
        ContentSigner rootSigner = new JcaContentSignerBuilder("SHA256withRSA").build(rootKeys.getPrivate());
        X509Certificate root = convert(rootBuilder.build(rootSigner));

        JcaX509v3CertificateBuilder leafBuilder = new JcaX509v3CertificateBuilder(rootName,
            BigInteger.valueOf(2), notBefore, notAfter, leafName, leafKeys.getPublic());
        leafBuilder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        leafBuilder.addExtension(Extension.keyUsage, true,
            new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
        leafBuilder.addExtension(Extension.subjectAlternativeName, false,
            new GeneralNames(new GeneralName(GeneralName.dNSName, "receiver.test")));
        X509Certificate leaf = convert(leafBuilder.build(rootSigner));
        leaf.verify(root.getPublicKey());
        return new GeneratedChain(leafKeys, leaf, root);
    }

    private static X509Certificate convert(X509CertificateHolder holder) throws Exception
    {
        return new JcaX509CertificateConverter().getCertificate(holder);
    }

    private static byte[] encodePem(String label, byte[] der)
    {
        String body = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der);
        return ("-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n")
            .getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] concatenate(byte[]... values)
    {
        int length = 0;

        for(byte[] value: values)
        {
            length += value.length;
        }

        byte[] combined = new byte[length];
        int offset = 0;

        for(byte[] value: values)
        {
            System.arraycopy(value, 0, combined, offset, value.length);
            offset += value.length;
        }

        return combined;
    }

    private static void assertSanValues(X509Certificate certificate, Set<String> expected) throws Exception
    {
        List<List<?>> entries = new ArrayList<>(certificate.getSubjectAlternativeNames());
        Set<String> values = entries.stream().map(entry -> entry.get(1).toString()).collect(Collectors.toSet());
        assertEquals(expected, values);
    }

    private record GeneratedChain(KeyPair leafKeyPair, X509Certificate leaf, X509Certificate root)
    {
    }
}
