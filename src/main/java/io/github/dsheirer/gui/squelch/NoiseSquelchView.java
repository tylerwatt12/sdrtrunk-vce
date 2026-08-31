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

import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.dsp.squelch.INoiseSquelchController;
import io.github.dsheirer.dsp.squelch.NoiseSquelch;
import io.github.dsheirer.dsp.squelch.NoiseSquelchState;
import io.github.dsheirer.gui.symbol.ChannelView;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.util.ThreadPool;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Side;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JavaFX view of live noise squelch operating state and controls.
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
     * Noise squelch history buffer size for a two-second chart at the 50 millisecond state cadence.
     */
    private static final int HISTORY_BUFFER_SIZE = 40;

    private static final String NOT_AVAILABLE = "not available";
    private final ConfigurationManager mConfigurationManager;
    private final List<NoiseSquelchState> mSquelchStateHistory = new ArrayList<>();
    private final AtomicReference<NoiseSquelchState> mPendingState = new AtomicReference<>();
    private final AtomicBoolean mChartUpdatePending = new AtomicBoolean();
    private volatile INoiseSquelchController mController;
    private ScheduledFuture<?> mTimerFuture;

    private ToggleButton mSquelchOverrideButton;
    private Button mDefaultsButton;
    private Slider mNoiseOpenSlider;
    private Slider mNoiseCloseSlider;
    private Slider mHysteresisOpenSlider;
    private Slider mHysteresisCloseSlider;

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
    private volatile boolean mControlsUpdated = true;
    private boolean mUpdatingControls;

    public NoiseSquelchView(ConfigurationManager configurationManager)
    {
        mConfigurationManager = configurationManager;
        init();
    }

    /**
     * Setup the user interface components.
     */
    private void init()
    {
        HBox leftStatus = new HBox(8);
        Label squelchHeaderLabel = new Label("Audio Squelch:");
        Label noiseHeaderLabel = new Label("Noise:");
        leftStatus.getChildren().addAll(squelchHeaderLabel, getSquelchStateLabel(), noiseHeaderLabel,
            getNoiseValueLabel());
        HBox hysteresisStatus = new HBox(8);
        Label hysteresisHeaderLabel = new Label("Hysteresis:");
        hysteresisStatus.getChildren().addAll(hysteresisHeaderLabel, getHysteresisValueLabel());
        BorderPane statusPane = new BorderPane();
        statusPane.setPadding(new Insets(5));
        statusPane.setLeft(leftStatus);
        statusPane.setRight(hysteresisStatus);

        GridPane controls = new GridPane();
        controls.setHgap(8);
        controls.setVgap(4);
        controls.setPadding(new Insets(0, 5, 5, 5));
        controls.setMaxWidth(Double.MAX_VALUE);

        Label noiseOpenLabel = new Label("Noise Open:");
        GridPane.setHalignment(noiseOpenLabel, HPos.RIGHT);
        controls.add(noiseOpenLabel, 0, 0);
        GridPane.setHgrow(getNoiseOpenSlider(), Priority.ALWAYS);
        controls.add(getNoiseOpenSlider(), 1, 0);

        Label noiseCloseLabel = new Label("Noise Close:");
        GridPane.setHalignment(noiseCloseLabel, HPos.RIGHT);
        controls.add(noiseCloseLabel, 2, 0);
        GridPane.setHgrow(getNoiseCloseSlider(), Priority.ALWAYS);
        controls.add(getNoiseCloseSlider(), 3, 0);

        Label hysteresisOpenLabel = new Label("Hysteresis Open:");
        GridPane.setHalignment(hysteresisOpenLabel, HPos.RIGHT);
        controls.add(hysteresisOpenLabel, 0, 1);
        GridPane.setHgrow(getHysteresisOpenSlider(), Priority.ALWAYS);
        controls.add(getHysteresisOpenSlider(), 1, 1);

        Label hysteresisCloseLabel = new Label("Hysteresis Close:");
        GridPane.setHalignment(hysteresisCloseLabel, HPos.RIGHT);
        controls.add(hysteresisCloseLabel, 2, 1);
        GridPane.setHgrow(getHysteresisCloseSlider(), Priority.ALWAYS);
        controls.add(getHysteresisCloseSlider(), 3, 1);

        controls.add(getSquelchOverrideButton(), 0, 2);
        controls.add(getDefaultsButton(), 1, 2);

        VBox.setVgrow(statusPane, Priority.NEVER);
        VBox.setVgrow(controls, Priority.NEVER);
        VBox.setVgrow(getActivityChart(), Priority.ALWAYS);
        getChildren().addAll(statusPane, controls, getActivityChart());
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
     * Receives the latest noise squelch state from the decoder without waiting for UI work.  Intermediate UI-only
     * states may be coalesced when the chart is busy.
     */
    @Override
    public void receive(NoiseSquelchState noiseSquelchState)
    {
        mPendingState.lazySet(noiseSquelchState);
    }

    /**
     * Initializes the view controls to the latest received noise squelch state which should always be accurate with the
     * current state of the noise squelch.
     * @param noiseSquelchState for initializing the controls.
     */
    private void updateViewControls(NoiseSquelchState noiseSquelchState)
    {
        Platform.runLater(() -> {
            if(mController != null && !mControlsUpdated)
            {
                mUpdatingControls = true;
                getNoiseOpenSlider().setValue(noiseSquelchState.noiseOpenThreshold());
                getNoiseCloseSlider().setValue(noiseSquelchState.noiseCloseThreshold());
                getHysteresisOpenSlider().setValue(noiseSquelchState.hysteresisOpenThreshold());
                getHysteresisCloseSlider().setValue(noiseSquelchState.hysteresisCloseThreshold());
                getSquelchOverrideButton().setSelected(noiseSquelchState.squelchOverride());
                mUpdatingControls = false;

                setControlsDisabled(false);
                getActivityChart().setDisable(false);
                updateLabels(noiseSquelchState);
                mControlsUpdated = true;
            }
        });
    }

    /**
     * Resets/clears chart and controls.
     */
    private void reset()
    {
        mPendingState.set(null);

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

        mUpdatingControls = true;
        getNoiseOpenSlider().setValue(NoiseSquelch.DEFAULT_NOISE_OPEN_THRESHOLD);
        getNoiseCloseSlider().setValue(NoiseSquelch.DEFAULT_NOISE_CLOSE_THRESHOLD);
        getHysteresisOpenSlider().setValue(NoiseSquelch.DEFAULT_HYSTERESIS_OPEN_THRESHOLD);
        getHysteresisCloseSlider().setValue(NoiseSquelch.DEFAULT_HYSTERESIS_CLOSE_THRESHOLD);
        getSquelchOverrideButton().setSelected(false);
        mUpdatingControls = false;
        setControlsDisabled(true);

        getHysteresisValueLabel().setText(NOT_AVAILABLE);
        getNoiseValueLabel().setText(NOT_AVAILABLE);
        getSquelchStateLabel().setText(NOT_AVAILABLE);

        mControlsUpdated = false;
    }

    /**
     * Updates the chart and labels from the latest state on the diagnostic timer thread.  The decoder callback never
     * accesses this history.
     */
    private void updateChart()
    {
        if(!mChartUpdatePending.compareAndSet(false, true))
        {
            return;
        }

        NoiseSquelchState pendingState = mPendingState.getAndSet(null);

        if(pendingState != null)
        {
            synchronized(mSquelchStateHistory)
            {
                mSquelchStateHistory.add(pendingState);

                if(mSquelchStateHistory.size() > HISTORY_BUFFER_SIZE)
                {
                    mSquelchStateHistory.removeFirst();
                }
            }

            if(!mControlsUpdated)
            {
                updateViewControls(pendingState);
            }
        }

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
                if(!isShowing() || mController == null)
                {
                    return;
                }

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
            finally
            {
                mChartUpdatePending.set(false);
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
        if(!Platform.isFxApplicationThread())
        {
            Platform.runLater(() -> setController(controller));
            return;
        }

        try
        {
            cancelTimer();

            //Unregister from previous controller.
            if(mController != null)
            {
                mController.setSquelchOverride(false);
                mController.setNoiseSquelchStateListener(null);
            }

            mController = null;
            reset();
            mController = controller;

            if(mController != null)
            {
                mController.setNoiseSquelchStateListener(this);
            }

            updateTimer();
        }
        catch(Exception e)
        {
            LOGGER.error("Error updating noise squelch controller", e);
        }
    }

    private void setControlsDisabled(boolean disabled)
    {
        getNoiseOpenSlider().setDisable(disabled);
        getNoiseCloseSlider().setDisable(disabled);
        getHysteresisOpenSlider().setDisable(disabled);
        getHysteresisCloseSlider().setDisable(disabled);
        getSquelchOverrideButton().setDisable(disabled);
        getDefaultsButton().setDisable(disabled);
        getNoiseValueLabel().setDisable(disabled);
        getHysteresisValueLabel().setDisable(disabled);
        getSquelchStateLabel().setDisable(disabled);
    }

    private Slider getNoiseOpenSlider()
    {
        if(mNoiseOpenSlider == null)
        {
            mNoiseOpenSlider = noiseSlider("Noise threshold that opens the squelch");
            mNoiseOpenSlider.valueProperty().addListener((observable, oldValue, newValue) -> updateNoiseThresholds());
        }

        return mNoiseOpenSlider;
    }

    private Slider getNoiseCloseSlider()
    {
        if(mNoiseCloseSlider == null)
        {
            mNoiseCloseSlider = noiseSlider("Noise threshold that closes the squelch");
            mNoiseCloseSlider.valueProperty().addListener((observable, oldValue, newValue) -> updateNoiseThresholds());
        }

        return mNoiseCloseSlider;
    }

    private Slider noiseSlider(String tooltip)
    {
        Slider slider = new Slider(NoiseSquelch.MINIMUM_NOISE_THRESHOLD, NoiseSquelch.MAXIMUM_NOISE_THRESHOLD,
            NoiseSquelch.DEFAULT_NOISE_OPEN_THRESHOLD);
        slider.setMajorTickUnit(0.1);
        slider.setMinorTickCount(4);
        slider.setShowTickMarks(true);
        slider.setShowTickLabels(true);
        slider.setTooltip(new Tooltip(tooltip));
        slider.setDisable(true);
        return slider;
    }

    private Slider getHysteresisOpenSlider()
    {
        if(mHysteresisOpenSlider == null)
        {
            mHysteresisOpenSlider = hysteresisSlider("Consecutive 10 ms windows required to open the squelch");
            mHysteresisOpenSlider.valueProperty()
                .addListener((observable, oldValue, newValue) -> updateHysteresisThresholds());
        }

        return mHysteresisOpenSlider;
    }

    private Slider getHysteresisCloseSlider()
    {
        if(mHysteresisCloseSlider == null)
        {
            mHysteresisCloseSlider = hysteresisSlider("Consecutive 10 ms windows required to close the squelch");
            mHysteresisCloseSlider.valueProperty()
                .addListener((observable, oldValue, newValue) -> updateHysteresisThresholds());
        }

        return mHysteresisCloseSlider;
    }

    private Slider hysteresisSlider(String tooltip)
    {
        Slider slider = new Slider(NoiseSquelch.MINIMUM_HYSTERESIS_THRESHOLD,
            NoiseSquelch.MAXIMUM_HYSTERESIS_THRESHOLD, NoiseSquelch.DEFAULT_HYSTERESIS_OPEN_THRESHOLD);
        slider.setBlockIncrement(1);
        slider.setMajorTickUnit(1);
        slider.setMinorTickCount(0);
        slider.setSnapToTicks(true);
        slider.setShowTickMarks(true);
        slider.setShowTickLabels(true);
        slider.setTooltip(new Tooltip(tooltip));
        slider.setDisable(true);
        return slider;
    }

    private ToggleButton getSquelchOverrideButton()
    {
        if(mSquelchOverrideButton == null)
        {
            mSquelchOverrideButton = new ToggleButton("Override");
            mSquelchOverrideButton.setDisable(true);
            mSquelchOverrideButton.setTooltip(new Tooltip("Temporarily keep the squelch open"));
            mSquelchOverrideButton.setOnAction(event -> {
                if(mController != null && !mUpdatingControls)
                {
                    mController.setSquelchOverride(mSquelchOverrideButton.isSelected());
                }
            });
        }

        return mSquelchOverrideButton;
    }

    private Button getDefaultsButton()
    {
        if(mDefaultsButton == null)
        {
            mDefaultsButton = new Button("Defaults");
            mDefaultsButton.setDisable(true);
            mDefaultsButton.setTooltip(new Tooltip("Restore the default squelch thresholds"));
            mDefaultsButton.setOnAction(event -> {
                mUpdatingControls = true;
                getNoiseOpenSlider().setValue(NoiseSquelch.DEFAULT_NOISE_OPEN_THRESHOLD);
                getNoiseCloseSlider().setValue(NoiseSquelch.DEFAULT_NOISE_CLOSE_THRESHOLD);
                getHysteresisOpenSlider().setValue(NoiseSquelch.DEFAULT_HYSTERESIS_OPEN_THRESHOLD);
                getHysteresisCloseSlider().setValue(NoiseSquelch.DEFAULT_HYSTERESIS_CLOSE_THRESHOLD);
                getSquelchOverrideButton().setSelected(false);
                mUpdatingControls = false;

                if(mController != null)
                {
                    mController.setSquelchOverride(false);
                    updateNoiseThresholds();
                    updateHysteresisThresholds();
                }
            });
        }

        return mDefaultsButton;
    }

    private void updateNoiseThresholds()
    {
        if(mController == null || mUpdatingControls || !isShowing())
        {
            return;
        }

        float open = (float)getNoiseOpenSlider().getValue();
        float close = (float)Math.max(open, getNoiseCloseSlider().getValue());

        if(close != (float)getNoiseCloseSlider().getValue())
        {
            mUpdatingControls = true;
            getNoiseCloseSlider().setValue(close);
            mUpdatingControls = false;
        }

        mController.setNoiseThreshold(open, close);
        scheduleConfigurationSave();
    }

    private void updateHysteresisThresholds()
    {
        if(mController == null || mUpdatingControls || !isShowing())
        {
            return;
        }

        int open = (int)Math.round(getHysteresisOpenSlider().getValue());
        int close = Math.max(open, (int)Math.round(getHysteresisCloseSlider().getValue()));

        if(close != (int)Math.round(getHysteresisCloseSlider().getValue()))
        {
            mUpdatingControls = true;
            getHysteresisCloseSlider().setValue(close);
            mUpdatingControls = false;
        }

        mController.setHysteresisThreshold(open, close);
        scheduleConfigurationSave();
    }

    private void scheduleConfigurationSave()
    {
        if(mConfigurationManager != null)
        {
            mConfigurationManager.scheduleConfigurationSave();
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
