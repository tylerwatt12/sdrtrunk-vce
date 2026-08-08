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

import io.github.dsheirer.alias.id.radio.P25FullyQualifiedRadio;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.alias.id.radio.RadioRange;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.alias.id.talkgroup.TalkgroupRange;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.identifier.talkgroup.TalkgroupIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.protocol.Protocol;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class P25AliasTest
{
    @Test
    void fullyQualifiedRadioValidatesTheCompleteIdentity()
    {
        assertTrue(new P25FullyQualifiedRadio(0xBEE00, 0x348, 0).isValid());
        assertTrue(new P25FullyQualifiedRadio(0xBEE00, 0x348, 0xFFFFFF).isValid());
        assertFalse(new P25FullyQualifiedRadio(-1, 0x348, 1).isValid());
        assertFalse(new P25FullyQualifiedRadio(0x100000, 0x348, 1).isValid());
        assertFalse(new P25FullyQualifiedRadio(0xBEE00, 0x1000, 1).isValid());
        assertFalse(new P25FullyQualifiedRadio(0xBEE00, 0x348, -1).isValid());
        P25FullyQualifiedRadio wrongProtocol = new P25FullyQualifiedRadio(0xBEE00, 0x348, 1);
        wrongProtocol.setProtocol(Protocol.DMR);
        assertFalse(wrongProtocol.isValid());
    }

    @Test
    void fullyQualifiedRadioIdentitiesCompareTheCompleteTuple()
    {
        P25FullyQualifiedRadio firstRadio = new P25FullyQualifiedRadio(1, 2, 300);
        P25FullyQualifiedRadio otherRadio = new P25FullyQualifiedRadio(3, 4, 300);
        Radio localRadio = new Radio(Protocol.APCO25, 300);
        assertFalse(firstRadio.matches(otherRadio));
        assertFalse(firstRadio.matches(localRadio));
        assertFalse(localRadio.matches(firstRadio));
        assertNotEquals(firstRadio, otherRadio);
        assertNotEquals(firstRadio, localRadio);
        assertNotEquals(0, firstRadio.compareTo(otherRadio));
    }

    @Test
    void aliasP25Talkgroup()
    {
        String correctAliasName = "Alias Talkgroup 1";
        AliasList aliasList = p25AliasList();

        Alias aliasTalkgroup1 = new Alias();
        aliasTalkgroup1.setName(correctAliasName);
        aliasTalkgroup1.setMatchIdentifier(new Talkgroup(Protocol.APCO25, 1));
        aliasList.addAlias(aliasTalkgroup1);

        Alias aliasTalkgroupRange = new Alias();
        aliasTalkgroupRange.setName("Alias Talkgroup Range");
        aliasTalkgroupRange.setMatchIdentifier(new TalkgroupRange(Protocol.APCO25, 1, 65535));
        aliasList.addAlias(aliasTalkgroupRange);

        TalkgroupIdentifier talkgroupIdentifier1 = APCO25Talkgroup.create(1);

        List<Alias> aliases = aliasList.getAliases(talkgroupIdentifier1);
        assertEquals(1, aliases.size(), "Expected 1 matching alias");
        assertEquals(correctAliasName, aliases.getFirst().getName(), "Unexpected alias name");
    }

    @Test
    void initializesMatcherDisplayWhenAssignedToAlias()
    {
        Talkgroup matcher = new Talkgroup(Protocol.APCO25, 13501);
        Alias alias = new Alias("LORAIN DISP");

        alias.setMatchIdentifier(matcher);

        assertEquals(matcher.toString(), matcher.valueProperty().get());
    }

    @Test
    void phase2AliasUsesP25LookupNamespace()
    {
        AliasList aliasList = p25AliasList();

        Alias talkgroupAlias = new Alias();
        talkgroupAlias.setName("Phase 2 Talkgroup");
        talkgroupAlias.setMatchIdentifier(new Talkgroup(Protocol.APCO25_PHASE2, 101));
        aliasList.addAlias(talkgroupAlias);

        Alias radioAlias = new Alias();
        radioAlias.setName("Phase 2 Radio");
        radioAlias.setMatchIdentifier(new Radio(Protocol.APCO25_PHASE2, 202));
        aliasList.addAlias(radioAlias);

        assertEquals(talkgroupAlias, aliasList.getAliases(APCO25Talkgroup.create(101)).getFirst());
        assertEquals(radioAlias, aliasList.getAliases(APCO25RadioIdentifier.createFrom(202)).getFirst());
    }

    @Test
    void decodedFullyQualifiedTalkgroupsUseTheLocalTalkgroupAlias()
    {
        int aliasGroup = 1;
        String correctAliasName = "Alias Talkgroup 1";

        AliasList aliasList = p25AliasList();

        Alias aliasTalkgroup1 = new Alias();
        aliasTalkgroup1.setName("Alias Talkgroup 1");
        aliasTalkgroup1.setMatchIdentifier(new Talkgroup(Protocol.APCO25, aliasGroup));
        aliasList.addAlias(aliasTalkgroup1);

        List<Alias> first = aliasList.getAliases(
            APCO25FullyQualifiedTalkgroupIdentifier.createTo(aliasGroup, 100, 200, 300));
        List<Alias> second = aliasList.getAliases(
            APCO25FullyQualifiedTalkgroupIdentifier.createTo(aliasGroup, 101, 201, 301));
        assertEquals(correctAliasName, first.getFirst().getName());
        assertEquals(correctAliasName, second.getFirst().getName(),
            "The decoded home tuple must not change alias matching");
    }

    @Test
    void decodedFullyQualifiedTalkgroupsUseTheLocalRangeFallback()
    {
        int wacn = 100;
        int system = 200;
        int originalGroup = 300;
        int aliasGroup = 1;
        String correctAliasName = "Alias Talkgroup 1";

        AliasList aliasList = p25AliasList();

        Alias aliasTalkgroupRange = new Alias();
        aliasTalkgroupRange.setName(correctAliasName);
        aliasTalkgroupRange.setMatchIdentifier(new TalkgroupRange(Protocol.APCO25, 1, 0xFFFF));
        aliasList.addAlias(aliasTalkgroupRange);

        List<Alias> aliases = aliasList.getAliases(
            APCO25FullyQualifiedTalkgroupIdentifier.createTo(aliasGroup, wacn, system, originalGroup));
        assertEquals(correctAliasName, aliases.getFirst().getName());
    }

    @Test
    void aliasP25Radio()
    {
        String correctAliasName = "Alias Radio 1";
        AliasList aliasList = p25AliasList();

        Alias correctAlias = new Alias();
        correctAlias.setName(correctAliasName);
        correctAlias.setMatchIdentifier(new Radio(Protocol.APCO25, 1));
        aliasList.addAlias(correctAlias);

        Alias aliasRadioRange = new Alias();
        aliasRadioRange.setName("Alias Radio Range");
        aliasRadioRange.setMatchIdentifier(new RadioRange(Protocol.APCO25, 1, 0xFFFFFF));
        aliasList.addAlias(aliasRadioRange);

        RadioIdentifier radioIdentifier1 = APCO25RadioIdentifier.createFrom(1);

        List<Alias> aliases = aliasList.getAliases(radioIdentifier1);
        assertEquals(1, aliases.size(), "Expected 1 matching alias");
        assertEquals(correctAliasName, aliases.getFirst().getName(), "Unexpected alias name");
    }

    /**
     * Tests that a fully qualified P25 radio aliases correctly when there is a matching fully qualified radio
     * alias ID along with a simple radio that has the same radio value, that is shadowing the fully qualified
     * version.
     */
    @Test
    void aliasP25FullyQualifiedRadio()
    {
        int wacn = 100;
        int system = 200;
        int originalRadio = 300;
        int aliasRadio = 1;
        String correctAliasName = "Alias Fully Qualified Radio 1";

        AliasList aliasList = p25AliasList();

        Alias aliasRadio1 = new Alias();
        aliasRadio1.setName("Alias Radio 1");
        aliasRadio1.setMatchIdentifier(new Radio(Protocol.APCO25, aliasRadio)); //Shadows the fully qualified variant
        aliasList.addAlias(aliasRadio1);

        Alias aliasFullyQualifiedRadio1 = new Alias();
        aliasFullyQualifiedRadio1.setName(correctAliasName);
        aliasFullyQualifiedRadio1.setMatchIdentifier(new P25FullyQualifiedRadio(wacn, system, originalRadio));
        aliasList.addAlias(aliasFullyQualifiedRadio1);

        //Identifier transmitted over the air that we want to alias
        APCO25FullyQualifiedRadioIdentifier p25FQR1 = APCO25FullyQualifiedRadioIdentifier.createFrom(aliasRadio, wacn, system, originalRadio);

        List<Alias> aliases = aliasList.getAliases(p25FQR1);
        assertEquals(1, aliases.size(), "Expected 1 matching alias");
        assertEquals(correctAliasName, aliases.getFirst().getName(), "Unexpected alias name");
    }

    /**
     * Tests that a fully qualified P25 radio aliases correctly to a simple radio alias for the local radio
     * value, when the user has not explicitly added a fully qualified radio alias ID.
     */
    @Test
    void aliasP25FullyQualifiedRadioToBasicRadioAlias()
    {
        int wacn = 100;
        int system = 200;
        int originalRadio = 300;
        int aliasRadio = 1;
        String correctAliasName = "Alias Radio 1";

        AliasList aliasList = p25AliasList();

        Alias aliasRadio1 = new Alias();
        aliasRadio1.setName(correctAliasName);
        aliasRadio1.setMatchIdentifier(new Radio(Protocol.APCO25, aliasRadio));
        aliasList.addAlias(aliasRadio1);

        //Identifier transmitted over the air that we want to alias
        APCO25FullyQualifiedRadioIdentifier p25FQTG1 = APCO25FullyQualifiedRadioIdentifier.createFrom(aliasRadio, wacn, system, originalRadio);

        List<Alias> aliases = aliasList.getAliases(p25FQTG1);
        assertEquals(0, aliases.size(), "Expected 0 matching aliases");
    }

    /**
     * Tests that a fully qualified P25 radio aliases correctly to a radio range alias for the local radio
     * value, when the user has not explicitly added a fully qualified radio alias ID.
     */
    @Test
    void aliasP25FullyQualifiedRadioToRadioRangeAlias()
    {
        int wacn = 100;
        int system = 200;
        int originalRadio = 300;
        int aliasRadio = 1;
        String correctAliasName = "Alias Radio Range";

        AliasList aliasList = p25AliasList();

        Alias aliasTalkgroup1 = new Alias();
        aliasTalkgroup1.setName(correctAliasName);
        aliasTalkgroup1.setMatchIdentifier(new RadioRange(Protocol.APCO25, 1, 0xFFFFFF));
        aliasList.addAlias(aliasTalkgroup1);

        //Identifier transmitted over the air that we want to alias
        APCO25FullyQualifiedRadioIdentifier p25FQTG1 = APCO25FullyQualifiedRadioIdentifier.createFrom(aliasRadio, wacn, system, originalRadio);

        List<Alias> aliases = aliasList.getAliases(p25FQTG1);
        assertEquals(0, aliases.size(), "Expected 0 matching aliases");
    }

    private static AliasList p25AliasList()
    {
        return new AliasList(
            new AliasListDefinition("Test Alias List", AliasListFamily.P25));
    }
}
