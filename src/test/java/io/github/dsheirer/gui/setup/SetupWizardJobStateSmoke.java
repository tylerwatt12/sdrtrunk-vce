package io.github.dsheirer.gui.setup;

import com.formdev.flatlaf.FlatLightLaf;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.portable.PortableApplicationPaths;
import io.github.dsheirer.portable.PortableDataRootLock;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.portable.SqlitePreferencesFactory;
import io.github.dsheirer.vector.calibrate.CalibrationRunner;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** Deterministic setup-save failure checks; never scans hardware, calibrates, or starts a receiver. */
public final class SetupWizardJobStateSmoke
{
    public static void main(String[] args) throws Exception
    {
        if(args.length != 1) throw new IllegalArgumentException("Supply a new wizard-smoke- disposable data root");
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        if(!root.getFileName().toString().startsWith("wizard-smoke-") || Files.exists(root))
            throw new IllegalArgumentException("Use a new wizard-smoke- disposable directory");
        Files.createDirectories(root);
        System.setProperty(PortableApplicationPaths.DATA_ROOT_PROPERTY, root.toString());
        Path database = root.resolve("database/sdrtrunk.sqlite");
        Path unavailable = root.resolve("missing/database/sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        SqlitePreferencesFactory.install(database);
        SetupProgress progress = new SetupProgress(false, false);
        progress.set(SetupStep.CALIBRATION, SetupProgress.State.NEEDS_ATTENTION);
        progress.save(database);
        SetupWizard[] holder = new SetupWizard[1];
        try
        {
            edt(() -> {
                FlatLightLaf.setup();
                var constructor = SetupWizard.class.getDeclaredConstructor(
                    String[].class, Path.class, PortableDataRootLock.class);
                constructor.setAccessible(true);
                SetupWizard wizard = constructor.newInstance(new String[]{"--setup-wizard"}, root, null);
                holder[0] = wizard;
                field("preferences").set(wizard, new UserPreferences());
                field("progress").set(wizard, progress);
                field("step").set(wizard, SetupStep.CALIBRATION);
                wizard.addNotify(); //A real displayable Swing tree without opening a modal window.
                field("database").set(wizard, unavailable);
            });
            SetupWizard wizard = holder[0];
            rejectedJobDoesNotStart(wizard, progress);
            for(CalibrationRunner.Result result : new CalibrationRunner.Result[]{
                new CalibrationRunner.Result(1, 0, true),
                new CalibrationRunner.Result(1, 1, false),
                new CalibrationRunner.Result(1, 0, false)})
                calibrationSaveFailureStaysVisible(wizard, progress, result);
            failedWorkHandlerKeepsSaveError(wizard, progress, database, unavailable);
            if(Files.exists(unavailable)) throw new AssertionError("Failure injection created a replacement database");
            if(SetupProgress.read(database).get(SetupStep.CALIBRATION) != SetupProgress.State.NEEDS_ATTENTION)
                throw new AssertionError("Failed save changed the persisted calibration state");
            System.out.println("Wizard job-state smoke passed: rejected job, three calibration outcomes, and failed-work handler");
        }
        finally
        {
            if(holder[0] != null) edt(() -> {
                SetupWizard wizard = holder[0];
                field("database").set(wizard, database);
                ((Timer)field("refresh").get(wizard)).stop();
                ((ExecutorService)field("worker").get(wizard)).shutdownNow();
                wizard.dispose();
            });
            SqlitePreferencesFactory.shutdown();
        }
    }

    private static void rejectedJobDoesNotStart(SetupWizard wizard, SetupProgress progress) throws Exception
    {
        AtomicBoolean workStarted = new AtomicBoolean(), successCalled = new AtomicBoolean();
        edt(() -> method("job", String.class, Runnable.class, Callable.class, Consumer.class).invoke(wizard,
            "Disposable setup operation", null,
            (Callable<Boolean>)() -> { workStarted.set(true); return true; },
            (Consumer<Boolean>)ignored -> successCalled.set(true)));
        //A queue barrier proves the rejected operation was never submitted, without a timing-based sleep.
        ((ExecutorService)field("worker").get(wizard)).submit(() -> {}).get(5, TimeUnit.SECONDS);
        edt(() -> {
            require(!workStarted.get(), "Setup work started after its initial progress save failed");
            require(!successCalled.get(), "Rejected setup work invoked its success continuation");
            require(!field("busy").getBoolean(wizard), "Rejected setup work left the wizard busy");
            require(field("cancellation").get(wizard) == null, "Rejected setup work retained cancellation state");
            require(!((JProgressBar)field("meter").get(wizard)).isVisible(), "Rejected setup work shows a progress meter");
            require(!((JButton)field("cancel").get(wizard)).isVisible(), "Rejected setup work shows a cancel action");
            require(progress.get(SetupStep.CALIBRATION) == SetupProgress.State.NEEDS_ATTENTION,
                "Rejected setup work did not restore its prior progress state");
            assertSaveError(wizard);
        });
    }

    private static void calibrationSaveFailureStaysVisible(SetupWizard wizard, SetupProgress progress,
                                                          CalibrationRunner.Result result) throws Exception
    {
        edt(() -> {
            progress.set(SetupStep.CALIBRATION, SetupProgress.State.NEEDS_ATTENTION);
            JPanel page = (JPanel)field("page").get(wizard);
            JLabel marker = new JLabel("Calibration review awaiting saved result");
            page.removeAll();
            page.add(marker);
            method("clearErrorFeedback").invoke(wizard);
            method("calibrationFinished", CalibrationRunner.Result.class).invoke(wizard, result);
            require(marker.getParent() == page, "Calibration completion replaced the page after its save failed");
            require(progress.get(SetupStep.CALIBRATION) == SetupProgress.State.NEEDS_ATTENTION,
                "Calibration completion did not restore progress after its save failed");
            assertSaveError(wizard);
        });
    }

    private static void failedWorkHandlerKeepsSaveError(SetupWizard wizard, SetupProgress progress,
                                                       Path database, Path unavailable) throws Exception
    {
        AtomicBoolean workStarted = new AtomicBoolean(), successCalled = new AtomicBoolean();
        AtomicBoolean failureHandled = new AtomicBoolean(), handlerClearedError = new AtomicBoolean();
        edt(() -> {
            field("database").set(wizard, database);
            progress.set(SetupStep.CALIBRATION, SetupProgress.State.NEEDS_ATTENTION);
            progress.save(database);
            Object accepted = method("job", String.class, Runnable.class, Callable.class,
                Consumer.class, Consumer.class).invoke(wizard, "Disposable failing setup operation", null,
                (Callable<Boolean>)() -> {
                    workStarted.set(true);
                    field("database").set(wizard, unavailable);
                    throw new IllegalStateException("Disposable setup failure");
                },
                (Consumer<Boolean>)ignored -> successCalled.set(true),
                (Consumer<Throwable>)ignored -> {
                    failureHandled.set(true);
                    try
                    {
                        method("clearErrorFeedback").invoke(wizard);
                        handlerClearedError.set(!((JTextArea)field("danger").get(wizard)).isVisible());
                    }
                    catch(Exception failure) { throw new RuntimeException(failure); }
                });
            require(Boolean.TRUE.equals(accepted), "The fixture operation did not start from a writable profile");
        });
        ((ExecutorService)field("worker").get(wizard)).submit(() -> {}).get(5, TimeUnit.SECONDS);
        edt(() -> {
            require(workStarted.get(), "The fixture did not exercise a completed work failure");
            require(!successCalled.get(), "Failed setup work invoked its success continuation");
            require(failureHandled.get() && handlerClearedError.get(),
                "The fixture handler did not exercise a page feedback reset");
            require(!field("busy").getBoolean(wizard), "Failed setup work left the wizard busy");
            assertSaveError(wizard);
            field("database").set(wizard, database);
            progress.set(SetupStep.CALIBRATION, SetupProgress.State.NEEDS_ATTENTION);
            progress.save(database);
        });
    }

    private static void assertSaveError(SetupWizard wizard) throws Exception
    {
        JTextArea error = (JTextArea)field("danger").get(wizard);
        require(error.isVisible() && error.getText().startsWith("Settings could not be saved."),
            "The current save failure is not visible");
        require(((JButton)field("copyError").get(wizard)).isVisible(), "The save error cannot be copied");
        require(((String)field("errorReport").get(wizard)).startsWith("Settings could not be saved."),
            "The copyable report does not describe the current save failure");
    }

    private static Field field(String name) throws Exception
    {
        Field field = SetupWizard.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Method method(String name, Class<?>... arguments) throws Exception
    {
        Method method = SetupWizard.class.getDeclaredMethod(name, arguments);
        method.setAccessible(true);
        return method;
    }

    private static void require(boolean condition, String message)
    {
        if(!condition) throw new AssertionError(message);
    }

    @FunctionalInterface private interface EdtAction { void run() throws Exception; }

    private static void edt(EdtAction action) throws Exception
    {
        try
        {
            SwingUtilities.invokeAndWait(() -> {
                try { action.run(); }
                catch(Exception failure) { throw new RuntimeException(failure); }
            });
        }
        catch(InvocationTargetException failure)
        {
            Throwable cause = failure.getCause();
            if(cause instanceof Error error) throw error;
            if(cause instanceof Exception exception) throw exception;
            throw failure;
        }
    }
}
