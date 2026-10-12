package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.source.tuner.diagnostic.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TunerAllocationFrequencySeedServiceTest
{
    @Test void exactSavedBindingSeedsConfirmedServingFrequenciesAndExcludesForeignNeighborAndUplink() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:"); Statement sql = connection.createStatement())
        {
            sql.execute("CREATE TABLE configuration_channel(configuration_id TEXT PRIMARY KEY, channel_kind TEXT, decoder_type TEXT, config_json TEXT)");
            sql.execute("CREATE TABLE receiver_channel(id INTEGER PRIMARY KEY,configuration_id TEXT UNIQUE,radio_system_id INTEGER)");
            sql.execute("CREATE TABLE radio_system(id INTEGER PRIMARY KEY,p25_wacn INTEGER,p25_system_id INTEGER)");
            sql.execute("CREATE TABLE p25_site_snapshot(channel_id INTEGER PRIMARY KEY,rfss INTEGER,site INTEGER)");
            sql.execute("CREATE TABLE p25_site_channel(channel_id INTEGER,channel_key TEXT,downlink_hz INTEGER,uplink_hz INTEGER,PRIMARY KEY(channel_id,channel_key))");
            sql.execute("CREATE TABLE p25_site_channel_tag(channel_id INTEGER,channel_key TEXT,tag TEXT,PRIMARY KEY(channel_id,channel_key,tag))");
            sql.execute("INSERT INTO configuration_channel VALUES('saved','TRUNKED','P25_PHASE1','{\"p25SiteIdentity\":{\"wacn\":1,\"system\":2,\"rfss\":3,\"site\":4}}')");
            sql.execute("INSERT INTO receiver_channel VALUES(10,'saved',20)");
            sql.execute("INSERT INTO radio_system VALUES(20,1,2)");
            sql.execute("INSERT INTO p25_site_snapshot VALUES(10,3,4)");
            sql.execute("INSERT INTO p25_site_channel VALUES(10,'control',851000000,806000000),(10,'voice',852000000,807000000),(10,'neighbor',853000000,808000000),(10,'unknown',854000000,809000000)");
            sql.execute("INSERT INTO p25_site_channel_tag VALUES(10,'control','CURRENT_CONTROL'),(10,'voice','VOICE'),(10,'neighbor','NEIGHBOR')");
            var frequencies = StatsWebDatabase.tunerAllocationKnownFrequencies(connection, "saved");
            assertEquals(List.of(851_000_000L, 852_000_000L), frequencies.stream().map(TunerAllocationKnownSite.Frequency::frequencyHz).toList());
            assertEquals(TunerAllocationContext.Role.CONTROL, frequencies.getFirst().role());
            assertEquals(TunerAllocationContext.Role.CALL, frequencies.getLast().role());
            sql.execute("UPDATE p25_site_snapshot SET site=5");
            assertTrue(StatsWebDatabase.tunerAllocationKnownFrequencies(connection, "saved").isEmpty());
            sql.execute("UPDATE p25_site_snapshot SET site=4");
            sql.execute("UPDATE radio_system SET p25_system_id=99");
            assertTrue(StatsWebDatabase.tunerAllocationKnownFrequencies(connection, "saved").isEmpty());
            sql.execute("UPDATE radio_system SET p25_system_id=2");
            sql.execute("UPDATE configuration_channel SET config_json='{}'");
            assertTrue(StatsWebDatabase.tunerAllocationKnownFrequencies(connection, "saved").isEmpty());
        }
    }

    @Test void backgroundSeederOnlyReadsActiveControlSitesOnceAndKeepsCollectionIndependentOfBrowsers()
    {
        TunerAllocationContext control = new TunerAllocationContext("saved", "Control", "System", "Site", null,
            TunerAllocationContext.Role.CONTROL);
        TunerAllocationContext voice = new TunerAllocationContext("saved", "Call", "System", "Site", null,
            TunerAllocationContext.Role.CALL);
        TunerAllocationContext conventional = new TunerAllocationContext("conventional", "Conventional", null, null, null,
            TunerAllocationContext.Role.CONVENTIONAL);
        List<String> reads = new ArrayList<>();
        List<TunerAllocationContext> observed = new ArrayList<>();
        try(var service = new TunerAllocationFrequencySeedService(() -> List.of(
            new TunerAllocationRuntimeChannel("tuner", voice, 852_000_000, 12_500),
            new TunerAllocationRuntimeChannel("tuner", control, 851_000_000, 12_500),
            new TunerAllocationRuntimeChannel("tuner", control, 851_000_000, 12_500),
            new TunerAllocationRuntimeChannel("tuner", conventional, 853_000_000, 12_500)), id -> {
                reads.add(id);
                return List.of(new TunerAllocationKnownSite.Frequency(854_000_000, 12_500, TunerAllocationContext.Role.CALL));
            }, (context, frequency) -> observed.add(context)))
        {
            service.refresh();
            assertEquals(List.of("saved"), reads);
            assertEquals(1, observed.size());
            assertEquals("saved", observed.getFirst().channelId());
            assertEquals(TunerAllocationContext.Role.CALL, observed.getFirst().role());
        }
    }
}
