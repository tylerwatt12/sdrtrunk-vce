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
package io.github.dsheirer.alias;

import io.github.dsheirer.alias.action.AliasAction;
import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.alias.id.dcs.Dcs;
import io.github.dsheirer.alias.id.esn.Esn;
import io.github.dsheirer.alias.id.priority.Priority;
import io.github.dsheirer.alias.id.radio.P25FullyQualifiedRadio;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.alias.id.radio.RadioRange;
import io.github.dsheirer.alias.id.status.UnitStatusID;
import io.github.dsheirer.alias.id.status.UserStatusID;
import io.github.dsheirer.alias.id.talkgroup.P25FullyQualifiedTalkgroup;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.alias.id.talkgroup.TalkgroupRange;
import io.github.dsheirer.alias.id.tone.TonesID;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.dcs.DCSIdentifier;
import io.github.dsheirer.identifier.esn.ESNIdentifier;
import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.identifier.patch.PatchGroupIdentifier;
import io.github.dsheirer.identifier.radio.FullyQualifiedRadioIdentifier;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.identifier.status.UnitStatusIdentifier;
import io.github.dsheirer.identifier.status.UserStatusIdentifier;
import io.github.dsheirer.identifier.talkgroup.FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.identifier.talkgroup.TalkgroupIdentifier;
import io.github.dsheirer.identifier.tone.ToneIdentifier;
import io.github.dsheirer.identifier.tone.ToneSequence;
import io.github.dsheirer.module.decode.dcs.DCSCode;
import io.github.dsheirer.protocol.Protocol;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javafx.beans.InvalidationListener;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * List of aliases that share the same alias list name and provides convenient methods for looking up alias
 * objects that match an identifier.
 */
public class AliasList
{
    private static final Logger mLog = LoggerFactory.getLogger(AliasList.class);
    private final Object mMutationLock = new Object();
    private final Map<Alias,AliasObserver> mAliasObservers = new IdentityHashMap<>();
    private volatile LookupIndex mLookupIndex = new LookupIndex();
    private boolean mRebuilding;
    private final String mName;
    private final ObservableList<Alias> mAliases = FXCollections.observableArrayList(Alias.extractor());

    /**
     * List of aliases where all aliases share the same list name.  Contains
     * several methods for alias lookup from identifier values, like talkgroups.
     *
     * Responds to alias change events to keep the internal alias list updated.
     */
    public AliasList(String name)
    {
        mName = name;
    }

    /**
     * Observable list of aliases contained in this alias list
     */
    public ObservableList<Alias> aliases()
    {
        return mAliases;
    }

    /**
     * Adds the alias to this list
     */
    public void addAlias(Alias alias)
    {
        if(alias == null)
        {
            return;
        }

        synchronized(mMutationLock)
        {
            if(!mAliasObservers.containsKey(alias))
            {
                mAliases.add(alias);
                observe(alias);
                rebuildIndexes();
            }
            else
            {
                rebuildIndexes();
            }
        }
    }

    /**
     * Adds a collection of aliases and publishes one rebuilt lookup snapshot.  This is the preferred path for loading
     * large alias lists because lookup remains lock-free and the index is rebuilt only once.
     */
    public void addAliases(Collection<? extends Alias> aliases)
    {
        if(aliases == null || aliases.isEmpty())
        {
            return;
        }

        synchronized(mMutationLock)
        {
            boolean changed = false;

            for(Alias alias: aliases)
            {
                if(alias != null && !mAliasObservers.containsKey(alias))
                {
                    mAliases.add(alias);
                    observe(alias);
                    changed = true;
                }
            }

            if(changed)
            {
                rebuildIndexes();
            }
        }
    }

    /**
     * Adds the alias and alias identifier to the internal type mapping.
     */
    private void addAliasID(AliasID id, Alias alias, LookupIndex index)
    {
        if(id != null && id.isValid())
        {
            try
            {
                switch(id.getType())
                {
                    case TALKGROUP:
                        Talkgroup talkgroup = (Talkgroup)id;
                        Protocol talkgroupProtocol = Objects.requireNonNull(talkgroup.getProtocol());

                        TalkgroupAliasList talkgroupAliasList = index.mTalkgroupProtocolMap.get(talkgroupProtocol);

                        if(talkgroupAliasList == null)
                        {
                            talkgroupAliasList = new TalkgroupAliasList();
                            index.mTalkgroupProtocolMap.put(talkgroupProtocol, talkgroupAliasList);
                        }

                        talkgroupAliasList.add(talkgroup, alias);
                        break;
                    case TALKGROUP_RANGE:
                        TalkgroupRange talkgroupRange = (TalkgroupRange)id;
                        Protocol talkgroupRangeProtocol = Objects.requireNonNull(talkgroupRange.getProtocol());

                        TalkgroupAliasList talkgroupRangeAliasList = index.mTalkgroupProtocolMap.get(talkgroupRangeProtocol);

                        if(talkgroupRangeAliasList == null)
                        {
                            talkgroupRangeAliasList = new TalkgroupAliasList();
                            index.mTalkgroupProtocolMap.put(talkgroupRangeProtocol, talkgroupRangeAliasList);
                        }

                        talkgroupRangeAliasList.add(talkgroupRange, alias);
                        break;
                    case P25_FULLY_QUALIFIED_RADIO_ID:
                        P25FullyQualifiedRadio qualifiedRadio = (P25FullyQualifiedRadio) id;
                        Protocol qualifiedRadioProtocol = Objects.requireNonNull(qualifiedRadio.getProtocol());

                        RadioAliasList p25RadioAliasList = index.mRadioProtocolMap.get(qualifiedRadioProtocol);

                        if(p25RadioAliasList == null)
                        {
                            p25RadioAliasList = new RadioAliasList();
                            index.mRadioProtocolMap.put(qualifiedRadioProtocol, p25RadioAliasList);
                        }

                        p25RadioAliasList.add(qualifiedRadio, alias);
                        break;
                    case P25_FULLY_QUALIFIED_TALKGROUP:
                        P25FullyQualifiedTalkgroup qualifiedTalkgroup = (P25FullyQualifiedTalkgroup) id;
                        Protocol qualifiedTalkgroupProtocol = Objects.requireNonNull(qualifiedTalkgroup.getProtocol());

                        TalkgroupAliasList p25TalkgroupAliasList = index.mTalkgroupProtocolMap.get(qualifiedTalkgroupProtocol);

                        if(p25TalkgroupAliasList == null)
                        {
                            p25TalkgroupAliasList = new TalkgroupAliasList();
                            index.mTalkgroupProtocolMap.put(qualifiedTalkgroupProtocol, p25TalkgroupAliasList);
                        }

                        p25TalkgroupAliasList.add(qualifiedTalkgroup, alias);
                        break;
                    case RADIO_ID:
                        Radio radio = (Radio)id;
                        Protocol radioProtocol = Objects.requireNonNull(radio.getProtocol());

                        RadioAliasList radioAliasList = index.mRadioProtocolMap.get(radioProtocol);

                        if(radioAliasList == null)
                        {
                            radioAliasList = new RadioAliasList();
                            index.mRadioProtocolMap.put(radioProtocol, radioAliasList);
                        }

                        radioAliasList.add(radio, alias);
                        break;
                    case RADIO_ID_RANGE:
                        RadioRange radioRange = (RadioRange)id;
                        Protocol radioRangeProtocol = Objects.requireNonNull(radioRange.getProtocol());

                        RadioAliasList radioRangeAliasList = index.mRadioProtocolMap.get(radioRangeProtocol);

                        if(radioRangeAliasList == null)
                        {
                            radioRangeAliasList = new RadioAliasList();
                            index.mRadioProtocolMap.put(radioRangeProtocol, radioRangeAliasList);
                        }

                        radioRangeAliasList.add(radioRange, alias);
                        break;
                    case DCS:
                        if(id instanceof Dcs dcs)
                        {
                            DCSCode dcsCode = Objects.requireNonNull(dcs.getDCSCode());
                            Alias existingDcsAlias = index.mDCSCodeAliasMap.get(dcsCode);

                            if(existingDcsAlias != null && !existingDcsAlias.equals(alias))
                            {
                                dcs.setOverlap(true);

                                for(AliasID aliasID: existingDcsAlias.getAliasIdentifiers())
                                {
                                    if(aliasID instanceof Dcs existingDcs &&
                                        Objects.equals(existingDcs.getDCSCode(), dcsCode))
                                    {
                                        existingDcs.setOverlap(true);
                                    }
                                }
                            }

                            index.mDCSCodeAliasMap.put(dcsCode, alias);
                        }
                        break;
                    case ESN:
                        String esn = ((Esn)id).getEsn();

                        if(esn != null && !esn.isEmpty())
                        {
                            index.mESNMap.put(esn.toLowerCase(), alias);
                        }
                        break;
                    case STATUS:
                        int userStatus = ((UserStatusID)id).getStatus();

                        Alias existingUserStatusAlias = index.mUserStatusMap.computeIfAbsent(userStatus, key -> alias);

                        if(!existingUserStatusAlias.equals(alias))
                        {
                            id.setOverlap(true);

                            for(AliasID aliasID: existingUserStatusAlias.getAliasIdentifiers())
                            {
                                if(aliasID instanceof UserStatusID userStatusID && userStatusID.getStatus() == userStatus)
                                {
                                    aliasID.setOverlap(true);
                                }
                            }
                        }
                        index.mUserStatusMap.put(userStatus, alias);
                        break;
                    case UNIT_STATUS:
                        int unitStatus = ((UnitStatusID)id).getStatus();

                        Alias existingUnitStatusAlias = index.mUnitStatusMap.computeIfAbsent(unitStatus, key -> alias);

                        if(!existingUnitStatusAlias.equals(alias))
                        {
                            id.setOverlap(true);

                            for(AliasID aliasID: existingUnitStatusAlias.getAliasIdentifiers())
                            {
                                if(aliasID instanceof UnitStatusID unitStatusID && unitStatusID.getStatus() == unitStatus)
                                {
                                    aliasID.setOverlap(true);
                                }
                            }
                        }
                        index.mUnitStatusMap.put(unitStatus, alias);
                        break;
                    case TONES:
                        ToneSequence toneSequence = ((TonesID)id).getToneSequence();

                        if(toneSequence != null)
                        {
                            Alias existingToneSequenceAlias = index.mToneSequenceMap.computeIfAbsent(toneSequence,
                                key -> alias);

                            if(!existingToneSequenceAlias.equals(alias))
                            {
                                id.setOverlap(true);

                                for(AliasID aliasID: existingToneSequenceAlias.getAliasIdentifiers())
                                {
                                    if(aliasID instanceof TonesID && aliasID.equals(id))
                                    {
                                        aliasID.setOverlap(true);
                                    }
                                }
                            }
                        }
                        break;
                    default:
                        // These identifier types don't participate in AliasList lookup indexes.
                        break;
                }
            }
            catch(Exception _)
            {
                mLog.error("Couldn't add alias ID {} for alias {}", id, alias);
            }
        }
    }

    /**
     * Removes the alias from this list
     */
    public void removeAlias(Alias alias)
    {
        if(alias == null)
        {
            return;
        }

        synchronized(mMutationLock)
        {
            if(mAliases.remove(alias))
            {
                unobserve(alias);
                rebuildIndexes();
            }
        }
    }

    /**
     * Removes a collection of aliases and publishes one rebuilt lookup snapshot.
     */
    public void removeAliases(Collection<? extends Alias> aliases)
    {
        if(aliases == null || aliases.isEmpty())
        {
            return;
        }

        synchronized(mMutationLock)
        {
            Set<Alias> aliasesToRemove = Collections.newSetFromMap(new IdentityHashMap<>());
            aliasesToRemove.addAll(aliases);

            if(aliasesToRemove.isEmpty())
            {
                return;
            }

            boolean changed = mAliases.removeIf(aliasesToRemove::contains);

            if(changed)
            {
                aliasesToRemove.forEach(this::unobserve);
                rebuildIndexes();
            }
        }
    }

    /**
     * Rebuilds all lookup indexes and recalculates overlap flags.
     */
    public void validate()
    {
        synchronized(mMutationLock)
        {
            rebuildIndexes();
        }
    }

    /**
     * Lookup alias by ESN
     */
    public Alias getESNAlias(String esn)
    {
        Alias alias = null;

        if(esn != null)
        {
            alias = mLookupIndex.mESNMap.get(esn.toLowerCase());
        }

        return alias;
    }

    /**
     * Alias list name
     */
    public String toString()
    {
        return mName;
    }

    /**
     * Alias list name
     */
    public String getName()
    {
        return mName;
    }

    /**
     * Indicates if this alias list has a non-null, non-empty name
     */
    private boolean hasName()
    {
        return mName != null && !mName.isEmpty();
    }

    /**
     * Updates the alias by removing it from this list and then adding it back to this list when the list name matches.
     */
    public void updateAlias(Alias alias)
    {
        if(alias == null)
        {
            return;
        }

        synchronized(mMutationLock)
        {
            if(mRebuilding)
            {
                return;
            }

            boolean contains = mAliasObservers.containsKey(alias);
            boolean belongs = belongsToThisList(alias);

            if(contains && !belongs)
            {
                mAliases.remove(alias);
                unobserve(alias);
                rebuildIndexes();
            }
            else if(!contains && belongs)
            {
                mAliases.add(alias);
                observe(alias);
                rebuildIndexes();
            }
            else if(contains)
            {
                rebuildIndexes();
            }
        }
    }

    /**
     * Reconciles a group of changed aliases and rebuilds at most once.  AliasModel uses this for bulk edits and moves.
     */
    public void updateAliases(Collection<? extends Alias> aliases)
    {
        if(aliases == null || aliases.isEmpty())
        {
            return;
        }

        synchronized(mMutationLock)
        {
            if(mRebuilding)
            {
                return;
            }

            boolean rebuild = false;
            Set<Alias> aliasesToRemove = Collections.newSetFromMap(new IdentityHashMap<>());
            List<Alias> aliasesToAdd = new ArrayList<>();

            for(Alias alias: aliases)
            {
                if(alias == null)
                {
                    continue;
                }

                boolean contains = mAliasObservers.containsKey(alias);
                boolean belongs = belongsToThisList(alias);

                if(contains && !belongs)
                {
                    aliasesToRemove.add(alias);
                    rebuild = true;
                }
                else if(!contains && belongs)
                {
                    aliasesToAdd.add(alias);
                    rebuild = true;
                }
            }

            if(!aliasesToRemove.isEmpty())
            {
                mAliases.removeIf(aliasesToRemove::contains);
                aliasesToRemove.forEach(this::unobserve);
            }

            for(Alias alias: aliasesToAdd)
            {
                mAliases.add(alias);
                observe(alias);
            }

            if(rebuild)
            {
                rebuildIndexes();
            }
        }
    }

    private boolean belongsToThisList(Alias alias)
    {
        return hasName() && alias.getAliasListName() != null && getName().equalsIgnoreCase(alias.getAliasListName());
    }

    private void observe(Alias alias)
    {
        if(!mAliasObservers.containsKey(alias))
        {
            AliasObserver observer = new AliasObserver(alias);
            mAliasObservers.put(alias, observer);
            observer.attach();
        }
    }

    private void unobserve(Alias alias)
    {
        AliasObserver observer = mAliasObservers.remove(alias);

        if(observer != null)
        {
            observer.detach();
        }

    }

    private void aliasLookupConfigurationChanged(Alias alias)
    {
        synchronized(mMutationLock)
        {
            if(mRebuilding || !mAliasObservers.containsKey(alias))
            {
                return;
            }

            rebuildIndexes();
        }
    }

    private void aliasListMembershipChanged(Alias alias)
    {
        synchronized(mMutationLock)
        {
            if(mRebuilding || !mAliasObservers.containsKey(alias))
            {
                return;
            }

            if(!belongsToThisList(alias))
            {
                mAliases.remove(alias);
                unobserve(alias);
                rebuildIndexes();
            }
        }
    }

    /**
     * Builds a complete replacement index before publishing it.  Decoder/audio lookup reads only the volatile
     * snapshot and never waits for an alias edit, move, or delete.
     */
    private void rebuildIndexes()
    {
        mRebuilding = true;

        try
        {
            LookupIndex rebuilt = new LookupIndex();

            for(Alias alias: mAliases)
            {
                for(AliasID aliasID: alias.getAliasIdentifiers())
                {
                    if(aliasID.overlapProperty().get())
                    {
                        aliasID.setOverlap(false);
                    }
                }
            }

            for(Alias alias: mAliases)
            {
                for(AliasID aliasID: alias.getAliasIdentifiers())
                {
                    addAliasID(aliasID, alias, rebuilt);
                }

                if(alias.hasActions())
                {
                    rebuilt.mHasAliasActions = true;
                }

            }

            rebuilt.prepare();
            mLookupIndex = rebuilt;
        }
        finally
        {
            mRebuilding = false;
        }
    }

    private boolean isLookupIdentifier(AliasID aliasID)
    {
        if(aliasID == null)
        {
            return false;
        }

        return switch(aliasID.getType())
        {
            case TALKGROUP, TALKGROUP_RANGE, P25_FULLY_QUALIFIED_TALKGROUP, RADIO_ID, RADIO_ID_RANGE,
                 P25_FULLY_QUALIFIED_RADIO_ID, DCS, ESN, STATUS, UNIT_STATUS, TONES -> true;
            default -> false;
        };
    }

    private class AliasObserver
    {
        private final Alias mAlias;
        private final InvalidationListener mAliasIDValueListener;
        private final InvalidationListener mAliasListNameListener;
        private final ListChangeListener<AliasID> mAliasIDListListener;
        private final ListChangeListener<AliasAction> mAliasActionListener;

        private AliasObserver(Alias alias)
        {
            mAlias = alias;
            mAliasIDValueListener = observable -> aliasLookupConfigurationChanged(mAlias);
            mAliasListNameListener = observable -> aliasListMembershipChanged(mAlias);
            mAliasIDListListener = change -> {
                boolean lookupChanged = false;

                while(change.next())
                {
                    if(change.wasRemoved())
                    {
                        change.getRemoved().forEach(this::detach);
                        lookupChanged |= change.getRemoved().stream().anyMatch(AliasList.this::isLookupIdentifier);
                    }

                    if(change.wasAdded())
                    {
                        change.getAddedSubList().forEach(this::attach);
                        lookupChanged |= change.getAddedSubList().stream()
                            .anyMatch(AliasList.this::isLookupIdentifier);
                    }
                }

                if(lookupChanged)
                {
                    aliasLookupConfigurationChanged(mAlias);
                }
            };
            mAliasActionListener = change -> aliasLookupConfigurationChanged(mAlias);
        }

        private void attach()
        {
            mAlias.aliasIds().forEach(this::attach);
            mAlias.aliasIds().addListener(mAliasIDListListener);
            mAlias.aliasActions().addListener(mAliasActionListener);
            mAlias.aliasListNameProperty().addListener(mAliasListNameListener);
        }

        private void detach()
        {
            mAlias.aliasIds().removeListener(mAliasIDListListener);
            mAlias.aliasActions().removeListener(mAliasActionListener);
            mAlias.aliasListNameProperty().removeListener(mAliasListNameListener);
            mAlias.aliasIds().forEach(this::detach);
        }

        private void attach(AliasID aliasID)
        {
            if(isLookupIdentifier(aliasID))
            {
                aliasID.valueProperty().addListener(mAliasIDValueListener);
            }
        }

        private void detach(AliasID aliasID)
        {
            if(isLookupIdentifier(aliasID))
            {
                aliasID.valueProperty().removeListener(mAliasIDValueListener);
            }
        }
    }

    /**
     * Returns an optional alias that is associated with the identifier
      * @param identifier to alias
     * @return list of alias or empty list
     */
    public List<Alias> getAliases(Identifier<?> identifier)
    {
        if(identifier != null)
        {
            LookupIndex index = mLookupIndex;

            switch(identifier.getForm())
            {
                case TALKGROUP:
                    TalkgroupIdentifier talkgroup = (TalkgroupIdentifier)identifier;

                    TalkgroupAliasList talkgroupAliasList = index.mTalkgroupProtocolMap.get(identifier.getProtocol());

                    if(talkgroupAliasList != null)
                    {
                        return toList(talkgroupAliasList.getAlias(talkgroup));
                    }
                    break;
                case PATCH_GROUP:
                    List<Alias> aliases = new ArrayList<>();

                    PatchGroupIdentifier patchGroupIdentifier = (PatchGroupIdentifier)identifier;
                    PatchGroup patchGroup = patchGroupIdentifier.getValue();

                    TalkgroupAliasList patchGroupAliasList =
                        index.mTalkgroupProtocolMap.get(patchGroupIdentifier.getProtocol());

                    if(patchGroupAliasList != null)
                    {
                        Alias alias = patchGroupAliasList.getAlias(patchGroup.getPatchGroup());

                        if(alias != null)
                        {
                            aliases.add(alias);
                        }

                        for(TalkgroupIdentifier patchedTalkgroup: patchGroup.getPatchedTalkgroupIdentifiers())
                        {
                            Alias patchedTalkgroupAlias = patchGroupAliasList.getAlias(patchedTalkgroup);

                            if(patchedTalkgroupAlias != null && !aliases.contains(patchedTalkgroupAlias))
                            {
                                aliases.add(patchedTalkgroupAlias);
                            }
                        }
                    }

                    if(patchGroup.hasPatchedRadios())
                    {
                        RadioAliasList radioAliasList = index.mRadioProtocolMap.get(patchGroupIdentifier.getProtocol());

                        if(radioAliasList != null)
                        {
                            for(RadioIdentifier patchedRadio: patchGroup.getPatchedRadioIdentifiers())
                            {
                                Alias patchedRadioAlias = radioAliasList.getAlias(patchedRadio);

                                if(patchedRadioAlias != null && !aliases.contains(patchedRadioAlias))
                                {
                                    aliases.add(patchedRadioAlias);
                                }
                            }
                        }
                    }

                    return aliases;
                case RADIO:
                    RadioIdentifier radio = (RadioIdentifier)identifier;

                    RadioAliasList radioAliasList = index.mRadioProtocolMap.get(identifier.getProtocol());

                    if(radioAliasList != null)
                    {
                        return toList(radioAliasList.getAlias(radio));
                    }
                    break;
                case ESN:
                    if(identifier instanceof ESNIdentifier esnidentifier)
                    {
                        return toList(getESNAlias(esnidentifier.getValue()));
                    }
                    break;
                case UNIT_STATUS:
                    if(identifier instanceof UnitStatusIdentifier unitstatusidentifier)
                    {
                        int status = unitstatusidentifier.getValue();
                        return toList(index.mUnitStatusMap.get(status));
                    }
                    break;
                case USER_STATUS:
                    if(identifier instanceof UserStatusIdentifier userstatusidentifier)
                    {
                        int status = userstatusidentifier.getValue();
                        return toList(index.mUserStatusMap.get(status));
                    }
                    break;
                case TONE:
                    if(identifier instanceof ToneIdentifier toneIdentifier)
                    {
                        ToneSequence toneSequence = toneIdentifier.getValue();

                        if(toneSequence != null && toneSequence.hasTones())
                        {
                            for(Map.Entry<ToneSequence,Alias> entry: index.mToneSequenceMap.entrySet())
                            {
                                if(entry.getKey().isContainedIn(toneSequence))
                                {
                                    return toList(entry.getValue());
                                }
                            }
                        }
                    }
                    else if(identifier instanceof DCSIdentifier dcsIdentifier)
                    {
                        DCSCode dcsCode = dcsIdentifier.getValue();

                        if(dcsCode != null)
                        {
                            return toList(index.mDCSCodeAliasMap.get(dcsCode));
                        }
                    }
                    break;
                default:
                    // Remaining identifier forms are not indexed in AliasList.
                    break;
            }
        }

        return Collections.emptyList();
    }

    private static List<Alias> toList(Alias alias)
    {
        if(alias != null)
        {
            return Collections.singletonList(alias);
        }

        return Collections.emptyList();
    }

    /**
     * Indicates if any of the identifiers contain a broadcast channel for streaming of audio.
     * @param identifierCollection to inspect
     * @return true if the identifier collection is designated for streaming to one or more channels.
     */
    public boolean isStreamable(IdentifierCollection identifierCollection)
    {
        for(Identifier<?> identifier: identifierCollection.getIdentifiers())
        {
            List<Alias> aliases = getAliases(identifier);

            for(Alias alias: aliases)
            {
                if(alias != null && alias.isStreamable())
                {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Indicates if any of the identifiers have been identified for recording.
     * @param identifierCollection to inspect
     * @return true if recordable.
     */
    public boolean isRecordable(IdentifierCollection identifierCollection)
    {
        for(Identifier<?> identifier: identifierCollection.getIdentifiers())
        {
            List<Alias> aliases = getAliases(identifier);

            for(Alias alias: aliases)
            {
                if(alias != null && alias.isRecordable())
                {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Indicates if any of the aliases in this list have an associated alias action
     */
    public boolean hasAliasActions()
    {
        return mLookupIndex.mHasAliasActions;
    }

    /**
     * Returns the lowest audio playback priority specified by aliases for identifiers in the
     * identifier collection.
     *
     * @param identifierCollection to inspect for audio priority
     * @return audio playback priority
     */
    public int getAudioPlaybackPriority(IdentifierCollection identifierCollection)
    {
        int priority = Priority.DEFAULT_PRIORITY;

        for(Identifier<?> identifier: identifierCollection.getIdentifiers())
        {
            List<Alias> aliases = getAliases(identifier);

            for(Alias alias: aliases)
            {
                if(alias != null && alias.getPlaybackPriority() < priority)
                {
                    priority = alias.getPlaybackPriority();
                }
            }
        }

        return priority;
    }

    /**
     * Returns a list of streaming broadcast channels specified for any of the identifiers in the collection.
     *
     * @return list of broadcast channels or an empty list
     */
    public List<BroadcastChannel> getBroadcastChannels(IdentifierCollection identifierCollection)
    {
        List<BroadcastChannel> channels = new ArrayList<>();

        for(Identifier<?> identifier: identifierCollection.getIdentifiers())
        {
            List<Alias> aliases = getAliases(identifier);

            for(Alias alias: aliases)
            {
                if(alias != null && alias.isStreamable())
                {
                    for(BroadcastChannel broadcastChannel: alias.getBroadcastChannels())
                    {
                        if(!channels.contains(broadcastChannel))
                        {
                            channels.add(broadcastChannel);
                        }
                    }
                }
            }
        }

        return channels;
    }

    private class LookupIndex
    {
        private final EnumMap<@NonNull Protocol,TalkgroupAliasList> mTalkgroupProtocolMap =
            new EnumMap<>(Protocol.class);
        private final EnumMap<@NonNull Protocol,RadioAliasList> mRadioProtocolMap =
            new EnumMap<>(Protocol.class);
        private final EnumMap<@NonNull DCSCode,Alias> mDCSCodeAliasMap = new EnumMap<>(DCSCode.class);
        private final Map<String,Alias> mESNMap = new HashMap<>();
        private final Map<Integer,Alias> mUnitStatusMap = new HashMap<>();
        private final Map<Integer,Alias> mUserStatusMap = new HashMap<>();
        private final Map<ToneSequence,Alias> mToneSequenceMap = new HashMap<>();
        private boolean mHasAliasActions;

        private void prepare()
        {
            mTalkgroupProtocolMap.values().forEach(TalkgroupAliasList::prepare);
            mRadioProtocolMap.values().forEach(RadioAliasList::prepare);
        }
    }

    /**
     * Listing of talkgroups and ranges for a specific protocol
     */
    public class TalkgroupAliasList
    {
        private final Map<String,Alias> mFullyQualifiedTalkgroupAliasMap = new HashMap<>();
        private final Map<Integer,Alias> mTalkgroupAliasMap = new HashMap<>();
        private final List<TalkgroupRangeEntry> mTalkgroupRanges = new ArrayList<>();
        private int[] mTalkgroupRangePrefixMaximums = new int[0];

        public Alias getAlias(TalkgroupIdentifier identifier)
        {
            //Attempt to do a fully qualified identifier match only
            if(identifier instanceof FullyQualifiedTalkgroupIdentifier fqti)
            {
                return mFullyQualifiedTalkgroupAliasMap.get(fqti.getFullyQualifiedTalkgroupAddress());
            }

            //Attempt to match the talkgroup value
            int value = identifier.getValue();

            Alias mapValue = mTalkgroupAliasMap.get(value);
            if (mapValue != null)
            {
                return mapValue;
            }

            //Alternatively, match the talkgroup to any talkgroup ranges
            int rangeIndex = lastRangeStartingAtOrBefore(value);

            while(rangeIndex >= 0 && mTalkgroupRangePrefixMaximums[rangeIndex] >= value)
            {
                TalkgroupRangeEntry entry = mTalkgroupRanges.get(rangeIndex);

                if(entry.contains(value))
                {
                    return entry.alias();
                }

                rangeIndex--;
            }

            return null;
        }

        public void add(Talkgroup talkgroup, Alias alias)
        {
            if(talkgroup instanceof P25FullyQualifiedTalkgroup fqt)
            {
                Alias existingFullyQualifiedTalkgroupAlias =
                    mFullyQualifiedTalkgroupAliasMap.computeIfAbsent(fqt.getHashKey(), key -> alias);

                //Detect collisions
                if(!existingFullyQualifiedTalkgroupAlias.equals(alias))
                {
                    fqt.setOverlap(true);

                    for(AliasID aliasID: existingFullyQualifiedTalkgroupAlias.getAliasIdentifiers())
                    {
                        if(aliasID instanceof P25FullyQualifiedTalkgroup existingFqt &&
                                existingFqt.getHashKey().contentEquals(fqt.getHashKey()))
                        {
                            aliasID.setOverlap(true);
                        }
                    }
                }
            }
            else
            {
                Alias existingTalkgroupAlias = mTalkgroupAliasMap.computeIfAbsent(talkgroup.getValue(), key -> alias);

                //Detect talkgroup collisions and set overlap flag for both
                if(!existingTalkgroupAlias.equals(alias))
                {
                    talkgroup.setOverlap(true);

                    for(AliasID aliasID: existingTalkgroupAlias.getAliasIdentifiers())
                    {
                        if(aliasID instanceof Talkgroup existingTalkgroup &&
                            !(existingTalkgroup instanceof P25FullyQualifiedTalkgroup) &&
                            existingTalkgroup.getProtocol() == talkgroup.getProtocol() &&
                            existingTalkgroup.getValue() == talkgroup.getValue())
                        {
                            aliasID.setOverlap(true);
                        }
                    }
                }

                mTalkgroupAliasMap.put(talkgroup.getValue(), alias);
            }
        }

        public void add(TalkgroupRange talkgroupRange, Alias alias)
        {
            mTalkgroupRanges.add(new TalkgroupRangeEntry(talkgroupRange.getMinTalkgroup(),
                talkgroupRange.getMaxTalkgroup(), talkgroupRange, alias));
        }

        private void prepare()
        {
            mTalkgroupRanges.sort((first, second) -> {
                int comparison = Integer.compare(first.minimum(), second.minimum());
                return comparison != 0 ? comparison : Integer.compare(first.maximum(), second.maximum());
            });
            mTalkgroupRangePrefixMaximums = new int[mTalkgroupRanges.size()];
            TalkgroupRangeEntry maximumEntry = null;
            TalkgroupRangeEntry secondMaximumEntry = null;
            int maximum = Integer.MIN_VALUE;

            for(int index = 0; index < mTalkgroupRanges.size(); index++)
            {
                TalkgroupRangeEntry entry = mTalkgroupRanges.get(index);
                TalkgroupRangeEntry overlapCandidate = maximumEntry != null &&
                    !maximumEntry.alias().equals(entry.alias()) ? maximumEntry : secondMaximumEntry;

                if(overlapCandidate != null && entry.minimum() <= overlapCandidate.maximum())
                {
                    entry.identifier().setOverlap(true);
                }

                if(maximumEntry == null)
                {
                    maximumEntry = entry;
                }
                else if(maximumEntry.alias().equals(entry.alias()))
                {
                    if(entry.maximum() > maximumEntry.maximum())
                    {
                        maximumEntry = entry;
                    }
                }
                else if(entry.maximum() > maximumEntry.maximum())
                {
                    secondMaximumEntry = maximumEntry;
                    maximumEntry = entry;
                }
                else if(secondMaximumEntry == null)
                {
                    secondMaximumEntry = entry;
                }
                else if(secondMaximumEntry.alias().equals(entry.alias()))
                {
                    if(entry.maximum() > secondMaximumEntry.maximum())
                    {
                        secondMaximumEntry = entry;
                    }
                }
                else if(entry.maximum() > secondMaximumEntry.maximum())
                {
                    secondMaximumEntry = entry;
                }

                maximum = Math.max(maximum, entry.maximum());
                mTalkgroupRangePrefixMaximums[index] = maximum;
            }

            TalkgroupRangeEntry minimumEntry = null;
            TalkgroupRangeEntry secondMinimumEntry = null;

            for(int index = mTalkgroupRanges.size() - 1; index >= 0; index--)
            {
                TalkgroupRangeEntry entry = mTalkgroupRanges.get(index);
                TalkgroupRangeEntry overlapCandidate = minimumEntry != null &&
                    !minimumEntry.alias().equals(entry.alias()) ? minimumEntry : secondMinimumEntry;

                if(overlapCandidate != null && overlapCandidate.minimum() <= entry.maximum())
                {
                    entry.identifier().setOverlap(true);
                }

                if(minimumEntry == null)
                {
                    minimumEntry = entry;
                }
                else if(minimumEntry.alias().equals(entry.alias()))
                {
                    if(entry.minimum() < minimumEntry.minimum())
                    {
                        minimumEntry = entry;
                    }
                }
                else if(entry.minimum() < minimumEntry.minimum())
                {
                    secondMinimumEntry = minimumEntry;
                    minimumEntry = entry;
                }
                else if(secondMinimumEntry == null)
                {
                    secondMinimumEntry = entry;
                }
                else if(secondMinimumEntry.alias().equals(entry.alias()))
                {
                    if(entry.minimum() < secondMinimumEntry.minimum())
                    {
                        secondMinimumEntry = entry;
                    }
                }
                else if(entry.minimum() < secondMinimumEntry.minimum())
                {
                    secondMinimumEntry = entry;
                }
            }
        }

        private int lastRangeStartingAtOrBefore(int value)
        {
            int low = 0;
            int high = mTalkgroupRanges.size() - 1;
            int result = -1;

            while(low <= high)
            {
                int middle = (low + high) >>> 1;

                if(mTalkgroupRanges.get(middle).minimum() <= value)
                {
                    result = middle;
                    low = middle + 1;
                }
                else
                {
                    high = middle - 1;
                }
            }

            return result;
        }

        /**
         * Removes the alias from both the talkgroup and the talkgroup range maps.
         */
        public void remove(Alias alias)
        {
            mFullyQualifiedTalkgroupAliasMap.values().removeAll(Collections.singleton(alias));
            mTalkgroupAliasMap.values().removeAll(Collections.singleton(alias));
            mTalkgroupRanges.removeIf(entry -> entry.alias().equals(alias));
        }
    }

    private record TalkgroupRangeEntry(int minimum, int maximum, TalkgroupRange identifier, Alias alias)
    {
        private boolean contains(int value)
        {
            return minimum <= value && value <= maximum;
        }

    }

    /**
     * Listing of radio IDs and ranges for a specific protocol
     */
    public class RadioAliasList
    {
        private final Map<String,Alias> mFullyQualifiedRadioAliasMap = new HashMap<>();
        private final Map<Integer,Alias> mRadioAliasMap = new HashMap<>();
        private final List<RadioRangeEntry> mRadioRanges = new ArrayList<>();
        private int[] mRadioRangePrefixMaximums = new int[0];

        public Alias getAlias(RadioIdentifier identifier)
        {
            //Match fully qualified identifier only.
            if(identifier instanceof FullyQualifiedRadioIdentifier fqri)
            {
                return mFullyQualifiedRadioAliasMap.get(fqri.getFullyQualifiedRadioAddress());
            }

            //Attempt to match against the radio identifier
            int value = identifier.getValue();

            Alias mapValue = mRadioAliasMap.get(value);
            if(mapValue != null)
            {
                return mapValue;
            }

            //Alternatively, attempt to match the radio address against any radio ranges.
            int rangeIndex = lastRangeStartingAtOrBefore(value);

            while(rangeIndex >= 0 && mRadioRangePrefixMaximums[rangeIndex] >= value)
            {
                RadioRangeEntry entry = mRadioRanges.get(rangeIndex);

                if(entry.contains(value))
                {
                    return entry.alias();
                }

                rangeIndex--;
            }

            return null;
        }

        public void add(Radio radio, Alias alias)
        {
            if(radio instanceof P25FullyQualifiedRadio fqr)
            {
                Alias existingFullyQualifiedRadioAlias =
                    mFullyQualifiedRadioAliasMap.computeIfAbsent(fqr.getHashKey(), key -> alias);

                //Detect collisions
                if(!existingFullyQualifiedRadioAlias.equals(alias))
                {
                    fqr.setOverlap(true);

                    for(AliasID aliasID: existingFullyQualifiedRadioAlias.getAliasIdentifiers())
                    {
                        if(aliasID instanceof P25FullyQualifiedRadio existingFqr &&
                                existingFqr.getHashKey().contentEquals(fqr.getHashKey()))
                        {
                            aliasID.setOverlap(true);
                        }
                    }
                }
            }
            else
            {
                Alias existingRadioAlias = mRadioAliasMap.computeIfAbsent(radio.getValue(), key -> alias);

                //Detect collisions
                if(!existingRadioAlias.equals(alias))
                {
                    radio.setOverlap(true);

                    for(AliasID aliasID: existingRadioAlias.getAliasIdentifiers())
                    {
                        if(aliasID instanceof Radio existingRadio &&
                            !(existingRadio instanceof P25FullyQualifiedRadio) &&
                            existingRadio.getProtocol() == radio.getProtocol() &&
                            existingRadio.getValue() == radio.getValue())
                        {
                            aliasID.setOverlap(true);
                        }
                    }
                }

                mRadioAliasMap.put(radio.getValue(), alias);
            }
        }

        public void add(RadioRange radioRange, Alias alias)
        {
            mRadioRanges.add(new RadioRangeEntry(radioRange.getMinRadio(), radioRange.getMaxRadio(), radioRange, alias));
        }

        private void prepare()
        {
            mRadioRanges.sort((first, second) -> {
                int comparison = Integer.compare(first.minimum(), second.minimum());
                return comparison != 0 ? comparison : Integer.compare(first.maximum(), second.maximum());
            });
            mRadioRangePrefixMaximums = new int[mRadioRanges.size()];
            RadioRangeEntry maximumEntry = null;
            RadioRangeEntry secondMaximumEntry = null;
            int maximum = Integer.MIN_VALUE;

            for(int index = 0; index < mRadioRanges.size(); index++)
            {
                RadioRangeEntry entry = mRadioRanges.get(index);
                RadioRangeEntry overlapCandidate = maximumEntry != null &&
                    !maximumEntry.alias().equals(entry.alias()) ? maximumEntry : secondMaximumEntry;

                if(overlapCandidate != null && entry.minimum() <= overlapCandidate.maximum())
                {
                    entry.identifier().setOverlap(true);
                }

                if(maximumEntry == null)
                {
                    maximumEntry = entry;
                }
                else if(maximumEntry.alias().equals(entry.alias()))
                {
                    if(entry.maximum() > maximumEntry.maximum())
                    {
                        maximumEntry = entry;
                    }
                }
                else if(entry.maximum() > maximumEntry.maximum())
                {
                    secondMaximumEntry = maximumEntry;
                    maximumEntry = entry;
                }
                else if(secondMaximumEntry == null)
                {
                    secondMaximumEntry = entry;
                }
                else if(secondMaximumEntry.alias().equals(entry.alias()))
                {
                    if(entry.maximum() > secondMaximumEntry.maximum())
                    {
                        secondMaximumEntry = entry;
                    }
                }
                else if(entry.maximum() > secondMaximumEntry.maximum())
                {
                    secondMaximumEntry = entry;
                }

                maximum = Math.max(maximum, entry.maximum());
                mRadioRangePrefixMaximums[index] = maximum;
            }

            RadioRangeEntry minimumEntry = null;
            RadioRangeEntry secondMinimumEntry = null;

            for(int index = mRadioRanges.size() - 1; index >= 0; index--)
            {
                RadioRangeEntry entry = mRadioRanges.get(index);
                RadioRangeEntry overlapCandidate = minimumEntry != null &&
                    !minimumEntry.alias().equals(entry.alias()) ? minimumEntry : secondMinimumEntry;

                if(overlapCandidate != null && overlapCandidate.minimum() <= entry.maximum())
                {
                    entry.identifier().setOverlap(true);
                }

                if(minimumEntry == null)
                {
                    minimumEntry = entry;
                }
                else if(minimumEntry.alias().equals(entry.alias()))
                {
                    if(entry.minimum() < minimumEntry.minimum())
                    {
                        minimumEntry = entry;
                    }
                }
                else if(entry.minimum() < minimumEntry.minimum())
                {
                    secondMinimumEntry = minimumEntry;
                    minimumEntry = entry;
                }
                else if(secondMinimumEntry == null)
                {
                    secondMinimumEntry = entry;
                }
                else if(secondMinimumEntry.alias().equals(entry.alias()))
                {
                    if(entry.minimum() < secondMinimumEntry.minimum())
                    {
                        secondMinimumEntry = entry;
                    }
                }
                else if(entry.minimum() < secondMinimumEntry.minimum())
                {
                    secondMinimumEntry = entry;
                }
            }
        }

        private int lastRangeStartingAtOrBefore(int value)
        {
            int low = 0;
            int high = mRadioRanges.size() - 1;
            int result = -1;

            while(low <= high)
            {
                int middle = (low + high) >>> 1;

                if(mRadioRanges.get(middle).minimum() <= value)
                {
                    result = middle;
                    low = middle + 1;
                }
                else
                {
                    high = middle - 1;
                }
            }

            return result;
        }

        /**
         * Removes the alias from both the radio and the radio range maps.
         */
        public void remove(Alias alias)
        {
            mFullyQualifiedRadioAliasMap.values().removeAll(Collections.singleton(alias));
            mRadioAliasMap.values().removeAll(Collections.singleton(alias));
            mRadioRanges.removeIf(entry -> entry.alias().equals(alias));
        }
    }

    private record RadioRangeEntry(int minimum, int maximum, RadioRange identifier, Alias alias)
    {
        private boolean contains(int value)
        {
            return minimum <= value && value <= maximum;
        }

    }
}
