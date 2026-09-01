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
package io.github.dsheirer.alias;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.id.dcs.Dcs;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.alias.id.radio.RadioRange;
import io.github.dsheirer.alias.id.status.UnitStatusID;
import io.github.dsheirer.alias.id.status.UserStatusID;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.alias.id.talkgroup.TalkgroupRange;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.dcs.DCSIdentifier;
import io.github.dsheirer.identifier.status.UnitStatusIdentifier;
import io.github.dsheirer.identifier.status.UserStatusIdentifier;
import io.github.dsheirer.module.decode.dcs.DCSCode;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.protocol.Protocol;
import java.util.List;
import org.junit.jupiter.api.Test;

class AliasListMutationTest
{
    @Test
    void exactAndRangeEditsPublishNewLookupsWithoutLeavingOldMatches()
    {
        AliasList aliasList = p25AliasList();
        Talkgroup talkgroup = new Talkgroup(Protocol.APCO25, 100);
        Alias exactAlias = alias("exact", talkgroup);
        TalkgroupRange talkgroupRange = new TalkgroupRange(Protocol.APCO25, 200, 299);
        Alias rangeAlias = alias("range", talkgroupRange);
        Radio radio = new Radio(Protocol.APCO25, 400);
        Alias radioAlias = alias("radio", radio);
        RadioRange radioRange = new RadioRange(Protocol.APCO25, 500, 599);
        Alias radioRangeAlias = alias("radio range", radioRange);
        aliasList.addAliases(List.of(exactAlias, rangeAlias, radioAlias, radioRangeAlias));

        assertSame(exactAlias, only(aliasList.getAliases(APCO25Talkgroup.create(100))));
        assertSame(rangeAlias, only(aliasList.getAliases(APCO25Talkgroup.create(250))));
        assertSame(radioAlias, only(aliasList.getAliases(APCO25RadioIdentifier.createFrom(400))));
        assertSame(radioRangeAlias, only(aliasList.getAliases(APCO25RadioIdentifier.createFrom(550))));
        assertSame(radioAlias, only(aliasList.getAliases(
            APCO25FullyQualifiedRadioIdentifier.createFrom(400, 4, 5, 600))));
        assertSame(radioRangeAlias, only(aliasList.getAliases(
            APCO25FullyQualifiedRadioIdentifier.createFrom(550, 4, 5, 600))));

        talkgroup.setValue(101);
        talkgroupRange.setMinTalkgroup(300);
        talkgroupRange.setMaxTalkgroup(399);
        radio.setValue(401);
        radioRange.setMinRadio(600);
        radioRange.setMaxRadio(699);

        assertTrue(aliasList.getAliases(APCO25Talkgroup.create(100)).isEmpty());
        assertTrue(aliasList.getAliases(APCO25Talkgroup.create(250)).isEmpty());
        assertTrue(aliasList.getAliases(APCO25RadioIdentifier.createFrom(400)).isEmpty());
        assertTrue(aliasList.getAliases(APCO25RadioIdentifier.createFrom(550)).isEmpty());
        assertTrue(aliasList.getAliases(
            APCO25FullyQualifiedRadioIdentifier.createFrom(400, 4, 5, 600)).isEmpty());
        assertTrue(aliasList.getAliases(
            APCO25FullyQualifiedRadioIdentifier.createFrom(550, 4, 5, 600)).isEmpty());
        assertSame(exactAlias, only(aliasList.getAliases(APCO25Talkgroup.create(101))));
        assertSame(rangeAlias, only(aliasList.getAliases(APCO25Talkgroup.create(350))));
        assertSame(radioAlias, only(aliasList.getAliases(APCO25RadioIdentifier.createFrom(401))));
        assertSame(radioRangeAlias, only(aliasList.getAliases(APCO25RadioIdentifier.createFrom(650))));
        assertSame(radioAlias, only(aliasList.getAliases(
            APCO25FullyQualifiedRadioIdentifier.createFrom(401, 10, 11, 1200))));
        assertSame(radioRangeAlias, only(aliasList.getAliases(
            APCO25FullyQualifiedRadioIdentifier.createFrom(650, 10, 11, 1200))));
    }

    @Test
    void dcsAndStatusEditsUseTheirOwnMapsAndRemoveCleanly()
    {
        AliasList p25AliasList = p25AliasList();
        AliasList nbfmAliasList = nbfmAliasList();
        Dcs dcs = new Dcs();
        dcs.setDCSCode(DCSCode.N023);
        Alias dcsAlias = alias("dcs", dcs);
        UnitStatusID unitStatus = new UnitStatusID();
        unitStatus.setStatus(3);
        Alias unitAlias = alias("unit", unitStatus);
        UserStatusID userStatus = new UserStatusID();
        userStatus.setStatus(3);
        Alias userAlias = alias("user", userStatus);
        nbfmAliasList.addAlias(dcsAlias);
        p25AliasList.addAliases(List.of(unitAlias, userAlias));

        assertSame(dcsAlias, only(nbfmAliasList.getAliases(new DCSIdentifier(DCSCode.N023))));
        assertSame(unitAlias, only(p25AliasList.getAliases(
            new UnitStatusIdentifier(3, Role.FROM, Protocol.APCO25))));
        assertSame(userAlias, only(p25AliasList.getAliases(
            new UserStatusIdentifier(3, Role.FROM, Protocol.APCO25))));

        dcs.setDCSCode(DCSCode.N025);
        unitStatus.setStatus(4);
        userStatus.setStatus(5);

        assertTrue(nbfmAliasList.getAliases(new DCSIdentifier(DCSCode.N023)).isEmpty());
        assertTrue(p25AliasList.getAliases(new UnitStatusIdentifier(3, Role.FROM, Protocol.APCO25)).isEmpty());
        assertTrue(p25AliasList.getAliases(new UserStatusIdentifier(3, Role.FROM, Protocol.APCO25)).isEmpty());
        assertSame(dcsAlias, only(nbfmAliasList.getAliases(new DCSIdentifier(DCSCode.N025))));
        assertSame(unitAlias, only(p25AliasList.getAliases(
            new UnitStatusIdentifier(4, Role.FROM, Protocol.APCO25))));
        assertSame(userAlias, only(p25AliasList.getAliases(
            new UserStatusIdentifier(5, Role.FROM, Protocol.APCO25))));

        nbfmAliasList.removeAlias(dcsAlias);
        p25AliasList.removeAliases(List.of(unitAlias, userAlias));
        assertTrue(nbfmAliasList.getAliases(new DCSIdentifier(DCSCode.N025)).isEmpty());
        assertTrue(p25AliasList.getAliases(new UnitStatusIdentifier(4, Role.FROM, Protocol.APCO25)).isEmpty());
        assertTrue(p25AliasList.getAliases(new UserStatusIdentifier(5, Role.FROM, Protocol.APCO25)).isEmpty());
    }

    @Test
    void collisionFlagsAreRecalculatedAfterEditAndDelete()
    {
        AliasList aliasList = p25AliasList();
        Talkgroup firstTalkgroup = new Talkgroup(Protocol.APCO25, 100);
        Talkgroup secondTalkgroup = new Talkgroup(Protocol.APCO25, 100);
        Alias first = alias("first", firstTalkgroup);
        Alias second = alias("second", secondTalkgroup);
        aliasList.addAliases(List.of(first, second));

        assertTrue(firstTalkgroup.overlapProperty().get());
        assertTrue(secondTalkgroup.overlapProperty().get());
        assertSame(second, only(aliasList.getAliases(APCO25Talkgroup.create(100))));

        secondTalkgroup.setValue(101);
        assertFalse(firstTalkgroup.overlapProperty().get());
        assertFalse(secondTalkgroup.overlapProperty().get());
        assertSame(first, only(aliasList.getAliases(APCO25Talkgroup.create(100))));
        assertSame(second, only(aliasList.getAliases(APCO25Talkgroup.create(101))));

        secondTalkgroup.setValue(100);
        assertTrue(firstTalkgroup.overlapProperty().get());
        assertTrue(secondTalkgroup.overlapProperty().get());
        aliasList.removeAlias(second);
        assertFalse(firstTalkgroup.overlapProperty().get());
        assertSame(first, only(aliasList.getAliases(APCO25Talkgroup.create(100))));
    }

    @Test
    void rangeAndDcsCollisionFlagsAreRecalculated()
    {
        AliasList p25AliasList = p25AliasList();
        AliasList nbfmAliasList = nbfmAliasList();
        TalkgroupRange firstRange = new TalkgroupRange(Protocol.APCO25, 100, 200);
        TalkgroupRange secondRange = new TalkgroupRange(Protocol.APCO25, 150, 250);
        Alias firstRangeAlias = alias("first range", firstRange);
        Alias secondRangeAlias = alias("second range", secondRange);
        Dcs firstDcs = new Dcs();
        firstDcs.setDCSCode(DCSCode.N023);
        Dcs secondDcs = new Dcs();
        secondDcs.setDCSCode(DCSCode.N023);
        Alias firstDcsAlias = alias("first dcs", firstDcs);
        Alias secondDcsAlias = alias("second dcs", secondDcs);
        p25AliasList.addAliases(List.of(firstRangeAlias, secondRangeAlias));
        nbfmAliasList.addAliases(List.of(firstDcsAlias, secondDcsAlias));

        assertTrue(firstRange.overlapProperty().get());
        assertTrue(secondRange.overlapProperty().get());
        assertTrue(firstDcs.overlapProperty().get());
        assertTrue(secondDcs.overlapProperty().get());
        assertSame(secondDcsAlias, only(nbfmAliasList.getAliases(new DCSIdentifier(DCSCode.N023))));

        secondRange.setMinTalkgroup(300);
        secondRange.setMaxTalkgroup(400);
        secondDcs.setDCSCode(DCSCode.N025);
        assertFalse(firstRange.overlapProperty().get());
        assertFalse(secondRange.overlapProperty().get());
        assertFalse(firstDcs.overlapProperty().get());
        assertFalse(secondDcs.overlapProperty().get());
        assertSame(firstDcsAlias, only(nbfmAliasList.getAliases(new DCSIdentifier(DCSCode.N023))));
        assertSame(secondDcsAlias, only(nbfmAliasList.getAliases(new DCSIdentifier(DCSCode.N025))));
    }

    @Test
    void dcsCollisionRemovalRevealsTheSurvivingAlias()
    {
        AliasList nbfmAliasList = nbfmAliasList();
        Dcs firstDcs = new Dcs();
        firstDcs.setDCSCode(DCSCode.N023);
        Dcs secondDcs = new Dcs();
        secondDcs.setDCSCode(DCSCode.N023);
        Alias firstDcsAlias = alias("first dcs", firstDcs);
        Alias secondDcsAlias = alias("second dcs", secondDcs);
        nbfmAliasList.addAliases(List.of(firstDcsAlias, secondDcsAlias));

        assertSame(secondDcsAlias, only(nbfmAliasList.getAliases(new DCSIdentifier(DCSCode.N023))));

        nbfmAliasList.removeAlias(secondDcsAlias);
        assertSame(firstDcsAlias, only(nbfmAliasList.getAliases(new DCSIdentifier(DCSCode.N023))));
        assertFalse(firstDcs.overlapProperty().get());
    }

    @Test
    void movingAliasBetweenCachedModelListsUpdatesBothSnapshots()
    {
        AliasModel model = new AliasModel();
        AliasListDefinition firstDefinition =
            new AliasListDefinition("first", AliasListFamily.P25);
        AliasListDefinition secondDefinition =
            new AliasListDefinition("second", AliasListFamily.P25);
        model.setAliasListDefinitions(List.of(firstDefinition, secondDefinition));
        AliasList firstList = model.getAliasList("first");
        AliasList secondList = model.getAliasList("second");
        Talkgroup talkgroup = new Talkgroup(Protocol.APCO25, 100);
        Alias alias = alias("moving", talkgroup);
        alias.setAliasListDefinition(firstDefinition);
        model.addAlias(alias);

        assertSame(alias, only(firstList.getAliases(APCO25Talkgroup.create(100))));
        assertTrue(secondList.getAliases(APCO25Talkgroup.create(100)).isEmpty());

        alias.setAliasListDefinition(secondDefinition);
        assertTrue(firstList.getAliases(APCO25Talkgroup.create(100)).isEmpty());
        assertSame(alias, only(secondList.getAliases(APCO25Talkgroup.create(100))));

        model.removeAlias(alias);
        assertTrue(secondList.getAliases(APCO25Talkgroup.create(100)).isEmpty());
    }

    private static Alias alias(String name, io.github.dsheirer.alias.id.AliasID aliasID)
    {
        Alias alias = new Alias(name);
        alias.setMatchIdentifier(aliasID);
        return alias;
    }

    private static AliasList p25AliasList()
    {
        return new AliasList(new AliasListDefinition("test", AliasListFamily.P25));
    }

    private static AliasList nbfmAliasList()
    {
        return new AliasList(new AliasListDefinition("test", AliasListFamily.NBFM));
    }

    private static Alias only(List<Alias> aliases)
    {
        assertEquals(1, aliases.size());
        return aliases.getFirst();
    }
}
