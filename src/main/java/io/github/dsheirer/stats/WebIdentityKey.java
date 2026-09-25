/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.module.decode.traffic.RadioSystemIdentityKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Creates an opaque identity key for observations whose radio-system owner is not yet proven.
 *
 * <p>The configuration ID is part of the digest, so equal numeric identities on two saved channels remain distinct.
 * These keys are presentation/session identity only: they are never accepted as canonical navigation references.
 * A later catalog mapping replaces them with the canonical {@code RadioSystemIdentityKey}; clients can reconcile the
 * change using the separately supplied observed local/native ID.</p>
 */
final class WebIdentityKey
{
    private static final HexFormat HEX = HexFormat.of();
    private static final ThreadLocal<MessageDigest> SHA_256 = ThreadLocal.withInitial(WebIdentityKey::sha256);

    private WebIdentityKey()
    {
    }

    static String channelScoped(String configurationId, String protocol, Form form, int observedLocalId,
                                Integer homeWacn, Integer homeSystemId, Integer homeIdentityId)
    {
        String identityHint = null;
        if(homeWacn != null && homeSystemId != null && homeIdentityId != null)
        {
            try
            {
                identityHint = RadioSystemIdentityKey.format(kind(form), homeWacn, homeSystemId, homeIdentityId);
            }
            catch(IllegalArgumentException exception)
            {
                //The semantic gate normally excludes invalid tuples. Retain a collision-free local fallback if a
                //future decoder supplies one so that projection cannot disrupt the bounded observer worker.
                identityHint = nullable(homeWacn) + ':' + nullable(homeSystemId) + ':' + nullable(homeIdentityId);
            }
        }
        return channelScoped(configurationId, protocol, form, observedLocalId, identityHint);
    }

    static String channelScoped(String configurationId, String protocol, Form form, int observedLocalId,
                                String identityHint)
    {
        if(configurationId == null || configurationId.isBlank() || protocol == null || protocol.isBlank() ||
            (form != Form.RADIO && form != Form.TALKGROUP && form != Form.PATCH_GROUP) || observedLocalId < 0)
        {
            return null;
        }

        String seed = configurationId.strip() + '\0' + protocol.strip().toLowerCase(java.util.Locale.ROOT) + '\0' +
            form.name() + '\0' + observedLocalId + '\0' + (identityHint != null ? identityHint : "x");

        MessageDigest digest = SHA_256.get();
        digest.reset();
        return "v1-local-" + HEX.formatHex(digest.digest(seed.getBytes(StandardCharsets.UTF_8)));
    }

    private static String nullable(Integer value)
    {
        return value != null ? value.toString() : "x";
    }

    private static int kind(Form form)
    {
        return switch(form)
        {
            case RADIO -> RadioSystemIdentityKey.KIND_RADIO;
            case TALKGROUP -> RadioSystemIdentityKey.KIND_TALKGROUP;
            case PATCH_GROUP -> RadioSystemIdentityKey.KIND_PATCH_GROUP;
            case null, default -> throw new IllegalArgumentException("Unsupported identity form");
        };
    }

    private static MessageDigest sha256()
    {
        try
        {
            return MessageDigest.getInstance("SHA-256");
        }
        catch(NoSuchAlgorithmException exception)
        {
            //SHA-256 is mandatory in every supported Java runtime.
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
