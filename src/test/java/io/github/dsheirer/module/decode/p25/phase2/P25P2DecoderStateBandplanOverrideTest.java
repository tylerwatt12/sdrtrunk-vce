/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.module.decode.p25.phase2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.Channel.ChannelType;
import io.github.dsheirer.identifier.patch.PatchGroupManager;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.P25TrafficChannelManager;
import io.github.dsheirer.module.decode.p25.bandplan.P25BandplanChannelType;
import io.github.dsheirer.module.decode.p25.bandplan.P25BandplanOverrideBand;
import io.github.dsheirer.module.decode.p25.bandplan.P25BandplanOverrideProfile;
import io.github.dsheirer.module.decode.p25.bandplan.P25BandplanOverrideRegistry;
import io.github.dsheirer.module.decode.p25.phase2.enumeration.DataUnitID;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.MacMessage;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.MacMessageFactory;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.NetworkStatusBroadcastExplicit;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.NetworkStatusBroadcastImplicit;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.RfssStatusBroadcastExplicit;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.RfssStatusBroadcastImplicit;
import java.util.List;
import org.junit.jupiter.api.Test;

class P25P2DecoderStateBandplanOverrideTest
{
    private static final P25SiteIdentity SITE = new P25SiteIdentity(0xBEE00, 0x49F, 1, 2);
    private static final int NAC = 0x941;
    private static final int BAND = 0;
    private static final int CHANNEL = 10;
    private static final long EXPECTED_FREQUENCY = 851_068_750L;

    @Test
    void implicitStatusMessagesBootstrapAndUseTheOverride()
    {
        Fixture fixture = fixture();
        fixture.state().receive(networkImplicit(1_000L));
        fixture.state().receive(rfssImplicit(1_001L));

        MacMessage resolved = rfssImplicit(1_002L);
        fixture.state().receive(resolved);

        RfssStatusBroadcastImplicit status = (RfssStatusBroadcastImplicit)resolved.getMacStructure();
        assertEquals(EXPECTED_FREQUENCY, status.getChannel().getDownlinkFrequency());
    }

    @Test
    void explicitStatusMessagesBootstrapAndUseTheOverride()
    {
        Fixture fixture = fixture();
        fixture.state().receive(networkExplicit(1_000L));
        fixture.state().receive(rfssExplicit(1_001L));

        MacMessage resolved = rfssExplicit(1_002L);
        fixture.state().receive(resolved);

        RfssStatusBroadcastExplicit status = (RfssStatusBroadcastExplicit)resolved.getMacStructure();
        assertEquals(EXPECTED_FREQUENCY, status.getChannel().getDownlinkFrequency());
    }

    private static Fixture fixture()
    {
        DecodeConfigP25Phase2 configuration = new DecodeConfigP25Phase2();
        configuration.setUseP25BandplanOverride(true);
        Channel channel = new Channel("P25 Phase 2 Control", ChannelType.STANDARD);
        channel.setDecodeConfiguration(configuration);
        P25BandplanOverrideProfile profile = new P25BandplanOverrideProfile(SITE.wacn(), SITE.system(), null, null,
            List.of(new P25BandplanOverrideBand(BAND, P25BandplanChannelType.FDMA, 851_006_250L, 12_500,
                6_250L, -45_000_000L)));
        P25TrafficChannelManager manager = new P25TrafficChannelManager(channel,
            P25BandplanOverrideRegistry.of(List.of(profile)));
        return new Fixture(new P25P2DecoderState(channel, 0, manager, new PatchGroupManager()));
    }

    private static MacMessage networkImplicit(long timestamp)
    {
        int offset = MacMessageFactory.DEFAULT_MAC_STRUCTURE_INDEX;
        CorrectedBinaryMessage bits = messageBits(123);
        bits.setInt(SITE.wacn(), IntField.length20(offset + 16));
        bits.setInt(SITE.system(), IntField.length12(offset + 36));
        bits.setInt(BAND, IntField.length4(offset + 48));
        bits.setInt(CHANNEL, IntField.length12(offset + 52));
        bits.setInt(NAC, IntField.length12(offset + 76));
        return message(timestamp, bits, new NetworkStatusBroadcastImplicit(bits, offset));
    }

    private static MacMessage networkExplicit(long timestamp)
    {
        int offset = MacMessageFactory.DEFAULT_MAC_STRUCTURE_INDEX;
        CorrectedBinaryMessage bits = messageBits(251);
        bits.setInt(SITE.wacn(), IntField.length20(offset + 16));
        bits.setInt(SITE.system(), IntField.length12(offset + 36));
        bits.setInt(BAND, IntField.length4(offset + 48));
        bits.setInt(CHANNEL, IntField.length12(offset + 52));
        bits.setInt(BAND, IntField.length4(offset + 64));
        bits.setInt(CHANNEL, IntField.length12(offset + 68));
        bits.setInt(NAC, IntField.length12(offset + 92));
        return message(timestamp, bits, new NetworkStatusBroadcastExplicit(bits, offset));
    }

    private static MacMessage rfssImplicit(long timestamp)
    {
        int offset = MacMessageFactory.DEFAULT_MAC_STRUCTURE_INDEX;
        CorrectedBinaryMessage bits = messageBits(122);
        setRfssIdentity(bits, offset);
        bits.setInt(BAND, IntField.length4(offset + 48));
        bits.setInt(CHANNEL, IntField.length12(offset + 52));
        return message(timestamp, bits, new RfssStatusBroadcastImplicit(bits, offset));
    }

    private static MacMessage rfssExplicit(long timestamp)
    {
        int offset = MacMessageFactory.DEFAULT_MAC_STRUCTURE_INDEX;
        CorrectedBinaryMessage bits = messageBits(250);
        setRfssIdentity(bits, offset);
        bits.setInt(BAND, IntField.length4(offset + 48));
        bits.setInt(CHANNEL, IntField.length12(offset + 52));
        bits.setInt(BAND, IntField.length4(offset + 64));
        bits.setInt(CHANNEL, IntField.length12(offset + 68));
        return message(timestamp, bits, new RfssStatusBroadcastExplicit(bits, offset));
    }

    private static void setRfssIdentity(CorrectedBinaryMessage bits, int offset)
    {
        bits.setInt(SITE.system(), IntField.length12(offset + 20));
        bits.setInt(SITE.rfss(), IntField.length8(offset + 32));
        bits.setInt(SITE.site(), IntField.length8(offset + 40));
    }

    private static CorrectedBinaryMessage messageBits(int opcode)
    {
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(180);
        bits.setInt(opcode, IntField.length8(MacMessageFactory.DEFAULT_MAC_STRUCTURE_INDEX));
        return bits;
    }

    private static MacMessage message(long timestamp, CorrectedBinaryMessage bits,
                                      io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.MacStructure structure)
    {
        MacMessage message = new MacMessage(0, DataUnitID.UNSCRAMBLED_LCCH, bits, timestamp, structure);
        message.setNAC(NAC);
        return message;
    }

    private record Fixture(P25P2DecoderState state)
    {
    }
}
