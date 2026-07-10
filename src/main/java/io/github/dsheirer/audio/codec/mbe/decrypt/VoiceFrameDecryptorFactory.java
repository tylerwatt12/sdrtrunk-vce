/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
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

package io.github.dsheirer.audio.codec.mbe.decrypt;

import io.github.dsheirer.preference.encryption.VoiceEncryptionKey;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates protocol and algorithm-specific voice frame decryptors.
 */
public class VoiceFrameDecryptorFactory
{
    private static final Logger mLog = LoggerFactory.getLogger(VoiceFrameDecryptorFactory.class);
    private final Supplier<? extends Collection<VoiceFrameDecryptorProvider>> mProviderSupplier;

    public VoiceFrameDecryptorFactory()
    {
        this(List.of());
    }

    public VoiceFrameDecryptorFactory(Collection<VoiceFrameDecryptorProvider> providers)
    {
        List<VoiceFrameDecryptorProvider> fixedProviders = providers == null ? List.of() : List.copyOf(providers);
        mProviderSupplier = () -> fixedProviders;
    }

    public VoiceFrameDecryptorFactory(VoiceDecryptionModuleManager moduleManager)
    {
        mProviderSupplier = moduleManager == null ? List::of : moduleManager::getProviders;
    }

    public Optional<VoiceFrameDecryptor> create(VoiceEncryptionContext context, VoiceEncryptionKey key)
    {
        if(context == null || key == null)
        {
            return Optional.empty();
        }

        Collection<VoiceFrameDecryptorProvider> providers = mProviderSupplier.get();

        for(VoiceFrameDecryptorProvider provider: providers == null ? List.<VoiceFrameDecryptorProvider>of() : providers)
        {
            if(provider.supports(context))
            {
                try
                {
                    return Optional.of(provider.create(context, key));
                }
                catch(VoiceFrameDecryptionException e)
                {
                    mLog.debug("Unable to create voice frame decryptor with provider " +
                        provider.getClass().getSimpleName(), e);
                    return Optional.empty();
                }
            }
        }

        return Optional.empty();
    }
}
