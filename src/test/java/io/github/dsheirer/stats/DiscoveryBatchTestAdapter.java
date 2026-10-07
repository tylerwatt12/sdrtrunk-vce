/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import io.github.dsheirer.channel.ChannelAdministrationService;
import io.github.dsheirer.configuration.ConfigurationManager;
import java.util.List;
import java.util.ArrayList;
import java.util.function.BooleanSupplier;

/** Scripted per-row outcomes for search endpoint tests; real persistence has separate transaction tests. */
final class DiscoveryBatchTestAdapter
{
    static ChannelAdministrationService.DiscoveryBatch save(SpectrumSearchService.Channels channels,
            List<ChannelAdministrationService.DiscoveryRequest> requests, long revision, BooleanSupplier cancelled)
        {
            List<ChannelAdministrationService.DiscoverySave> outcomes = new ArrayList<>();
            ConfigurationManager.ConfigurationPublicationException publicationFailure = null;
            RuntimeException stopped = null;
            for(var request: requests)
            {
                try
                {
                    if(stopped != null)
                    {
                        outcomes.add(new ChannelAdministrationService.DiscoverySave(null, stopped));
                        continue;
                    }
                    if(cancelled.getAsBoolean()) throw new IllegalStateException("The search was cancelled");
                    var created = channels.createTrunked(request.definition(), request.evidence(), request.newAliasListName(),
                        revision, request.autoStart());
                    outcomes.add(new ChannelAdministrationService.DiscoverySave(created, null));
                    revision = channels.revision();
                }
                catch(ConfigurationManager.ConfigurationPublicationException failure)
                {
                    publicationFailure = failure;
                    stopped = failure;
                    outcomes.add(new ChannelAdministrationService.DiscoverySave(failure.committedConfigurationId() != null ?
                        new ChannelAdministrationService.DiscoveryCreated(failure.committedConfigurationId(), failure.committedAliasListId()) : null,
                        failure.committedConfigurationId() == null ? failure : null));
                }
                catch(RuntimeException failure)
                {
                    outcomes.add(new ChannelAdministrationService.DiscoverySave(null, failure));
                    if(failure instanceof ChannelAdministrationService.StaleRevisionException) stopped = failure;
                }
            }
            return new ChannelAdministrationService.DiscoveryBatch(outcomes, publicationFailure);
        }
}
