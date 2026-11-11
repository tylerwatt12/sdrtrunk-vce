package io.github.dsheirer.module.decode.p25.telemetry;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class P25VoiceBsiTrackerTest {
 private static final long F=851012500;
 private static P25NetworkConfigurationSnapshot control() {
  return new P25NetworkConfigurationSnapshot("P25_PHASE_1",null,null,List.of(
   new P25NetworkConfigurationSnapshot.Channel("base_station","1-2",F,null,false,1,"CONTROL",1000L)),List.of(),List.of(),List.of(),List.of());
 }
 @Test void sparseVoiceEvidenceIsPerCarrierAndDoesNotReplaceControlEvidence() {
  var t=new P25VoiceBsiTracker(); t.generation(1,1);
  t.observe(F,"WKSD533",1000); t.observe(F,"WKSD533",1000); t.observe(F+12500,"WKSD533",2000);
  assertEquals(1,t.merge(control()).channels().size());
  t.observe(F,"WKSD533",1801000);
  var channels=t.merge(control()).channels(); assertEquals(2,channels.size());
  assertEquals("CONTROL",channels.getFirst().callsign()); assertEquals("WKSD533",channels.getLast().callsign());
  assertEquals(1801000,channels.getLast().observedAtMs());
  t.observe(F,"",3601000); t.observe(F,"OTHER",3602000);
  assertEquals("WKSD533",t.merge(control()).channels().getLast().callsign());
  t.observe(F,"OTHER",3603000); assertEquals("OTHER",t.merge(control()).channels().getLast().callsign());
  t.generation(1,2); assertEquals(1,t.merge(control()).channels().size());
 }
 @Test void expiredOrReorderedEvidenceCannotConfirmOrRewind() {
  var t=new P25VoiceBsiTracker(); t.observe(F,"WKSD533",1000); t.observe(F,"WKSD533",7201001);
  assertEquals(1,t.merge(control()).channels().size());
  t.observe(F,"WKSD533",7201002); t.observe(F,"OTHER",1000); t.observe(F,"OTHER",2000);
  assertEquals("WKSD533",t.merge(control()).channels().getLast().callsign());
  assertEquals(7201002,t.merge(control()).channels().getLast().observedAtMs());
 }
}
