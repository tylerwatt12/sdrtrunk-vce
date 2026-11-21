package io.github.dsheirer.preference.decoder;

import io.github.dsheirer.preference.encryption.VoiceEncryptionProtocol;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VoiceDecryptionModulePreferenceTest
{
    @Test
    void providersAreReadyOnEveryLaunchWithoutSavedModuleConfiguration()
    {
        for(int launch = 0; launch < 2; launch++)
        {
            var preference = new VoiceDecryptionModulePreference(ignored -> {});
            assertTrue(preference.getModuleManager().isLoaded());
            for(VoiceEncryptionProtocol protocol: VoiceEncryptionProtocol.values())
            {
                assertFalse(preference.getModuleManager().getSupportedAlgorithms(protocol).isEmpty());
            }
        }
    }
}
