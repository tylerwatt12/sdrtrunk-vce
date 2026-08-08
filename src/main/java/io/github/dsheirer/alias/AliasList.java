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

import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.dcs.Dcs;
import io.github.dsheirer.alias.id.esn.Esn;
import io.github.dsheirer.alias.id.radio.P25FullyQualifiedRadio;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.alias.id.radio.RadioRange;
import io.github.dsheirer.alias.id.status.UnitStatusID;
import io.github.dsheirer.alias.id.status.UserStatusID;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.alias.id.talkgroup.TalkgroupRange;
import io.github.dsheirer.alias.id.tone.TonesID;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.dcs.DCSIdentifier;
import io.github.dsheirer.identifier.esn.ESNIdentifier;
import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.identifier.patch.PatchGroupIdentifier;
import io.github.dsheirer.identifier.radio.FullyQualifiedRadioIdentifier;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.identifier.status.UnitStatusIdentifier;
import io.github.dsheirer.identifier.status.UserStatusIdentifier;
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
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
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
    private final AliasListDefinition mDefinition;
    private final ObservableList<Alias> mAliases = FXCollections.observableArrayList(Alias.extractor());

    /**
     * List of aliases where all aliases share the same list name.  Contains
     * several methods for alias lookup from identifier values, like talkgroups.
     *
     * Responds to alias change events to keep the internal alias list updated.
     */
    private AliasList(String name)
    {
        mName = name;
        mDefinition = null;
    }

    public static AliasList empty(String name)
    {
        return new AliasList(name);
    }

    public AliasList(AliasListDefinition definition)
    {
        mDefinition = definition;
        mName = definition != null ? definition.getName() : null;
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

        requireOperationalMatcher(alias);

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
                    requireOperationalMatcher(alias);
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
        if(id != null && id.isValid() && AliasMatchRegistry.supports(mDefinition, id))
        {
            try
            {
                switch(id.getType())
                {
                    case TALKGROUP:
                        Talkgroup talkgroup = (Talkgroup)id;
                        Protocol talkgroupProtocol =
                            lookupProtocol(Objects.requireNonNull(talkgroup.getProtocol()));

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
                        Protocol talkgroupRangeProtocol =
                            lookupProtocol(Objects.requireNonNull(talkgroupRange.getProtocol()));

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
                        Protocol qualifiedRadioProtocol =
                            lookupProtocol(Objects.requireNonNull(qualifiedRadio.getProtocol()));

                        RadioAliasList p25RadioAliasList = index.mRadioProtocolMap.get(qualifiedRadioProtocol);

                        if(p25RadioAliasList == null)
                        {
                            p25RadioAliasList = new RadioAliasList();
                            index.mRadioProtocolMap.put(qualifiedRadioProtocol, p25RadioAliasList);
                        }

                        p25RadioAliasList.add(qualifiedRadio, alias);
                        break;
                    case RADIO_ID:
                        Radio radio = (Radio)id;
                        Protocol radioProtocol = lookupProtocol(Objects.requireNonNull(radio.getProtocol()));

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
                        Protocol radioRangeProtocol =
                            lookupProtocol(Objects.requireNonNull(radioRange.getProtocol()));

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

                                AliasID aliasID = existingDcsAlias.getMatchIdentifier();

                                if(aliasID instanceof Dcs existingDcs &&
                                    Objects.equals(existingDcs.getDCSCode(), dcsCode))
                                {
                                    existingDcs.setOverlap(true);
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

                            AliasID aliasID = existingUserStatusAlias.getMatchIdentifier();

                            if(aliasID instanceof UserStatusID userStatusID &&
                                userStatusID.getStatus() == userStatus)
                            {
                                aliasID.setOverlap(true);
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

                            AliasID aliasID = existingUnitStatusAlias.getMatchIdentifier();

                            if(aliasID instanceof UnitStatusID unitStatusID &&
                                unitStatusID.getStatus() == unitStatus)
                            {
                                aliasID.setOverlap(true);
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

                                AliasID aliasID = existingToneSequenceAlias.getMatchIdentifier();

                                if(aliasID instanceof TonesID && aliasID.equals(id))
                                {
                                    aliasID.setOverlap(true);
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
        return getName();
    }

    /**
     * Alias list name
     */
    public String getName()
    {
        return mDefinition != null ? mDefinition.getName() : mName;
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
        return alias != null && alias.belongsTo(mDefinition);
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
                AliasID aliasID = alias.getMatchIdentifier();

                if(aliasID != null && aliasID.overlapProperty().get())
                {
                    aliasID.setOverlap(false);
                }
            }

            for(Alias alias: mAliases)
            {
                addAliasID(alias.getMatchIdentifier(), alias, rebuilt);

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
        return AliasMatchRegistry.isOperational(mDefinition, aliasID);
    }

    private void requireOperationalMatcher(Alias alias)
    {
        AliasID matcher = alias.getMatchIdentifier();

        if(!AliasMatchRegistry.isOperational(mDefinition, matcher))
        {
            throw new IllegalArgumentException("Alias [" + alias.getName() +
                "] must have one valid matcher supported by alias list [" + getName() + "]");
        }
    }

    private class AliasObserver
    {
        private final Alias mAlias;
        private final InvalidationListener mAliasIDValueListener;
        private final InvalidationListener mAliasListNameListener;
        private final ChangeListener<AliasID> mMatchIdentifierListener;

        private AliasObserver(Alias alias)
        {
            mAlias = alias;
            mAliasIDValueListener = observable -> aliasLookupConfigurationChanged(mAlias);
            mAliasListNameListener = observable -> aliasListMembershipChanged(mAlias);
            mMatchIdentifierListener = (_, previous, current) -> {
                detach(previous);
                attach(current);
                aliasLookupConfigurationChanged(mAlias);
            };
        }

        private void attach()
        {
            attach(mAlias.getMatchIdentifier());
            mAlias.matchIdentifierProperty().addListener(mMatchIdentifierListener);
            mAlias.aliasListNameProperty().addListener(mAliasListNameListener);
        }

        private void detach()
        {
            detach(mAlias.getMatchIdentifier());
            mAlias.matchIdentifierProperty().removeListener(mMatchIdentifierListener);
            mAlias.aliasListNameProperty().removeListener(mAliasListNameListener);
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

                    TalkgroupAliasList talkgroupAliasList =
                        index.mTalkgroupProtocolMap.get(lookupProtocol(identifier.getProtocol()));

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
                        index.mTalkgroupProtocolMap.get(lookupProtocol(patchGroupIdentifier.getProtocol()));

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
                        RadioAliasList radioAliasList =
                            index.mRadioProtocolMap.get(lookupProtocol(patchGroupIdentifier.getProtocol()));

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

                    RadioAliasList radioAliasList =
                        index.mRadioProtocolMap.get(lookupProtocol(identifier.getProtocol()));

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

    /**
     * Returns this list's action-only fallback when the supplied talkgroup destination has no matching real alias.
     * A patch group is considered matched when its patch talkgroup or any patched talkgroup has an exact or range
     * alias. Patched radio aliases do not turn an unknown talkgroup into a known talkgroup.
     *
     * @return immutable unmatched-talkgroup policy, or null when this is not a talkgroup destination or a real
     * alias already matches
     */
    public UnmatchedTalkgroupPolicy getUnmatchedTalkgroupPolicy(Identifier<?> identifier)
    {
        if(mDefinition == null || identifier == null || identifier.getRole() != Role.TO ||
            !supportsUnmatchedTalkgroup(mDefinition.getFamily(), identifier.getProtocol()))
        {
            return null;
        }

        LookupIndex index = mLookupIndex;

        if(identifier.getForm() == Form.TALKGROUP &&
            identifier instanceof TalkgroupIdentifier talkgroupIdentifier)
        {
            TalkgroupAliasList talkgroups =
                index.mTalkgroupProtocolMap.get(lookupProtocol(identifier.getProtocol()));
            return talkgroups == null || talkgroups.getAlias(talkgroupIdentifier) == null ?
                mDefinition.getUnmatchedTalkgroupPolicy() : null;
        }

        if(identifier.getForm() == Form.PATCH_GROUP &&
            identifier instanceof PatchGroupIdentifier patchGroupIdentifier)
        {
            PatchGroup patchGroup = patchGroupIdentifier.getValue();
            if(patchGroup == null || patchGroup.getPatchGroup() == null)
            {
                return null;
            }

            TalkgroupAliasList talkgroups =
                index.mTalkgroupProtocolMap.get(lookupProtocol(patchGroupIdentifier.getProtocol()));

            if(talkgroups == null)
            {
                return mDefinition.getUnmatchedTalkgroupPolicy();
            }

            if(talkgroups.getAlias(patchGroup.getPatchGroup()) != null)
            {
                return null;
            }

            for(TalkgroupIdentifier patchedTalkgroup: patchGroup.getPatchedTalkgroupIdentifiers())
            {
                if(talkgroups.getAlias(patchedTalkgroup) != null)
                {
                    return null;
                }
            }

            return mDefinition.getUnmatchedTalkgroupPolicy();
        }

        return null;
    }

    private static boolean supportsUnmatchedTalkgroup(AliasListFamily family, Protocol protocol)
    {
        if(family == null || protocol == null)
        {
            return false;
        }

        return switch(family)
        {
            case P25 -> protocol == Protocol.APCO25 || protocol == Protocol.APCO25_PHASE2;
            case DMR -> protocol == Protocol.DMR;
            case NXDN -> protocol == Protocol.NXDN;
            default -> false;
        };
    }

    private static List<Alias> toList(Alias alias)
    {
        if(alias != null)
        {
            return Collections.singletonList(alias);
        }

        return Collections.emptyList();
    }

    private static Protocol lookupProtocol(Protocol protocol)
    {
        return protocol == Protocol.APCO25_PHASE2 ? Protocol.APCO25 : protocol;
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
        private final Map<Integer,Alias> mTalkgroupAliasMap = new HashMap<>();
        private final List<TalkgroupRangeEntry> mTalkgroupRanges = new ArrayList<>();
        private int[] mTalkgroupRangePrefixMaximums = new int[0];

        public Alias getAlias(TalkgroupIdentifier identifier)
        {
            //P25 fully-qualified signaling carries a local talkgroup address in getValue(). Alias matching uses that
            //local address just like every other P25 talkgroup and deliberately ignores the rarely used home tuple.
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
            Alias existingTalkgroupAlias = mTalkgroupAliasMap.computeIfAbsent(talkgroup.getValue(), key -> alias);

            //Detect collisions and set overlap flag for both
            if(!existingTalkgroupAlias.equals(alias))
            {
                talkgroup.setOverlap(true);

                AliasID aliasID = existingTalkgroupAlias.getMatchIdentifier();

                if(aliasID instanceof Talkgroup existingTalkgroup &&
                    lookupProtocol(existingTalkgroup.getProtocol()) == lookupProtocol(talkgroup.getProtocol()) &&
                    existingTalkgroup.getValue() == talkgroup.getValue())
                {
                    aliasID.setOverlap(true);
                }
            }

            mTalkgroupAliasMap.put(talkgroup.getValue(), alias);
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

                    AliasID aliasID = existingFullyQualifiedRadioAlias.getMatchIdentifier();

                    if(aliasID instanceof P25FullyQualifiedRadio existingFqr &&
                        existingFqr.getHashKey().contentEquals(fqr.getHashKey()))
                    {
                        aliasID.setOverlap(true);
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

                    AliasID aliasID = existingRadioAlias.getMatchIdentifier();

                    if(aliasID instanceof Radio existingRadio &&
                        !(existingRadio instanceof P25FullyQualifiedRadio) &&
                        lookupProtocol(existingRadio.getProtocol()) == lookupProtocol(radio.getProtocol()) &&
                        existingRadio.getValue() == radio.getValue())
                    {
                        aliasID.setOverlap(true);
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
