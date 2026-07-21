/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.database.upgrade;

import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.Window;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;

/**
 * Small modal progress window for the pre-startup database upgrade.
 */
public final class UpgradeProgressDialog
{
    private UpgradeProgressDialog()
    {
    }

    public static <T> T run(Window owner, String title, Operation<T> operation) throws Exception
    {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        Runnable show = () -> show(owner, title, operation, result, failure);

        if(SwingUtilities.isEventDispatchThread())
        {
            show.run();
        }
        else
        {
            try
            {
                SwingUtilities.invokeAndWait(show);
            }
            catch(InterruptedException e)
            {
                Thread.currentThread().interrupt();
                throw e;
            }
            catch(InvocationTargetException e)
            {
                Throwable cause = e.getCause();

                if(cause instanceof Exception exception)
                {
                    throw exception;
                }

                throw new RuntimeException(cause);
            }
        }

        if(failure.get() != null)
        {
            throw failure.get();
        }

        return result.get();
    }

    private static <T> void show(Window owner, String title, Operation<T> operation, AtomicReference<T> result,
                                 AtomicReference<Exception> failure)
    {
        JDialog dialog = new JDialog(owner, title, Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        JLabel status = new JLabel("Preparing upgrade...");
        JProgressBar progress = new JProgressBar();
        progress.setIndeterminate(true);
        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBorder(BorderFactory.createEmptyBorder(18, 20, 18, 20));
        content.add(status, BorderLayout.NORTH);
        content.add(progress, BorderLayout.CENTER);
        dialog.setContentPane(content);
        dialog.setMinimumSize(new Dimension(460, 115));
        dialog.pack();
        dialog.setLocationRelativeTo(owner);

        SwingWorker<T,String> worker = new SwingWorker<>()
        {
            @Override
            protected T doInBackground() throws Exception
            {
                return operation.run(this::publish);
            }

            @Override
            protected void process(List<String> steps)
            {
                if(!steps.isEmpty())
                {
                    status.setText(steps.get(steps.size() - 1) + "...");
                }
            }

            @Override
            protected void done()
            {
                try
                {
                    result.set(get());
                }
                catch(InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    failure.set(e);
                }
                catch(ExecutionException e)
                {
                    Throwable cause = e.getCause();
                    failure.set(cause instanceof Exception exception ? exception : new RuntimeException(cause));
                }
                finally
                {
                    dialog.dispose();
                }
            }
        };

        worker.execute();
        dialog.setVisible(true);
    }

    @FunctionalInterface
    public interface Operation<T>
    {
        T run(PreviousBuildUpgradeService.ProgressListener progress) throws Exception;
    }
}
