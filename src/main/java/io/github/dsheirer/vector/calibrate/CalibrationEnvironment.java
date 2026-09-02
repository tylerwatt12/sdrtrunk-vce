/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.vector.calibrate;

import java.util.Objects;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.ShortVector;

/**
 * Identifies the host and JVM environment that produced persisted SIMD calibration choices.
 *
 * <p>Calibration preferences live in the portable application database.  The signature prevents a portable-data
 * migration, JVM update, or vector-width configuration change from applying one computer's measured choices to a
 * different execution environment.  The signature intentionally contains only non-secret runtime characteristics.
 * It does not serialize benchmark results or JVM arguments.</p>
 */
record CalibrationEnvironment(String osName, String osArchitecture, String osVersion, String javaVendor,
                              String javaVmName, String javaVmVersion, String javaVmInfo,
                              String javaRuntimeVersion, int preferredFloatBits, int preferredShortBits)
{
    private static final int SIGNATURE_FORMAT_VERSION = 1;

    CalibrationEnvironment
    {
        osName = Objects.requireNonNull(osName);
        osArchitecture = Objects.requireNonNull(osArchitecture);
        osVersion = Objects.requireNonNull(osVersion);
        javaVendor = Objects.requireNonNull(javaVendor);
        javaVmName = Objects.requireNonNull(javaVmName);
        javaVmVersion = Objects.requireNonNull(javaVmVersion);
        javaVmInfo = Objects.requireNonNull(javaVmInfo);
        javaRuntimeVersion = Objects.requireNonNull(javaRuntimeVersion);

        if(preferredFloatBits <= 0 || preferredShortBits <= 0)
        {
            throw new IllegalArgumentException("Preferred SIMD widths must be greater than zero");
        }
    }

    /** Current host/JVM calibration environment. */
    static CalibrationEnvironment current()
    {
        return new CalibrationEnvironment(property("os.name"), property("os.arch"), property("os.version"),
            property("java.vendor"), property("java.vm.name"), property("java.vm.version"),
            property("java.vm.info"), property("java.runtime.version"),
            FloatVector.SPECIES_PREFERRED.vectorBitSize(), ShortVector.SPECIES_PREFERRED.vectorBitSize());
    }

    /** Stable persisted signature for this environment. */
    String signature()
    {
        return String.join("\u001F",
            "format=" + SIGNATURE_FORMAT_VERSION,
            "os.name=" + osName,
            "os.arch=" + osArchitecture,
            "os.version=" + osVersion,
            "java.vendor=" + javaVendor,
            "java.vm.name=" + javaVmName,
            "java.vm.version=" + javaVmVersion,
            "java.vm.info=" + javaVmInfo,
            "java.runtime.version=" + javaRuntimeVersion,
            "float.bits=" + preferredFloatBits,
            "short.bits=" + preferredShortBits);
    }

    /**
     * Invalidates every registered calibration when the stored signature is absent or differs from this environment.
     *
     * @return true when invalidation was performed
     */
    boolean invalidateIfChanged(String storedSignature, Iterable<Calibration> calibrations)
    {
        Objects.requireNonNull(calibrations, "Calibrations cannot be null");

        if(signature().equals(storedSignature))
        {
            return false;
        }

        for(Calibration calibration: calibrations)
        {
            calibration.reset();
        }

        return true;
    }

    /**
     * Indicates whether a persisted fixed-width implementation fits this host's native preferred species.  SDRplay
     * conversion combines short and float species, so its fixed width must fit both.
     */
    boolean supports(CalibrationType type, Implementation implementation)
    {
        Objects.requireNonNull(type, "Calibration type cannot be null");
        Objects.requireNonNull(implementation, "Implementation cannot be null");

        int requiredBits = switch(implementation)
        {
            case VECTOR_SIMD_64 -> 64;
            case VECTOR_SIMD_128 -> 128;
            case VECTOR_SIMD_256 -> 256;
            case VECTOR_SIMD_512 -> 512;
            case SCALAR, UNCALIBRATED, VECTOR_SIMD_PREFERRED -> 0;
        };

        if(requiredBits == 0)
        {
            return true;
        }

        if(requiredBits > preferredFloatBits)
        {
            return false;
        }

        return type != CalibrationType.RSP_SAMPLE_CONVERTER || requiredBits <= preferredShortBits;
    }

    private static String property(String name)
    {
        try
        {
            return System.getProperty(name, "unknown");
        }
        catch(SecurityException exception)
        {
            return "unavailable";
        }
    }
}
