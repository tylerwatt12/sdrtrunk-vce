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
 * <p>The local file choosers may gather certificate and key in separate UI steps, but this service imports the pair
 * together so a mismatched or cancelled replacement cannot damage previously working material.</p>
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

    public TlsMaterial importPem(Path selectedCertificate, Path selectedPrivateKey) throws TlsMaterialException
    {
        return mDelegate.importPem(Objects.requireNonNull(selectedCertificate,
                "Selected certificate path cannot be null"),
            Objects.requireNonNull(selectedPrivateKey, "Selected private-key path cannot be null"));
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
