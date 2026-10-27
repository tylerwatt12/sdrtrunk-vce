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

package io.github.dsheirer.identifier.alias;

import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.identifier.radio.FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.traffic.P25SubscriberIdentity;
import io.github.dsheirer.module.decode.traffic.RadioSystemKey;
import io.github.dsheirer.module.decode.traffic.RadioSystemIdentityKey;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNRadioIdentifier;
import io.github.dsheirer.protocol.Protocol;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;

/**
 * Talker alias cache manager.  Collects observed talker aliases and inserts them into an identifier collection when
 * the corresponding radio is active in a FROM role.
 *
 * This implementation is thread safe and is intended to be used across control and traffic channels.
 */
public class TalkerAliasManager
{
    private final AtomicReference<AliasGeneration> mGeneration = new AtomicReference<>(new AliasGeneration());

    /** O(1) retirement of a reception/system generation; retained immutable call snapshots remain valid. */
    public void clear()
    {
        mGeneration.set(new AliasGeneration());
    }

    /**
     * Administrative removal of one observed name. Channel fallback keys require the caller to select only their
     * saved channel's cache; those entries deliberately carry no invented native system identity.
     */
    public synchronized void forget(String systemKey, RadioSystemIdentityKey.Identity identity, String expectedAlias)
    {
        boolean p25 = RadioSystemKey.isP25Native(systemKey);
        boolean channelScoped = RadioSystemKey.isChannelScoped(systemKey);
        Protocol fallbackProtocol = channelScoped && systemKey.startsWith("dmr:") ? Protocol.DMR : Protocol.NXDN;
        int fallbackDomain = channelScoped && systemKey.startsWith("nxdn-d:") ? 1 : 0;
        int servingWacn = p25 ? Integer.parseInt(systemKey, 4, 9, 16) : -1;
        int servingSystem = p25 ? Integer.parseInt(systemKey, 10, 13, 16) : -1;
        int homeWacn = identity.hasHome() ? identity.homeWacn() : servingWacn;
        int homeSystem = identity.hasHome() ? identity.homeSystemId() : servingSystem;
        BiPredicate<AliasKey,TalkerAliasIdentifier> forgotten = (key, alias) ->
        {
            boolean scopeMatches = p25 ? key.protocol() == Protocol.APCO25 &&
                key.homeWacn() == homeWacn && key.homeSystem() == homeSystem &&
                alias.servingWacn() == servingWacn && alias.servingSystem() == servingSystem :
                channelScoped ? !identity.hasHome() && key.nativeSystemKey() == null &&
                    key.protocol() == fallbackProtocol && key.addressDomain() == fallbackDomain :
                    systemKey.equals(key.nativeSystemKey());
            return scopeMatches && key.radio() == identity.identityId() &&
                expectedAlias.equals(TalkerAliasText.normalize(alias.getValue()));
        };
        AliasGeneration generation = mGeneration.get();
        AliasGeneration retained = new AliasGeneration();
        // Publish the handoff before copying. In-flight unrelated observations forward to retained; the copy
        // cannot overwrite them. Decoder callbacks never take this administrative monitor.
        generation.retirement = new AliasRetirement(retained, forgotten);
        generation.forEach((key, alias) ->
        {
            if(!forgotten.test(key, alias)) retained.putIfAbsent(key, alias);
        });
        // A simultaneous reception clear retires both generations and must never be undone by this copy.
        mGeneration.compareAndSet(generation, retained);
    }

    /**
     * Updates the alias for the
     * @param identifier
     * @param alias
     */
    public void update(RadioIdentifier identifier, TalkerAliasIdentifier alias)
    {
        update(identifier, alias, null);
    }

    /** Captures a native P25 address in the transmitting call's serving scope, never a numeric roaming shortcut. */
    public void update(RadioIdentifier identifier, TalkerAliasIdentifier alias, String servingSystemKey)
    {
        //An in-flight observation may finish after clear(). It belongs only to the generation it entered.
        AliasGeneration generation = mGeneration.get();
        if(RadioSystemKey.isChannelScoped(servingSystemKey)) servingSystemKey = null;
        if(identifier == null || identifier.getRole() != Role.FROM || alias == null) return;
        TalkerAliasIdentifier owned = alias.bindTo(identifier, servingSystemKey);
        if(owned == null) return;
        //Omitted caller scope cannot turn a previously scoped observation into an unscoped cache entry.
        AliasKey key = servingSystemKey != null ? aliasKey(identifier, servingSystemKey) :
            aliasKey(identifier, owned.servingWacn(), owned.servingSystem(), owned.nativeSystemKey());
        if(key != null)
        {
            while(true)
            {
                generation.put(key, owned);
                AliasRetirement retirement = generation.retirement;
                if(retirement == null || retirement.forgotten().test(key, owned)) return;
                generation = retirement.next();
            }
        }
    }

    /**
     * Indicates if an alias exists for the identifier
     * @param radioIdentifier to test
     * @return true if an alias exists.
     */
    public boolean hasAlias(RadioIdentifier radioIdentifier)
    {
        return hasAlias(radioIdentifier, null);
    }

    public boolean hasAlias(RadioIdentifier radioIdentifier, String servingSystemKey)
    {
        AliasKey key = aliasKey(radioIdentifier, servingSystemKey);
        return key != null && mGeneration.get().containsKey(key);
    }

    /**
     * Enriches the immutable identifier collection by detecting a radio identifier with the FROM role, lookup a
     * matching alias, and insert the alias into a new mutable identifier collection.  Any existing talker alias is
     * removed first so that an alias for a previous FROM radio cannot survive a source-radio change.
     * @param originalIC to enrich
     * @return an enriched or stale-alias-cleaned collection, or the original when no change is required.
     */
    public IdentifierCollection enrich(IdentifierCollection originalIC)
    {
        return enrich(originalIC, null);
    }

    public IdentifierCollection enrich(IdentifierCollection originalIC, String servingSystemKey)
    {
        Identifier fromRadio = originalIC.getFromIdentifier();
        TalkerAliasIdentifier alias = fromRadio instanceof RadioIdentifier rid ? lookup(rid, servingSystemKey) : null;
        Identifier existingAlias = originalIC.getIdentifier(IdentifierClass.USER, Form.TALKER_ALIAS, Role.FROM);

        if(alias == null && existingAlias == null)
        {
            return originalIC;
        }

        MutableIdentifierCollection enrichedIC = new MutableIdentifierCollection(originalIC.getIdentifiers(),
            originalIC.getTimeslot());
        enrichedIC.remove(IdentifierClass.USER, Form.TALKER_ALIAS, Role.FROM);

        if(alias != null)
        {
            enrichedIC.update(alias.bindTo((RadioIdentifier)fromRadio, servingSystemKey));
        }

        return enrichedIC;
    }

    /**
     * Enriches the mutable identifier collection by detecting a radio identifier with the FROM role, lookup a
     * matching alias, and insert the alias into the mutable identifier collection argument.
     * @param mic to enrich
     */
    public void enrichMutable(MutableIdentifierCollection mic)
    {
        enrichMutable(mic, null);
    }

    public void enrichMutable(MutableIdentifierCollection mic, String servingSystemKey)
    {
        enrichMutable(mic, RadioSystemKey.isP25Native(servingSystemKey) ?
            Integer.parseInt(servingSystemKey, 4, 9, 16) : -1, RadioSystemKey.isP25Native(servingSystemKey) ?
            Integer.parseInt(servingSystemKey, 10, 13, 16) : -1, servingSystemKey);
    }

    /** Avoids key formatting on decoder callbacks that already have validated serving-system fields. */
    public void enrichMutable(MutableIdentifierCollection mic, int servingWacn, int servingSystem)
    {
        enrichMutable(mic, servingWacn, servingSystem, null);
    }

    private void enrichMutable(MutableIdentifierCollection mic, int servingWacn, int servingSystem,
                               String nativeSystemKey)
    {
        Identifier fromRadio = mic.getFromIdentifier();
        mic.remove(IdentifierClass.USER, Form.TALKER_ALIAS, Role.FROM);

        if(fromRadio instanceof RadioIdentifier rid)
        {
            AliasKey key = aliasKey(rid, servingWacn, servingSystem, nativeSystemKey);
            TalkerAliasIdentifier alias = key != null ? mGeneration.get().get(key) : null;

            if(alias != null)
            {
                mic.update(rid.getProtocol() == Protocol.APCO25 || rid.getProtocol() == Protocol.APCO25_PHASE2 ?
                    alias.bindTo(rid, servingWacn, servingSystem) : alias.bindTo(rid, nativeSystemKey));
            }
        }
    }

    private TalkerAliasIdentifier lookup(RadioIdentifier radio, String servingSystemKey)
    {
        AliasKey key = aliasKey(radio, servingSystemKey);
        return key != null ? mGeneration.get().get(key) : null;
    }

    private static final class AliasGeneration extends ConcurrentHashMap<AliasKey,TalkerAliasIdentifier>
    {
        private volatile AliasRetirement retirement;
    }

    private record AliasRetirement(AliasGeneration next,
                                    BiPredicate<AliasKey,TalkerAliasIdentifier> forgotten) {}

    /** One cache key for a home-system subscriber and its native address; visiting subscriber tuples remain exact. */
    private static AliasKey aliasKey(RadioIdentifier radio, String servingSystemKey)
    {
        if(RadioSystemKey.isChannelScoped(servingSystemKey)) servingSystemKey = null;
        boolean scoped = RadioSystemKey.isP25Native(servingSystemKey);
        return aliasKey(radio, scoped ? Integer.parseInt(servingSystemKey, 4, 9, 16) : -1,
            scoped ? Integer.parseInt(servingSystemKey, 10, 13, 16) : -1, servingSystemKey);
    }

    private static AliasKey aliasKey(RadioIdentifier radio, int servingWacn, int servingSystem, String nativeSystemKey)
    {
        if(RadioSystemKey.isChannelScoped(nativeSystemKey)) nativeSystemKey = null;
        if(radio == null || radio.getProtocol() == null || !radio.isValid()) return null;
        Protocol protocol = radio.getProtocol() == Protocol.APCO25_PHASE2 ? Protocol.APCO25 : radio.getProtocol();
        if(protocol == Protocol.APCO25)
        {
            if(radio instanceof FullyQualifiedRadioIdentifier)
            {
                P25SubscriberIdentity subscriber = P25SubscriberIdentity.from(radio);
                return subscriber != null ? new AliasKey(protocol, null, 0, subscriber.homeWacn(),
                    subscriber.homeSystemId(), subscriber.subscriberId()) : null;
            }
            if(radio.getValue() < 1 || radio.getValue() > 0xFFFFFC) return null;
            if(servingWacn >= 0 && servingWacn <= 0xFFFFF && servingSystem >= 0 && servingSystem <= 0xFFF)
            {
                return new AliasKey(protocol, null, 0, servingWacn, servingSystem, radio.getValue());
            }
        }
        return new AliasKey(protocol, protocol == Protocol.APCO25 ? null : nativeSystemKey, radio instanceof NXDNRadioIdentifier nxdn && nxdn.isTypeD() ? 1 : 0,
            -1, -1, radio.getValue());
    }

    private record AliasKey(Protocol protocol, String nativeSystemKey, int addressDomain, int homeWacn, int homeSystem, int radio)
    {
        @Override
        public String toString()
        {
            return homeWacn >= 0 ? new P25SubscriberIdentity(homeWacn, homeSystem, radio).display() :
                Integer.toString(radio);
        }
    }
}
