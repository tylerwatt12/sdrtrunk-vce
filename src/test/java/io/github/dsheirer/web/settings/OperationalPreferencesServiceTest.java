package io.github.dsheirer.web.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.audio.broadcast.PatchGroupStreamingOption;
import io.github.dsheirer.audio.convert.InputAudioFormat;
import io.github.dsheirer.audio.convert.MP3Setting;
import io.github.dsheirer.preference.application.ApplicationPreference;
import io.github.dsheirer.preference.call.CallManagementPreference;
import io.github.dsheirer.preference.mp3.MP3Preference;
import io.github.dsheirer.preference.record.RecordPreference;
import io.github.dsheirer.record.RecordFormat;
import org.junit.jupiter.api.Test;

class OperationalPreferencesServiceTest
{
    @Test
    void oneFieldUpdatesUseExistingOwnersAndDetectLocalChangesWithoutPersistedRevision()
    {
        Fixture fixture = new Fixture();
        OperationalPreferencesService.Snapshot initial = fixture.service.snapshot();
        assertEquals(64, initial.revision().length());
        assertEquals(PatchGroupStreamingOption.PATCH_GROUP, initial.settings().patchGroupStreamingOption());
        assertEquals(30, initial.settings().statsLoggingRetentionDays());

        OperationalPreferencesService.UpdateResult updated = fixture.service.update(initial.revision(),
            OperationalPreferencesService.Field.PATCH_GROUP_STREAMING_OPTION, "TALKGROUPS");
        assertTrue(updated.updated());
        assertEquals(PatchGroupStreamingOption.TALKGROUPS, fixture.calls.getPatchGroupStreamingOption());
        assertNotEquals(initial.revision(), updated.snapshot().revision());
        assertEquals(1, fixture.calls.mWrites);

        OperationalPreferencesService.UpdateResult stale = fixture.service.update(initial.revision(),
            OperationalPreferencesService.Field.AUDIO_RECORD_FORMAT, "WAVE");
        assertFalse(stale.updated());
        assertEquals(RecordFormat.MP3, fixture.record.getAudioRecordFormat());

        fixture.application.setStatsLoggingRetentionDays(45); // Simulate a local Java preference update.
        assertNotEquals(updated.snapshot().revision(), fixture.service.snapshot().revision());
        assertFalse(fixture.service.update(updated.snapshot().revision(),
            OperationalPreferencesService.Field.STATS_LOGGING_ENABLED, true).updated());
        assertFalse(fixture.application.isStatsLoggingEnabled());
    }

    @Test
    void mp3AndStatsChangesValidateBeforeTheSinglePreferenceSetterRuns()
    {
        Fixture fixture = new Fixture();
        String initialRevision = fixture.service.snapshot().revision();
        assertThrows(IllegalArgumentException.class, () -> fixture.service.update(initialRevision,
            OperationalPreferencesService.Field.MP3_INPUT_AUDIO_FORMAT, "SR_44100"));
        assertEquals(0, fixture.mp3.mWrites);
        assertThrows(IllegalArgumentException.class, () -> fixture.service.update(initialRevision,
            OperationalPreferencesService.Field.STATS_LOGGING_RETENTION_DAYS, 0));
        assertEquals(0, fixture.application.mWrites);

        String revision = fixture.service.update(initialRevision, OperationalPreferencesService.Field.MP3_SETTING, "VBR_5")
            .snapshot().revision();
        revision = fixture.service.update(revision,
            OperationalPreferencesService.Field.MP3_INPUT_AUDIO_FORMAT, "SR_44100").snapshot().revision();
        assertEquals(MP3Setting.VBR_5, fixture.mp3.getMP3Setting());
        assertEquals(InputAudioFormat.SR_44100, fixture.mp3.getAudioSampleRate());
        String incompatibleRevision = revision;
        assertThrows(IllegalArgumentException.class, () -> fixture.service.update(incompatibleRevision,
            OperationalPreferencesService.Field.MP3_SETTING, "CBR_16"));
        assertEquals(MP3Setting.VBR_5, fixture.mp3.getMP3Setting());

        revision = fixture.service.update(revision,
            OperationalPreferencesService.Field.STATS_LOGGING_ENABLED, true).snapshot().revision();
        revision = fixture.service.update(revision,
            OperationalPreferencesService.Field.STATS_DETAILED_HISTORY_ENABLED, true).snapshot().revision();
        revision = fixture.service.update(revision,
            OperationalPreferencesService.Field.STATS_LOGGING_RETENTION_DAYS, 365).snapshot().revision();
        assertTrue(fixture.application.isStatsLoggingEnabled());
        assertTrue(fixture.application.isStatsDetailedHistoryEnabled());
        assertEquals(365, fixture.application.getStatsLoggingRetentionDays());
        assertEquals(3, fixture.application.mWrites);
    }

    static final class Fixture
    {
        final Calls calls = new Calls();
        final Record record = new Record();
        final Mp3 mp3 = new Mp3();
        final Application application = new Application();
        final OperationalPreferencesService service = new OperationalPreferencesService(calls, record, mp3, application);
    }

    static final class Calls extends CallManagementPreference
    {
        private PatchGroupStreamingOption mValue = PatchGroupStreamingOption.PATCH_GROUP;
        int mWrites;

        Calls() { super(ignored -> {}); }
        @Override public PatchGroupStreamingOption getPatchGroupStreamingOption() { return mValue; }
        @Override public void setPatchGroupStreamingOption(PatchGroupStreamingOption value) { mValue = value; mWrites++; }
    }

    static final class Record extends RecordPreference
    {
        private RecordFormat mValue = RecordFormat.MP3;
        int mWrites;

        Record() { super(ignored -> {}); }
        @Override public RecordFormat getAudioRecordFormat() { return mValue; }
        @Override public void setAudioRecordFormat(RecordFormat value) { mValue = value; mWrites++; }
    }

    static final class Mp3 extends MP3Preference
    {
        private MP3Setting mSetting = MP3Setting.CBR_16;
        private InputAudioFormat mFormat = InputAudioFormat.SR_16000;
        private boolean mNormalize;
        int mWrites;

        Mp3() { super(ignored -> {}); }
        @Override public MP3Setting getMP3Setting() { return mSetting; }
        @Override public void setMP3Setting(MP3Setting value) { mSetting = value; mWrites++; }
        @Override public InputAudioFormat getAudioSampleRate() { return mFormat; }
        @Override public void setAudioSampleRate(InputAudioFormat value) { mFormat = value; mWrites++; }
        @Override public boolean isNormalizeAudioBeforeEncode() { return mNormalize; }
        @Override public void setNormalizeAudioBeforeEncode(boolean value) { mNormalize = value; mWrites++; }
    }

    static final class Application extends ApplicationPreference
    {
        private boolean mSummary;
        private boolean mDetailed;
        private int mRetention = 30;
        int mWrites;

        Application() { super(ignored -> {}); }
        @Override public boolean isStatsLoggingEnabled() { return mSummary; }
        @Override public void setStatsLoggingEnabled(boolean value) { mSummary = value; mWrites++; }
        @Override public boolean isStatsDetailedHistoryEnabled() { return mDetailed; }
        @Override public void setStatsDetailedHistoryEnabled(boolean value) { mDetailed = value; mWrites++; }
        @Override public int getStatsLoggingRetentionDays() { return mRetention; }
        @Override public void setStatsLoggingRetentionDays(int value) { mRetention = value; mWrites++; }
    }
}
