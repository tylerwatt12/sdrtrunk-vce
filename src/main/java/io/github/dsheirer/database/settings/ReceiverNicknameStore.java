/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.settings;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Durable nickname writes on administration workers; all receiver/browser projection reads use one immutable cache. */
public final class ReceiverNicknameStore
{
    private final ApplicationSettingsStore mSettings;
    private volatile Map<String,String> mNicknames;

    public ReceiverNicknameStore(Path database) throws IOException, SQLException
    {
        this(new ApplicationSettingsStore(database));
    }

    public ReceiverNicknameStore(ApplicationSettingsStore settings) throws IOException, SQLException
    {
        mSettings = Objects.requireNonNull(settings);
        mNicknames = ReceiverNicknameSchema.decode(settings.loadJson(ReceiverNicknameSchema.KEY,
            ReceiverNicknameSchema.MAXIMUM_BYTES).orElseThrow(() -> new SQLException("Receiver nicknames are missing")));
    }

    public String get(String receiverId)
    {
        return receiverId != null ? mNicknames.getOrDefault(receiverId, "") : "";
    }

    public Map<String,String> snapshot()
    {
        return mNicknames;
    }

    public synchronized void set(String receiverId, String nickname) throws IOException, SQLException
    {
        ReceiverNicknameSchema.requireReceiverId(receiverId);
        String normalized = normalize(nickname);
        if(get(receiverId).equals(normalized)) return;
        Map<String,String> changed = new LinkedHashMap<>(mNicknames);
        if(normalized.isEmpty()) changed.remove(receiverId);
        else changed.put(receiverId, normalized);
        if(changed.size() > ReceiverNicknameSchema.MAXIMUM_NICKNAMES)
            throw new IllegalArgumentException("Too many receiver nicknames are saved; clear an unused nickname first");
        Map<String,String> next = Map.copyOf(changed);
        mSettings.save(ReceiverNicknameSchema.KEY, next);
        mNicknames = next;
    }

    public static String normalize(String nickname)
    {
        return ReceiverNicknameSchema.normalize(nickname);
    }
}
