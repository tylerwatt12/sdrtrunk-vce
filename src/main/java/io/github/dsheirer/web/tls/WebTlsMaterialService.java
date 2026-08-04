/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.web.tls;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Local node-administration facade for the embedded web server's managed TLS material.
 *
 * <p>The local file choosers may gather a certificate and key in separate UI steps or select one PKCS#12/PFX bundle.
 * This service validates a complete selection before installation so a mismatched or cancelled replacement cannot
 * damage previously working material.</p>
 */
public final class WebTlsMaterialService
{
    private final TlsMaterialService mDelegate;

    public WebTlsMaterialService(Path portableDataRoot)
    {
        mDelegate = new TlsMaterialService(portableDataRoot);
    }

    public Path certificatePath()
    {
        return mDelegate.getCertificatePath();
    }

    public Path privateKeyPath()
    {
        return mDelegate.getPrivateKeyPath();
    }

    /**
     * Validates a complete PEM pair for preview without changing the managed files.
     */
    public TlsMaterial validatePem(Path selectedCertificate, Path selectedPrivateKey) throws TlsMaterialException
    {
        return mDelegate.validatePem(Objects.requireNonNull(selectedCertificate,
                "Selected certificate path cannot be null"),
            Objects.requireNonNull(selectedPrivateKey, "Selected private-key path cannot be null"));
    }

    public TlsMaterial importPem(Path selectedCertificate, Path selectedPrivateKey) throws TlsMaterialException
    {
        return mDelegate.importPem(Objects.requireNonNull(selectedCertificate,
                "Selected certificate path cannot be null"),
            Objects.requireNonNull(selectedPrivateKey, "Selected private-key path cannot be null"));
    }

    /**
     * Validates a bounded PKCS#12/PFX bundle for preview without changing the managed files. The supplied password
     * array is consumed and cleared before this method returns.
     */
    public TlsMaterial validatePkcs12(Path selectedPkcs12, char[] password) throws TlsMaterialException
    {
        return mDelegate.validatePkcs12(selectedPkcs12, password);
    }

    /**
     * Imports a bounded PKCS#12/PFX bundle. The supplied password array is consumed and cleared before this method
     * returns.
     */
    public TlsMaterial importPkcs12(Path selectedPkcs12, char[] password) throws TlsMaterialException
    {
        return mDelegate.importPkcs12(selectedPkcs12, password);
    }

    /**
     * Revalidates and atomically installs material previously returned by a validate method.
     */
    public TlsMaterial install(TlsMaterial material) throws TlsMaterialException
    {
        return mDelegate.install(Objects.requireNonNull(material, "TLS material cannot be null"));
    }

    public TlsMaterial generateSelfSigned(String commonName, List<String> subjectAlternativeNames)
        throws TlsMaterialException
    {
        if(commonName == null || commonName.isBlank())
        {
            throw new TlsMaterialException("Self-signed certificate common name cannot be blank");
        }

        return mDelegate.generateSelfSigned(commonName, subjectAlternativeNames);
    }

    /**
     * Loads and cryptographically validates the installed material. Runtime HTTPS startup uses the returned record.
     */
    public TlsMaterial validateInstalledMaterial() throws TlsMaterialException
    {
        return mDelegate.load();
    }
}
