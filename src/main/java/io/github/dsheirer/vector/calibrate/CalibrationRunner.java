package io.github.dsheirer.vector.calibrate;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Shared UI-independent calibration job; callbacks never execute inside timed operations. */
public final class CalibrationRunner
{
    private static final AtomicBoolean ACTIVE = new AtomicBoolean();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    public record Progress(int completed, int total, String operation) {}
    public record Result(int completed, int failed, boolean cancelled) {}
    public void cancel() { cancelled.set(true); }
    public Result run(List<? extends Calibration> pending, Consumer<Progress> progress, Consumer<String> output)
    {
        if(!ACTIVE.compareAndSet(false, true)) throw new IllegalStateException("Calibration is already running.");
        int done = 0;
        int failures = 0;
        try
        {
            for(Calibration calibration: List.copyOf(pending))
            {
                if(cancelled.get()) break;
                progress.accept(new Progress(done, pending.size(), calibration.getType().toString()));
                try
                {
                    calibration.calibrate();
                    if(!calibration.isCalibrated()) throw new CalibrationException("No valid selection");
                    if(calibration.getType() == CalibrationType.RSP_SAMPLE_CONVERTER)
                        io.github.dsheirer.source.tuner.sdrplay.RspSampleConverterFactory.setImplementation(calibration.getImplementation());
                    output.accept(calibration.getType() + ": " + calibration.getImplementation());
                }
                catch(Exception e)
                {
                    calibration.reset();
                    failures++;
                    output.accept(calibration.getType() + ": failed; retry this operation.");
                }
                done++;
                progress.accept(new Progress(done, pending.size(), calibration.getType().toString()));
            }
            return new Result(done, failures, cancelled.get());
        }
        finally { ACTIVE.set(false); }
    }
}
