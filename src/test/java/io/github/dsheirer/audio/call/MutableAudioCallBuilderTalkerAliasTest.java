/*
 * Copyright (C) 2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.audio.call;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.IdentifierUpdateNotification;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.alias.P25TalkerAliasIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.APCO25System;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Wacn;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import org.junit.jupiter.api.Test;

class MutableAudioCallBuilderTalkerAliasTest
{
    @Test
    void sourceChangeCannotRetainPreviousTalkersAlias()
    {
        MutableAudioCallBuilder builder = builder();
        var previousRadio = APCO25RadioIdentifier.createFrom(120001);
        var previousAlias = P25TalkerAliasIdentifier.create("UNIT ONE").bindTo(previousRadio);
        builder.addIdentifier(previousRadio);
        builder.addIdentifier(previousAlias);

        builder.receive(notification(previousRadio, IdentifierUpdateNotification.Operation.REMOVE));
        builder.receive(notification(APCO25RadioIdentifier.createFrom(120002),
            IdentifierUpdateNotification.Operation.ADD));
        builder.receive(notification(previousAlias, IdentifierUpdateNotification.Operation.REMOVE));

        assertEquals(120002, builder.getIdentifierCollection().getFromIdentifier().getValue());
        assertNull(talkerAlias(builder));
        var nextAlias = P25TalkerAliasIdentifier.create("UNIT TWO").bindTo(builder.getIdentifierCollection());
        builder.addIdentifier(nextAlias);
        assertSame(nextAlias, talkerAlias(builder));
    }

    @Test
    void terminalRemovalsPreserveCompletedCallsSourceAndAlias()
    {
        MutableAudioCallBuilder builder = builder();
        var radio = APCO25RadioIdentifier.createFrom(120001);
        var alias = P25TalkerAliasIdentifier.create("UNIT ONE").bindTo(radio);
        builder.addIdentifier(radio);
        builder.addIdentifier(alias);

        builder.receive(notification(radio, IdentifierUpdateNotification.Operation.REMOVE));
        builder.receive(notification(alias, IdentifierUpdateNotification.Operation.REMOVE));
        builder.complete(1_000);

        assertSame(radio, builder.getIdentifierCollection().getFromIdentifier());
        assertSame(alias, talkerAlias(builder));
    }

    @Test
    void repeatedSourceAndEquivalentQualificationPreserveAlias()
    {
        MutableAudioCallBuilder builder = builder();
        var radio = APCO25RadioIdentifier.createFrom(120001);
        var alias = P25TalkerAliasIdentifier.create("UNIT ONE").bindTo(radio);
        builder.addIdentifier(radio);
        builder.addIdentifier(alias);
        builder.addIdentifier(APCO25RadioIdentifier.createFrom(120001));
        assertSame(alias, talkerAlias(builder));
        builder.addIdentifier(APCO25RadioIdentifier.createFrom(0));
        assertEquals(120001, builder.getIdentifierCollection().getFromIdentifier().getValue());
        assertSame(alias, talkerAlias(builder));

        builder.addIdentifier(APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(120001,
            0x12345, 0x123, 900001));
        assertEquals(alias.getValue(), talkerAlias(builder).getValue());
    }

    @Test
    void differentQualifiedSubscribersCannotShareAliasThroughSameWorkingAddress()
    {
        MutableAudioCallBuilder builder = builder();
        builder.addIdentifier(APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(120001,
            0x12345, 0x123, 900001));
        builder.addIdentifier(P25TalkerAliasIdentifier.create("UNIT ONE").bindTo(builder.getIdentifierCollection()));
        builder.addIdentifier(APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(120001,
            0x12345, 0x123, 900002));

        assertNull(talkerAlias(builder));
    }

    @Test
    void homeSystemCanonicalPromotionPreservesAlias()
    {
        MutableAudioCallBuilder builder = builder();
        builder.addIdentifier(APCO25Wacn.create(0x12345));
        builder.addIdentifier(APCO25System.create(0x123));
        builder.addIdentifier(APCO25RadioIdentifier.createFrom(120001));
        var alias = P25TalkerAliasIdentifier.create("UNIT ONE").bindTo(builder.getIdentifierCollection());
        builder.addIdentifier(alias);
        builder.addIdentifier(APCO25FullyQualifiedRadioIdentifier.createFrom(120001, 0x12345, 0x123,
            120001));

        assertEquals(alias.getValue(), talkerAlias(builder).getValue());
    }

    @Test
    void sameSubscriberWorkingAddressRevisionPreservesAlias()
    {
        MutableAudioCallBuilder builder = builder();
        builder.addIdentifier(APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(120001,
            0x12345, 0x123, 900001));
        var alias = P25TalkerAliasIdentifier.create("UNIT ONE").bindTo(builder.getIdentifierCollection());
        builder.addIdentifier(alias);
        builder.addIdentifier(APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(120002,
            0x12345, 0x123, 900001));

        assertEquals(alias.getValue(), talkerAlias(builder).getValue());
    }

    @Test
    void anotherTimeslotCannotReplaceSourceOrRemoveAlias()
    {
        MutableAudioCallBuilder builder = builder();
        builder.addIdentifier(APCO25RadioIdentifier.createFrom(120001));
        var alias = P25TalkerAliasIdentifier.create("UNIT ONE").bindTo(builder.getIdentifierCollection());
        builder.addIdentifier(alias);
        builder.receive(new IdentifierUpdateNotification(APCO25RadioIdentifier.createFrom(120002),
            IdentifierUpdateNotification.Operation.ADD, 2));

        assertEquals(120001, builder.getIdentifierCollection().getFromIdentifier().getValue());
        assertSame(alias, talkerAlias(builder));
    }

    private static Identifier<?> talkerAlias(MutableAudioCallBuilder builder)
    {
        return builder.getIdentifierCollection().getIdentifier(IdentifierClass.USER, Form.TALKER_ALIAS, Role.FROM);
    }

    private static MutableAudioCallBuilder builder()
    {
        return new MutableAudioCallBuilder(AliasList.empty("test"), 1);
    }

    private static IdentifierUpdateNotification notification(Identifier<?> identifier,
                                                             IdentifierUpdateNotification.Operation operation)
    {
        return new IdentifierUpdateNotification(identifier, operation, 1);
    }
}
