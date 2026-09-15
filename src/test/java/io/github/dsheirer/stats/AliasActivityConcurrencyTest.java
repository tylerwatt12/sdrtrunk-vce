/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.preference.UserPreferences;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AliasActivityConcurrencyTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    @SuppressWarnings("unchecked")
    void activityPagesRemainBoundedWhileCompleteHundredThousandAliasExportIsSpooled() throws Exception
    {
        AliasActivityRepresentativeTestDatabase.Fixture fixture =
            AliasActivityRepresentativeTestDatabase.createExact(mTemporaryFolder.resolve("concurrent.sqlite"));
        StatsWebDatabase database = new StatsWebDatabase(new UserPreferences(), fixture.database());
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch exportStarted = new CountDownLatch(1);
        AtomicBoolean exporting = new AtomicBoolean();
        AtomicInteger pagesStartedDuringExport = new AtomicInteger();

        try(var executor = Executors.newVirtualThreadPerTaskExecutor())
        {
            Future<TimedExport> export = executor.submit(() ->
            {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                exporting.set(true);
                exportStarted.countDown();
                long started = System.nanoTime();
                try
                {
                    AliasTransferExport.Prepared prepared = database.aliasTransferExport(new StatsRequest(Map.of(
                        "scope", "all", "list", Long.toString(fixture.aliasListId()))));
                    return new TimedExport(prepared, elapsedMillis(started));
                }
                finally
                {
                    exporting.set(false);
                }
            });
            Future<Long> pages = executor.submit(() ->
            {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                assertTrue(exportStarted.await(10, TimeUnit.SECONDS));
                long started = System.nanoTime();

                for(int index = 0; index < 12; index++)
                {
                    if(exporting.get())
                    {
                        pagesStartedDuringExport.incrementAndGet();
                    }
                    String sort = switch(index % 3)
                    {
                        case 0 -> "logical_call_count";
                        case 1 -> "signaling_observation_count";
                        default -> "last_evidence_ms";
                    };
                    Map<String,Object> response = database.aliases(new StatsRequest(Map.of(
                        "list", Long.toString(fixture.aliasListId()), "sort", sort, "direction", "desc",
                        "limit", "100", "offset", Integer.toString(index * 100))));
                    List<Map<String,Object>> rows = (List<Map<String,Object>>)response.get("rows");
                    assertEquals(100, rows.size());
                    Set<Long> ids = new HashSet<>();
                    rows.forEach(row -> ids.add(((Number)row.get("alias_id")).longValue()));
                    assertEquals(rows.size(), ids.size());
                    assertTrue((Boolean)response.get("has_more"));
                }
                return elapsedMillis(started);
            });

            start.countDown();
            TimedExport completed = export.get(60, TimeUnit.SECONDS);
            AliasTransferExport.Prepared prepared = completed.prepared();
            try(prepared)
            {
                long pageMillis = pages.get(60, TimeUnit.SECONDS);
                assertEquals(100_000, prepared.rowCount());
                assertTrue(prepared.byteCount() > 1_000_000);
                assertEquals(prepared.byteCount(), Files.size(prepared.path()));
                assertTrue(pagesStartedDuringExport.get() > 0,
                    "At least one bounded Activity page must start while export spooling is active");
                assertFalse(exporting.get());
                System.out.println("ALIAS_CONCURRENT export_ms=" + completed.elapsedMillis() +
                    " twelve_pages_ms=" + pageMillis + " overlapping_pages=" +
                    pagesStartedDuringExport.get());
            }
        }
    }

    private static long elapsedMillis(long started)
    {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private record TimedExport(AliasTransferExport.Prepared prepared, long elapsedMillis)
    {
    }
}
