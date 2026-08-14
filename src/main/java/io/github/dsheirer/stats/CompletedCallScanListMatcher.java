/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */
package io.github.dsheirer.stats;

import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.audio.call.ResolvedCallPolicy;
import io.github.dsheirer.scanlist.ScanList;
import io.github.dsheirer.scanlist.ScanListConfiguration;
import io.github.dsheirer.scanlist.ScanListModel;
import java.util.LinkedHashSet;
import java.util.Set;

/** Resolves scan-list delivery once from the final duplicate-elected logical call. */
final class CompletedCallScanListMatcher
{
    private final ScanListModel mScanListModel;

    CompletedCallScanListMatcher(ScanListModel scanListModel)
    {
        mScanListModel = scanListModel;
    }

    Set<Long> match(CompletedAudioCall call)
    {
        if(call == null || call.resolvedPolicy() == null || mScanListModel == null)
        {
            return Set.of();
        }

        ScanListConfiguration configuration = mScanListModel.configuration();
        Set<Long> matches = new LinkedHashSet<>();

        for(ResolvedCallPolicy.MatchContext context : call.resolvedPolicy().matchContexts())
        {
            for(Long aliasId : context.matchedAliasIds())
            {
                matches.addAll(configuration.scanListIdsForAlias(aliasId));
            }

            if(context.aliasListId() > 0 &&
                context.talkgroupMatchStatus() == AliasList.TalkgroupMatchStatus.UNMATCHED)
            {
                matches.addAll(configuration.scanListIdsForUnmatchedTalkgroups(context.aliasListId()));
            }
        }

        matches.removeIf(scanListId -> {
            ScanList scanList = configuration.scanList(scanListId);
            return scanList == null || !scanList.isPublished();
        });
        return Set.copyOf(matches);
    }
}
