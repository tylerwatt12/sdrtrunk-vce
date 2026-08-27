/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.dsheirer.web.auth.AccessTier;
import io.github.dsheirer.web.auth.Pbkdf2PasswordHasher;
import io.github.dsheirer.web.auth.WebPasswordVerifier;
import java.nio.file.Path;
import java.sql.DriverManager;

/** Writes the exact format-4 web.access.v1 document used by migration tests. */
final class LegacyWebAccessTestData
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private LegacyWebAccessTestData()
    {
    }

    static void storePrimaryAdmin(Path database, char[] password) throws Exception
    {
        store(database, password, null, null, null);
    }

    static void storePrimaryAdminAndUser(Path database, char[] adminPassword, String username,
                                         char[] userPassword, AccessTier tier) throws Exception
    {
        store(database, adminPassword, username, userPassword, tier);
    }

    private static void store(Path database, char[] adminPassword, String username, char[] userPassword,
                              AccessTier tier) throws Exception
    {
        Pbkdf2PasswordHasher hasher = new Pbkdf2PasswordHasher();
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.put("formatVersion", 1);
        root.set("primaryAdmin", credential(hasher.createVerifier("admin", adminPassword, 1)));
        var users = root.putArray("users");

        if(username != null)
        {
            ObjectNode user = users.addObject();
            user.put("tier", tier.name());
            user.set("credential", credential(hasher.createVerifier(username, userPassword, 1)));
        }

        root.putObject("policyOverrides");

        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var statement = connection.prepareStatement("""
                INSERT INTO application_settings(key, settings_json, updated_at_ms)
                VALUES ('web.access.v1', ?, 1)
                """))
        {
            statement.setString(1, OBJECT_MAPPER.writeValueAsString(root));
            statement.executeUpdate();
        }
    }

    private static ObjectNode credential(WebPasswordVerifier verifier)
    {
        ObjectNode node = OBJECT_MAPPER.createObjectNode();
        node.put("version", verifier.version());
        node.put("username", verifier.username());
        node.put("algorithm", verifier.algorithm());
        node.put("iterations", verifier.iterations());
        node.put("derivedKeyBits", verifier.derivedKeyBits());
        node.put("saltBase64", verifier.saltBase64());
        node.put("passwordHashBase64", verifier.passwordHashBase64());
        node.put("passwordChangedAtEpochMillis", verifier.passwordChangedAtEpochMillis());
        node.put("credentialVersion", verifier.authRevision());
        return node;
    }
}
