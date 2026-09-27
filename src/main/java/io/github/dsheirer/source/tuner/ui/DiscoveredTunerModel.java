/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.source.tuner.ui;

import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.tuner.TunerEvent;
import io.github.dsheirer.source.tuner.configuration.TunerConfigurationManager;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.source.tuner.manager.DiscoveredTunerRegistry;
import java.awt.EventQueue;
import java.text.DecimalFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.table.AbstractTableModel;

/** Swing projection of the receiver-owned tuner registry. Discovery never waits for the event dispatch thread. */
public class DiscoveredTunerModel extends AbstractTableModel
{
    private static final long serialVersionUID = 1L;
    public static final int COLUMN_TUNER_STATUS = 0;
    public static final int COLUMN_TUNER_CLASS = 1;
    public static final int COLUMN_TUNER_TYPE = 2;
    public static final int COLUMN_FREQUENCY = 3;
    public static final int COLUMN_CHANNEL_COUNT = 4;
    private static final String[] COLUMN_HEADERS = {"Status", "Class", "Type", "Frequency", "Channels"};
    private final DiscoveredTunerRegistry mRegistry;
    private final DecimalFormat mFrequencyFormat = new DecimalFormat("0.00000");
    private final CopyOnWriteArrayList<Listener<TunerEvent>> mEventListeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean mRefreshPending = new AtomicBoolean();
    private final AtomicBoolean mRowsRefreshPending = new AtomicBoolean();
    private final AtomicBoolean mLockEventPending = new AtomicBoolean();
    private final AtomicReference<TunerEvent> mLatestLockEvent = new AtomicReference<>();

    /** Standalone constructor retained for existing desktop callers. */
    public DiscoveredTunerModel(TunerConfigurationManager configurations)
    {
        this(new DiscoveredTunerRegistry(configurations));
    }

    public DiscoveredTunerModel(DiscoveredTunerRegistry registry)
    {
        mRegistry = registry;
        // Registry changes can outpace the EDT. Row indexes from discovery threads would already be stale by the
        // time a queued Swing notification runs, so one bounded refresh always reads the latest registry snapshot.
        mRegistry.addChangeListener((change, tuner, index) ->
        {
            if(change == DiscoveredTunerRegistry.Change.UPDATED)
            {
                scheduleRowsRefresh();
            }
            else
            {
                scheduleRefresh();
            }
        });
        mRegistry.addTunerEventListener(event ->
        {
            if(event.getEvent() != TunerEvent.Event.UPDATE_LOCK_STATE)
            {
                return;
            }
            mLatestLockEvent.set(event);
            dispatchPendingLockEvent();
        });
    }

    private void dispatchPendingLockEvent()
    {
        if(mLockEventPending.compareAndSet(false, true))
        {
            EventQueue.invokeLater(() ->
            {
                TunerEvent latest = mLatestLockEvent.getAndSet(null);
                mLockEventPending.set(false);
                if(latest != null)
                {
                    for(Listener<TunerEvent> listener: mEventListeners)
                    {
                        listener.receive(latest);
                    }
                }
                if(mLatestLockEvent.get() != null)
                {
                    dispatchPendingLockEvent();
                }
            });
        }
    }

    private void scheduleRefresh()
    {
        if(mRefreshPending.compareAndSet(false, true))
        {
            EventQueue.invokeLater(() ->
            {
                mRefreshPending.set(false);
                fireTableDataChanged();
            });
        }
    }

    private void scheduleRowsRefresh()
    {
        if(mRowsRefreshPending.compareAndSet(false, true))
        {
            EventQueue.invokeLater(() ->
            {
                mRowsRefreshPending.set(false);
                int rowCount = getRowCount();
                if(rowCount > 0)
                {
                    fireTableRowsUpdated(0, rowCount - 1);
                }
            });
        }
    }

    public List<DiscoveredTuner> getAvailableTuners()
    {
        return mRegistry.availableTuners();
    }

    public List<DiscoveredTuner> getTunersSnapshot()
    {
        return mRegistry.snapshot();
    }

    public DiscoveredTuner getDiscoveredTuner(int index)
    {
        List<DiscoveredTuner> snapshot = mRegistry.snapshot();
        return index >= 0 && index < snapshot.size() ? snapshot.get(index) : null;
    }

    public DiscoveredTuner getDiscoveredTuner(String id)
    {
        return mRegistry.find(id);
    }

    public void addDiscoveredTuner(DiscoveredTuner tuner)
    {
        mRegistry.add(tuner);
    }

    public void removeDiscoveredTuner(DiscoveredTuner tuner)
    {
        mRegistry.remove(tuner);
    }

    public void tunerBecameAvailable(DiscoveredTuner tuner)
    {
        if(tuner != null && tuner.hasTuner())
        {
            mRegistry.tunerStatusUpdated(tuner, tuner.getTunerStatus(), tuner.getTunerStatus());
        }
    }

    public void releaseDiscoveredTuners()
    {
        mRegistry.release();
    }

    public boolean hasTuner(DiscoveredTuner tuner)
    {
        return mRegistry.contains(tuner);
    }

    public boolean hasUsbTuner(int bus, String portAddress)
    {
        return mRegistry.hasUsbTuner(bus, portAddress);
    }

    public DiscoveredTuner removeUsbTuner(int bus, String portAddress)
    {
        return mRegistry.removeUsbTuner(bus, portAddress);
    }

    public void addListener(Listener<TunerEvent> listener)
    {
        mEventListeners.addIfAbsent(listener);
    }

    public void removeListener(Listener<TunerEvent> listener)
    {
        mEventListeners.remove(listener);
    }

    @Override
    public int getRowCount()
    {
        return mRegistry.snapshot().size();
    }

    @Override
    public int getColumnCount()
    {
        return COLUMN_HEADERS.length;
    }

    @Override
    public String getColumnName(int column)
    {
        return COLUMN_HEADERS[column];
    }

    @Override
    public Object getValueAt(int row, int column)
    {
        DiscoveredTuner discovered = getDiscoveredTuner(row);
        if(discovered == null)
        {
            return null;
        }
        return switch(column)
        {
            case COLUMN_TUNER_STATUS -> discovered.getTunerStatus();
            case COLUMN_TUNER_CLASS -> discovered.getTunerClass().toString();
            case COLUMN_TUNER_TYPE -> discovered.hasTuner() ? discovered.getTuner().getTunerType().getLabel() : "";
            case COLUMN_FREQUENCY -> discovered.hasTuner() ?
                mFrequencyFormat.format(discovered.getTuner().getTunerController().getFrequency() / 1E6D) + " MHz" : "";
            case COLUMN_CHANNEL_COUNT -> discovered.hasTuner() ?
                discovered.getTuner().getChannelSourceManager().getTunerChannelCount() + " (" +
                    (discovered.getTuner().getTunerController().isLockedSampleRate() ? "LOCKED)" : "UNLOCKED)") : "";
            default -> null;
        };
    }

    public String getDiagnosticReport()
    {
        StringBuilder report = new StringBuilder("Discovered Tuner Model Diagnostic Report\n");
        for(DiscoveredTuner tuner: mRegistry.snapshot())
        {
            report.append("\n\n--------------- DISCOVERED TUNER --------------------\n\n")
                .append(tuner.getDiagnosticReport()).append('\n');
        }
        return report.toString();
    }
}
