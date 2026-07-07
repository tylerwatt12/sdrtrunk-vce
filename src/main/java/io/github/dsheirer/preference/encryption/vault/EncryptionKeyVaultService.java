/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.preference.encryption.vault;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.preference.encryption.VoiceEncryptionKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.prefs.Preferences;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Password-protected SQLite-backed storage for known voice encryption keys.
 */
public class EncryptionKeyVaultService
{
    private static final Logger mLog = LoggerFactory.getLogger(EncryptionKeyVaultService.class);
    private static final int DEFAULT_ITERATIONS = 210_000;
    private static final int SALT_BYTES = 16;
    private static final int NONCE_BYTES = 12;
    private static final int KEY_BITS = 256;
    private static final int GCM_TAG_BITS = 128;
    private static final String KDF = "PBKDF2WithHmacSHA256";
    private static final String CIPHER = "AES-256-GCM";
    private static final String KEY_PROMPT_ON_LAUNCH = "vault.prompt.on.launch";
    private static final String KEY_SAVED_PASSWORD = "vault.saved.password";

    private final Path mVaultPath;
    private final Preferences mPreferences = Preferences.userNodeForPackage(EncryptionKeyVaultService.class);
    private final SecureRandom mSecureRandom = new SecureRandom();
    private final ObjectMapper mObjectMapper = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .enable(SerializationFeature.INDENT_OUTPUT);
    private final ObjectProperty<EncryptionKeyVaultState> mState =
        new SimpleObjectProperty<>(EncryptionKeyVaultState.MISSING);
    private final StringProperty mStatus = new SimpleStringProperty("Encryption vault missing");
    private final BooleanProperty mSavedPasswordPresent = new SimpleBooleanProperty(false);
    private final BooleanProperty mPromptOnLaunch = new SimpleBooleanProperty(false);
    private List<VoiceEncryptionKey> mKeys = List.of();
    private byte[] mVaultKey;

    /**
     * Constructs an instance.
     */
    public EncryptionKeyVaultService(Path vaultPath)
    {
        mVaultPath = Objects.requireNonNull(vaultPath).toAbsolutePath().normalize();
        mSavedPasswordPresent.set(hasSavedPasswordStored());
        mPromptOnLaunch.set(mPreferences.getBoolean(KEY_PROMPT_ON_LAUNCH, isVaultInitialized()));
        mPromptOnLaunch.addListener((observable, oldValue, newValue) ->
            mPreferences.putBoolean(KEY_PROMPT_ON_LAUNCH, newValue));
        refreshState();
    }

    public Path getVaultPath()
    {
        return mVaultPath;
    }

    public ObjectProperty<EncryptionKeyVaultState> stateProperty()
    {
        return mState;
    }

    public StringProperty statusProperty()
    {
        return mStatus;
    }

    public BooleanProperty savedPasswordPresentProperty()
    {
        return mSavedPasswordPresent;
    }

    public BooleanProperty promptOnLaunchProperty()
    {
        return mPromptOnLaunch;
    }

    public synchronized EncryptionKeyVaultState getState()
    {
        return mState.get();
    }

    public synchronized boolean isUnlocked()
    {
        return getState() == EncryptionKeyVaultState.UNLOCKED;
    }

    public synchronized boolean hasVault()
    {
        return isVaultInitialized();
    }

    public boolean isPromptOnLaunch()
    {
        return mPromptOnLaunch.get();
    }

    public void setPromptOnLaunch(boolean promptOnLaunch)
    {
        mPromptOnLaunch.set(promptOnLaunch);
    }

    public boolean hasSavedPassword()
    {
        return mSavedPasswordPresent.get();
    }

    public synchronized List<VoiceEncryptionKey> getKeys()
    {
        return isUnlocked() ? copy(mKeys) : List.of();
    }

    public synchronized void replaceKeys(Collection<VoiceEncryptionKey> keys) throws EncryptionKeyVaultException
    {
        requireUnlocked();
        mKeys = copy(keys);
        writePayload(mKeys);
        setState(EncryptionKeyVaultState.UNLOCKED, "Encryption vault unlocked");
    }

    public synchronized void createVault(char[] password) throws EncryptionKeyVaultException
    {
        requirePassword(password);

        if(!Files.isRegularFile(mVaultPath))
        {
            throw new EncryptionKeyVaultException("Encryption vault schema is missing. Restart SDRTrunk so startup " +
                "schema preparation can create it.");
        }

        if(hasVault())
        {
            throw new EncryptionKeyVaultException("Encryption vault already exists.");
        }

        byte[] key = null;

        try
        {
            byte[] salt = randomBytes(SALT_BYTES);
            key = deriveKey(password, salt, DEFAULT_ITERATIONS);

            try(Connection connection = open())
            {
                connection.setAutoCommit(false);
                try
                {
                    setMetadata(connection, "kdf", KDF);
                    setMetadata(connection, "kdf_iterations", Integer.toString(DEFAULT_ITERATIONS));
                    setMetadata(connection, "salt", encode(salt));
                    setMetadata(connection, "cipher", CIPHER);
                    setMetadata(connection, "gcm_tag_bits", Integer.toString(GCM_TAG_BITS));
                    writePayload(connection, key, List.of());
                    connection.commit();
                }
                catch(SQLException | IOException | GeneralSecurityException e)
                {
                    connection.rollback();
                    throw e;
                }
                finally
                {
                    connection.setAutoCommit(true);
                }
            }

            setVaultKey(key);
            mKeys = List.of();
            setPromptOnLaunch(true);
            setState(EncryptionKeyVaultState.UNLOCKED, "Encryption vault created and unlocked");
        }
        catch(IOException | SQLException | GeneralSecurityException e)
        {
            clearVaultKey();
            setState(EncryptionKeyVaultState.ERROR, "Unable to create encryption vault");
            throw new EncryptionKeyVaultException("Unable to create encryption vault.", e);
        }
        finally
        {
            clear(key);
        }
    }

    public synchronized void unlock(char[] password, boolean savePassword) throws EncryptionKeyVaultException
    {
        requirePassword(password);

        try
        {
            VaultMaterial material = loadVault(password);
            try
            {
                setVaultKey(material.key());
                mKeys = material.keys();

                if(savePassword)
                {
                    savePassword(password);
                    mLog.warn("Encryption vault password is saved locally. This is unsafe.");
                }

                setState(EncryptionKeyVaultState.UNLOCKED, "Encryption vault unlocked");
            }
            finally
            {
                clear(material.key());
            }
        }
        catch(EncryptionKeyVaultException e)
        {
            clearVaultKey();
            setState(hasVault() ? EncryptionKeyVaultState.LOCKED : EncryptionKeyVaultState.MISSING,
                e.getMessage());
            throw e;
        }
    }

    public synchronized boolean tryAutoUnlockSavedPassword()
    {
        char[] savedPassword = getSavedPassword();

        if(savedPassword == null || savedPassword.length == 0)
        {
            return false;
        }

        try
        {
            unlock(savedPassword, true);
            return true;
        }
        catch(EncryptionKeyVaultException e)
        {
            mLog.warn("Saved encryption vault password could not unlock the vault. Decryption remains disabled.");
            return false;
        }
        finally
        {
            clear(savedPassword);
        }
    }

    public synchronized void disableForRun()
    {
        clearVaultKey();
        mKeys = List.of();
        setState(EncryptionKeyVaultState.DISABLED, "Encryption vault disabled for this run");
    }

    public synchronized void lock()
    {
        clearVaultKey();
        mKeys = List.of();
        setState(hasVault() ? EncryptionKeyVaultState.LOCKED : EncryptionKeyVaultState.MISSING,
            hasVault() ? "Encryption vault locked" : vaultMissingStatus());
    }

    public synchronized void changePassword(char[] currentPassword, char[] newPassword) throws EncryptionKeyVaultException
    {
        requirePassword(newPassword);
        VaultMaterial current = loadVault(currentPassword);
        byte[] newKey = null;

        try(Connection connection = open())
        {
            byte[] salt = randomBytes(SALT_BYTES);
            newKey = deriveKey(newPassword, salt, DEFAULT_ITERATIONS);
            connection.setAutoCommit(false);

            try
            {
                setMetadata(connection, "kdf", KDF);
                setMetadata(connection, "kdf_iterations", Integer.toString(DEFAULT_ITERATIONS));
                setMetadata(connection, "salt", encode(salt));
                setMetadata(connection, "cipher", CIPHER);
                setMetadata(connection, "gcm_tag_bits", Integer.toString(GCM_TAG_BITS));
                writePayload(connection, newKey, current.keys());
                connection.commit();
                setVaultKey(newKey);
                mKeys = current.keys();
                forgetSavedPassword();
                setState(EncryptionKeyVaultState.UNLOCKED, "Encryption vault password changed");
            }
            catch(SQLException | IOException | GeneralSecurityException e)
            {
                connection.rollback();
                throw e;
            }
            finally
            {
                connection.setAutoCommit(true);
            }
        }
        catch(IOException | SQLException | GeneralSecurityException e)
        {
            throw new EncryptionKeyVaultException("Unable to change encryption vault password.", e);
        }
        finally
        {
            clear(current.key());
            clear(newKey);
        }
    }

    public synchronized boolean verifyPassword(char[] password)
    {
        try
        {
            VaultMaterial material = loadVault(password);
            clear(material.key());
            return true;
        }
        catch(EncryptionKeyVaultException e)
        {
            return false;
        }
    }

    public synchronized void forgetSavedPassword()
    {
        mPreferences.remove(KEY_SAVED_PASSWORD);
        mSavedPasswordPresent.set(false);
    }

    public synchronized void exportVault(Path destination) throws EncryptionKeyVaultException
    {
        if(!Files.exists(mVaultPath))
        {
            throw new EncryptionKeyVaultException("Encryption vault does not exist.");
        }

        try
        {
            Files.createDirectories(destination.toAbsolutePath().normalize().getParent());
            Files.copy(mVaultPath, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        catch(IOException e)
        {
            throw new EncryptionKeyVaultException("Unable to export encryption vault.", e);
        }
    }

    public synchronized void refreshState()
    {
        if(isUnlocked())
        {
            setState(EncryptionKeyVaultState.UNLOCKED, "Encryption vault unlocked");
        }
        else if(hasVault())
        {
            setState(EncryptionKeyVaultState.LOCKED, "Encryption vault locked");
        }
        else
        {
            setState(EncryptionKeyVaultState.MISSING, vaultMissingStatus());
        }
    }

    private void writePayload(List<VoiceEncryptionKey> keys) throws EncryptionKeyVaultException
    {
        try(Connection connection = open())
        {
            writePayload(connection, mVaultKey, keys);
        }
        catch(IOException | SQLException | GeneralSecurityException e)
        {
            throw new EncryptionKeyVaultException("Unable to save encryption vault keys.", e);
        }
    }

    private VaultMaterial loadVault(char[] password) throws EncryptionKeyVaultException
    {
        requirePassword(password);

        if(!Files.exists(mVaultPath))
        {
            throw new EncryptionKeyVaultException("Encryption vault does not exist.");
        }

        try(Connection connection = open())
        {
            int schemaVersion = parseInt(getMetadata(connection, "schema_version"), -1);

            if(schemaVersion != EncryptionKeyVaultSchema.SCHEMA_VERSION)
            {
                throw new EncryptionKeyVaultException("Unsupported encryption vault schema version [" +
                    schemaVersion + "].");
            }

            String saltText = getMetadata(connection, "salt");
            int iterations = parseInt(getMetadata(connection, "kdf_iterations"), -1);

            if(saltText == null || iterations <= 0)
            {
                throw new EncryptionKeyVaultException("Encryption vault metadata is incomplete.");
            }

            byte[] key = deriveKey(password, decode(saltText), iterations);
            Payload payload = readPayload(connection);
            List<VoiceEncryptionKey> keys = decryptPayload(key, payload);
            return new VaultMaterial(key, keys);
        }
        catch(AEADBadTagException e)
        {
            throw new EncryptionKeyVaultException("Incorrect encryption vault password.", e);
        }
        catch(IOException | SQLException | GeneralSecurityException e)
        {
            throw new EncryptionKeyVaultException("Unable to open encryption vault.", e);
        }
    }

    private Connection open() throws IOException, SQLException
    {
        if(!Files.isRegularFile(mVaultPath))
        {
            throw new IOException("Encryption vault SQLite schema is missing: " + mVaultPath);
        }

        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mVaultPath);

        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA busy_timeout=" + SdrTrunkDatabase.BUSY_TIMEOUT_MILLISECONDS);
            statement.execute("PRAGMA foreign_keys=ON");
        }

        EncryptionKeyVaultSchema.validate(connection);
        return connection;
    }

    private void writePayload(Connection connection, byte[] key, Collection<VoiceEncryptionKey> keys)
        throws IOException, GeneralSecurityException, SQLException
    {
        byte[] nonce = randomBytes(NONCE_BYTES);
        byte[] json = mObjectMapper.writeValueAsBytes(copy(keys));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
        byte[] ciphertext = cipher.doFinal(json);

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO vault_payload (id, nonce, ciphertext, updated_at_ms)
            VALUES (1, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                nonce = excluded.nonce,
                ciphertext = excluded.ciphertext,
                updated_at_ms = excluded.updated_at_ms
            """))
        {
            statement.setBytes(1, nonce);
            statement.setBytes(2, ciphertext);
            statement.setLong(3, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    private List<VoiceEncryptionKey> decryptPayload(byte[] key, Payload payload)
        throws GeneralSecurityException, IOException
    {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
            new GCMParameterSpec(GCM_TAG_BITS, payload.nonce()));
        byte[] json = cipher.doFinal(payload.ciphertext());
        List<VoiceEncryptionKey> keys = mObjectMapper.readValue(new String(json, StandardCharsets.UTF_8),
            new TypeReference<>() {});
        return copy(keys);
    }

    private Payload readPayload(Connection connection) throws SQLException, EncryptionKeyVaultException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT nonce, ciphertext FROM vault_payload WHERE id = 1
            """);
            ResultSet resultSet = statement.executeQuery())
        {
            if(resultSet.next())
            {
                return new Payload(resultSet.getBytes("nonce"), resultSet.getBytes("ciphertext"));
            }
        }

        throw new EncryptionKeyVaultException("Encryption vault payload is missing.");
    }

    private boolean isVaultInitialized()
    {
        if(!Files.isRegularFile(mVaultPath))
        {
            return false;
        }

        try(Connection connection = open())
        {
            return hasPayload(connection) && getMetadata(connection, "schema_version") != null;
        }
        catch(IOException | SQLException e)
        {
            return false;
        }
    }

    private boolean hasPayload(Connection connection) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT 1 FROM vault_payload WHERE id = 1
            """);
            ResultSet resultSet = statement.executeQuery())
        {
            return resultSet.next();
        }
    }

    private String vaultMissingStatus()
    {
        if(Files.isRegularFile(mVaultPath))
        {
            return "Encryption vault schema exists; vault password has not been created";
        }

        return "Encryption vault schema missing";
    }

    private void setMetadata(Connection connection, String key, String value) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO vault_metadata (key, value)
            VALUES (?, ?)
            ON CONFLICT(key) DO UPDATE SET value = excluded.value
            """))
        {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.executeUpdate();
        }
    }

    private String getMetadata(Connection connection, String key) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT value FROM vault_metadata WHERE key = ?
            """))
        {
            statement.setString(1, key);

            try(ResultSet resultSet = statement.executeQuery())
            {
                if(resultSet.next())
                {
                    return resultSet.getString("value");
                }
            }
        }

        return null;
    }

    private byte[] deriveKey(char[] password, byte[] salt, int iterations) throws GeneralSecurityException
    {
        PBEKeySpec keySpec = new PBEKeySpec(password, salt, iterations, KEY_BITS);

        try
        {
            byte[] key = SecretKeyFactory.getInstance(KDF).generateSecret(keySpec).getEncoded();

            if(key.length != KEY_BITS / 8)
            {
                throw new GeneralSecurityException("Unexpected encryption vault key length.");
            }

            return key;
        }
        finally
        {
            keySpec.clearPassword();
        }
    }

    private void requireUnlocked() throws EncryptionKeyVaultException
    {
        if(!isUnlocked() || mVaultKey == null)
        {
            throw new EncryptionKeyVaultException("Encryption vault is locked.");
        }
    }

    private void requirePassword(char[] password) throws EncryptionKeyVaultException
    {
        if(password == null || password.length == 0)
        {
            throw new EncryptionKeyVaultException("Encryption vault password is required.");
        }
    }

    private void setVaultKey(byte[] key)
    {
        clearVaultKey();
        mVaultKey = Arrays.copyOf(key, key.length);
    }

    private void clearVaultKey()
    {
        if(mVaultKey != null)
        {
            Arrays.fill(mVaultKey, (byte)0);
            mVaultKey = null;
        }
    }

    private List<VoiceEncryptionKey> copy(Collection<VoiceEncryptionKey> keys)
    {
        List<VoiceEncryptionKey> copy = new ArrayList<>();

        if(keys != null)
        {
            for(VoiceEncryptionKey key: keys)
            {
                copy.add(key.copy());
            }
        }

        return copy;
    }

    private byte[] randomBytes(int length)
    {
        byte[] bytes = new byte[length];
        mSecureRandom.nextBytes(bytes);
        return bytes;
    }

    private boolean hasSavedPasswordStored()
    {
        String savedPassword = mPreferences.get(KEY_SAVED_PASSWORD, null);
        return savedPassword != null && !savedPassword.isBlank();
    }

    private char[] getSavedPassword()
    {
        String savedPassword = mPreferences.get(KEY_SAVED_PASSWORD, null);
        return savedPassword == null ? null : savedPassword.toCharArray();
    }

    private void savePassword(char[] password)
    {
        mPreferences.put(KEY_SAVED_PASSWORD, new String(password));
        mSavedPasswordPresent.set(true);
    }

    private void setState(EncryptionKeyVaultState state, String status)
    {
        Runnable updater = () -> {
            mState.set(state);
            mStatus.set(status);
            mSavedPasswordPresent.set(hasSavedPasswordStored());
        };

        try
        {
            if(Platform.isFxApplicationThread())
            {
                updater.run();
            }
            else
            {
                Platform.runLater(updater);
            }
        }
        catch(IllegalStateException e)
        {
            updater.run();
        }
    }

    private static void clear(char[] password)
    {
        if(password != null)
        {
            Arrays.fill(password, '\0');
        }
    }

    private static void clear(byte[] bytes)
    {
        if(bytes != null)
        {
            Arrays.fill(bytes, (byte)0);
        }
    }

    private static String encode(byte[] bytes)
    {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] decode(String text)
    {
        return Base64.getDecoder().decode(text);
    }

    private static int parseInt(String value, int fallback)
    {
        try
        {
            return Integer.parseInt(value);
        }
        catch(Exception e)
        {
            return fallback;
        }
    }

    private record Payload(byte[] nonce, byte[] ciphertext) {}
    private record VaultMaterial(byte[] key, List<VoiceEncryptionKey> keys) {}
}
