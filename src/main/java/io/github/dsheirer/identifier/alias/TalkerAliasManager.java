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
import io.github.dsheirer.protocol.Protocol;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Talker alias cache manager.  Collects observed talker aliases and inserts them into an identifier collection when
 * the corresponding radio is active in a FROM role.
 *
 * This implementation is thread safe and is intended to be used across control and traffic channels.
 */
public class TalkerAliasManager
{
    private final Map<AliasKey,TalkerAliasIdentifier> mAliasMap = new ConcurrentHashMap<>();

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
        AliasKey key = aliasKey(identifier, servingSystemKey);
        if(identifier != null && identifier.getRole() == Role.FROM && alias != null && key != null)
        {
            mAliasMap.put(key, alias);
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
        return key != null && mAliasMap.containsKey(key);
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
            enrichedIC.update(alias);
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
            Integer.parseInt(servingSystemKey, 10, 13, 16) : -1);
    }

    /** Avoids key formatting on decoder callbacks that already have validated serving-system fields. */
    public void enrichMutable(MutableIdentifierCollection mic, int servingWacn, int servingSystem)
    {
        Identifier fromRadio = mic.getFromIdentifier();
        mic.remove(IdentifierClass.USER, Form.TALKER_ALIAS, Role.FROM);

        if(fromRadio instanceof RadioIdentifier rid)
        {
            AliasKey key = aliasKey(rid, servingWacn, servingSystem);
            TalkerAliasIdentifier alias = key != null ? mAliasMap.get(key) : null;

            if(alias != null)
            {
                mic.update(alias);
            }
        }
    }

    /**
     * Creates a summary listing of talker aliases
     * @return summary.
     */
    public String getAliasSummary()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("Active System Radio Aliases\n");
        sb.append("  Radio\tTalker Alias (TA-)\n");
        List<AliasKey> radios = new ArrayList<>(mAliasMap.keySet());

        if(!radios.isEmpty())
        {
            radios.sort(Comparator.comparing(AliasKey::protocol).thenComparingInt(AliasKey::homeWacn)
                .thenComparingInt(AliasKey::homeSystem).thenComparingInt(AliasKey::radio));
            for(AliasKey radio : radios)
            {
                sb.append("  ").append(radio);
                sb.append("\t").append(mAliasMap.get(radio));
                sb.append("\n");
            }
        }
        else
        {
            sb.append("  None\n");
        }

        return sb.toString();
    }

    private TalkerAliasIdentifier lookup(RadioIdentifier radio, String servingSystemKey)
    {
        AliasKey key = aliasKey(radio, servingSystemKey);
        return key != null ? mAliasMap.get(key) : null;
    }

    /** One cache key for a home-system subscriber and its native address; visiting subscriber tuples remain exact. */
    private static AliasKey aliasKey(RadioIdentifier radio, String servingSystemKey)
    {
        boolean scoped = RadioSystemKey.isP25Native(servingSystemKey);
        return aliasKey(radio, scoped ? Integer.parseInt(servingSystemKey, 4, 9, 16) : -1,
            scoped ? Integer.parseInt(servingSystemKey, 10, 13, 16) : -1);
    }

    private static AliasKey aliasKey(RadioIdentifier radio, int servingWacn, int servingSystem)
    {
        if(radio == null || radio.getProtocol() == null || !radio.isValid()) return null;
        Protocol protocol = radio.getProtocol() == Protocol.APCO25_PHASE2 ? Protocol.APCO25 : radio.getProtocol();
        if(protocol == Protocol.APCO25)
        {
            if(radio instanceof FullyQualifiedRadioIdentifier)
            {
                P25SubscriberIdentity subscriber = P25SubscriberIdentity.from(radio);
                return subscriber != null ? new AliasKey(protocol, subscriber.homeWacn(),
                    subscriber.homeSystemId(), subscriber.subscriberId()) : null;
            }
            if(radio.getValue() < 1 || radio.getValue() > 0xFFFFFC) return null;
            if(servingWacn >= 0 && servingWacn <= 0xFFFFF && servingSystem >= 0 && servingSystem <= 0xFFF)
            {
                return new AliasKey(protocol, servingWacn, servingSystem, radio.getValue());
            }
        }
        return new AliasKey(protocol, -1, -1, radio.getValue());
    }

    private record AliasKey(Protocol protocol, int homeWacn, int homeSystem, int radio)
    {
        @Override
        public String toString()
        {
            return homeWacn >= 0 ? new P25SubscriberIdentity(homeWacn, homeSystem, radio).display() :
                Integer.toString(radio);
        }
    }
}
