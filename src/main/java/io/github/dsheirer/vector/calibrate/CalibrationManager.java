/*
 * *****************************************************************************
 * Copyright (C) 2014-2025 Dennis Sheirer
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

package io.github.dsheirer.vector.calibrate;

import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.calibration.VectorCalibrationPreference;
import io.github.dsheirer.source.tuner.sdrplay.RspSampleConverterFactory;
import io.github.dsheirer.vector.calibrate.demodulator.AmplitudeDemodulatorCalibration;
import io.github.dsheirer.vector.calibrate.demodulator.DifferentialDemodulatorCalibration;
import io.github.dsheirer.vector.calibrate.demodulator.FmDemodulatorCalibration;
import io.github.dsheirer.vector.calibrate.filter.FirFilterCalibration;
import io.github.dsheirer.vector.calibrate.filter.PolyphaseChannelizerFilterCalibration;
import io.github.dsheirer.vector.calibrate.filter.PulseShapingFirFilterCalibration;
import io.github.dsheirer.vector.calibrate.filter.RealHalfBand11TapFilterCalibration;
import io.github.dsheirer.vector.calibrate.filter.RealHalfBand15TapFilterCalibration;
import io.github.dsheirer.vector.calibrate.filter.RealHalfBand23TapFilterCalibration;
import io.github.dsheirer.vector.calibrate.filter.RealHalfBand63TapFilterCalibration;
import io.github.dsheirer.vector.calibrate.filter.RealHalfBandDefaultFilterCalibration;
import io.github.dsheirer.vector.calibrate.gain.ComplexGainCalibration;
import io.github.dsheirer.vector.calibrate.interpolator.InterpolatorCalibration;
import io.github.dsheirer.vector.calibrate.mixer.ComplexMixerCalibration;
import io.github.dsheirer.vector.calibrate.oscillator.ComplexOscillatorCalibration;
import io.github.dsheirer.vector.calibrate.oscillator.RealOscillatorCalibration;
import io.github.dsheirer.vector.calibrate.sample.RspSampleConverterCalibration;
import io.github.dsheirer.vector.calibrate.sample.UnpackedByteSampleConverterCalibration;
import io.github.dsheirer.vector.calibrate.sample.UnpackedInterleavedSampleConverterCalibration;
import io.github.dsheirer.vector.calibrate.sample.UnpackedSampleConverterCalibration;
import io.github.dsheirer.vector.calibrate.sync.DMRSoftSyncCalibration;
import io.github.dsheirer.vector.calibrate.sync.NXDNSoftSyncCalibration;
import io.github.dsheirer.vector.calibrate.sync.P25P1SoftSyncCalibration;
import io.github.dsheirer.vector.calibrate.window.WindowCalibration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.prefs.Preferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Determines the optimal (scalar vs vector) class to use for the current CPU architecture.
 */
@SuppressWarnings("java:S6548")
public class CalibrationManager
{
    private static final String PREFERENCE_KEY_CALIBRATION_ENVIRONMENT = "calibration.environment.signature";
    private static final Logger mLog = LoggerFactory.getLogger(CalibrationManager.class);
    private Map<CalibrationType, Calibration> mCalibrationMap = new HashMap<>();
    private final CalibrationEnvironment mCalibrationEnvironment = CalibrationEnvironment.current();
    private final Preferences mPreferences = Preferences.userNodeForPackage(CalibrationManager.class);
    private static CalibrationManager sInstance;
    private static VectorCalibrationPreference sVectorCalibrationPreference;

    /**
     * Uses the singleton pattern to construct a single instance.
     */
    private CalibrationManager()
    {
    }

    /**
     * Access a singleton instance of this class, initializing the instance with the
     * specified User Preferences instance, if it hasn't already been intitialized.
     *
     * Note: invoke this method first with a preferences instance to ensure it is the one that is used.
     */
    public static CalibrationManager getInstance(UserPreferences userPreferences)
    {
        if(sVectorCalibrationPreference == null)
        {
            sVectorCalibrationPreference = userPreferences.getVectorCalibrationPreference();
        }

        return getInstance();
    }

    /**
     * Access a singleton instance of this class.
     */
    public static CalibrationManager getInstance()
    {
        if(sInstance == null)
        {
            if(sVectorCalibrationPreference == null)
            {
                sVectorCalibrationPreference = new UserPreferences().getVectorCalibrationPreference();
            }

            sInstance = new CalibrationManager();

            sInstance.add(new UnpackedByteSampleConverterCalibration());
            sInstance.add(new RspSampleConverterCalibration());
            sInstance.add(new UnpackedSampleConverterCalibration());
            sInstance.add(new UnpackedInterleavedSampleConverterCalibration());
            sInstance.add(new ComplexGainCalibration());
            sInstance.add(new ComplexOscillatorCalibration());
            sInstance.add(new ComplexMixerCalibration());
            sInstance.add(new AmplitudeDemodulatorCalibration());
            sInstance.add(new DMRSoftSyncCalibration());
            sInstance.add(new DifferentialDemodulatorCalibration());
            sInstance.add(new FirFilterCalibration());
            sInstance.add(new PulseShapingFirFilterCalibration());
            sInstance.add(new PolyphaseChannelizerFilterCalibration());
            sInstance.add(new FmDemodulatorCalibration());
            sInstance.add(new InterpolatorCalibration());
            sInstance.add(new NXDNSoftSyncCalibration());
            sInstance.add(new P25P1SoftSyncCalibration());
            sInstance.add(new RealHalfBand11TapFilterCalibration());
            sInstance.add(new RealHalfBand15TapFilterCalibration());
            sInstance.add(new RealHalfBand23TapFilterCalibration());
            sInstance.add(new RealHalfBand63TapFilterCalibration());
            sInstance.add(new RealHalfBandDefaultFilterCalibration());
            sInstance.add(new RealOscillatorCalibration());
            sInstance.add(new WindowCalibration());
            sInstance.initializeCalibrationEnvironment();
            RspSampleConverterFactory.setImplementation(
                sInstance.getImplementation(CalibrationType.RSP_SAMPLE_CONVERTER));
        }

        return sInstance;
    }

    /**
     * Adds the calibration to the map of calibrations for this manager.
      * @param calibration to add
     */
    private void add(Calibration calibration)
    {
        if(mCalibrationMap.containsKey(calibration.getType()))
        {
            throw new IllegalStateException("Calibration Type [" + calibration.getType() +
                    "] is already registered for [" + mCalibrationMap.get(calibration.getType()).getClass());
        }

        mCalibrationMap.put(calibration.getType(), calibration);
    }

    /**
     * Invalidates portable calibration results when the host/JVM SIMD environment changes and rejects any persisted
     * fixed width that is wider than the current host's native preferred species.
     */
    private void initializeCalibrationEnvironment()
    {
        String currentSignature = mCalibrationEnvironment.signature();
        String storedSignature = mPreferences.get(PREFERENCE_KEY_CALIBRATION_ENVIRONMENT, null);

        if(mCalibrationEnvironment.invalidateIfChanged(storedSignature, mCalibrationMap.values()))
        {
            mPreferences.put(PREFERENCE_KEY_CALIBRATION_ENVIRONMENT, currentSignature);
            mLog.info("CPU calibration environment changed; all SIMD calibrations will be rerun.");
        }

        for(Calibration calibration: mCalibrationMap.values())
        {
            Implementation implementation = calibration.getImplementation();

            if(!mCalibrationEnvironment.supports(calibration.getType(), implementation))
            {
                mLog.warn("Ignoring unsupported calibration implementation [{}] for [{}] on this host",
                    implementation, calibration.getType());
                calibration.reset();
            }
        }
    }

    /**
     * List of calibration types available
     */
    public List<CalibrationType> getCalibrationTypes()
    {
        return List.copyOf(mCalibrationMap.keySet());
    }

    /**
     * Access a calibration by type
     * @param type of calibration
     * @return calibration instance, or null.
     */
    public Calibration getCalibration(CalibrationType type)
    {
        return mCalibrationMap.get(type);
    }

    /**
     * Identifies the optimal operation for the calibration type.
     * @param type of calibration
     * @return operation
     */
    public Implementation getImplementation(CalibrationType type)
    {
        if(sVectorCalibrationPreference.isVectorEnabled())
        {
            Calibration calibration = getCalibration(type);

            if(calibration != null)
            {
                Implementation implementation = calibration.getImplementation();

                if(mCalibrationEnvironment.supports(type, implementation))
                {
                    return implementation;
                }

                return Implementation.SCALAR;
            }

            return Implementation.UNCALIBRATED;
        }

        return Implementation.SCALAR;
    }

    /**
     * Indicates if all calibrations are calibrated.
     * @return true if all calibrations are calibrated or if there are no calibrations registered.
     */
    public boolean isCalibrated()
    {
        for(Calibration calibration: mCalibrationMap.values())
        {
            if(!calibration.isCalibrated())
            {
                return false;
            }
        }

        return true;
    }

    /**
     * Resets all calibrations to an uncalibrated state.
     */
    public void reset()
    {
        for(Calibration calibration: mCalibrationMap.values())
        {
            calibration.reset();
        }
    }

    /**
     * Resets a specific calibration type.
     */
    public void reset(CalibrationType type)
    {
        Calibration calibration = mCalibrationMap.get(type);

        if(calibration != null)
        {
            calibration.reset();
        }
    }

    /**
     * Calibrates any calibrations that are not currently calibrated
     * @throws CalibrationException if any errors are encountered by any of the calibrations.
     */
    public void calibrate() throws CalibrationException
    {
        List<Calibration> uncalibrated = getUncalibrated();

        if(uncalibrated.isEmpty())
        {
            mLog.info("No additional calibrations are required at this time.");
        }
        else
        {
            mLog.info("Calibrating software for optimal performance on this computer.");
            mLog.info("*** Please be patient, this may take a few minutes ***");

            int calibrationCounter = 0;

            for(Calibration calibration: uncalibrated)
            {
                if(!calibration.isCalibrated())
                {
                    mLog.info("Calibrating [" + ++calibrationCounter + " of " + uncalibrated.size() +
                            "] Type: " + calibration.getType());
                    calibration.calibrate();
                }
            }

            mLog.info("Calibration Complete!");
        }
    }

    /**
     * List of calibrations that need to be performed.
     */
    public List<Calibration> getUncalibrated()
    {
        List<Calibration> uncalibrated = new ArrayList<>();

        for(Calibration calibration: mCalibrationMap.values())
        {
            if(!calibration.isCalibrated())
            {
                uncalibrated.add(calibration);
            }
        }

        //Sort by calibration type
        Collections.sort(uncalibrated, Comparator.comparing(Calibration::getType));

        return uncalibrated;
    }

    public static void main(String[] args)
    {
        CalibrationManager manager = getInstance();

        manager.reset(CalibrationType.DIFFERENTIAL_DEMODULATOR);

        if(!manager.isCalibrated())
        {
            try
            {
                manager.calibrate();
            }
            catch(Exception e)
            {
                e.printStackTrace();
            }
        }


        StringBuilder sb1 = new StringBuilder();
        sb1.append("Updated Calibration Settings:\n");
        for(CalibrationType type: CalibrationType.values())
        {
            Calibration calibration = manager.getCalibration(type);
            if(calibration != null)
            {
                sb1.append("\t").append(type.name()).append("\t").append(manager.getCalibration(type).getImplementation()).append("\n");
            }
        }
        mLog.info(sb1.toString());
        mLog.info("Complete");
    }
}
