/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.web.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceDecryptionModuleManager;
import io.github.dsheirer.preference.encryption.EncryptionKeyPreference;
import io.github.dsheirer.preference.encryption.VoiceEncryptionAlgorithm;
import io.github.dsheirer.preference.encryption.VoiceEncryptionKey;
import io.github.dsheirer.preference.encryption.VoiceEncryptionProtocol;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Administrator-only voice-key configuration using the receiver's live key preference. */
public final class EncryptionKeysHttpController
{
    public static final String PATH = "/api/v1/admin/encryption-keys";
    private static final Set<String> FIELDS = Set.of("label", "enabled", "protocol", "algorithm_id",
        "key_id", "key_hex", "scope");
    private static final Pattern REVISION = Pattern.compile("\"([0-9a-f]{64})\"");
    private static final Logger LOG = LoggerFactory.getLogger(EncryptionKeysHttpController.class);
    private final EncryptionKeyPreference mPreference;
    private final VoiceDecryptionModuleManager mModule;

    public EncryptionKeysHttpController(EncryptionKeyPreference preference, VoiceDecryptionModuleManager module)
    {
        mPreference = Objects.requireNonNull(preference);
        mModule = Objects.requireNonNull(module);
    }

    public void handle(HttpExchange exchange) throws IOException
    {
        WebRequestSecurity.prepareSecurityHeaders(exchange);
        exchange.getResponseHeaders().set("Vary", "Cookie");
        if(!WebHttpSupport.requireNoQuery(exchange)) return;
        String path = exchange.getRequestURI().getRawPath();
        boolean collection = PATH.equals(path);
        String id = itemId(exchange);
        if(!collection && id == null)
        {
            WebHttpSupport.notFound(exchange);
            return;
        }
        try
        {
            String method = exchange.getRequestMethod();
            if(collection && "GET".equals(method))
            {
                requireEmptyBody(exchange);
                send(exchange, 200, mPreference.snapshot());
                return;
            }
            if(collection && !"POST".equals(method) || !collection && !Set.of("PUT", "DELETE").contains(method))
            {
                WebHttpSupport.methodNotAllowed(exchange, collection ? "GET, POST" : "PUT, DELETE");
                return;
            }
            String revision = requireRevision(exchange);
            EncryptionKeyPreference.UpdateResult result;
            if("DELETE".equals(method))
            {
                requireEmptyBody(exchange);
                result = mPreference.remove(revision, id);
            }
            else
            {
                VoiceEncryptionKey key = readKey(exchange, id);
                result = collection ? mPreference.create(revision, key) : mPreference.update(revision, key);
            }
            switch(result.status())
            {
                case CONFLICT -> send(exchange, 409, result.snapshot());
                case NOT_FOUND -> ApiHttpResponse.sendError(exchange, 404, "encryption_key_not_found",
                    "This key no longer exists. Reload the key list.");
                case UPDATED -> send(exchange, collection ? 201 : 200, result.snapshot());
            }
        }
        catch(WebHttpSupport.RequestException failure)
        {
            // An oversized request was rejected before its body was drained. Do not let the next
            // unsafe request reuse a connection that the HTTP server must close after its bounded drain.
            if(failure.status() == 413) exchange.getResponseHeaders().set("Connection", "close");
            ApiHttpResponse.sendError(exchange, failure.status(), failure.code(), failure.getMessage());
        }
        catch(IllegalArgumentException failure)
        {
            ApiHttpResponse.sendError(exchange, 422, "invalid_encryption_key", failure.getMessage());
        }
        catch(RuntimeException failure)
        {
            // Exception messages and nested causes may contain entered key material.
            LOG.warn("Voice encryption key request failed ({})", failure.getClass().getSimpleName());
            ApiHttpResponse.sendError(exchange, 500, "encryption_keys_failed",
                "GET".equals(exchange.getRequestMethod()) ?
                    "The keys could not be loaded. Reload the key list and try again." :
                    "The keys could not be saved. Try again.");
        }
    }

    private static String itemId(HttpExchange exchange)
    {
        String raw = exchange.getRequestURI().getRawPath();
        if(!raw.startsWith(PATH + "/")) return null;
        String segment = raw.substring(PATH.length() + 1);
        if(segment.isEmpty() || segment.indexOf('/') >= 0) return null;
        // URI path decoding retains literal '+' and admits an encoded slash in an existing opaque identifier.
        String id = exchange.getRequestURI().getPath().substring(PATH.length() + 1);
        return id.isBlank() || id.length() > 128 ? null : id;
    }

    private VoiceEncryptionKey readKey(HttpExchange exchange, String id)
        throws IOException, WebHttpSupport.RequestException
    {
        JsonNode request = WebHttpSupport.readJsonObject(exchange, FIELDS);
        if(request.size() != FIELDS.size())
            throw new IllegalArgumentException("The key details are incomplete. Reload and try again.");
        String label = optionalText(request, "label", "Label");
        String scope = optionalText(request, "scope", "Scope");
        JsonNode enabled = request.get("enabled");
        if(enabled == null || !enabled.isBoolean())
            throw new IllegalArgumentException("Choose whether this key is enabled.");
        JsonNode protocolValue = request.get("protocol");
        VoiceEncryptionProtocol protocol;
        try
        {
            if(protocolValue == null || !protocolValue.isTextual()) throw new IllegalArgumentException();
            protocol = VoiceEncryptionProtocol.valueOf(protocolValue.textValue());
        }
        catch(IllegalArgumentException failure)
        {
            throw new IllegalArgumentException("Select a supported protocol.");
        }
        int algorithmId = integer(request, "algorithm_id", 255, "Select a supported algorithm.");
        VoiceEncryptionAlgorithm algorithm = VoiceEncryptionAlgorithm.fromValue(protocol, algorithmId);
        if(algorithm == null || !mModule.supports(algorithm))
            throw new IllegalArgumentException("Select a supported algorithm for this protocol.");
        int keyId = integer(request, "key_id", 65535, "Enter a key ID from 0 to 65535.");
        JsonNode hex = request.get("key_hex");
        if(hex == null || !hex.isTextual() || hex.textValue().length() > 4096 ||
            !VoiceEncryptionKey.isValidHexKey(hex.textValue()))
            throw new IllegalArgumentException("Enter a non-empty key as hexadecimal pairs.");
        String keyHex = VoiceEncryptionKey.normalizeKeyHex(hex.textValue());
        int bytes = keyHex.length() / 2;
        int expected = algorithm.getExpectedKeyBytes();
        if(algorithm == VoiceEncryptionAlgorithm.APCO25_ADP ? bytes > expected : bytes != expected)
            throw new IllegalArgumentException(algorithm == VoiceEncryptionAlgorithm.APCO25_ADP ?
                "ADP keys must contain 1 to 5 bytes." : "This algorithm requires a " + expected + "-byte key.");
        VoiceEncryptionKey key = new VoiceEncryptionKey(id);
        key.setLabel(label);
        key.setEnabled(enabled.booleanValue());
        key.setProtocol(protocol);
        key.setAlgorithmId(algorithmId);
        key.setKeyId(keyId);
        key.setKeyHex(keyHex);
        key.setScope(scope);
        return key;
    }

    private static String optionalText(JsonNode request, String field, String label)
    {
        JsonNode value = request.get(field);
        if(value != null && value.isNull()) return null;
        if(value == null || !value.isTextual() || value.textValue().length() > 4096)
            throw new IllegalArgumentException(label + " must contain at most 4096 characters.");
        return value.textValue();
    }

    private static int integer(JsonNode request, String field, int maximum, String message)
    {
        JsonNode value = request.get(field);
        if(value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0 ||
            value.intValue() > maximum) throw new IllegalArgumentException(message);
        return value.intValue();
    }

    private static String requireRevision(HttpExchange exchange) throws WebHttpSupport.RequestException
    {
        List<String> values = exchange.getRequestHeaders().get("If-Match");
        if(values == null || values.isEmpty())
            throw new WebHttpSupport.RequestException(428, "revision_required", "Reload the key list before saving.");
        if(values.size() != 1)
            throw new WebHttpSupport.RequestException(400, "invalid_revision", "Reload the key list before saving.");
        var match = REVISION.matcher(values.getFirst());
        if(!match.matches())
            throw new WebHttpSupport.RequestException(400, "invalid_revision", "Reload the key list before saving.");
        return match.group(1);
    }

    private static void requireEmptyBody(HttpExchange exchange) throws WebHttpSupport.RequestException
    {
        if(WebHttpSupport.hasRequestBody(exchange))
            throw new WebHttpSupport.RequestException(400, "invalid_request", "This request cannot include a body.");
    }

    private void send(HttpExchange exchange, int status, EncryptionKeyPreference.Snapshot snapshot) throws IOException
    {
        exchange.getResponseHeaders().set("ETag", '"' + snapshot.revision() + '"');
        ApiHttpResponse.sendDocument(exchange, status, new Document(snapshot.revision(), snapshot.keys().stream()
            .map(key -> new Key(key.getId(), key.getLabel(), key.isEnabled(), key.getProtocol().name(),
                key.getAlgorithmId(), key.getKeyId(), key.getKeyHex(), key.getScope())).toList(),
            Arrays.stream(VoiceEncryptionProtocol.values()).map(protocol -> new Protocol(protocol.name(),
                protocol == VoiceEncryptionProtocol.APCO25 ? "P25" : protocol.toString(),
                mModule.getSupportedAlgorithms(protocol).stream().map(algorithm -> new Algorithm(algorithm.getValue(),
                    algorithm.getFullLabel(), algorithm.getExpectedKeyBytes(),
                    algorithm == VoiceEncryptionAlgorithm.APCO25_ADP ? 1 : algorithm.getExpectedKeyBytes(),
                    algorithm.getExpectedKeyBytes())).toList())).toList()));
    }

    public record Document(String revision, List<Key> keys, List<Protocol> protocols) {}
    public record Key(String id, String label, boolean enabled, String protocol, int algorithmId,
                      int keyId, String keyHex, String scope) {}
    public record Protocol(String id, String label, List<Algorithm> algorithms) {}
    public record Algorithm(int id, String label, int keyBytes, int minimumKeyBytes, int maximumKeyBytes) {}
}
