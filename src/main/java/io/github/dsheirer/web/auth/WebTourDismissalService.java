/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.web.auth;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Durable, account-owned tour versions, independent of personal preference revisions. */
public final class WebTourDismissalService
{
    public static final int MAXIMUM_GUIDES = 64;
    public static final int MAXIMUM_GUIDE_VERSION = 1_000_000;
    public static final int MAXIMUM_JSON_BYTES = 8192;
    private static final Pattern GUIDE_ID = Pattern.compile("[a-z][a-z0-9_-]{0,63}");
    private final WebTourDismissalRepository mRepository;

    public WebTourDismissalService(Path databasePath)
    {
        mRepository = new WebTourDismissalRepository(databasePath);
    }

    public Snapshot get(WebAccessAccount account) throws IOException, SQLException
    {
        return new Snapshot(1, mRepository.get(Objects.requireNonNull(account)));
    }

    /** Merge highest known versions without removing another browser's dismissals. */
    public Snapshot merge(WebAccessAccount account, Snapshot incoming) throws IOException, SQLException
    {
        Objects.requireNonNull(account, "Authenticated web account cannot be null");
        Objects.requireNonNull(incoming, "Tour dismissals cannot be null");
        return new Snapshot(1, mRepository.merge(account, incoming.dismissed()));
    }

    public record Snapshot(int version, Map<String,Integer> dismissed)
    {
        public Snapshot
        {
            if(version != 1 || dismissed == null || dismissed.size() > MAXIMUM_GUIDES)
            {
                throw new IllegalArgumentException("Tour dismissals are invalid or exceed the account limit");
            }
            TreeMap<String,Integer> copy = new TreeMap<>();
            dismissed.forEach((id, guideVersion) -> {
                if(id == null || !GUIDE_ID.matcher(id).matches() || guideVersion == null ||
                    guideVersion < 1 || guideVersion > MAXIMUM_GUIDE_VERSION)
                {
                    throw new IllegalArgumentException("Tour ID or version is invalid");
                }
                copy.put(id, guideVersion);
            });
            dismissed = Collections.unmodifiableMap(copy);
        }
    }
}
