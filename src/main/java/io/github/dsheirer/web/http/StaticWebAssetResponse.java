/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.http;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

/** Reusable, bounded representations for static browser files. Never caches receiver data. */
public final class StaticWebAssetResponse
{
    private static final long MAXIMUM_CACHE_BYTES = 16L * 1024 * 1024;
    private static final int MAXIMUM_CACHE_ENTRIES = 96;
    private static final long MAXIMUM_ASSET_BYTES = 4L * 1024 * 1024;
    private static final String PUBLIC_ORIGIN_MARKER = "__VCE_PUBLIC_ORIGIN__";
    private static final Pattern HTTP_AUTHORITY = Pattern.compile(
        "(?:[A-Za-z0-9.-]+|\\[[A-Fa-f0-9:.]+\\])(?::[0-9]{1,5})?");
    private final long mMaximumCacheBytes;
    private final int mMaximumCacheEntries;
    private final long mMaximumAssetBytes;
    private final Map<AssetKey,Asset> mCache = new LinkedHashMap<>(16, 0.75f, true);
    private long mCacheBytes;

    public StaticWebAssetResponse()
    {
        this(MAXIMUM_CACHE_BYTES, MAXIMUM_CACHE_ENTRIES, MAXIMUM_ASSET_BYTES);
    }

    StaticWebAssetResponse(long maximumCacheBytes, int maximumCacheEntries, long maximumAssetBytes)
    {
        if(maximumCacheBytes < 0 || maximumCacheEntries < 0 || maximumAssetBytes < 0 ||
            maximumAssetBytes >= Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid asset cache bounds");
        mMaximumCacheBytes = maximumCacheBytes;
        mMaximumCacheEntries = maximumCacheEntries;
        mMaximumAssetBytes = maximumAssetBytes;
    }

    /** The caller resolves the public asset path and prepares security headers before calling this method. */
    public void send(HttpExchange exchange, Path file, String contentType, String webClientRevision) throws IOException
    {
        send(exchange, file, contentType, webClientRevision, null);
    }

    /** Render only the public document's origin markers; never use forwarding headers or receiver data. */
    public void sendIndex(HttpExchange exchange, Path file, String contentType, String webClientRevision)
        throws IOException
    {
        String origin = publicOrigin(WebRequestSecurity.isSecureTransport(exchange),
            exchange.getRequestHeaders().get("Host"));
        if(origin == null)
        {
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
            return;
        }
        send(exchange, file, contentType, webClientRevision, origin);
    }

    static String publicOrigin(boolean secure, List<String> authorities)
    {
        if(authorities == null || authorities.size() != 1) return null;
        String authority = authorities.getFirst();
        if(authority == null || authority.length() > 320 || !HTTP_AUTHORITY.matcher(authority).matches()) return null;
        try
        {
            String scheme = secure ? "https" : "http";
            URI origin = URI.create(scheme + "://" + authority);
            int port = origin.getPort();
            if(origin.getHost() == null || origin.getRawUserInfo() != null ||
                !origin.getRawPath().isEmpty() || origin.getRawQuery() != null || origin.getRawFragment() != null ||
                port == 0 || port > 65_535) return null;
            boolean defaultPort = port == (secure ? 443 : 80);
            return scheme + "://" + origin.getHost().toLowerCase(Locale.ROOT) +
                (port >= 0 && !defaultPort ? ":" + port : "");
        }
        catch(IllegalArgumentException exception)
        {
            return null;
        }
    }

    private void send(HttpExchange exchange, Path file, String contentType, String webClientRevision,
                      String publicOrigin) throws IOException
    {
        Asset asset = asset(file.toAbsolutePath().normalize(), compressible(contentType), publicOrigin);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType);
        // Even version query values are reused during local previews. Revalidate rather than assume immutability.
        headers.set("Cache-Control", "no-cache");
        headers.set("Vary", "Accept-Encoding");
        if(webClientRevision != null) headers.set("X-Sdrtrunk-Web-Revision", webClientRevision);

        Encoding encoding = encoding(exchange.getRequestHeaders().get("Accept-Encoding"), asset);
        if(encoding == Encoding.UNACCEPTABLE)
        {
            exchange.sendResponseHeaders(406, -1);
            exchange.close();
            return;
        }
        boolean gzip = encoding == Encoding.GZIP;
        byte[] bytes = gzip ? asset.gzip() : asset.identity();
        String etag = gzip ? asset.gzipEtag() : asset.etag();
        headers.set("ETag", etag);
        if(gzip) headers.set("Content-Encoding", "gzip");
        if(matches(exchange.getRequestHeaders().get("If-None-Match"), etag))
        {
            exchange.sendResponseHeaders(304, -1);
            exchange.close();
            return;
        }

        long length = bytes != null ? bytes.length : asset.stamp().size();
        boolean head = "HEAD".equals(exchange.getRequestMethod());
        headers.set("Content-Length", Long.toString(length));
        exchange.sendResponseHeaders(200, head || length == 0 ? -1 : length);
        try
        {
            if(!head && length > 0)
            {
                try(OutputStream output = exchange.getResponseBody())
                {
                    if(bytes != null) output.write(bytes);
                    else Files.copy(file, output);
                }
            }
        }
        finally
        {
            exchange.close();
        }
    }

    private synchronized Asset asset(Path file, boolean compressible, String publicOrigin) throws IOException
    {
        AssetKey key = new AssetKey(file, publicOrigin);
        FileStamp stamp;
        try
        {
            stamp = stamp(file);
        }
        catch(IOException exception)
        {
            remove(file);
            throw exception;
        }
        Asset previous = mCache.get(key);
        if(previous != null && previous.stamp().equals(stamp)) return previous;
        for(var iterator = mCache.entrySet().iterator(); iterator.hasNext();)
        {
            var entry = iterator.next();
            if(entry.getKey().file().equals(file) && !entry.getValue().stamp().equals(stamp))
            {
                mCacheBytes -= entry.getValue().bytes();
                iterator.remove();
            }
        }
        // Large files stream directly; they cannot occupy the browser-script compression cache.
        if(stamp.size() > mMaximumAssetBytes)
        {
            if(publicOrigin != null) throw new IOException("Public web document exceeds the bounded asset size");
            return new Asset(stamp, null, null, "W/" + etag(stamp.toString().getBytes(StandardCharsets.UTF_8)), null);
        }

        byte[] identity;
        try(InputStream input = Files.newInputStream(file))
        {
            identity = input.readNBytes((int)mMaximumAssetBytes + 1);
        }
        FileStamp afterRead = stamp(file);
        if(identity.length > mMaximumAssetBytes)
        {
            if(publicOrigin != null) throw new IOException("Public web document exceeds the bounded asset size");
            return new Asset(afterRead, null, null,
                "W/" + etag(afterRead.toString().getBytes(StandardCharsets.UTF_8)), null);
        }
        if(publicOrigin != null)
        {
            String escapedOrigin = publicOrigin.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("'", "&#39;").replace("<", "&lt;").replace(">", "&gt;");
            String template = new String(identity, StandardCharsets.UTF_8);
            long renderedBytes = identity.length;
            for(int offset = template.indexOf(PUBLIC_ORIGIN_MARKER); offset >= 0;
                offset = template.indexOf(PUBLIC_ORIGIN_MARKER, offset + PUBLIC_ORIGIN_MARKER.length()))
                renderedBytes += escapedOrigin.length() - PUBLIC_ORIGIN_MARKER.length();
            if(renderedBytes > mMaximumAssetBytes)
                throw new IOException("Rendered public web document exceeds the bounded asset size");
            identity = template.replace(PUBLIC_ORIGIN_MARKER, escapedOrigin).getBytes(StandardCharsets.UTF_8);
            if(identity.length > mMaximumAssetBytes)
                throw new IOException("Rendered public web document exceeds the bounded asset size");
        }
        if(!stamp.equals(afterRead))
        {
            // Preview replacement raced this request; retry on the next request without caching a mixed snapshot.
            return new Asset(afterRead, identity, null, etag(identity), null);
        }
        byte[] gzip = null;
        if(compressible)
        {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try(GZIPOutputStream compressor = new GZIPOutputStream(output))
            {
                compressor.write(identity);
            }
            gzip = output.toByteArray();
        }
        Asset asset = new Asset(stamp, identity, gzip, etag(identity), gzip != null ? etag(gzip) : null);
        long bytes = asset.bytes();
        if(bytes <= mMaximumCacheBytes && mMaximumCacheEntries > 0)
        {
            while(!mCache.isEmpty() && (mCache.size() >= mMaximumCacheEntries ||
                mCacheBytes + bytes > mMaximumCacheBytes)) remove(mCache.keySet().iterator().next());
            mCache.put(key, asset);
            mCacheBytes += bytes;
        }
        return asset;
    }

    private void remove(Path file)
    {
        for(var iterator = mCache.entrySet().iterator(); iterator.hasNext();)
        {
            var entry = iterator.next();
            if(entry.getKey().file().equals(file))
            {
                mCacheBytes -= entry.getValue().bytes();
                iterator.remove();
            }
        }
    }

    private void remove(AssetKey key)
    {
        Asset asset = mCache.remove(key);
        if(asset != null) mCacheBytes -= asset.bytes();
    }

    /** Release a cached representation when the public asset resolver observes its removal. */
    public synchronized void invalidate(Path file)
    {
        remove(file.toAbsolutePath().normalize());
    }

    synchronized long cachedBytes() { return mCacheBytes; }
    synchronized int cachedEntries() { return mCache.size(); }

    private static FileStamp stamp(Path file) throws IOException
    {
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
        return new FileStamp(attributes.size(), attributes.lastModifiedTime(), attributes.creationTime(),
            attributes.fileKey());
    }

    private static String etag(byte[] bytes)
    {
        try
        {
            return '"' + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)) + '"';
        }
        catch(NoSuchAlgorithmException exception)
        {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static boolean compressible(String contentType)
    {
        String type = Objects.requireNonNullElse(contentType, "").toLowerCase(Locale.ROOT);
        return type.startsWith("text/") || type.startsWith("application/javascript") ||
            type.startsWith("application/json") || type.startsWith("application/manifest+json") ||
            type.startsWith("image/svg+xml");
    }

    private static Encoding encoding(List<String> values, Asset asset)
    {
        if(values == null || values.isEmpty()) return Encoding.IDENTITY;
        double gzip = -1, identity = -1, wildcard = -1;
        for(String value: values)
        {
            for(String part: value.split(","))
            {
                String[] parameters = part.trim().split(";");
                String name = parameters[0].trim().toLowerCase(Locale.ROOT);
                double quality = 1;
                for(int index = 1; index < parameters.length; index++)
                {
                    String parameter = parameters[index].trim();
                    if(parameter.toLowerCase(Locale.ROOT).startsWith("q="))
                    {
                        try { quality = Double.parseDouble(parameter.substring(2).trim()); }
                        catch(NumberFormatException ignored) { quality = 0; }
                        if(!Double.isFinite(quality) || quality < 0 || quality > 1) quality = 0;
                    }
                }
                switch(name)
                {
                    case "gzip" -> gzip = quality;
                    case "identity" -> identity = quality;
                    case "*" -> wildcard = quality;
                    default -> { }
                }
            }
        }
        if(gzip < 0) gzip = Math.max(0, wildcard);
        if(identity < 0) identity = wildcard == 0 ? 0 : 1;
        if(asset.gzip() != null && gzip > 0 && gzip >= identity &&
            (identity == 0 || asset.gzip().length < asset.identity().length)) return Encoding.GZIP;
        if(identity > 0) return Encoding.IDENTITY;
        if(asset.gzip() != null && gzip > 0) return Encoding.GZIP;
        return Encoding.UNACCEPTABLE;
    }

    private static boolean matches(List<String> values, String etag)
    {
        if(values == null) return false;
        String expected = etag.startsWith("W/") ? etag.substring(2) : etag;
        for(String value: values)
        {
            for(String candidate: value.split(","))
            {
                candidate = candidate.trim();
                if(candidate.equals("*")) return true;
                if(candidate.startsWith("W/")) candidate = candidate.substring(2);
                if(candidate.equals(expected)) return true;
            }
        }
        return false;
    }

    private enum Encoding { IDENTITY, GZIP, UNACCEPTABLE }
    private record AssetKey(Path file, String publicOrigin) { }
    private record FileStamp(long size, FileTime modified, FileTime created, Object fileKey) { }
    private record Asset(FileStamp stamp, byte[] identity, byte[] gzip, String etag, String gzipEtag)
    {
        long bytes() { return (identity != null ? identity.length : 0L) + (gzip != null ? gzip.length : 0L); }
    }
}
