/*
 * *****************************************************************************
 * Copyright (C) 2014-2024 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.preference;

import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.settings.ApplicationSettingsStore;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.module.decode.p25.bandplan.P25BandplanOverrideRegistry;
import io.github.dsheirer.preference.application.ApplicationPreference;
import io.github.dsheirer.preference.calibration.VectorCalibrationPreference;
import io.github.dsheirer.preference.call.CallManagementPreference;
import io.github.dsheirer.preference.decoder.JmbeLibraryPreference;
import io.github.dsheirer.preference.decoder.VoiceDecryptionModulePreference;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import io.github.dsheirer.preference.encryption.EncryptionKeyPreference;
import io.github.dsheirer.preference.event.DecodeEventPreference;
import io.github.dsheirer.preference.identifier.TalkgroupFormatPreference;
import io.github.dsheirer.preference.javafx.JavaFxPreferences;
import io.github.dsheirer.preference.mp3.MP3Preference;
import io.github.dsheirer.preference.nowplaying.NowPlayingPreference;
import io.github.dsheirer.preference.radioreference.RadioReferencePreference;
import io.github.dsheirer.preference.record.RecordPreference;
import io.github.dsheirer.preference.source.TunerPreference;
import io.github.dsheirer.preference.swing.SwingPreference;
import io.github.dsheirer.sample.Listener;

/**
 * User Preferences.  A collection of preferences that can be accessed by preference type.
 *
 * Note: user preference updates are broadcast throughout the system using the Google Guava Event Bus.  Each component
 * can register with the event bus and annotate a method to receive updates:
 *
 * To register a component to receive events, add this in the constructor:
 * MyEventBus.getEventBus().register(this);
 *
 * To receive preference update notifications, annotate a method with @Subscribe and use a PreferenceType argument:
 *
 * @Subscribe
 * public void preferenceUpdated(PreferenceType preferenceType)
 * {
 * }
 */
public class UserPreferences implements Listener<PreferenceType>
{
    private ApplicationPreference mApplicationPreference;
    private DecodeEventPreference mDecodeEventPreference;
    private DirectoryPreference mDirectoryPreference;
    private CallManagementPreference mCallManagementPreference;
    private EncryptionKeyPreference mEncryptionKeyPreference;
    private JmbeLibraryPreference mJmbeLibraryPreference;
    private VoiceDecryptionModulePreference mVoiceDecryptionModulePreference;
    private MP3Preference mMP3Preference;
    private NowPlayingPreference mNowPlayingPreference;
    private RadioReferencePreference mRadioReferencePreference;
    private RecordPreference mRecordPreference;
    private TalkgroupFormatPreference mTalkgroupFormatPreference;
    private TunerPreference mTunerPreference;
    private VectorCalibrationPreference mVectorCalibrationPreference;
    private P25BandplanOverrideRegistry mP25BandplanOverrideRegistry;

    private SwingPreference mSwingPreference = new SwingPreference();
    private JavaFxPreferences mJavaFxPreferences = new JavaFxPreferences();

    /**
     * Constructs a new user preferences instance
     */
    public UserPreferences()
    {
        loadPreferenceTypes();
    }

    /**
     * Application general/miscellaneous preferences.
     * @return application preferences.
     */
    public ApplicationPreference getApplicationPreference()
    {
        return mApplicationPreference;
    }

    /**
     * Vector/SIMD calibration preferences.
     * @return vector calibration preferences.
     */
    public VectorCalibrationPreference getVectorCalibrationPreference()
    {
        return mVectorCalibrationPreference;
    }

    /**
     * Java FX window management preferences
     */
    public JavaFxPreferences getJavaFxPreferences()
    {
        return mJavaFxPreferences;
    }

    /**
     * Decode Event preferences
     */
    public DecodeEventPreference getDecodeEventPreference()
    {
        return mDecodeEventPreference;
    }

    /**
     * Decoder preferences
     */
    public JmbeLibraryPreference getJmbeLibraryPreference()
    {
        return mJmbeLibraryPreference;
    }

    /**
     * Optional voice decryption module preference and runtime manager.
     */
    public VoiceDecryptionModulePreference getVoiceDecryptionModulePreference()
    {
        return mVoiceDecryptionModulePreference;
    }

    /**
     * Voice encryption key preferences.
     */
    public EncryptionKeyPreference getEncryptionKeyPreference()
    {
        return mEncryptionKeyPreference;
    }

    /**
     * Directory preferences
     */
    public DirectoryPreference getDirectoryPreference()
    {
        return mDirectoryPreference;
    }

    /**
     * Radio reference web services preferences
     */
    public RadioReferencePreference getRadioReferencePreference()
    {
        return mRadioReferencePreference;
    }

    /**
     * Recording preferences
     */
    public RecordPreference getRecordPreference()
    {
        return mRecordPreference;
    }

    /**
     * MP3 preferences
     */
    public MP3Preference getMP3Preference()
    {
        return mMP3Preference;
    }

    /**
     * Browser Live activity and optional Java desktop view preferences
     */
    public NowPlayingPreference getNowPlayingPreference()
    {
        return mNowPlayingPreference;
    }

    /**
     * Identifier preferences
     */
    public TalkgroupFormatPreference getTalkgroupFormatPreference()
    {
        return mTalkgroupFormatPreference;
    }

    /**
     * Tuner preferences
     */
    public TunerPreference getTunerPreference()
    {
        return mTunerPreference;
    }

    /**
     * Swing window location/size user preferences
     */
    public SwingPreference getSwingPreference()
    {
        return mSwingPreference;
    }

    /**
     * Call routing preferences
     */
    public CallManagementPreference getCallManagementPreference()
    {
        return mCallManagementPreference;
    }

    /**
     * Receiver-wide manually configured P25 bandplan overrides.
     */
    public synchronized P25BandplanOverrideRegistry getP25BandplanOverrideRegistry()
    {
        if(mP25BandplanOverrideRegistry == null)
        {
            mP25BandplanOverrideRegistry = new P25BandplanOverrideRegistry(
                new ApplicationSettingsStore(SdrTrunkDatabasePath.getDatabasePath(this)));
        }

        return mP25BandplanOverrideRegistry;
    }

    /**
     * Loads the managed preferences
     */
    private void loadPreferenceTypes()
    {
        mApplicationPreference = new ApplicationPreference(this::receive);
        mDecodeEventPreference = new DecodeEventPreference(this::receive);
        mDirectoryPreference = new DirectoryPreference(this::receive);
        mCallManagementPreference = new CallManagementPreference(this::receive);
        mEncryptionKeyPreference = new EncryptionKeyPreference(this::receive, mDirectoryPreference);
        mJmbeLibraryPreference = new JmbeLibraryPreference(this::receive);
        mVoiceDecryptionModulePreference = new VoiceDecryptionModulePreference(this::receive);
        mMP3Preference = new MP3Preference(this::receive);
        mNowPlayingPreference = new NowPlayingPreference(this::receive);
        mRadioReferencePreference = new RadioReferencePreference(this::receive);
        mRecordPreference = new RecordPreference(this::receive);
        mTalkgroupFormatPreference = new TalkgroupFormatPreference(this::receive);
        mTunerPreference = new TunerPreference(this::receive);
        mVectorCalibrationPreference = new VectorCalibrationPreference(this::receive);
    }

    /**
     * Primary method for receiving notification from a managed preference that a preference type
     * has been changed/updated.
     *
     * @param preferenceType that is updated
     */
    @Override
    public void receive(PreferenceType preferenceType)
    {
        MyEventBus.getGlobalEventBus().post(preferenceType);
    }
}
