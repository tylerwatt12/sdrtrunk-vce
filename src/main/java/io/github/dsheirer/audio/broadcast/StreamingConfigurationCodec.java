/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.broadcast;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.audio.broadcast.broadcastify.*;
import io.github.dsheirer.audio.broadcast.icecast.*;
import io.github.dsheirer.audio.broadcast.openmhz.OpenMHzConfiguration;
import io.github.dsheirer.audio.broadcast.radioresolve.RadioResolveConfiguration;
import io.github.dsheirer.audio.broadcast.rdioscanner.RdioScannerConfiguration;
import io.github.dsheirer.audio.broadcast.shoutcast.v1.ShoutcastV1Configuration;
import java.net.URI;
import java.util.*;

/** Code-owned provider fields. Never serialize a provider model into a web response: it contains credentials. */
public final class StreamingConfigurationCodec
{
    private static final ObjectMapper COPIER = new ObjectMapper()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public record Field(String key, String label, String type, long minimum, long maximum, boolean advanced,
                        List<String> choices) {}
    private record Binding(Field field, String getter, String setter, Class<?> valueType) {}
    public record Provider(String id, String label, boolean connectionTest, List<Field> fields) {}
    public record Definition(String configurationId, String provider, Map<String,Object> settings,
                             Set<String> configuredCredentials) {}

    public static BroadcastConfiguration template(String provider)
    {
        BroadcastServerType type;
        try { type = BroadcastServerType.valueOf(provider); }
        catch(Exception exception) { throw invalid("Choose a supported streaming provider"); }
        BroadcastConfiguration configuration = switch(type)
        {
            case BROADCASTIFY -> new BroadcastifyFeedConfiguration();
            case BROADCASTIFY_CALL -> new BroadcastifyCallConfiguration();
            case BROADCASTIFY_CALL_SITE -> new BroadcastifyCallSiteConfiguration();
            case ICECAST_HTTP -> new IcecastHTTPConfiguration();
            case ICECAST_TCP -> new IcecastTCPConfiguration();
            case SHOUTCAST_V1 -> new ShoutcastV1Configuration();
            case RDIOSCANNER_CALL -> new RdioScannerConfiguration();
            case OPENMHZ -> new OpenMHzConfiguration();
            case RADIORESOLVE -> new RadioResolveConfiguration();
            default -> throw invalid("Choose a supported streaming provider");
        };
        configuration.setPassword(null);
        return configuration;
    }

    public static List<Provider> providers()
    {
        return Arrays.stream(BroadcastServerType.values()).filter(type -> type != BroadcastServerType.UNKNOWN)
            .map(type -> new Provider(type.name(), type.toString(), testable(type),
                bindings(template(type.name())).stream().map(Binding::field).toList())).toList();
    }

    public static boolean testable(BroadcastServerType type)
    {
        return type == BroadcastServerType.BROADCASTIFY_CALL || type == BroadcastServerType.BROADCASTIFY_CALL_SITE;
    }

    public static BroadcastConfiguration copy(BroadcastConfiguration source)
    {
        try { return COPIER.readValue(COPIER.writerFor(BroadcastConfiguration.class).writeValueAsBytes(source), BroadcastConfiguration.class); }
        catch(Exception exception) { throw new IllegalStateException("Unable to copy streaming configuration", exception); }
    }

    public static Definition view(BroadcastConfiguration configuration)
    {
        Map<String,Object> values = new LinkedHashMap<>();
        Set<String> credentials = new LinkedHashSet<>();
        for(Binding binding: bindings(configuration))
        {
            Object value = read(configuration, binding);
            if(binding.field().type().equals("password"))
            {
                if(value instanceof String text && !text.isEmpty()) credentials.add(binding.field().key());
            }
            else values.put(binding.field().key(), value instanceof Enum<?> item ? item.name() : value);
        }
        return new Definition(configuration.getConfigurationId(), configuration.getBroadcastServerType().name(),
            values, credentials);
    }

    /** Partial updates retain all omitted values, including secrets and unexposed encoding settings. */
    public static BroadcastConfiguration apply(BroadcastConfiguration source, Map<String,Object> changes)
    {
        if(changes == null) throw invalid("Streaming settings are required");
        BroadcastConfiguration candidate = copy(source);
        Map<String,Binding> allowed = new LinkedHashMap<>();
        bindings(candidate).forEach(binding -> allowed.put(binding.field().key(), binding));
        for(Map.Entry<String,Object> change: changes.entrySet())
        {
            Binding binding = allowed.get(change.getKey());
            if(binding == null) throw invalid("Unsupported streaming setting");
            Object value = checked(binding, change.getValue());
            try { candidate.getClass().getMethod(binding.setter(), binding.valueType()).invoke(candidate, value); }
            catch(ReflectiveOperationException exception) { throw invalid("Unable to apply " + binding.field().label()); }
        }
        if(candidate.getName() == null || candidate.getName().isBlank()) throw invalid("Name is required");
        if(candidate.getHost() != null && !candidate.getHost().isBlank()) validateHost(candidate);
        if(candidate.isEnabled())
        {
            if(!candidate.isValid() || candidate.getHost() == null || candidate.getHost().isBlank())
                throw invalid("Complete the connection settings before enabling this destination");
            for(Binding binding: allowed.values())
            {
                if(binding.field().type().equals("password") &&
                    (!(read(candidate, binding) instanceof String text) || text.isBlank()))
                    throw invalid(binding.field().label() + " is required before enabling this destination");
            }
        }
        return candidate;
    }

    private static void validateHost(BroadcastConfiguration candidate)
    {
        String host = candidate.getHost();
        if(host.chars().anyMatch(Character::isWhitespace)) throw invalid("Server address cannot contain whitespace");
        boolean http = !(candidate instanceof IcecastConfiguration || candidate instanceof ShoutcastV1Configuration);
        if(http)
        {
            try
            {
                URI uri = URI.create(host);
                if(!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null ||
                    uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null)
                    throw invalid("Use an HTTP or HTTPS service address without credentials, query, or fragment");
            }
            catch(IllegalArgumentException exception) { throw invalid("Enter a valid HTTP or HTTPS service address"); }
        }
        else if(host.contains("/") || host.contains("@")) throw invalid("Enter a server hostname without a URL scheme");
    }

    private static Object checked(Binding binding, Object value)
    {
        Field field = binding.field();
        if(binding.valueType() == boolean.class)
        {
            if(!(value instanceof Boolean)) throw invalid(field.label() + " must be enabled or disabled");
            return value;
        }
        if(binding.valueType() == int.class || binding.valueType() == long.class)
        {
            if(!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long))
                throw invalid(field.label() + " must be a whole number");
            long number = ((Number)value).longValue();
            if(number < field.minimum() || number > field.maximum()) throw invalid(field.label() + " is out of range");
            if(binding.valueType() == int.class) return Integer.valueOf((int)number);
            return Long.valueOf(number);
        }
        if(binding.valueType() == RadioResolveConfiguration.Mode.class)
        {
            if(!(value instanceof String text) || !field.choices().contains(text)) throw invalid("Choose a publishing mode");
            return RadioResolveConfiguration.Mode.valueOf((String)value);
        }
        if(!(value instanceof String text) || text.length() > field.maximum() || text.indexOf('\0') >= 0 ||
            text.contains("\r") || text.contains("\n")) throw invalid(field.label() + " is invalid or too long");
        return field.type().equals("password") ? text : text.trim();
    }

    private static Object read(BroadcastConfiguration configuration, Binding binding)
    {
        try { return configuration.getClass().getMethod(binding.getter()).invoke(configuration); }
        catch(ReflectiveOperationException exception) { throw new IllegalStateException("Unable to read streaming setting"); }
    }

    private static List<Binding> bindings(BroadcastConfiguration configuration)
    {
        List<Binding> fields = new ArrayList<>();
        fields.add(text("name", "Name", "Name", false));
        fields.add(flag("enabled", "Enable this destination", "isEnabled", "Enabled", false));
        fields.add(text("host", "Server address", "Host", false));
        fields.add(number("maximum_recording_age", "Maximum queued call age (milliseconds)", "MaximumRecordingAge", 0,
            604800000L, long.class, true));
        if(configuration instanceof IcecastConfiguration || configuration instanceof ShoutcastV1Configuration)
        {
            fields.add(number("port", "Port", "Port", 1, 65535, int.class, false));
            fields.add(secret("password", "Password", "Password"));
            fields.add(number("delay", "Delivery delay (milliseconds)", "Delay", 0, 604800000L, long.class, true));
        }
        else fields.add(secret("api_key", "API key", "ApiKey"));
        if(configuration instanceof BroadcastifyCallConfiguration)
        {
            fields.add(number("system_id", "System ID", "SystemID", 0, Integer.MAX_VALUE, int.class, false));
            fields.add(flag("test_enabled", "Send periodic keep-alive", "isTestEnabled", "TestEnabled", true));
            fields.add(number("test_interval", "Keep-alive interval (minutes)", "TestInterval", 1, 1440, int.class, true));
        }
        if(configuration instanceof BroadcastifyCallSiteConfiguration)
        {
            fields.add(number("alias_list_id", "Alias List", "AliasListId", 1, 9007199254740991L, long.class, false));
            fields.add(text("channel_configuration_id", "Trunked site", "ChannelConfigurationId", false));
        }
        if(configuration instanceof RdioScannerConfiguration)
            fields.add(number("system_id", "System ID", "SystemID", 0, Integer.MAX_VALUE, int.class, false));
        if(configuration instanceof OpenMHzConfiguration) fields.add(text("system_name", "System short name", "SystemName", false));
        if(configuration instanceof RadioResolveConfiguration)
        {
            fields.add(new Binding(new Field("mode", "Publishing mode", "select", 0, 0, false,
                Arrays.stream(RadioResolveConfiguration.Mode.values()).map(Enum::name).toList()), "getMode", "setMode",
                RadioResolveConfiguration.Mode.class));
            fields.add(flag("ignore_certificate_errors", "Ignore certificate errors", "isIgnoreCertificateErrors",
                "IgnoreCertificateErrors", true));
        }
        if(configuration instanceof IcecastConfiguration)
        {
            fields.add(text("mount_point", "Mount point", "MountPoint", false));
            if(!(configuration instanceof BroadcastifyFeedConfiguration))
            {
                fields.add(text("user_name", "Username", "UserName", false));
                fields.add(flag("inline", "Inline metadata", "getInline", "Inline", true));
                fields.add(text("url", "Website URL", "URL", true));
            }
        }
        if(configuration instanceof BroadcastifyFeedConfiguration)
        {
            fields.add(number("feed_id", "Feed ID", "FeedID", 0, Integer.MAX_VALUE, int.class, false));
            fields.add(flag("verbose_logging", "Verbose logging", "isVerboseLogging", "VerboseLogging", true));
        }
        else if(configuration instanceof IcecastConfiguration || configuration instanceof ShoutcastV1Configuration)
        {
            fields.add(text("description", "Description", "Description", true));
            fields.add(text("genre", "Genre", "Genre", true));
            fields.add(flag("public", "Public listing", "isPublic", "Public", true));
        }
        return List.copyOf(fields);
    }

    private static Binding text(String key, String label, String property, boolean advanced)
    { return new Binding(new Field(key,label,"text",0,1024,advanced,List.of()), "get"+property,"set"+property,String.class); }
    private static Binding secret(String key, String label, String property)
    { return new Binding(new Field(key,label,"password",0,4096,false,List.of()),"get"+property,"set"+property,String.class); }
    private static Binding flag(String key, String label, String getter, String property, boolean advanced)
    { return new Binding(new Field(key,label,"boolean",0,0,advanced,List.of()),getter,"set"+property,boolean.class); }
    private static Binding number(String key, String label, String property, long min, long max, Class<?> type, boolean advanced)
    { return new Binding(new Field(key,label,"number",min,max,advanced,List.of()),"get"+property,"set"+property,type); }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
