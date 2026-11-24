package io.github.dsheirer.source.tuner.manager;

/**
 * Receiver-owned channel recovery, invoked only by the USB lifecycle worker. No tuner allocation/lifecycle lock
 * may be held while a listener stops or starts channels. Hardware callbacks never invoke these operations.
 */
public interface UsbTunerRecoveryListener
{
    UsbTunerRecoveryListener NONE = new UsbTunerRecoveryListener() {};

    default void tunerUnavailable(DiscoveredUSBTuner tuner) {}

    default void tunerAvailable(DiscoveredUSBTuner tuner) {}

    default void tunerDisabled(DiscoveredUSBTuner tuner) {}

    default boolean hasInterruptedChannels(String tunerIdentity) { return false; }
}
