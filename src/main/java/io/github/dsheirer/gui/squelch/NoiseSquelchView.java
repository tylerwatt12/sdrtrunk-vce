/*
 * *****************************************************************************
 * Copyright (C) 2014-2025 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.gui.squelch;

import io.github.dsheirer.dsp.squelch.INoiseSquelchController;
import io.github.dsheirer.dsp.squelch.NoiseSquelchState;
import io.github.dsheirer.gui.symbol.ChannelView;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.util.ThreadPool;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Side;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read-only JavaFX diagnostic view of noise squelch operating state.
 *
 * The NoiseSquelch control can be configured with noise and hysteresis open and close thresholds.  The two value ranges
 * for noise and hysteresis run in opposite directions relative to squelch open and close.  This view inverts the noise
 * value range from (0.5 to 0.0) to (0.0 to 10.0) by inverting the noise and noise threshold values and scaling those
 * values onto the range of 0-10 to present the user with a unified view of noise and hysteresis where both values can
 * be plotted onto an x/y chart.
 */
public class NoiseSquelchView extends ChannelView implements Listener<NoiseSquelchState>
{
    private static final Logger LOGGER = LoggerFactory.getLogger(NoiseSquelchView.class);
    private static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat("0.00");

    /**
     * Scales the noise value (0.0 to 0.5) to align with the hysteresis value range (0.0 to 10.0).  Value is determined
     * by hysteresis-max (10.0) divided by noise-max (0.5).
     */
    private static final float NOISE_DISPLAY_SCALOR = 20.0f;

    /**
     * Noise inversion value for converting displayed values in range 0-10 to usable noise values in the range 10 - 0.
     */
    private static final float NOISE_INVERSION_BASE = 10.0f;

    /**
     * Noise squelch history buffer size for x-axis of the XY chart, in units of 10 milliseconds.
     */
    private static final int HISTORY_BUFFER_SIZE = 200; //200 x 10ms = 2,000ms / 2-second history view

    private static final String NOT_AVAILABLE = "not available";
    private final List<NoiseSquelchState> mSquelchStateHistory = new ArrayList<>();
    private INoiseSquelchController mController;
    private ScheduledFuture<?> mTimerFuture;

    private LineChart<Number,Number> mActivityChart;
    private final XYChart.Series<Number,Number> mNoiseSeries = new XYChart.Series<>();
    private final XYChart.Series<Number,Number> mNoiseOpenThresholdSeries = new XYChart.Series<>();
    private final XYChart.Series<Number,Number> mNoiseCloseThresholdSeries = new XYChart.Series<>();
    private final XYChart.Series<Number,Number> mHysteresisSeries = new XYChart.Series<>();
    private final XYChart.Series<Number,Number> mHysteresisOpenThresholdSeries = new XYChart.Series<>();
    private final XYChart.Series<Number,Number> mHysteresisCloseThresholdSeries = new XYChart.Series<>();
    private Label mSquelchStateLabel;
    private Label mNoiseValueLabel;
    private Label mHysteresisValueLabel;
    private boolean mControlsUpdated = true;

    public NoiseSquelchView()
    {
        init();
    }

    /**
     * Setup the user interface components.
     */
    private void init()
    {
        GridPane gridPane = new GridPane();
        gridPane.setHgap(8);
        gridPane.setPadding(new Insets(5));
        gridPane.setMaxWidth(Double.MAX_VALUE);

        Label squelchHeaderLabel = new Label("Audio Squelch:");
        GridPane.setHalignment(squelchHeaderLabel, HPos.CENTER);
        gridPane.add(squelchHeaderLabel, 0, 0);
        gridPane.add(getSquelchStateLabel(), 1, 0);

        Label noiseHeaderLabel = new Label("Noise:");
        GridPane.setHalignment(noiseHeaderLabel, HPos.RIGHT);
        gridPane.add(noiseHeaderLabel, 2, 0);

        GridPane.setHgrow(getNoiseValueLabel(), Priority.ALWAYS);
        GridPane.setHalignment(getNoiseValueLabel(), HPos.LEFT);
        gridPane.add(getNoiseValueLabel(), 3, 0);

        Label hysteresisHeaderLabel = new Label("Hysteresis:");
        GridPane.setHalignment(hysteresisHeaderLabel, HPos.RIGHT);
        gridPane.add(hysteresisHeaderLabel, 4, 0);

        GridPane.setHgrow(getHysteresisValueLabel(), Priority.ALWAYS);
        GridPane.setHalignment(getHysteresisValueLabel(), HPos.LEFT);
        gridPane.add(getHysteresisValueLabel(), 5, 0);

        VBox.setVgrow(gridPane, Priority.NEVER);
        VBox.setVgrow(getActivityChart(), Priority.ALWAYS);
        getChildren().addAll(gridPane, getActivityChart());
    }

    /**
     * Sets this view as showing and starts the chart update timer, or sets this view as hidden and stops the update timer
     * @param showing to indicate if this view is selected by the user and showing.
     */
    @Override
    public void setShowing(boolean showing)
    {
        super.setShowing(showing);
        updateTimer();
    }

    /**
     * Cancels the chart update timer.
     */
    private synchronized void cancelTimer()
    {
        if(mTimerFuture != null)
        {
            mTimerFuture.cancel(true);
            mTimerFuture = null;
        }
    }

    /**
     * Updates the timer to process incoming decoder states and update the XY chart values.
     */
    private synchronized void updateTimer()
    {
        if(isShowing() && mController != null && mTimerFuture == null)
        {
            //Start the timer
            mTimerFuture = ThreadPool.SCHEDULED.scheduleAtFixedRate(this::updateChart, 0, 50, TimeUnit.MILLISECONDS);
        }
        else if((!isShowing() || mController == null) && mTimerFuture != null)
        {
            cancelTimer();
        }
    }

    /**
     * Primary method for receiving noise squelch state updates from the decoder.  Manages the squelch state history
     * buffer size.  This method is invoked by the channel buffer processing thread and cooperates with the chart
     * update timer thread by synchronizing/blocking on the squelch state history.
     * @param noiseSquelchState to add to the history.
     */
    @Override
    public void receive(NoiseSquelchState noiseSquelchState)
    {
        synchronized(mSquelchStateHistory)
        {
            mSquelchStateHistory.add(noiseSquelchState);

            while(mSquelchStateHistory.size() > HISTORY_BUFFER_SIZE)
            {
                mSquelchStateHistory.removeFirst();
            }
        }

        /**
         * If this is the first squelch state, update the view controls with these initial values.
         */
        if(!mControlsUpdated)
        {
            updateViewControls(noiseSquelchState);
        }
    }

    /**
     * Initializes the view controls to the latest received noise squelch state which should always be accurate with the
     * current state of the noise squelch.
     * @param noiseSquelchState for initializing the controls.
     */
    private void updateViewControls(NoiseSquelchState noiseSquelchState)
    {
        Platform.runLater(() -> {
            getNoiseValueLabel().setDisable(false);
            getHysteresisValueLabel().setDisable(false);
            getSquelchStateLabel().setDisable(false);
            getActivityChart().setDisable(false);
            updateLabels(noiseSquelchState);

            mControlsUpdated = true;
        });
    }

    /**
     * Resets/clears chart and controls.
     */
    private void reset()
    {
        //Clear the squelch state history
        synchronized(mSquelchStateHistory)
        {
            mSquelchStateHistory.clear();
        }

        //Clear the chart axis
        getActivityChart().setDisable(true);
        for(int x = 0; x < HISTORY_BUFFER_SIZE; x++)
        {
            mNoiseSeries.getData().get(x).setYValue(0);
            mNoiseOpenThresholdSeries.getData().get(x).setYValue(0);
            mNoiseCloseThresholdSeries.getData().get(x).setYValue(0);
            mHysteresisSeries.getData().get(x).setYValue(0);
            mHysteresisOpenThresholdSeries.getData().get(x).setYValue(0);
            mHysteresisCloseThresholdSeries.getData().get(x).setYValue(0);
        }

        getHysteresisValueLabel().setDisable(true);
        getHysteresisValueLabel().setText(NOT_AVAILABLE);

        getNoiseValueLabel().setDisable(true);
        getNoiseValueLabel().setText(NOT_AVAILABLE);

        getSquelchStateLabel().setDisable(true);
        getSquelchStateLabel().setText(NOT_AVAILABLE);

        mControlsUpdated = false;
    }

    /**
     * Updates the chart and label values from the noise squelch state history buffer.  This method is fired by the
     * scheduled timer process and cooperates squelch state history buffer thread access by synchronizing/blocking on the
     * squelch state history buffer.
     */
    private void updateChart()
    {
        final int[] hysteresis = new int[HISTORY_BUFFER_SIZE];
        final int[] hysteresisOpenThreshold = new int[HISTORY_BUFFER_SIZE];
        final int[] hysteresisCloseThreshold = new int[HISTORY_BUFFER_SIZE];
        final float[] noise = new float[HISTORY_BUFFER_SIZE];
        final float[] noiseOpenThreshold = new float[HISTORY_BUFFER_SIZE];
        final float[] noiseCloseThreshold = new float[HISTORY_BUFFER_SIZE];
        NoiseSquelchState latestState = null;

        synchronized(mSquelchStateHistory)
        {
            if(!mSquelchStateHistory.isEmpty())
            {
                latestState = mSquelchStateHistory.getLast();
            }

            if(mSquelchStateHistory.size() == HISTORY_BUFFER_SIZE)
            {
                for(int x = 0; x < HISTORY_BUFFER_SIZE; x++)
                {
                    if(mSquelchStateHistory.size() > x)
                    {
                        NoiseSquelchState state = mSquelchStateHistory.get(x);

                        noise[x] = toDisplayNoise(state.noise());
                        noiseOpenThreshold[x] = toDisplayNoise(state.noiseOpenThreshold());
                        noiseCloseThreshold[x] = toDisplayNoise(state.noiseCloseThreshold());

                        hysteresis[x] = state.hysteresis();
                        hysteresisOpenThreshold[x] = state.hysteresisOpenThreshold();
                        hysteresisCloseThreshold[x] = state.hysteresisCloseThreshold();

                    }
                }
            }
            else
            {
                //On startup, make the values stream in from the right instead of the left until the buffer is full.
                int offset = 0;

                for(int x = 0; x < mSquelchStateHistory.size(); x++)
                {
                    offset = x + (HISTORY_BUFFER_SIZE - mSquelchStateHistory.size());

                    NoiseSquelchState state = mSquelchStateHistory.get(x);

                    noise[offset] = toDisplayNoise(state.noise());
                    noiseOpenThreshold[offset] = toDisplayNoise(state.noiseOpenThreshold());
                    noiseCloseThreshold[offset] = toDisplayNoise(state.noiseCloseThreshold());

                    hysteresis[offset] = state.hysteresis();
                    hysteresisOpenThreshold[offset] = state.hysteresisOpenThreshold();
                    hysteresisCloseThreshold[offset] = state.hysteresisCloseThreshold();

                }
            }
        }

        final NoiseSquelchState finalLatestState = latestState;

        //Update the chart and label displays on the JavaFX thread.
        Platform.runLater(() -> {
            try
            {
                for(int x = 0; x < HISTORY_BUFFER_SIZE; x++)
                {
                    mNoiseSeries.getData().get(x).setYValue(noise[x]);
                    mNoiseOpenThresholdSeries.getData().get(x).setYValue(noiseOpenThreshold[x]);
                    mNoiseCloseThresholdSeries.getData().get(x).setYValue(noiseCloseThreshold[x]);
                    mHysteresisSeries.getData().get(x).setYValue(hysteresis[x]);
                    mHysteresisOpenThresholdSeries.getData().get(x).setYValue(hysteresisOpenThreshold[x]);
                    mHysteresisCloseThresholdSeries.getData().get(x).setYValue(hysteresisCloseThreshold[x]);
                }

                if(finalLatestState != null)
                {
                    updateLabels(finalLatestState);
                }
            }
            catch(Exception e)
            {
                LOGGER.error("Error updating audio squelch noise values in squelch view", e);
            }
        });
    }

    /**
     * Sets the noise squelch controller for this view.  Unregisters the previous controller, clears the display and
     * registers the new controller on the JavaFX UI thread if it is non-null.
     *
     * Note: this method is invoked by the Swing UI thread in response to user action.
     *
     * @param controller to set (non-null) or clear (null).
     */
    public void setController(INoiseSquelchController controller)
    {
        try
        {
            cancelTimer();

            //Unregister from previous controller.
            if(mController != null)
            {
                mController.setNoiseSquelchStateListener(null);
            }

            //Nullify the controller so the reset doesn't trigger any save actions.
            mController = null;

            //Since this is invoked on the Swing UI thread, transfer control to the JavaFX UI thread since we're
            //accessing the JavaFX controls.
            Platform.runLater(() -> {
                reset();

                mController = controller;

                if(mController != null)
                {
                    mController.setNoiseSquelchStateListener(NoiseSquelchView.this);
                }

                updateTimer();
            });
        }
        catch(Exception e)
        {
            LOGGER.error("Error updating noise squelch controller", e);
        }
    }

    /**
     * Label to display the squelch state.
     */
    private Label getSquelchStateLabel()
    {
        if(mSquelchStateLabel == null)
        {
            mSquelchStateLabel = new Label(NOT_AVAILABLE);
            mSquelchStateLabel.setDisable(true);
            mSquelchStateLabel.setStyle("-fx-font-weight: bold;");
        }

        return mSquelchStateLabel;
    }

    /**
     * Label to display the open, close and current noise values.
     */
    private Label getNoiseValueLabel()
    {
        if(mNoiseValueLabel == null)
        {
            mNoiseValueLabel = new Label(NOT_AVAILABLE);
            mNoiseValueLabel.setDisable(true);
            mNoiseValueLabel.setPadding(new Insets(0, 0, 0, 3));
        }

        return mNoiseValueLabel;
    }

    /**
     * Label to display the open, close and current hysteresis values.
     */
    private Label getHysteresisValueLabel()
    {
        if(mHysteresisValueLabel == null)
        {
            mHysteresisValueLabel = new Label(NOT_AVAILABLE);
            mHysteresisValueLabel.setDisable(true);
            mHysteresisValueLabel.setPadding(new Insets(0, 0, 0, 3));
        }

        return mHysteresisValueLabel;
    }

    private void updateLabels(NoiseSquelchState state)
    {
        getSquelchStateLabel().setText(state.squelchOverride() ? "Override" : state.squelch() ? "Closed" : "Open");
        getNoiseValueLabel().setText("Open " + DECIMAL_FORMAT.format(toDisplayNoise(state.noiseOpenThreshold())) +
                "  Close " + DECIMAL_FORMAT.format(toDisplayNoise(state.noiseCloseThreshold())) + "  Current " +
                DECIMAL_FORMAT.format(toDisplayNoise(state.noise())));
        getHysteresisValueLabel().setText("Open " + state.hysteresisOpenThreshold() + "  Close " +
                state.hysteresisCloseThreshold() + "  Current " + state.hysteresis());
    }

    /**
     * Converts from the noise squelch controller noise value to the display noise value.
     * @param noiseValue from the controller
     * @return value for display
     */
    private static float toDisplayNoise(float noiseValue)
    {
        return NOISE_INVERSION_BASE - (noiseValue * NOISE_DISPLAY_SCALOR);
    }

    /**
     * Line chart displaying combined noise and hysteresis history.  Uses 6x lines to display open, close and current
     * values for noise and hysteresis.
     */
    private LineChart<Number,Number> getActivityChart()
    {
        if(mActivityChart == null)
        {
            NumberAxis xAxis = new NumberAxis(0, HISTORY_BUFFER_SIZE - 1,  0);
            NumberAxis yAxis = new NumberAxis(-0.5, 10.5, 1);
            mActivityChart = new LineChart<>(xAxis, yAxis);
            mActivityChart.getStyleClass().add("noise-squelch-chart");
            mActivityChart.setLegendSide(Side.RIGHT);
            mActivityChart.setPadding(new Insets(0, 5, 0, 0));
            mActivityChart.setAnimated(false); //Turn off animation
            mActivityChart.setCreateSymbols(false); //Turn off data point markers
            mActivityChart.setMaxHeight(Double.MAX_VALUE);
            mActivityChart.setMaxWidth(Double.MAX_VALUE);
            mActivityChart.lookup(".chart-plot-background").setStyle("-fx-background-color: black;");

            for(int x = 0; x < HISTORY_BUFFER_SIZE; x++)
            {
                mNoiseSeries.getData().add(new XYChart.Data<>(x, 0));
                mNoiseOpenThresholdSeries.getData().add(new XYChart.Data<>(x, 0));
                mNoiseCloseThresholdSeries.getData().add(new XYChart.Data<>(x, 0));
                mHysteresisSeries.getData().add(new XYChart.Data<>(x, 0));
                mHysteresisOpenThresholdSeries.getData().add(new XYChart.Data<>(x, 0));
                mHysteresisCloseThresholdSeries.getData().add(new XYChart.Data<>(x, 0));
            }

            mNoiseSeries.setName("Noise (N)");
            mNoiseOpenThresholdSeries.setName("N-Open");
            mNoiseCloseThresholdSeries.setName("N-Close");

            mHysteresisSeries.setName("Hysteresis");
            mHysteresisOpenThresholdSeries.setName("H-Open");
            mHysteresisCloseThresholdSeries.setName("H-Close");

            mActivityChart.getData().add(mNoiseSeries);
            mActivityChart.getData().add(mNoiseOpenThresholdSeries);
            mActivityChart.getData().add(mNoiseCloseThresholdSeries);
            mActivityChart.getData().add(mHysteresisSeries);
            mActivityChart.getData().add(mHysteresisCloseThresholdSeries);
            mActivityChart.getData().add(mHysteresisOpenThresholdSeries);
        }

        return mActivityChart;
    }
}
