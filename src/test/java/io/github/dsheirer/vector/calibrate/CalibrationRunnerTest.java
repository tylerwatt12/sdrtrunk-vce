package io.github.dsheirer.vector.calibrate;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CalibrationRunnerTest
{
    @Test void partialFailurePreservesValidResultsAndRetryRunsOnlyOutstandingTests()
    {
        Fake first=new Fake(), second=new Fake(); second.fail=true;
        var result=new CalibrationRunner().run(List.of(first,second),p->assertFalse(first.timed || second.timed),s->{});
        assertEquals(1,result.failed()); assertTrue(first.isCalibrated()); assertFalse(second.isCalibrated());
        second.fail=false;
        new CalibrationRunner().run(List.of(first,second).stream().filter(c->!c.isCalibrated()).toList(),p->{},s->{});
        assertEquals(1,first.runs); assertEquals(2,second.runs);
    }

    @Test void acknowledgedCancellationWaitsForCurrentTestAndRejectsDuplicateJob() throws Exception
    {
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
        Fake first=new Fake(); Fake second=new Fake();
        first.work=()-> { entered.countDown(); try { assertTrue(release.await(5,TimeUnit.SECONDS)); } catch(InterruptedException e) { throw new AssertionError(e); } };
        CalibrationRunner runner=new CalibrationRunner();
        try(var executor=Executors.newSingleThreadExecutor())
        {
            var future=executor.submit(()->runner.run(List.of(first,second),p->{},s->{}));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class,()->new CalibrationRunner().run(List.of(second),p->{},s->{}));
            runner.cancel(); assertFalse(future.isDone()); release.countDown();
            assertTrue(future.get(5,TimeUnit.SECONDS).cancelled());
            assertEquals(1,first.runs); assertEquals(0,second.runs); assertTrue(first.isCalibrated());
        }
        finally { release.countDown(); }
    }

    private static class Fake extends Calibration
    {
        boolean fail, timed; int runs; Implementation choice=Implementation.UNCALIBRATED; Runnable work=()->{};
        Fake() { super(CalibrationType.WINDOW); }
        @Override public Implementation getImplementation() { return choice; }
        @Override public void reset() { choice=Implementation.UNCALIBRATED; }
        @Override public void calibrate() throws CalibrationException
        {
            timed=true;
            try { runs++; work.run(); if(fail) throw new CalibrationException("test failure"); choice=Implementation.SCALAR; }
            finally { timed=false; }
        }
    }
}
