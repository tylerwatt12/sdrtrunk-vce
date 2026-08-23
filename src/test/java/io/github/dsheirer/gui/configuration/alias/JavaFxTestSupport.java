/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.gui.configuration.alias;

import io.github.dsheirer.portable.PortableApplicationPaths;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;

/** Small JavaFX and portable-path boundary for display-backed control tests. */
final class JavaFxTestSupport
{
    private static final long TIMEOUT_SECONDS = 30;
    private static boolean sToolkitStarted;

    private JavaFxTestSupport()
    {
    }

    static synchronized void startToolkit() throws Exception
    {
        if(sToolkitStarted)
        {
            return;
        }

        CountDownLatch started = new CountDownLatch(1);

        try
        {
            Platform.startup(() ->
            {
                Platform.setImplicitExit(false);
                started.countDown();
            });
        }
        catch(IllegalStateException alreadyStarted)
        {
            started.countDown();
        }

        if(!started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        {
            throw new IllegalStateException("Timed out starting the JavaFX toolkit");
        }

        sToolkitStarted = true;
    }

    static <T> T onFxThread(Callable<T> operation) throws Exception
    {
        FutureTask<T> task = new FutureTask<>(operation);

        if(Platform.isFxApplicationThread())
        {
            task.run();
        }
        else
        {
            Platform.runLater(task);
        }

        return task.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /** Drains direct and one-level-deferred JavaFX notifications. */
    static void drainEvents() throws Exception
    {
        onFxThread(() -> null);
        onFxThread(() -> null);
    }

    static synchronized <T> T withPortableDataRoot(Path dataRoot, Callable<T> operation) throws Exception
    {
        String property = PortableApplicationPaths.DATA_ROOT_PROPERTY;
        String previous = System.getProperty(property);

        try
        {
            System.setProperty(property, dataRoot.toString());
            resetPortablePaths();
            return operation.call();
        }
        finally
        {
            if(previous == null)
            {
                System.clearProperty(property);
            }
            else
            {
                System.setProperty(property, previous);
            }

            resetPortablePaths();
        }
    }

    private static void resetPortablePaths() throws Exception
    {
        Method reset = PortableApplicationPaths.class.getDeclaredMethod("resetForTest");
        reset.setAccessible(true);

        try
        {
            reset.invoke(null);
        }
        catch(InvocationTargetException e)
        {
            Throwable cause = e.getCause();

            if(cause instanceof Exception exception)
            {
                throw exception;
            }

            throw e;
        }
    }
}
