/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.preference.record;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.Test;

class RecordPreferenceTest
{
    @Test
    void recordedCallRetentionAndStorageAreIndependentBoundedAndPersistent() throws Exception
    {
        Preferences preferences = Preferences.userRoot().node(
            "/io/github/dsheirer/test/" + UUID.randomUUID());
        AtomicInteger updates = new AtomicInteger();

        try
        {
            RecordPreference preference = new RecordPreference(ignored -> updates.incrementAndGet(), preferences);
            assertEquals(RecordPreference.DEFAULT_RECORDED_CALL_RETENTION_DAYS,
                preference.getRecordedCallRetentionDays());
            assertEquals(RecordPreference.DEFAULT_RECORDED_CALL_MAXIMUM_RETAINED_MIB,
                preference.getRecordedCallMaximumRetainedMiB());
            assertEquals(2_000L * 1024L * 1024L, preference.getRecordedCallMaximumRetainedBytes());

            preference.setRecordedCallRetentionDays(0);
            preference.setRecordedCallMaximumRetainedMiB(0);
            RecordPreference minimum = new RecordPreference(ignored -> {}, preferences);
            assertEquals(RecordPreference.MINIMUM_RECORDED_CALL_RETENTION_DAYS,
                minimum.getRecordedCallRetentionDays());
            assertEquals(RecordPreference.MINIMUM_RECORDED_CALL_MAXIMUM_RETAINED_MIB,
                minimum.getRecordedCallMaximumRetainedMiB());

            preference.setRecordedCallRetentionDays(Integer.MAX_VALUE);
            preference.setRecordedCallMaximumRetainedMiB(Integer.MAX_VALUE);
            RecordPreference maximum = new RecordPreference(ignored -> {}, preferences);
            assertEquals(RecordPreference.MAXIMUM_RECORDED_CALL_RETENTION_DAYS,
                maximum.getRecordedCallRetentionDays());
            assertEquals(RecordPreference.MAXIMUM_RECORDED_CALL_MAXIMUM_RETAINED_MIB,
                maximum.getRecordedCallMaximumRetainedMiB());

            preference.setRecordedCallRetentionDays(45);
            preference.setRecordedCallMaximumRetainedMiB(4_096);
            RecordPreference reloaded = new RecordPreference(ignored -> {}, preferences);
            assertEquals(45, reloaded.getRecordedCallRetentionDays());
            assertEquals(4_096, reloaded.getRecordedCallMaximumRetainedMiB());
            assertEquals(6, updates.get());
        }
        finally
        {
            preferences.removeNode();
        }
    }
}
