/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.module.decode.event.DecodeEvent;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase1.P25P1DataUnitID;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.standard.LCTelephoneInterconnectVoiceChannelUser;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.isp.MessageUpdateRequest;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.GroupVoiceChannelGrant;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.MessageUpdate;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.ProtectionParameterUpdate;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.TelephoneInterconnectVoiceChannelGrantUpdate;
import io.github.dsheirer.protocol.Protocol;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Radio field contracts from TIA-102.AABC-B 4.2.9.1, 6.1.11.1, 6.2.10.1, 6.2.13 and
 * TIA-102.AABF-A 7.3.6. A small radio address must not become a group merely because it fits in 16 bits.
 */
class P25Phase1RadioAddressMapperTest
{
    private static final String CONFIGURATION_ID = "223e4567-e89b-42d3-a456-426614174000";
    private static final P25P1DataUnitID DATA_UNIT = P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1;
    private static final int[] RADIO_ADDRESSES = {1_234, 4_782_270, 0xFFFFFC};

    @Test
    void explicitSubscriberTargetsKeepTheirRadioTypeThroughActivityMapping()
    {
        for(int address: RADIO_ADDRESSES)
        {
            CorrectedBinaryMessage message = tsbk(0x1C, address, 32);
            MessageUpdate update = new MessageUpdate(DATA_UNIT, message, 0x344, 1_000L);
            assertRadioTarget(address, update.getTargetAddress(), update.getIdentifiers(), DecodeEventType.SDM);

            MessageUpdateRequest request = new MessageUpdateRequest(DATA_UNIT, message, 0x344, 1_000L);
            assertRadioTarget(address, request.getTargetAddress(), request.getIdentifiers(), DecodeEventType.SDM);

            ProtectionParameterUpdate protection = new ProtectionParameterUpdate(DATA_UNIT,
                tsbk(0x3F, address, 56), 0x344, 1_000L);
            assertRadioTarget(address, protection.getTargetAddress(), protection.getIdentifiers(),
                DecodeEventType.COMMAND);
        }
    }

    @Test
    void telephoneSubscriberEndpointsKeepTheirRadioTypeAndExistingTrackingPlacement()
    {
        for(int address: RADIO_ADDRESSES)
        {
            TelephoneInterconnectVoiceChannelGrantUpdate grant = new TelephoneInterconnectVoiceChannelGrantUpdate(
                DATA_UNIT, tsbk(0x09, address, 56), 0x344, 1_000L);
            assertRadioTarget(address, grant.getAnyAddress(), grant.getIdentifiers(), DecodeEventType.CALL_INTERCONNECT);

            CorrectedBinaryMessage lc = new CorrectedBinaryMessage(72);
            lc.setInt(0x06, IntField.length6(2));
            lc.setInt(address, IntField.length24(48));
            LCTelephoneInterconnectVoiceChannelUser user = new LCTelephoneInterconnectVoiceChannelUser(lc);
            assertRadioTarget(address, user.getAddress(), user.getIdentifiers(), DecodeEventType.CALL_INTERCONNECT);
        }
    }

    @Test
    void anActualGroupFieldKeepsItsGroupTypeEvenWhenTheSourceRadioHasTheSameNumber()
    {
        CorrectedBinaryMessage message = tsbk(0x00, 1_234, 56);
        message.setInt(1_234, IntField.length16(40));
        GroupVoiceChannelGrant grant = new GroupVoiceChannelGrant(DATA_UNIT, message, 0x344, 1_000L);

        ReceiverActivityRecords.ActivityEvent record = map(grant.getIdentifiers(), DecodeEventType.CALL_GROUP);

        assertNotNull(record);
        assertEquals("1234", record.sourceRadioId());
        assertEquals("1234", record.targetId());
        assertEquals(Form.TALKGROUP.name(), record.targetKind());
        assertEquals(TrunkedIdentityPolicy.IDENTITY_KIND_TALKGROUP,
            TrunkedIdentityPolicy.identityKindCode(record.targetKind()));
    }

    private static void assertRadioTarget(int address, Identifier<?> endpoint, List<Identifier> identifiers,
                                          DecodeEventType eventType)
    {
        RadioIdentifier radio = assertInstanceOf(RadioIdentifier.class, endpoint);
        assertEquals(address, radio.getValue().intValue());
        assertEquals(Form.RADIO, radio.getForm());
        //Telephone messages locate the endpoint in TO for existing tracking, not to claim who initiated the call.
        assertEquals(Role.TO, radio.getRole());

        ReceiverActivityRecords.ActivityEvent record = map(identifiers, eventType);

        assertNotNull(record);
        assertEquals(Integer.toString(address), record.targetId());
        assertEquals(Form.RADIO.name(), record.targetKind());
        assertEquals(TrunkedIdentityPolicy.IDENTITY_KIND_RADIO,
            TrunkedIdentityPolicy.identityKindCode(record.targetKind()));
        assertEquals(ReceiverActivityRecords.P25IdentityState.ORDINARY, record.p25TargetIdentity().state());
        assertNull(record.targetObservedWorkingId(), "A local address alone does not prove a canonical subscriber");
        assertFalse(record.countedCall(), "A raw receiver observation does not count a completed trunked call");
    }

    private static ReceiverActivityRecords.ActivityEvent map(List<Identifier> identifiers, DecodeEventType type)
    {
        Channel channel = new Channel("P25", Channel.ChannelType.STANDARD);
        channel.setConfigurationId(CONFIGURATION_ID);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        DecodeEvent event = new DecodeEvent(type, 1_000L);
        event.setProtocol(Protocol.APCO25);
        event.setIdentifierCollection(new MutableIdentifierCollection(identifiers));
        return new ReceiverActivityMapper().map(channel, event);
    }

    private static CorrectedBinaryMessage tsbk(int opcode, int address, int addressOffset)
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(96);
        message.setInt(opcode, IntField.length6(2));
        message.setInt(address, IntField.length24(addressOffset));
        return message;
    }
}
