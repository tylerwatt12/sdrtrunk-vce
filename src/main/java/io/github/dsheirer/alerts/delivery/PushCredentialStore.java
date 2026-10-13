package io.github.dsheirer.alerts.delivery;

import io.github.dsheirer.database.settings.ApplicationSettingsStore;
import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.Base64;
import java.util.UUID;

/** Optional typed installation credentials in the existing keyed settings store; excluded from web snapshots. */
public final class PushCredentialStore
{
    private static final String KEY = "alerts.push.credentials";
    private final ApplicationSettingsStore mSettings;
    private Credentials mCredentials;

    public PushCredentialStore(Path database) throws IOException, SQLException
    {
        mSettings = new ApplicationSettingsStore(database);
        mCredentials = mSettings.load(KEY, Credentials.class).orElse(Credentials.empty());
    }

    public synchronized Credentials get() { return mCredentials; }

    public synchronized Credentials prepareRegistration() throws IOException, SQLException
    {
        if(!mCredentials.apiKey().isEmpty() && !"deregistered".equals(mCredentials.registrationStatus())) return mCredentials;
        byte[] token = new byte[32]; new SecureRandom().nextBytes(token);
        Credentials next = new Credentials(UUID.randomUUID().toString(),
            Base64.getUrlEncoder().withoutPadding().encodeToString(token), "", "unregistered", "0",
            mCredentials.directPublicKey(), mCredentials.directPrivateKey());
        save(next); // Commit before the remote request; retry after an unknown outcome uses the same identity.
        return next;
    }

    public synchronized void registration(String senderId, String status) throws IOException, SQLException
    {
        Credentials old = mCredentials;
        save(new Credentials(old.installationId(), old.apiKey(), senderId, status, old.updateCursor(),
            old.directPublicKey(), old.directPrivateKey()));
    }

    public synchronized void cursor(String cursor) throws IOException, SQLException
    {
        if(!cursor.matches("[0-9]{1,20}")) throw new IllegalArgumentException("Invalid update cursor");
        Credentials old = mCredentials;
        if(cursor.equals(old.updateCursor())) return;
        save(new Credentials(old.installationId(), old.apiKey(), old.senderId(), old.registrationStatus(), cursor,
            old.directPublicKey(), old.directPrivateKey()));
    }

    public synchronized WebPushCodec.Keys directKeys() throws IOException, SQLException, GeneralSecurityException
    {
        if(mCredentials.directPrivateKey().isEmpty())
        {
            WebPushCodec.Keys keys = WebPushCodec.generateKeys();
            Credentials old = mCredentials;
            save(new Credentials(old.installationId(), old.apiKey(), old.senderId(), old.registrationStatus(),
                old.updateCursor(), keys.publicKey(), keys.privateKey()));
        }
        return new WebPushCodec.Keys(mCredentials.directPublicKey(), mCredentials.directPrivateKey());
    }

    private void save(Credentials next) throws IOException, SQLException
    {
        mSettings.save(KEY, next);
        mCredentials = next;
    }

    public record Credentials(String installationId, String apiKey, String senderId, String registrationStatus,
                              String updateCursor, String directPublicKey, String directPrivateKey)
    {
        static Credentials empty() { return new Credentials("", "", "", "unregistered", "0", "", ""); }
        @Override public String toString() { return "PushCredentials[redacted]"; }
    }
}
