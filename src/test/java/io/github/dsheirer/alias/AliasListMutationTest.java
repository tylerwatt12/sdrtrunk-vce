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
import io.github.dsheirer.alias.id.radio.P25FullyQualifiedRadio;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.alias.id.radio.RadioRange;
import io.github.dsheirer.alias.id.status.UnitStatusID;
import io.github.dsheirer.alias.id.status.UserStatusID;
import io.github.dsheirer.alias.id.talkgroup.P25FullyQualifiedTalkgroup;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.alias.id.talkgroup.TalkgroupRange;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.dcs.DCSIdentifier;
import io.github.dsheirer.identifier.status.UnitStatusIdentifier;
import io.github.dsheirer.identifier.status.UserStatusIdentifier;
import io.github.dsheirer.module.decode.dcs.DCSCode;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25FullyQualifiedTalkgroupIdentifier;
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
        assertSame(exactAlias, only(aliasList.getAliases(APCO25Talkgroup.create(101))));
        assertSame(rangeAlias, only(aliasList.getAliases(APCO25Talkgroup.create(350))));
        assertSame(radioAlias, only(aliasList.getAliases(APCO25RadioIdentifier.createFrom(401))));
        assertSame(radioRangeAlias, only(aliasList.getAliases(APCO25RadioIdentifier.createFrom(650))));
    }

    @Test
    void fullyQualifiedEditsAndRemovalDoNotLeaveStaleMatches()
    {
        AliasList aliasList = p25AliasList();
        P25FullyQualifiedTalkgroup talkgroup = new P25FullyQualifiedTalkgroup(1, 2, 300);
        Alias talkgroupAlias = alias("fq talkgroup", talkgroup);
        P25FullyQualifiedRadio radio = new P25FullyQualifiedRadio(4, 5, 600);
        Alias radioAlias = alias("fq radio", radio);
        aliasList.addAliases(List.of(talkgroupAlias, radioAlias));

        assertSame(talkgroupAlias, only(aliasList.getAliases(
            APCO25FullyQualifiedTalkgroupIdentifier.createTo(30, 1, 2, 300))));
        assertSame(radioAlias, only(aliasList.getAliases(
            APCO25FullyQualifiedRadioIdentifier.createFrom(60, 4, 5, 600))));

        talkgroup.setWacn(7);
        talkgroup.setSystem(8);
        talkgroup.setValue(900);
        radio.setWacn(10);
        radio.setSystem(11);
        radio.setValue(1200);

        assertTrue(aliasList.getAliases(
            APCO25FullyQualifiedTalkgroupIdentifier.createTo(30, 1, 2, 300)).isEmpty());
        assertTrue(aliasList.getAliases(
            APCO25FullyQualifiedRadioIdentifier.createFrom(60, 4, 5, 600)).isEmpty());
        assertSame(talkgroupAlias, only(aliasList.getAliases(
            APCO25FullyQualifiedTalkgroupIdentifier.createTo(30, 7, 8, 900))));
        assertSame(radioAlias, only(aliasList.getAliases(
            APCO25FullyQualifiedRadioIdentifier.createFrom(60, 10, 11, 1200))));

        aliasList.removeAlias(talkgroupAlias);
        aliasList.removeAlias(radioAlias);
        assertTrue(aliasList.getAliases(
            APCO25FullyQualifiedTalkgroupIdentifier.createTo(30, 7, 8, 900)).isEmpty());
        assertTrue(aliasList.getAliases(
            APCO25FullyQualifiedRadioIdentifier.createFrom(60, 10, 11, 1200)).isEmpty());
    }

    @Test
    void dcsAndStatusEditsUseTheirOwnMapsAndRemoveCleanly()
    {
        AliasList aliasList = p25AliasList();
        Dcs dcs = new Dcs();
        dcs.setDCSCode(DCSCode.N023);
        Alias dcsAlias = alias("dcs", dcs);
        UnitStatusID unitStatus = new UnitStatusID();
        unitStatus.setStatus(3);
        Alias unitAlias = alias("unit", unitStatus);
        UserStatusID userStatus = new UserStatusID();
        userStatus.setStatus(3);
        Alias userAlias = alias("user", userStatus);
        aliasList.addAliases(List.of(dcsAlias, unitAlias, userAlias));

        assertSame(dcsAlias, only(aliasList.getAliases(new DCSIdentifier(DCSCode.N023))));
        assertSame(unitAlias, only(aliasList.getAliases(
            new UnitStatusIdentifier(3, Role.FROM, Protocol.APCO25))));
        assertSame(userAlias, only(aliasList.getAliases(
            new UserStatusIdentifier(3, Role.FROM, Protocol.APCO25))));

        dcs.setDCSCode(DCSCode.N025);
        unitStatus.setStatus(4);
        userStatus.setStatus(5);

        assertTrue(aliasList.getAliases(new DCSIdentifier(DCSCode.N023)).isEmpty());
        assertTrue(aliasList.getAliases(new UnitStatusIdentifier(3, Role.FROM, Protocol.APCO25)).isEmpty());
        assertTrue(aliasList.getAliases(new UserStatusIdentifier(3, Role.FROM, Protocol.APCO25)).isEmpty());
        assertSame(dcsAlias, only(aliasList.getAliases(new DCSIdentifier(DCSCode.N025))));
        assertSame(unitAlias, only(aliasList.getAliases(
            new UnitStatusIdentifier(4, Role.FROM, Protocol.APCO25))));
        assertSame(userAlias, only(aliasList.getAliases(
            new UserStatusIdentifier(5, Role.FROM, Protocol.APCO25))));

        aliasList.removeAliases(List.of(dcsAlias, unitAlias, userAlias));
        assertTrue(aliasList.getAliases(new DCSIdentifier(DCSCode.N025)).isEmpty());
        assertTrue(aliasList.getAliases(new UnitStatusIdentifier(4, Role.FROM, Protocol.APCO25)).isEmpty());
        assertTrue(aliasList.getAliases(new UserStatusIdentifier(5, Role.FROM, Protocol.APCO25)).isEmpty());
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
        AliasList aliasList = p25AliasList();
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
        aliasList.addAliases(List.of(firstRangeAlias, secondRangeAlias, firstDcsAlias, secondDcsAlias));

        assertTrue(firstRange.overlapProperty().get());
        assertTrue(secondRange.overlapProperty().get());
        assertTrue(firstDcs.overlapProperty().get());
        assertTrue(secondDcs.overlapProperty().get());
        assertSame(secondDcsAlias, only(aliasList.getAliases(new DCSIdentifier(DCSCode.N023))));

        secondRange.setMinTalkgroup(300);
        secondRange.setMaxTalkgroup(400);
        secondDcs.setDCSCode(DCSCode.N025);
        assertFalse(firstRange.overlapProperty().get());
        assertFalse(secondRange.overlapProperty().get());
        assertFalse(firstDcs.overlapProperty().get());
        assertFalse(secondDcs.overlapProperty().get());
        assertSame(firstDcsAlias, only(aliasList.getAliases(new DCSIdentifier(DCSCode.N023))));
        assertSame(secondDcsAlias, only(aliasList.getAliases(new DCSIdentifier(DCSCode.N025))));
    }

    @Test
    void fullyQualifiedAndDcsCollisionRemovalRevealsTheSurvivingAlias()
    {
        AliasList aliasList = p25AliasList();
        P25FullyQualifiedTalkgroup firstTalkgroup = new P25FullyQualifiedTalkgroup(1, 2, 300);
        P25FullyQualifiedTalkgroup secondTalkgroup = new P25FullyQualifiedTalkgroup(1, 2, 300);
        Alias firstTalkgroupAlias = alias("first fq talkgroup", firstTalkgroup);
        Alias secondTalkgroupAlias = alias("second fq talkgroup", secondTalkgroup);
        P25FullyQualifiedRadio firstRadio = new P25FullyQualifiedRadio(4, 5, 600);
        P25FullyQualifiedRadio secondRadio = new P25FullyQualifiedRadio(4, 5, 600);
        Alias firstRadioAlias = alias("first fq radio", firstRadio);
        Alias secondRadioAlias = alias("second fq radio", secondRadio);
        Dcs firstDcs = new Dcs();
        firstDcs.setDCSCode(DCSCode.N023);
        Dcs secondDcs = new Dcs();
        secondDcs.setDCSCode(DCSCode.N023);
        Alias firstDcsAlias = alias("first dcs", firstDcs);
        Alias secondDcsAlias = alias("second dcs", secondDcs);
        aliasList.addAliases(List.of(firstTalkgroupAlias, secondTalkgroupAlias, firstRadioAlias, secondRadioAlias,
            firstDcsAlias, secondDcsAlias));

        assertSame(firstTalkgroupAlias, only(aliasList.getAliases(
            APCO25FullyQualifiedTalkgroupIdentifier.createTo(30, 1, 2, 300))));
        assertSame(firstRadioAlias, only(aliasList.getAliases(
            APCO25FullyQualifiedRadioIdentifier.createFrom(60, 4, 5, 600))));
        assertSame(secondDcsAlias, only(aliasList.getAliases(new DCSIdentifier(DCSCode.N023))));
        assertTrue(firstTalkgroup.overlapProperty().get());
        assertTrue(secondTalkgroup.overlapProperty().get());
        assertTrue(firstRadio.overlapProperty().get());
        assertTrue(secondRadio.overlapProperty().get());

        aliasList.removeAliases(List.of(firstTalkgroupAlias, firstRadioAlias, secondDcsAlias));
        assertSame(secondTalkgroupAlias, only(aliasList.getAliases(
            APCO25FullyQualifiedTalkgroupIdentifier.createTo(30, 1, 2, 300))));
        assertSame(secondRadioAlias, only(aliasList.getAliases(
            APCO25FullyQualifiedRadioIdentifier.createFrom(60, 4, 5, 600))));
        assertSame(firstDcsAlias, only(aliasList.getAliases(new DCSIdentifier(DCSCode.N023))));
        assertFalse(secondTalkgroup.overlapProperty().get());
        assertFalse(secondRadio.overlapProperty().get());
        assertFalse(firstDcs.overlapProperty().get());
    }

    @Test
    void movingAliasBetweenCachedModelListsUpdatesBothSnapshots()
    {
        AliasModel model = new AliasModel();
        AliasListDefinition firstDefinition =
            new AliasListDefinition("first", "System", AliasListFamily.P25);
        AliasListDefinition secondDefinition =
            new AliasListDefinition("second", "System", AliasListFamily.P25);
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
        return new AliasList(new AliasListDefinition("test", "test", AliasListFamily.P25));
    }

    private static Alias only(List<Alias> aliases)
    {
        assertEquals(1, aliases.size());
        return aliases.getFirst();
    }
}
