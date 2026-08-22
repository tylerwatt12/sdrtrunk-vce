/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.preference.nowplaying;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.preference.nowplaying.NowPlayingPreference.DecodeQualityDisplayMode;
import io.github.dsheirer.preference.nowplaying.NowPlayingPreference.LiveActivitySettings;
import io.github.dsheirer.preference.portable.SqlitePreferencesFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NowPlayingPreferencePortableTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void liveActivityBatchRoundTripsThroughPortablePreferencesInFreshJvms() throws Exception
    {
        Path database = mTemporaryFolder.resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        runProcess(database, "write");
        runProcess(database, "verify");
    }

    private static void runProcess(Path database, String operation) throws Exception
    {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(javaExecutable, "-cp", System.getProperty("java.class.path"),
            PreferenceProcess.class.getName(), database.toString(), operation).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
    }

    public static class PreferenceProcess
    {
        public static void main(String[] args) throws Exception
        {
            SqlitePreferencesFactory.install(Path.of(args[0]));

            try
            {
                NowPlayingPreference preference = new NowPlayingPreference(ignored -> {});
                LiveActivitySettings expected = new LiveActivitySettings(true, 1_250, false, false, true,
                    DecodeQualityDisplayMode.DETAILED, 350);

                if("write".equals(args[1]))
                {
                    preference.setLiveActivitySettings(expected);
                }
                else if(!expected.equals(preference.getLiveActivitySettings()))
                {
                    throw new AssertionError("Unexpected persisted Live activity settings: " +
                        preference.getLiveActivitySettings());
                }
            }
            finally
            {
                SqlitePreferencesFactory.shutdown();
            }
        }
    }
}
