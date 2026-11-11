package io.github.dsheirer.module.decode.p25.phase1.message.lc.motorola;
import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.LinkControlWordFactory;
import io.github.dsheirer.module.decode.p25.reference.MotorolaBsi;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class LCMotorolaBaseStationIdTest {
 @Test void decodesCapturedAndBlankWordsAndPreservesTheOpaqueTail() {
  var word=assertInstanceOf(LCMotorolaBaseStationId.class,
   LinkControlWordFactory.create(CorrectedBinaryMessage.loadHex("0590B20A1928820000"),1000,true));
  assertEquals("WKSD533",word.getCallsign()); assertEquals(0,word.getRawFinalOctet());
  assertTrue(word.getIdentifiers().isEmpty());
  assertEquals("",new LCMotorolaBaseStationId(CorrectedBinaryMessage.loadHex("059000000000000000")).getCallsign());
  var changed=new LCMotorolaBaseStationId(CorrectedBinaryMessage.loadHex("0590B20A19288200A7"));
  assertEquals("WKSD533",changed.getCallsign()); assertEquals(0xA7,changed.getRawFinalOctet());
  assertTrue(changed.toString().contains("A7"));
 }
 @Test void zeroPaddingIsOmittedAndAllEightCharactersAreAvailable() {
  assertEquals("jjjjjjjj",MotorolaBsi.decodeCallsign(CorrectedBinaryMessage.loadHex("0590FFFFFFFFFFFF00")));
  var bits=new CorrectedBinaryMessage(72); bits.load(16,6,1); bits.load(58,6,63);
  assertEquals(",j",MotorolaBsi.decodeCallsign(bits));
 }
 @Test void requiresMotorolaClearTerminatorContext() {
  for(String hex: new String[]{"0500B20A1928820000","05A4B20A1928820000","8590B20A1928820000"})
   assertFalse(LinkControlWordFactory.create(CorrectedBinaryMessage.loadHex(hex),1000,true) instanceof LCMotorolaBaseStationId);
  assertFalse(LinkControlWordFactory.create(CorrectedBinaryMessage.loadHex("0590B20A1928820000"),1000,false) instanceof LCMotorolaBaseStationId);
 }
}
