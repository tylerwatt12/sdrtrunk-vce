/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.web.tls;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.IDN;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * Bounded, platform-neutral manager for the embedded web server's local PEM certificate and private key.
 *
 * <p>The service owns exactly two durable artifacts beneath the portable data root:</p>
 * <ul>
 *     <li>{@code security/tls/certificate.pem}</li>
 *     <li>{@code security/tls/private-key.pem}</li>
 * </ul>
 *
 * <p>Imports are read and fully validated before the managed files are touched. Installation uses fixed-size staging
 * files and atomic same-directory moves. A bounded marker makes an interrupted two-file replacement fail closed on
 * the next load instead of serving a mismatched certificate and key. A subsequent successful import or generation
 * replaces that incomplete installation; no history or rollback generations are retained.</p>
 */
public final class TlsMaterialService
{
    static final int MAXIMUM_CERTIFICATE_PEM_BYTES = 256 * 1024;
    static final int MAXIMUM_PRIVATE_KEY_PEM_BYTES = 64 * 1024;
    static final int MAXIMUM_CERTIFICATE_COUNT = 8;
    static final int MAXIMUM_SAN_COUNT = 32;
    static final int MAXIMUM_SAN_CHARACTERS = 253;
    private static final int RSA_KEY_BITS = 2_048;
    private static final Duration SELF_SIGNED_VALIDITY = Duration.ofDays(365);
    private static final Duration SELF_SIGNED_CLOCK_SKEW = Duration.ofMinutes(5);
    private static final byte[] KEY_MATCH_CHALLENGE =
        "sdrtrunk-vce TLS private key validation".getBytes(StandardCharsets.US_ASCII);
    private static final Pattern CERTIFICATE_PATTERN = pemPattern("CERTIFICATE");
    private static final Pattern PRIVATE_KEY_PATTERN = pemPattern("PRIVATE KEY");
    private static final Set<PosixFilePermission> OWNER_DIRECTORY_PERMISSIONS =
        PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> OWNER_FILE_PERMISSIONS =
        PosixFilePermissions.fromString("rw-------");

    private final Path mTlsDirectory;
    private final Path mCertificatePath;
    private final Path mPrivateKeyPath;
    private final Path mCertificateStagingPath;
    private final Path mPrivateKeyStagingPath;
    private final Path mInstallationMarkerPath;
    private final SecureRandom mSecureRandom;
    private final Clock mClock;

    public TlsMaterialService(Path portableDataRoot)
    {
        this(portableDataRoot, new SecureRandom(), Clock.systemUTC());
    }

    TlsMaterialService(Path portableDataRoot, SecureRandom secureRandom, Clock clock)
    {
        Path dataRoot = Objects.requireNonNull(portableDataRoot, "Portable data root cannot be null")
            .toAbsolutePath().normalize();
        mTlsDirectory = dataRoot.resolve("security").resolve("tls");
        mCertificatePath = mTlsDirectory.resolve("certificate.pem");
        mPrivateKeyPath = mTlsDirectory.resolve("private-key.pem");
        mCertificateStagingPath = mTlsDirectory.resolve(".certificate.pem.staging");
        mPrivateKeyStagingPath = mTlsDirectory.resolve(".private-key.pem.staging");
        mInstallationMarkerPath = mTlsDirectory.resolve(".installing");
        mSecureRandom = Objects.requireNonNull(secureRandom, "Secure random cannot be null");
        mClock = Objects.requireNonNull(clock, "Clock cannot be null");
    }

    public Path getCertificatePath()
    {
        return mCertificatePath;
    }

    public Path getPrivateKeyPath()
    {
        return mPrivateKeyPath;
    }

    /**
     * Indicates that a complete managed pair is present. Parsing and cryptographic validation are performed by
     * {@link #load()}.
     */
    public synchronized boolean isInstalled()
    {
        return !Files.exists(mInstallationMarkerPath, LinkOption.NOFOLLOW_LINKS) &&
            Files.isRegularFile(mCertificatePath, LinkOption.NOFOLLOW_LINKS) &&
            Files.isRegularFile(mPrivateKeyPath, LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Loads and validates the managed certificate chain and private key.
     */
    public synchronized TlsMaterial load() throws TlsMaterialException
    {
        if(Files.exists(mInstallationMarkerPath, LinkOption.NOFOLLOW_LINKS))
        {
            throw new TlsMaterialException("TLS material installation is incomplete; import or generate it again");
        }

        byte[] certificatePem = readBounded(mCertificatePath, MAXIMUM_CERTIFICATE_PEM_BYTES,
            "Managed TLS certificate");
        byte[] privateKeyPem = readBounded(mPrivateKeyPath, MAXIMUM_PRIVATE_KEY_PEM_BYTES,
            "Managed TLS private key");

        try
        {
            return parseAndValidate(certificatePem, privateKeyPem);
        }
        finally
        {
            Arrays.fill(privateKeyPem, (byte)0);
        }
    }

    /**
     * Imports a locally selected certificate PEM chain and matching unencrypted PKCS#8 private-key PEM.
     */
    public synchronized TlsMaterial importPem(Path selectedCertificatePem, Path selectedPrivateKeyPem)
        throws TlsMaterialException
    {
        byte[] certificatePem = readBounded(selectedCertificatePem, MAXIMUM_CERTIFICATE_PEM_BYTES,
            "Selected TLS certificate");
        byte[] privateKeyPem = readBounded(selectedPrivateKeyPem, MAXIMUM_PRIVATE_KEY_PEM_BYTES,
            "Selected TLS private key");

        try
        {
            TlsMaterial material = parseAndValidate(certificatePem, privateKeyPem);
            install(material);
            return material;
        }
        finally
        {
            Arrays.fill(privateKeyPem, (byte)0);
        }
    }

    /**
     * Generates and installs an RSA-2048/SHA-256 self-signed certificate for the supplied DNS names and literal IP
     * addresses. At least one SAN is required; the first canonical SAN is also used as the certificate common name.
     */
    public synchronized TlsMaterial generateSelfSigned(Collection<String> subjectAlternativeNames)
        throws TlsMaterialException
    {
        List<SubjectAlternativeName> names = normalizeSubjectAlternativeNames(subjectAlternativeNames);
        return generateSelfSigned(names.getFirst().value(), names);
    }

    /**
     * Generates and installs an RSA-2048/SHA-256 self-signed certificate using the supplied common name and SANs.
     */
    public synchronized TlsMaterial generateSelfSigned(String commonName,
                                                        Collection<String> subjectAlternativeNames)
        throws TlsMaterialException
    {
        SubjectAlternativeName normalizedCommonName = normalizeSubjectAlternativeName(commonName);
        List<SubjectAlternativeName> names = normalizeSubjectAlternativeNames(subjectAlternativeNames);
        return generateSelfSigned(normalizedCommonName.value(), names);
    }

    private TlsMaterial generateSelfSigned(String commonName, List<SubjectAlternativeName> names)
        throws TlsMaterialException
    {

        try
        {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(RSA_KEY_BITS, mSecureRandom);
            KeyPair keyPair = generator.generateKeyPair();
            Instant now = mClock.instant();
            Date notBefore = Date.from(now.minus(SELF_SIGNED_CLOCK_SKEW));
            Date notAfter = Date.from(now.plus(SELF_SIGNED_VALIDITY));
            X500Name subject = new X500NameBuilder(BCStyle.INSTANCE)
                .addRDN(BCStyle.CN, commonName)
                .build();
            BigInteger serial = new BigInteger(160, mSecureRandom).abs().setBit(159);
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(subject, serial, notBefore,
                notAfter, subject, keyPair.getPublic());
            GeneralName[] generalNames = names.stream()
                .map(name -> new GeneralName(name.ipAddress() ? GeneralName.iPAddress : GeneralName.dNSName,
                    name.value()))
                .toArray(GeneralName[]::new);
            JcaX509ExtensionUtils extensionUtils = new JcaX509ExtensionUtils();
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            builder.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
            builder.addExtension(Extension.extendedKeyUsage, false,
                new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
            builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(generalNames));
            builder.addExtension(Extension.subjectKeyIdentifier, false,
                extensionUtils.createSubjectKeyIdentifier(keyPair.getPublic()));
            builder.addExtension(Extension.authorityKeyIdentifier, false,
                extensionUtils.createAuthorityKeyIdentifier(keyPair.getPublic()));
            ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate());
            X509CertificateHolder holder = builder.build(signer);
            X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(holder);
            TlsMaterial material = validate(List.of(certificate), keyPair.getPrivate());
            install(material);
            return material;
        }
        catch(GeneralSecurityException | IOException | OperatorCreationException exception)
        {
            throw new TlsMaterialException("Unable to generate self-signed TLS material", exception);
        }
    }

    private TlsMaterial parseAndValidate(byte[] certificatePem, byte[] privateKeyPem) throws TlsMaterialException
    {
        List<X509Certificate> certificates = parseCertificates(certificatePem);
        List<byte[]> privateKeyBlocks = parsePemBlocks(privateKeyPem, PRIVATE_KEY_PATTERN, 1, "private key");

        try
        {
            X509Certificate leaf = certificates.getFirst();
            String keyAlgorithm = supportedKeyAlgorithm(leaf.getPublicKey().getAlgorithm());
            byte[] privateKeyBlock = privateKeyBlocks.getFirst();
            PrivateKey privateKey = KeyFactory.getInstance(keyAlgorithm)
                .generatePrivate(new PKCS8EncodedKeySpec(privateKeyBlock));
            return validate(certificates, privateKey);
        }
        catch(GeneralSecurityException exception)
        {
            throw new TlsMaterialException("Unable to parse TLS certificate or unencrypted PKCS#8 private key",
                exception);
        }
        finally
        {
            privateKeyBlocks.forEach(block -> Arrays.fill(block, (byte)0));
        }
    }

    private List<X509Certificate> parseCertificates(byte[] certificatePem) throws TlsMaterialException
    {
        List<byte[]> certificateBlocks = parsePemBlocks(certificatePem, CERTIFICATE_PATTERN,
            MAXIMUM_CERTIFICATE_COUNT, "certificate");
        List<X509Certificate> certificates = new ArrayList<>(certificateBlocks.size());

        try
        {
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");

            for(byte[] certificateBlock: certificateBlocks)
            {
                try(ByteArrayInputStream input = new ByteArrayInputStream(certificateBlock))
                {
                    X509Certificate certificate = (X509Certificate)certificateFactory.generateCertificate(input);

                    if(input.available() != 0)
                    {
                        throw new TlsMaterialException("TLS certificate PEM contains trailing binary data");
                    }

                    certificates.add(certificate);
                }
            }

            return certificates;
        }
        catch(CertificateException | IOException exception)
        {
            throw new TlsMaterialException("Unable to parse the TLS certificate PEM chain", exception);
        }
    }

    private TlsMaterial validate(List<X509Certificate> certificates, PrivateKey privateKey)
        throws TlsMaterialException
    {
        if(certificates.isEmpty() || certificates.size() > MAXIMUM_CERTIFICATE_COUNT)
        {
            throw new TlsMaterialException("TLS certificate chain must contain between 1 and " +
                MAXIMUM_CERTIFICATE_COUNT + " certificates");
        }

        try
        {
            validateCertificateChain(certificates);
            validateKeyMatch(certificates.getFirst(), privateKey);
            return new TlsMaterial(privateKey, certificates);
        }
        catch(TlsMaterialException exception)
        {
            throw exception;
        }
        catch(GeneralSecurityException exception)
        {
            throw new TlsMaterialException("TLS certificate, chain, and private key validation failed", exception);
        }
    }

    private void validateCertificateChain(List<X509Certificate> certificates) throws TlsMaterialException
    {
        if(certificates.isEmpty() || certificates.size() > MAXIMUM_CERTIFICATE_COUNT)
        {
            throw new TlsMaterialException("TLS certificate chain must contain between 1 and " +
                MAXIMUM_CERTIFICATE_COUNT + " certificates");
        }

        try
        {
            Date now = Date.from(mClock.instant());

            for(X509Certificate certificate: certificates)
            {
                certificate.checkValidity(now);
            }

            for(int index = 0; index < certificates.size() - 1; index++)
            {
                X509Certificate certificate = certificates.get(index);
                X509Certificate issuer = certificates.get(index + 1);

                if(!certificate.getIssuerX500Principal().equals(issuer.getSubjectX500Principal()))
                {
                    throw new TlsMaterialException("TLS certificate chain is not ordered leaf first");
                }

                certificate.verify(issuer.getPublicKey());
            }
        }
        catch(TlsMaterialException exception)
        {
            throw exception;
        }
        catch(GeneralSecurityException exception)
        {
            throw new TlsMaterialException("TLS certificate chain validation failed", exception);
        }
    }

    private static void validateKeyMatch(X509Certificate leaf, PrivateKey privateKey)
        throws GeneralSecurityException, TlsMaterialException
    {
        String publicAlgorithm = supportedKeyAlgorithm(leaf.getPublicKey().getAlgorithm());
        String privateAlgorithm = supportedKeyAlgorithm(privateKey.getAlgorithm());

        if(!publicAlgorithm.equals(privateAlgorithm))
        {
            throw new TlsMaterialException("TLS private key does not match the leaf certificate");
        }

        String signatureAlgorithm = switch(publicAlgorithm)
        {
            case "RSA" -> "SHA256withRSA";
            case "EC" -> "SHA256withECDSA";
            default -> throw new TlsMaterialException("Unsupported TLS private key algorithm");
        };
        Signature signature = Signature.getInstance(signatureAlgorithm);
        signature.initSign(privateKey);
        signature.update(KEY_MATCH_CHALLENGE);
        byte[] signed = signature.sign();

        try
        {
            signature.initVerify(leaf.getPublicKey());
            signature.update(KEY_MATCH_CHALLENGE);

            if(!signature.verify(signed))
            {
                throw new TlsMaterialException("TLS private key does not match the leaf certificate");
            }
        }
        finally
        {
            Arrays.fill(signed, (byte)0);
        }
    }

    private static String supportedKeyAlgorithm(String algorithm) throws TlsMaterialException
    {
        return switch(algorithm)
        {
            case "RSA" -> "RSA";
            case "EC", "ECDSA" -> "EC";
            default -> throw new TlsMaterialException("Unsupported TLS private key algorithm");
        };
    }

    private void install(TlsMaterial material) throws TlsMaterialException
    {
        byte[] certificatePem;
        byte[] privateKeyPem;

        try
        {
            certificatePem = encodeCertificatePem(material.certificateChain());
            privateKeyPem = encodePem("PRIVATE KEY", material.privateKey().getEncoded());
        }
        catch(GeneralSecurityException exception)
        {
            throw new TlsMaterialException("Unable to encode validated TLS material", exception);
        }

        boolean commitStarted = false;
        boolean commitCompleted = false;

        try
        {
            createManagedDirectory();
            Files.deleteIfExists(mCertificateStagingPath);
            Files.deleteIfExists(mPrivateKeyStagingPath);
            writeStagingFile(mCertificateStagingPath, certificatePem);
            writeStagingFile(mPrivateKeyStagingPath, privateKeyPem);

            // Prove the canonical staged representation still parses and matches before changing either active file.
            parseAndValidate(certificatePem, privateKeyPem);
            writeStagingFile(mInstallationMarkerPath, new byte[]{'1', '\n'});
            commitStarted = true;
            atomicReplace(mPrivateKeyStagingPath, mPrivateKeyPath);
            atomicReplace(mCertificateStagingPath, mCertificatePath);
            Files.delete(mInstallationMarkerPath);
            commitCompleted = true;
        }
        catch(IOException | TlsMaterialException exception)
        {
            throw new TlsMaterialException(commitStarted ?
                "TLS material installation was interrupted; import or generate it again" :
                "Unable to stage TLS material", exception);
        }
        finally
        {
            Arrays.fill(privateKeyPem, (byte)0);
            deleteQuietly(mCertificateStagingPath);
            deleteQuietly(mPrivateKeyStagingPath);

            if(!commitStarted || commitCompleted)
            {
                deleteQuietly(mInstallationMarkerPath);
            }
        }
    }

    private void createManagedDirectory() throws IOException
    {
        Files.createDirectories(mTlsDirectory);
        setPosixPermissionsIfSupported(mTlsDirectory, OWNER_DIRECTORY_PERMISSIONS);
    }

    private static void writeStagingFile(Path path, byte[] content) throws IOException
    {
        Files.deleteIfExists(path);

        try(FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))
        {
            ByteBuffer buffer = ByteBuffer.wrap(content);

            while(buffer.hasRemaining())
            {
                channel.write(buffer);
            }

            channel.force(true);
        }

        setPosixPermissionsIfSupported(path, OWNER_FILE_PERMISSIONS);
    }

    private static void atomicReplace(Path source, Path destination) throws IOException
    {
        try
        {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        catch(AtomicMoveNotSupportedException exception)
        {
            throw new IOException("The TLS directory does not support atomic file replacement", exception);
        }
    }

    private static void setPosixPermissionsIfSupported(Path path, Set<PosixFilePermission> permissions)
        throws IOException
    {
        try
        {
            Files.setPosixFilePermissions(path, permissions);
        }
        catch(UnsupportedOperationException ignored)
        {
            // Windows and other non-POSIX providers rely on the portable data root's owner protection.
        }
    }

    private static byte[] encodeCertificatePem(List<X509Certificate> certificates)
        throws GeneralSecurityException
    {
        StringBuilder encoded = new StringBuilder();

        for(X509Certificate certificate: certificates)
        {
            encoded.append(new String(encodePem("CERTIFICATE", certificate.getEncoded()),
                StandardCharsets.US_ASCII));
        }

        return encoded.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] encodePem(String label, byte[] der)
    {
        Objects.requireNonNull(der, "DER content cannot be null");
        String body = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der);
        return ("-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n")
            .getBytes(StandardCharsets.US_ASCII);
    }

    private static List<byte[]> parsePemBlocks(byte[] pem, Pattern pattern, int maximumBlocks, String label)
        throws TlsMaterialException
    {
        String text = decodeUtf8(pem, label);
        Matcher matcher = pattern.matcher(text);
        List<byte[]> blocks = new ArrayList<>();
        int cursor = 0;

        while(matcher.find())
        {
            if(!text.substring(cursor, matcher.start()).isBlank())
            {
                throw new TlsMaterialException("TLS " + label + " PEM contains unsupported content");
            }

            if(blocks.size() >= maximumBlocks)
            {
                throw new TlsMaterialException("TLS " + label + " PEM exceeds the allowed item count");
            }

            String base64 = matcher.group(1).replaceAll("\\s", "");

            try
            {
                blocks.add(Base64.getDecoder().decode(base64));
            }
            catch(IllegalArgumentException exception)
            {
                throw new TlsMaterialException("TLS " + label + " PEM contains invalid Base64", exception);
            }

            cursor = matcher.end();
        }

        if(blocks.isEmpty() || !text.substring(cursor).isBlank())
        {
            throw new TlsMaterialException("TLS " + label + " PEM has an unsupported or missing envelope");
        }

        return blocks;
    }

    private static String decodeUtf8(byte[] content, String label) throws TlsMaterialException
    {
        try
        {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(content)).toString();
        }
        catch(CharacterCodingException exception)
        {
            throw new TlsMaterialException("TLS " + label + " PEM is not valid UTF-8", exception);
        }
    }

    private static Pattern pemPattern(String label)
    {
        return Pattern.compile("-----BEGIN " + Pattern.quote(label) + "-----\\s*" +
            "([A-Za-z0-9+/=\\r\\n\\t ]+)\\s*-----END " + Pattern.quote(label) + "-----");
    }

    private static byte[] readBounded(Path selected, int maximumBytes, String label) throws TlsMaterialException
    {
        Path path = Objects.requireNonNull(selected, label + " path cannot be null")
            .toAbsolutePath().normalize();

        try
        {
            if(!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            {
                throw new TlsMaterialException(label + " is not a regular file");
            }

            long size = Files.size(path);

            if(size < 1 || size > maximumBytes)
            {
                throw new TlsMaterialException(label + " must contain between 1 and " + maximumBytes + " bytes");
            }

            try(InputStream input = Files.newInputStream(path))
            {
                byte[] content = input.readNBytes(maximumBytes + 1);

                if(content.length > maximumBytes || input.read() != -1)
                {
                    throw new TlsMaterialException(label + " exceeds the maximum allowed size");
                }

                return content;
            }
        }
        catch(TlsMaterialException exception)
        {
            throw exception;
        }
        catch(IOException exception)
        {
            throw new TlsMaterialException("Unable to read " + label.toLowerCase(Locale.ROOT), exception);
        }
    }

    private static List<SubjectAlternativeName> normalizeSubjectAlternativeNames(Collection<String> supplied)
        throws TlsMaterialException
    {
        if(supplied == null || supplied.isEmpty() || supplied.size() > MAXIMUM_SAN_COUNT)
        {
            throw new TlsMaterialException("Provide between 1 and " + MAXIMUM_SAN_COUNT +
                " DNS names or IP addresses");
        }

        Map<String,SubjectAlternativeName> normalized = new LinkedHashMap<>();

        for(String value: supplied)
        {
            SubjectAlternativeName name = normalizeSubjectAlternativeName(value);
            normalized.putIfAbsent((name.ipAddress() ? "ip:" : "dns:") + name.value(), name);
        }

        if(normalized.isEmpty())
        {
            throw new TlsMaterialException("At least one DNS name or IP address is required");
        }

        return List.copyOf(normalized.values());
    }

    private static SubjectAlternativeName normalizeSubjectAlternativeName(String supplied)
        throws TlsMaterialException
    {
        if(supplied == null || supplied.isBlank())
        {
            throw new TlsMaterialException("TLS subject alternative names cannot be blank");
        }

        String candidate = supplied.strip();

        if(candidate.length() > MAXIMUM_SAN_CHARACTERS || candidate.indexOf('/') >= 0 ||
            candidate.indexOf('[') >= 0 || candidate.indexOf(']') >= 0)
        {
            throw new TlsMaterialException("Invalid TLS subject alternative name");
        }

        boolean resemblesIpAddress = candidate.indexOf(':') >= 0 ||
            candidate.chars().allMatch(character -> Character.isDigit(character) || character == '.');

        if(resemblesIpAddress)
        {
            try
            {
                return new SubjectAlternativeName(true, InetAddress.ofLiteral(candidate).getHostAddress());
            }
            catch(IllegalArgumentException exception)
            {
                throw new TlsMaterialException("Invalid literal IP address in TLS subject alternative names",
                    exception);
            }
        }

        try
        {
            String ascii = IDN.toASCII(candidate, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);

            if(ascii.endsWith("."))
            {
                ascii = ascii.substring(0, ascii.length() - 1);
            }

            if(ascii.isBlank() || ascii.length() > MAXIMUM_SAN_CHARACTERS || ascii.contains("*") ||
                Arrays.stream(ascii.split("\\.", -1)).anyMatch(label -> label.isEmpty() || label.length() > 63 ||
                    label.startsWith("-") || label.endsWith("-")))
            {
                throw new TlsMaterialException("Invalid DNS name in TLS subject alternative names");
            }

            return new SubjectAlternativeName(false, ascii);
        }
        catch(IllegalArgumentException exception)
        {
            throw new TlsMaterialException("Invalid DNS name in TLS subject alternative names", exception);
        }
    }

    private static void deleteQuietly(Path path)
    {
        try
        {
            Files.deleteIfExists(path);
        }
        catch(IOException ignored)
        {
            // A fixed staging artifact is overwritten on the next operation, so cleanup remains bounded.
        }
    }

    private record SubjectAlternativeName(boolean ipAddress, String value)
    {
    }
}
