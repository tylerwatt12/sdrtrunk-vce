package io.github.dsheirer.module.decode.event;

import static org.junit.jupiter.api.Assertions.*;
import io.github.dsheirer.alias.*;
import io.github.dsheirer.audio.call.*;
import io.github.dsheirer.audio.tone.CompletedToneSequence;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.configuration.SiteConfigurationIdentifier;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.nbfm.NBFMTalkgroup;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.identifier.patch.APCO25PatchGroup;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.protocol.Protocol;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CompletedToneEventViewTest
{
    @Test void capturedSourceScopesQualifiedAliasNamesIdsSiteAndFamilyWithoutActivity()
    {
        var definition=new AliasListDefinition("Alerts",AliasListFamily.P25);definition.setId(1);
        var aliases=new AliasList(definition);
        var group=new Alias("Dispatch");group.setId(77);
        group.setMatchIdentifier(new io.github.dsheirer.alias.id.talkgroup.Talkgroup(Protocol.APCO25,9000));aliases.addAlias(group);
        var radio=new Alias("Engine 1");radio.setId(78);
        radio.setMatchIdentifier(new io.github.dsheirer.alias.id.radio.Radio(Protocol.APCO25,9001));aliases.addAlias(radio);
        var model=new AliasModel(){@Override public AliasList getAliasList(IdentifierCollection ignored){return aliases;}};
        var homeGroup=APCO25FullyQualifiedTalkgroupIdentifier.createTo(9000,0xBEE00,0x348,9000);
        var homeRadio=APCO25FullyQualifiedRadioIdentifier.createFrom(9001,0xBEE00,0x348,9001);
        var view=DecodeEventViewService.completedToneView(sequence(DecoderType.P25_PHASE1,false,List.of(homeGroup,homeRadio,
            SiteConfigurationIdentifier.create("East"))),model);
        assertEquals(List.of(77L,78L),view.alertFacts().aliasIds());
        assertEquals("Dispatch",view.toAliases());assertEquals("Engine 1",view.fromAliases());
        assertEquals("East",view.alertFacts().siteName());assertEquals("1:2",view.alertFacts().siteId());
        assertEquals("P25",view.alertFacts().protocolFamily());assertEquals("p25:bee00:348",view.servingSystemKey());
        assertEquals(List.of("HZ_437_50"),view.alertFacts().toneCodes());
        var foreignGroup=APCO25FullyQualifiedTalkgroupIdentifier.createTo(9000,0xBEE00,0x349,9000);
        var foreignRadio=APCO25FullyQualifiedRadioIdentifier.createFrom(9001,0xBEE00,0x349,9001);
        view=DecodeEventViewService.completedToneView(sequence(DecoderType.P25_PHASE1,false,List.of(foreignGroup,foreignRadio)),model);
        assertTrue(view.alertFacts().aliasIds().isEmpty());assertNull(view.toAliases());assertNull(view.fromAliases());
    }

    @Test void tonePresenceDoesNotReplaceAnalogGroupPatchOrPrivateCallTypes()
    {
        assertType("CALL",DecoderType.NBFM,false,List.of(new NBFMTalkgroup(200)));
        assertType("CALL_GROUP",DecoderType.P25_PHASE1,false,List.of(APCO25Talkgroup.create(200)));
        assertType("CALL_GROUP_ENCRYPTED",DecoderType.P25_PHASE1,true,List.of(APCO25Talkgroup.create(200)));
        assertType("CALL_PATCH_GROUP",DecoderType.P25_PHASE2,false,List.of(APCO25PatchGroup.create(200)));
        assertType("CALL_PATCH_GROUP_ENCRYPTED",DecoderType.P25_PHASE2,true,List.of(APCO25PatchGroup.create(200)));
        assertType("CALL_UNIT_TO_UNIT",DecoderType.P25_PHASE1,false,List.of(APCO25RadioIdentifier.createFrom(100),APCO25RadioIdentifier.createTo(200)));
        assertType("CALL_UNIT_TO_UNIT_ENCRYPTED",DecoderType.P25_PHASE1,true,List.of(APCO25RadioIdentifier.createFrom(100),APCO25RadioIdentifier.createTo(200)));
    }
    @Test void completedNativeDigitalCallsKeepCapturedSiteScopeAcrossSourceCopies()
    {
        for(var decoder:List.of(DecoderType.DMR,DecoderType.NXDN))
        {
            var id=new AudioCallId(22,decoder.ordinal(),0);
            var source=new CallLegSource(decoder,"00000000-0000-4000-8000-000000000001","Native site",null,1,null,null,null,false,
                decoder==DecoderType.DMR?"dmr:tier3:small:1":"nxdn-c:global:1").withServingSiteId("2");
            source=source.asTrafficChannel().withSiteEvidenceGeneration(2,3).withRadioSystemKey(source.radioSystemKey());
            var snapshot=new AudioCallSnapshot(id,null,null,new IdentifierCollection(),Set.of(),1000,3000,1,0,1000,3000,false,true,
                CallEncryptionState.CLEAR,false,null,VoiceCallQuality.EMPTY,CallLegId.from(id),source,null);
            var sequence=new CompletedToneSequence(snapshot,0,CompletedToneSequence.Source.ENCODED,
                CompletedToneSequence.Method.AMBE_TONE_V1,List.of(new CompletedToneSequence.Element(List.of(437500),"HZ_437_50",0L,100,null)));
            var view=DecodeEventViewService.completedToneView(sequence,null);
            assertEquals("2",view.alertFacts().siteId());assertEquals(source.radioSystemKey(),view.alertFacts().systemKey());
            assertEquals(decoder.name(),view.alertFacts().protocolFamily());
            assertNull(source.withRadioSystemKey(null).servingSiteId(),"Changed system identity cannot reuse an older site's scope");
        }
    }

    static void assertType(String type,DecoderType decoder,boolean encrypted,List<Identifier> identifiers)
    {
        assertEquals(type,DecodeEventViewService.completedToneView(sequence(decoder,encrypted,identifiers),null).eventType());
    }
    static CompletedToneSequence sequence(DecoderType decoder,boolean encrypted,List<Identifier> identifiers)
    {
        var id=new AudioCallId(22,1,0);
        boolean p25=decoder==DecoderType.P25_PHASE1||decoder==DecoderType.P25_PHASE2;
        var source=new CallLegSource(decoder,"00000000-0000-4000-8000-000000000001","Southwest",null,1,
            p25?new P25SiteIdentity(0xBEE00,0x348,1,2):null,null,null,false,p25?"p25:bee00:348":null);
        var snapshot=new AudioCallSnapshot(id,null,null,new IdentifierCollection(identifiers),Set.of(),1000,3000,1,0,1000,3000,false,true,
            encrypted?CallEncryptionState.ENCRYPTED:CallEncryptionState.CLEAR,false,null,VoiceCallQuality.EMPTY,CallLegId.from(id),source,null);
        return new CompletedToneSequence(snapshot,1000,CompletedToneSequence.Source.DECODED_AUDIO,
            CompletedToneSequence.Method.P25_PCM_AMBE_TONE_V2,List.of(new CompletedToneSequence.Element(List.of(437500),null,50L,250,.99)));
    }
}
