/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.web.tls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
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
        TlsMaterial generated = service.generateSelfSigned("receiver.example",
            List.of("receiver.example", "192.0.2.10", "127.0.0.1"));

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
        assertSanValues(generated.leafCertificate(), Set.of("receiver.example", "192.0.2.10", "127.0.0.1"));
        assertEquals("CN=receiver.example", generated.subjectDisplayName());
        assertEquals("CN=receiver.example", generated.issuerDisplayName());
        assertEquals(generated.leafCertificate().getNotBefore().toInstant(), generated.notBefore());
        assertEquals(generated.leafCertificate().getNotAfter().toInstant(), generated.notAfter());
        assertEquals(Set.of("DNS:receiver.example", "IP:192.0.2.10", "IP:127.0.0.1"),
            Set.copyOf(generated.subjectAlternativeNames()));
        assertTrue(generated.isSelfSigned());
        assertTrue(generated.coversHost("RECEIVER.EXAMPLE."));
        assertTrue(generated.coversHost("192.0.2.10"));
        assertFalse(generated.coversHost("receiver-other.example"));
        assertFalse(generated.coversHost("999.1.2.3"));

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
    void pemPreviewDoesNotInstallUntilTheValidatedMaterialIsConfirmed() throws Exception
    {
        WebTlsMaterialService source = new WebTlsMaterialService(mTemporaryDirectory.resolve("source"));
        TlsMaterial expected = source.generateSelfSigned("receiver.test", List.of("receiver.test"));
        WebTlsMaterialService target = new WebTlsMaterialService(mTemporaryDirectory.resolve("target"));

        TlsMaterial preview = target.validatePem(source.certificatePath(), source.privateKeyPath());
        assertEquals(expected.leafSha256Fingerprint(), preview.leafSha256Fingerprint());
        assertFalse(Files.exists(target.certificatePath()));
        assertFalse(Files.exists(target.privateKeyPath()));

        assertSame(preview, target.install(preview));
        assertEquals(preview.leafSha256Fingerprint(), target.validateInstalledMaterial().leafSha256Fingerprint());
    }

    @Test
    void pkcs12PreviewAndInstallSupportsRsaAndClearsThePassword() throws Exception
    {
        WebTlsMaterialService source = new WebTlsMaterialService(mTemporaryDirectory.resolve("source"));
        TlsMaterial expected = source.generateSelfSigned("receiver.test", List.of("receiver.test", "192.0.2.25"));
        Path selected = mTemporaryDirectory.resolve("receiver.pfx");
        writePkcs12(selected, "bundle password".toCharArray(), expected);
        WebTlsMaterialService target = new WebTlsMaterialService(mTemporaryDirectory.resolve("target"));
        char[] suppliedPassword = "bundle password".toCharArray();

        TlsMaterial preview = target.validatePkcs12(selected, suppliedPassword);
        assertCleared(suppliedPassword);
        assertEquals(expected.leafSha256Fingerprint(), preview.leafSha256Fingerprint());
        assertFalse(Files.exists(target.certificatePath()));
        assertFalse(Files.exists(target.privateKeyPath()));

        target.install(preview);
        assertEquals(expected.leafSha256Fingerprint(), target.validateInstalledMaterial().leafSha256Fingerprint());
    }

    @Test
    void pkcs12ImportSupportsEcPrivateKeys() throws Exception
    {
        TlsMaterial expected = generateSelfSignedEc("ec.receiver.test", "2001:db8::25");
        Path selected = mTemporaryDirectory.resolve("ec-receiver.p12");
        writePkcs12(selected, new char[0], expected);
        WebTlsMaterialService target = new WebTlsMaterialService(mTemporaryDirectory.resolve("target"));
        char[] password = new char[0];

        TlsMaterial imported = target.importPkcs12(selected, password);
        assertEquals("EC", imported.privateKey().getAlgorithm());
        assertEquals(expected.leafSha256Fingerprint(), imported.leafSha256Fingerprint());
        assertEquals("EC", target.validateInstalledMaterial().privateKey().getAlgorithm());
        assertTrue(imported.coversHost("[2001:db8::25]:443"));
        assertTrue(imported.coversHost("[2001:db8::25%25en0]:443"));
        assertTrue(imported.coversHost("ec.receiver.test:8443"));
    }

    @Test
    void pkcs12RejectsWrongPasswordAndMultipleKeyEntriesWithoutChangingManagedMaterial() throws Exception
    {
        TlsMaterialService first = new TlsMaterialService(mTemporaryDirectory.resolve("first"));
        TlsMaterialService second = new TlsMaterialService(mTemporaryDirectory.resolve("second"));
        TlsMaterial firstMaterial = first.generateSelfSigned(List.of("first.test"));
        TlsMaterial secondMaterial = second.generateSelfSigned(List.of("second.test"));
        TlsMaterialService target = new TlsMaterialService(mTemporaryDirectory.resolve("target"));
        TlsMaterial original = target.generateSelfSigned(List.of("target.test"));
        Path selected = mTemporaryDirectory.resolve("multiple.pfx");
        writePkcs12(selected, "correct password".toCharArray(), firstMaterial, secondMaterial);

        char[] wrongPassword = "wrong password".toCharArray();
        assertThrows(TlsMaterialException.class, () -> target.validatePkcs12(selected, wrongPassword));
        assertCleared(wrongPassword);
        assertEquals(original.leafSha256Fingerprint(), target.load().leafSha256Fingerprint());

        char[] correctPassword = "correct password".toCharArray();
        assertThrows(TlsMaterialException.class, () -> target.validatePkcs12(selected, correctPassword));
        assertCleared(correctPassword);
        assertEquals(original.leafSha256Fingerprint(), target.load().leafSha256Fingerprint());
    }

    @Test
    void pkcs12BoundsAreEnforcedAndPasswordIsClearedBeforeFileValidation() throws Exception
    {
        Path oversized = mTemporaryDirectory.resolve("oversized.pfx");
        Files.write(oversized, new byte[TlsMaterialService.MAXIMUM_PKCS12_BYTES + 1]);
        TlsMaterialService target = new TlsMaterialService(mTemporaryDirectory.resolve("target"));
        char[] password = "temporary password".toCharArray();

        assertThrows(TlsMaterialException.class, () -> target.validatePkcs12(oversized, password));
        assertCleared(password);
        assertFalse(Files.exists(target.getCertificatePath()));
        assertFalse(Files.exists(target.getPrivateKeyPath()));
    }

    @Test
    void certificatePreviewMatchesOnlyOneLabelForDnsWildcards() throws Exception
    {
        TlsMaterial material = generateSelfSignedEc("*.receiver.test", "192.0.2.40");
        assertTrue(material.coversHost("east.receiver.test"));
        assertTrue(material.coversHost("EAST.RECEIVER.TEST."));
        assertFalse(material.coversHost("deep.east.receiver.test"));
        assertFalse(material.coversHost("receiver.test"));
        assertFalse(material.coversHost("192.0.2.41"));
    }

    @Test
    void hostnameCoverageInspectsNamesBeyondTheBoundedPreview() throws Exception
    {
        GeneralName[] names = new GeneralName[66];

        for(int index = 0; index < names.length - 1; index++)
        {
            names[index] = new GeneralName(GeneralName.dNSName, "unused-" + index + ".receiver.test");
        }

        names[names.length - 1] = new GeneralName(GeneralName.dNSName, "last.receiver.test");
        TlsMaterial material = generateSelfSignedEc("preview.receiver.test", names);

        assertEquals(64, material.subjectAlternativeNames().size());
        assertTrue(material.coversHost("last.receiver.test"));
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
    void rejectsLeafCertificateAuthoritiesAndNonServerExtendedKeyUsage() throws Exception
    {
        TlsMaterialService service = new TlsMaterialService(mTemporaryDirectory.resolve("target"));
        TlsMaterial certificateAuthority = generateSelfSignedMaterial("RSA", true,
            KeyUsage.digitalSignature | KeyUsage.keyCertSign, KeyPurposeId.id_kp_serverAuth);
        TlsMaterial clientOnly = generateSelfSignedMaterial("RSA", false, KeyUsage.digitalSignature,
            KeyPurposeId.id_kp_clientAuth);

        TlsMaterialException caFailure = assertThrows(TlsMaterialException.class,
            () -> service.install(certificateAuthority));
        assertTrue(caFailure.getMessage().contains("cannot be a certificate authority"));
        TlsMaterialException ekuFailure = assertThrows(TlsMaterialException.class,
            () -> service.install(clientOnly));
        assertTrue(ekuFailure.getMessage().contains("server authentication"));
        assertFalse(Files.exists(service.getCertificatePath()));
        assertFalse(Files.exists(service.getPrivateKeyPath()));
    }

    @Test
    void rejectsRsaOrEcLeafsWithoutDigitalSignatureUsage() throws Exception
    {
        TlsMaterialService service = new TlsMaterialService(mTemporaryDirectory.resolve("target"));
        TlsMaterial rsa = generateSelfSignedMaterial("RSA", false, KeyUsage.keyEncipherment,
            KeyPurposeId.id_kp_serverAuth);
        TlsMaterial ec = generateSelfSignedMaterial("EC", false, KeyUsage.keyEncipherment,
            KeyPurposeId.id_kp_serverAuth);

        TlsMaterialException rsaFailure = assertThrows(TlsMaterialException.class, () -> service.install(rsa));
        assertTrue(rsaFailure.getMessage().contains("server signatures"));
        TlsMaterialException ecFailure = assertThrows(TlsMaterialException.class, () -> service.install(ec));
        assertTrue(ecFailure.getMessage().contains("server signatures"));
        assertFalse(Files.exists(service.getCertificatePath()));
        assertFalse(Files.exists(service.getPrivateKeyPath()));
    }

    @Test
    void rejectsSuppliedIssuersWithoutCaOrCertificateSigningPermission() throws Exception
    {
        TlsMaterialService service = new TlsMaterialService(mTemporaryDirectory.resolve("target"));
        GeneratedChain nonCaIssuer = generateCertificateChain(false, KeyUsage.digitalSignature);
        GeneratedChain nonSigningIssuer = generateCertificateChain(true, KeyUsage.digitalSignature);

        TlsMaterialException caFailure = assertThrows(TlsMaterialException.class, () -> service.install(
            new TlsMaterial(nonCaIssuer.leafKeyPair().getPrivate(),
                List.of(nonCaIssuer.leaf(), nonCaIssuer.root()))));
        assertTrue(caFailure.getMessage().contains("issuer certificate is not a certificate authority"));
        TlsMaterialException keyUsageFailure = assertThrows(TlsMaterialException.class, () -> service.install(
            new TlsMaterial(nonSigningIssuer.leafKeyPair().getPrivate(),
                List.of(nonSigningIssuer.leaf(), nonSigningIssuer.root()))));
        assertTrue(keyUsageFailure.getMessage().contains("not permitted to sign certificates"));
        assertFalse(Files.exists(service.getCertificatePath()));
        assertFalse(Files.exists(service.getPrivateKeyPath()));
    }

    @Test
    void mismatchIsRejectedBeforeAnExistingManagedPairChanges() throws Exception
    {
        TlsMaterialService first = new TlsMaterialService(mTemporaryDirectory.resolve("first"));
        TlsMaterialService second = new TlsMaterialService(mTemporaryDirectory.resolve("second"));
        TlsMaterialService target = new TlsMaterialService(mTemporaryDirectory.resolve("target"));
        TlsMaterial firstMaterial = first.generateSelfSigned(List.of("first.test"));
        TlsMaterial secondMaterial = second.generateSelfSigned(List.of("second.test"));
        TlsMaterial original = target.generateSelfSigned(List.of("target.test"));

        assertThrows(TlsMaterialException.class,
            () -> target.importPem(first.getCertificatePath(), second.getPrivateKeyPath()));
        assertThrows(TlsMaterialException.class, () -> target.install(
            new TlsMaterial(secondMaterial.privateKey(), firstMaterial.certificateChain())));
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
        return generateCertificateChain(true, KeyUsage.keyCertSign | KeyUsage.cRLSign);
    }

    private static GeneratedChain generateCertificateChain(boolean issuerIsCertificateAuthority,
                                                             int issuerKeyUsage) throws Exception
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
        rootBuilder.addExtension(Extension.basicConstraints, true,
            new BasicConstraints(issuerIsCertificateAuthority));
        rootBuilder.addExtension(Extension.keyUsage, true, new KeyUsage(issuerKeyUsage));
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

    private static TlsMaterial generateSelfSignedMaterial(String algorithm, boolean certificateAuthority,
                                                           int keyUsage, KeyPurposeId extendedKeyUsage)
        throws Exception
    {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(algorithm);

        if("EC".equals(algorithm))
        {
            generator.initialize(new ECGenParameterSpec("secp256r1"));
        }
        else
        {
            generator.initialize(2_048);
        }

        KeyPair keyPair = generator.generateKeyPair();
        Instant now = Instant.now();
        Date notBefore = Date.from(now.minus(Duration.ofMinutes(5)));
        Date notAfter = Date.from(now.plus(Duration.ofDays(30)));
        X500Name subject = new X500Name("CN=purpose.receiver.test");
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(subject,
            BigInteger.valueOf(20 + Math.abs(keyUsage)), notBefore, notAfter, subject, keyPair.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(certificateAuthority));
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(keyUsage));
        builder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(extendedKeyUsage));
        builder.addExtension(Extension.subjectAlternativeName, false,
            new GeneralNames(new GeneralName(GeneralName.dNSName, "purpose.receiver.test")));
        String signatureAlgorithm = "EC".equals(algorithm) ? "SHA256withECDSA" : "SHA256withRSA";
        ContentSigner signer = new JcaContentSignerBuilder(signatureAlgorithm).build(keyPair.getPrivate());
        return new TlsMaterial(keyPair.getPrivate(), List.of(convert(builder.build(signer))));
    }

    private static TlsMaterial generateSelfSignedEc(String dnsName, String ipAddress) throws Exception
    {
        return generateSelfSignedEc(dnsName, new GeneralName[]{
            new GeneralName(GeneralName.dNSName, dnsName),
            new GeneralName(GeneralName.iPAddress, ipAddress)
        });
    }

    private static TlsMaterial generateSelfSignedEc(String dnsName, GeneralName[] subjectAlternativeNames)
        throws Exception
    {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair keyPair = generator.generateKeyPair();
        Instant now = Instant.now();
        Date notBefore = Date.from(now.minus(Duration.ofMinutes(5)));
        Date notAfter = Date.from(now.plus(Duration.ofDays(30)));
        X500Name subject = new X500Name("CN=" + dnsName);
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(subject,
            BigInteger.valueOf(10), notBefore, notAfter, subject, keyPair.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
        builder.addExtension(Extension.subjectAlternativeName, false,
            new GeneralNames(subjectAlternativeNames));
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.getPrivate());
        return new TlsMaterial(keyPair.getPrivate(), List.of(convert(builder.build(signer))));
    }

    private static void writePkcs12(Path path, char[] password, TlsMaterial... materials) throws Exception
    {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, password);

        for(int index = 0; index < materials.length; index++)
        {
            TlsMaterial material = materials[index];
            Certificate[] chain = material.certificateChain().toArray(Certificate[]::new);
            keyStore.setKeyEntry("server-" + index, material.privateKey(), password, chain);
        }

        try(var output = Files.newOutputStream(path))
        {
            keyStore.store(output, password);
        }
        finally
        {
            Arrays.fill(password, '\0');
        }
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

    private static void assertCleared(char[] password)
    {
        for(char character: password)
        {
            assertEquals('\0', character);
        }
    }

    private record GeneratedChain(KeyPair leafKeyPair, X509Certificate leaf, X509Certificate root)
    {
    }
}
