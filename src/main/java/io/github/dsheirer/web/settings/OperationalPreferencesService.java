/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.web.settings;

import io.github.dsheirer.audio.broadcast.PatchGroupStreamingOption;
import io.github.dsheirer.audio.convert.InputAudioFormat;
import io.github.dsheirer.audio.convert.MP3Setting;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.application.ApplicationPreference;
import io.github.dsheirer.preference.call.CallManagementPreference;
import io.github.dsheirer.preference.mp3.MP3Preference;
import io.github.dsheirer.preference.record.RecordPreference;
import io.github.dsheirer.record.RecordFormat;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Receiver-wide operational preferences over their existing owners; no duplicate persisted state. */
public final class OperationalPreferencesService
{
    private final CallManagementPreference mCalls;
    private final RecordPreference mRecord;
    private final MP3Preference mMp3;
    private final ApplicationPreference mApplication;

    public OperationalPreferencesService(UserPreferences preferences)
    {
        this(preferences.getCallManagementPreference(), preferences.getRecordPreference(),
            preferences.getMP3Preference(), preferences.getApplicationPreference());
    }

    OperationalPreferencesService(CallManagementPreference calls, RecordPreference record,
                                  MP3Preference mp3, ApplicationPreference application)
    {
        mCalls = Objects.requireNonNull(calls);
        mRecord = Objects.requireNonNull(record);
        mMp3 = Objects.requireNonNull(mp3);
        mApplication = Objects.requireNonNull(application);
    }

    public synchronized Snapshot snapshot()
    {
        Settings settings = new Settings(mCalls.getPatchGroupStreamingOption(), mRecord.getAudioRecordFormat(),
            mMp3.getMP3Setting(), mMp3.getAudioSampleRate(), mMp3.isNormalizeAudioBeforeEncode(),
            mApplication.isStatsLoggingEnabled(), mApplication.isStatsDetailedHistoryEnabled(),
            mApplication.getStatsLoggingRetentionDays());
        return new Snapshot(revision(settings), settings, options());
    }

    /** A single validated preference change, guarded against stale browser or local-editor state. */
    public synchronized UpdateResult update(String expectedRevision, Field field, Object value)
    {
        Objects.requireNonNull(field, "Operational preference field is required");
        Snapshot before = snapshot();
        if(!before.revision().equals(expectedRevision))
        {
            return new UpdateResult(false, before);
        }

        Settings current = before.settings();
        switch(field)
        {
            case PATCH_GROUP_STREAMING_OPTION -> {
                PatchGroupStreamingOption option = requireEnum(value, PatchGroupStreamingOption.class);
                if(option != current.patchGroupStreamingOption()) mCalls.setPatchGroupStreamingOption(option);
            }
            case AUDIO_RECORD_FORMAT -> {
                RecordFormat format = requireEnum(value, RecordFormat.class);
                if(format != current.audioRecordFormat()) mRecord.setAudioRecordFormat(format);
            }
            case MP3_SETTING -> {
                MP3Setting setting = requireEnum(value, MP3Setting.class);
                if(!setting.getSupportedSampleRates().contains(current.mp3InputAudioFormat()))
                {
                    throw new IllegalArgumentException("Choose an input audio rate supported by the new MP3 setting first");
                }
                if(setting != current.mp3Setting()) mMp3.setMP3Setting(setting);
            }
            case MP3_INPUT_AUDIO_FORMAT -> {
                InputAudioFormat format = requireEnum(value, InputAudioFormat.class);
                if(!current.mp3Setting().getSupportedSampleRates().contains(format))
                {
                    throw new IllegalArgumentException("Input audio rate is not supported by the current MP3 setting");
                }
                if(format != current.mp3InputAudioFormat()) mMp3.setAudioSampleRate(format);
            }
            case MP3_NORMALIZE_AUDIO -> {
                boolean normalize = requireBoolean(value);
                if(normalize != current.mp3NormalizeAudio()) mMp3.setNormalizeAudioBeforeEncode(normalize);
            }
            case STATS_LOGGING_ENABLED -> {
                boolean enabled = requireBoolean(value);
                if(enabled != current.statsLoggingEnabled()) mApplication.setStatsLoggingEnabled(enabled);
            }
            case STATS_DETAILED_HISTORY_ENABLED -> {
                boolean enabled = requireBoolean(value);
                if(enabled != current.statsDetailedHistoryEnabled()) mApplication.setStatsDetailedHistoryEnabled(enabled);
            }
            case STATS_LOGGING_RETENTION_DAYS -> {
                int days = requireRetention(value);
                if(days != current.statsLoggingRetentionDays()) mApplication.setStatsLoggingRetentionDays(days);
            }
        }

        return new UpdateResult(true, snapshot());
    }

    private static boolean requireBoolean(Object value)
    {
        if(value instanceof Boolean bool) return bool;
        throw new IllegalArgumentException("Preference value must be a boolean");
    }

    private static int requireRetention(Object value)
    {
        if(value instanceof Integer days && days >= ApplicationPreference.MIN_STATS_LOGGING_RETENTION_DAYS &&
            days <= ApplicationPreference.MAX_STATS_LOGGING_RETENTION_DAYS)
        {
            return days;
        }
        throw new IllegalArgumentException("Statistics retention must be between 1 and 365 days");
    }

    private static <T extends Enum<T>> T requireEnum(Object value, Class<T> type)
    {
        if(value instanceof String name)
        {
            try
            {
                return Enum.valueOf(type, name);
            }
            catch(IllegalArgumentException ignored)
            {
            }
        }
        throw new IllegalArgumentException("Unsupported preference option");
    }

    private static String revision(Settings settings)
    {
        String canonical = String.join("\u0000", settings.patchGroupStreamingOption().name(),
            settings.audioRecordFormat().name(), settings.mp3Setting().name(),
            settings.mp3InputAudioFormat().name(), Boolean.toString(settings.mp3NormalizeAudio()),
            Boolean.toString(settings.statsLoggingEnabled()),
            Boolean.toString(settings.statsDetailedHistoryEnabled()),
            Integer.toString(settings.statsLoggingRetentionDays()));
        try
        {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        }
        catch(NoSuchAlgorithmException exception)
        {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static Options options()
    {
        Map<String,List<Option>> rates = new LinkedHashMap<>();
        for(MP3Setting setting: MP3Setting.values())
        {
            rates.put(setting.name(), Arrays.stream(InputAudioFormat.values())
                .filter(setting.getSupportedSampleRates()::contains)
                .map(rate -> new Option(rate.name(), rate.toString())).toList());
        }
        return new Options(enumOptions(PatchGroupStreamingOption.values()), enumOptions(RecordFormat.values()),
            enumOptions(MP3Setting.values()), Map.copyOf(rates),
            ApplicationPreference.MIN_STATS_LOGGING_RETENTION_DAYS,
            ApplicationPreference.MAX_STATS_LOGGING_RETENTION_DAYS);
    }

    private static <T extends Enum<T>> List<Option> enumOptions(T[] values)
    {
        return Arrays.stream(values).map(value -> new Option(value.name(), value.toString())).toList();
    }

    public enum Field
    {
        PATCH_GROUP_STREAMING_OPTION("patch_group_streaming_option"),
        AUDIO_RECORD_FORMAT("audio_record_format"),
        MP3_SETTING("mp3_setting"),
        MP3_INPUT_AUDIO_FORMAT("mp3_input_audio_format"),
        MP3_NORMALIZE_AUDIO("mp3_normalize_audio"),
        STATS_LOGGING_ENABLED("stats_logging_enabled"),
        STATS_DETAILED_HISTORY_ENABLED("stats_detailed_history_enabled"),
        STATS_LOGGING_RETENTION_DAYS("stats_logging_retention_days");

        private final String mPath;

        Field(String path)
        {
            mPath = path;
        }

        public static Field fromPath(String path)
        {
            for(Field field: values())
            {
                if(field.mPath.equals(path)) return field;
            }
            throw new IllegalArgumentException("Unknown operational preference");
        }
    }

    public record Settings(PatchGroupStreamingOption patchGroupStreamingOption, RecordFormat audioRecordFormat,
                           MP3Setting mp3Setting, InputAudioFormat mp3InputAudioFormat, boolean mp3NormalizeAudio,
                           boolean statsLoggingEnabled, boolean statsDetailedHistoryEnabled,
                           int statsLoggingRetentionDays)
    {
    }

    public record Option(String value, String label) { }
    public record Options(List<Option> patchGroupStreamingOptions, List<Option> audioRecordFormats,
                          List<Option> mp3Settings, Map<String,List<Option>> mp3InputAudioFormatsBySetting,
                          int minimumStatsLoggingRetentionDays, int maximumStatsLoggingRetentionDays) { }
    public record Snapshot(String revision, Settings settings, Options options) { }
    public record UpdateResult(boolean updated, Snapshot snapshot) { }
}
