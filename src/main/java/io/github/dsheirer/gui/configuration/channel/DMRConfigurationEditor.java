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

package io.github.dsheirer.gui.configuration.channel;

import io.github.dsheirer.gui.control.IntegerTextField;
import io.github.dsheirer.gui.configuration.eventlog.EventLogConfigurationEditor;
import io.github.dsheirer.gui.configuration.record.RecordConfigurationEditor;
import io.github.dsheirer.gui.configuration.source.FrequencyEditor;
import io.github.dsheirer.gui.configuration.source.FrequencyField;
import io.github.dsheirer.gui.configuration.source.SourceConfigurationEditor;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.config.AuxDecodeConfiguration;
import io.github.dsheirer.module.decode.config.DecodeConfiguration;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.dmr.channel.TimeslotFrequency;
import io.github.dsheirer.module.log.EventLogType;
import io.github.dsheirer.module.log.config.EventLogConfiguration;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.record.RecorderType;
import io.github.dsheirer.record.config.RecordConfiguration;
import io.github.dsheirer.source.config.SourceConfiguration;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Callback;
import org.controlsfx.control.ToggleSwitch;

/**
 * DMR channel configuration editor
 */
public class DMRConfigurationEditor extends ChannelConfigurationEditor
{
    private TitledPane mDecoderPane;
    private TitledPane mEventLogPane;
    private TitledPane mRecordPane;
    private TitledPane mSourcePane;
    private SourceConfigurationEditor<SourceConfiguration> mSourceConfigurationEditor;
    private EventLogConfigurationEditor mEventLogConfigurationEditor;
    private RecordConfigurationEditor mRecordConfigurationEditor;
    private ToggleSwitch mIgnoreDataCallsButton;
    private ToggleSwitch mIgnoreCRCChecksumsButton;
    private ToggleSwitch mUseCompressedTalkgroupsToggle;
    private Spinner<Integer> mTrafficChannelPoolSizeSpinner;
    private TableView<TimeslotFrequency> mTimeslotFrequencyTable;
    private IntegerTextField mLogicalChannelNumberField;
    private FrequencyField mDownlinkFrequencyField;
    private Button mAddTimeslotFrequencyButton;
    private Button mDeleteTimeslotFrequencyButton;
    private Button mCopyTimeslotMapButton;
    private Button mPasteTimeslotMapButton;
    private Spinner<Integer> mChannelRotationDelaySpinner;

    /**
     * Constructs an instance
     * @param configurationManager for configuration data
     * @param tunerManager for tuners
     * @param userPreferences for preferences
     */
    public DMRConfigurationEditor(ConfigurationManager configurationManager, TunerManager tunerManager,
                                  UserPreferences userPreferences, IFilterProcessor filterProcessor)
    {
        super(configurationManager, tunerManager, userPreferences, filterProcessor);
        getTitledPanesBox().getChildren().add(getSourcePane());
        getTitledPanesBox().getChildren().add(getDecoderPane());
        getTitledPanesBox().getChildren().add(getEventLogPane());
        getTitledPanesBox().getChildren().add(getRecordPane());
    }

    @Override
    public DecoderType getDecoderType()
    {
        return DecoderType.DMR;
    }

    private TitledPane getSourcePane()
    {
        if(mSourcePane == null)
        {
            mSourcePane = new TitledPane("Source", getSourceConfigurationEditor());
            mSourcePane.setExpanded(true);
        }

        return mSourcePane;
    }

    private TitledPane getDecoderPane()
    {
        if(mDecoderPane == null)
        {
            mDecoderPane = new TitledPane();
            mDecoderPane.setText("Decoder: DMR");
            mDecoderPane.setExpanded(true);

            GridPane gridPane = new GridPane();
            gridPane.setPadding(new Insets(10,10,10,10));
            gridPane.setHgap(10);
            gridPane.setVgap(10);

            int row = 0;

            Label poolSizeLabel = new Label("Max Traffic Channels");
            GridPane.setHalignment(poolSizeLabel, HPos.RIGHT);
            GridPane.setConstraints(poolSizeLabel, 0, row);
            gridPane.getChildren().add(poolSizeLabel);

            GridPane.setConstraints(getTrafficChannelPoolSizeSpinner(), 1, row);
            gridPane.getChildren().add(getTrafficChannelPoolSizeSpinner());

            GridPane.setConstraints(getIgnoreDataCallsButton(), 2, row);
            gridPane.getChildren().add(getIgnoreDataCallsButton());

            Label ignoreDataLabel = new Label("Ignore Data Calls");
            GridPane.setHalignment(ignoreDataLabel, HPos.LEFT);
            GridPane.setConstraints(ignoreDataLabel, 3, row);
            gridPane.getChildren().add(ignoreDataLabel);

            GridPane.setConstraints(getIgnoreCRCChecksumsButton(), 4, row);
            gridPane.getChildren().add(getIgnoreCRCChecksumsButton());

            Label ignoreCRCLabel = new Label("Ignore CRC Checksums (RAS)");
            GridPane.setHalignment(ignoreCRCLabel, HPos.LEFT);
            GridPane.setConstraints(ignoreCRCLabel, 5, row);
            gridPane.getChildren().add(ignoreCRCLabel);

            GridPane.setConstraints(getUseCompressedTalkgroupsToggle(), 6, row);
            gridPane.getChildren().add(getUseCompressedTalkgroupsToggle());

            Label useCompressedTalkgroupsLabel = new Label("Use Compressed Talkgroups");
            GridPane.setHalignment(useCompressedTalkgroupsLabel, HPos.LEFT);
            GridPane.setConstraints(useCompressedTalkgroupsLabel, 7, row);
            gridPane.getChildren().add(useCompressedTalkgroupsLabel);

            Label timeslotTableLabel = new Label("Logical Channel Number (LCN) to Frequency Map. Required for: Connect Plus and Tier-III systems that don't use absolute frequencies.  LSN = Logical Slot Number");
            GridPane.setHalignment(timeslotTableLabel, HPos.LEFT);
            GridPane.setConstraints(timeslotTableLabel, 0, ++row, 6, 1);
            gridPane.getChildren().add(timeslotTableLabel);

            GridPane.setConstraints(getTimeslotTable(), 0, ++row, 6, 3);
            gridPane.getChildren().add(getTimeslotTable());

            VBox buttonsBox = new VBox();
            buttonsBox.setAlignment(Pos.CENTER);
            buttonsBox.setSpacing(10);
            buttonsBox.getChildren().addAll(getAddTimeslotFrequencyButton(), getDeleteTimeslotFrequencyButton(),
                getCopyTimeslotMapButton(), getPasteTimeslotMapButton());

            GridPane.setConstraints(buttonsBox, 6, row, 1, 3);
            gridPane.getChildren().addAll(buttonsBox);

            row += 3;

            HBox editorBox = new HBox();
            editorBox.setAlignment(Pos.CENTER_LEFT);
            editorBox.setSpacing(5);

            Label lcnLabel = new Label("LCN");
            editorBox.getChildren().addAll(lcnLabel, getLogicalChannelNumberField());

            Label downlinkLabel = new Label("Frequency (MHz)");
            downlinkLabel.setPadding(new Insets(0,0,0,5));
            editorBox.getChildren().addAll(downlinkLabel,getDownlinkFrequencyField());

            GridPane.setConstraints(editorBox, 0, row, 4, 1);
            gridPane.getChildren().add(editorBox);

            mDecoderPane.setContent(gridPane);
        }

        return mDecoderPane;
    }

    private TitledPane getEventLogPane()
    {
        if(mEventLogPane == null)
        {
            mEventLogPane = new TitledPane("Logging", getEventLogConfigurationEditor());
            mEventLogPane.setExpanded(false);
        }

        return mEventLogPane;
    }

    private TitledPane getRecordPane()
    {
        if(mRecordPane == null)
        {
            mRecordPane = new TitledPane();
            mRecordPane.setText("Recording");
            mRecordPane.setExpanded(false);

            Label notice = new Label("Note: use aliases to control call audio recording");
            notice.setPadding(new Insets(10, 10, 0, 10));

            VBox vBox = new VBox();
            vBox.getChildren().addAll(getRecordConfigurationEditor(), notice);

            mRecordPane.setContent(vBox);
        }

        return mRecordPane;
    }

    private SourceConfigurationEditor<SourceConfiguration> getSourceConfigurationEditor()
    {
        if(mSourceConfigurationEditor == null)
        {
            mSourceConfigurationEditor = new FrequencyEditor(mTunerManager,
                DecodeConfigDMR.CHANNEL_ROTATION_DELAY_MINIMUM_MS,
                DecodeConfigDMR.CHANNEL_ROTATION_DELAY_MAXIMUM_MS,
                DecodeConfigDMR.CHANNEL_ROTATION_DELAY_DEFAULT_MS);

            //Add a listener so that we can push change notifications up to this editor
            mSourceConfigurationEditor.modifiedProperty()
                .addListener((observable, oldValue, newValue) -> modifiedProperty().set(true));
        }

        return mSourceConfigurationEditor;
    }

    private EventLogConfigurationEditor getEventLogConfigurationEditor()
    {
        if(mEventLogConfigurationEditor == null)
        {
            List<EventLogType> types = new ArrayList<>();
            types.add(EventLogType.CALL_EVENT);
            types.add(EventLogType.DECODED_MESSAGE);
            types.add(EventLogType.TRAFFIC_CALL_EVENT);
            types.add(EventLogType.TRAFFIC_DECODED_MESSAGE);

            mEventLogConfigurationEditor = new EventLogConfigurationEditor(types);
            mEventLogConfigurationEditor.setPadding(new Insets(5,5,5,5));
            mEventLogConfigurationEditor.modifiedProperty().addListener((observable, oldValue, newValue) -> modifiedProperty().set(true));
        }

        return mEventLogConfigurationEditor;
    }

    private TableView<TimeslotFrequency> getTimeslotTable()
    {
        if(mTimeslotFrequencyTable == null)
        {
            mTimeslotFrequencyTable = new TableView<>(FXCollections.observableArrayList(TimeslotFrequency.extractor()));
            mTimeslotFrequencyTable.setPrefHeight(100.0);

            TableColumn<TimeslotFrequency,Number> numberColumn = new TableColumn<>("LCN");
            numberColumn.setPrefWidth(75);
            numberColumn.setCellValueFactory(cellData -> cellData.getValue().getNumberProperty());
            mTimeslotFrequencyTable.getColumns().add(numberColumn);
            mTimeslotFrequencyTable.getSortOrder().add(numberColumn);

            TableColumn<TimeslotFrequency,Number> downlinkColumn = new TableColumn<>("Frequency (MHz)");
            downlinkColumn.setCellValueFactory(cellData -> cellData.getValue().getDownlinkMHz());
            downlinkColumn.setPrefWidth(150);
            mTimeslotFrequencyTable.getColumns().add(downlinkColumn);

            TableColumn<TimeslotFrequency,String> lsnColumn = new TableColumn<>("IDs (TS1/TS2)");
            lsnColumn.setPrefWidth(225);
            lsnColumn.setCellValueFactory(new PropertyValueFactory<>("description"));
            mTimeslotFrequencyTable.getColumns().add(lsnColumn);


            mTimeslotFrequencyTable.getSelectionModel().selectedItemProperty()
                .addListener((observable, oldValue, newValue) -> setTimeslot(newValue));
            mTimeslotFrequencyTable.setOnKeyPressed(event -> {
                if(event.isShortcutDown() && event.getCode() == KeyCode.C)
                {
                    copyTimeslotMap();
                    event.consume();
                }
                else if(event.isShortcutDown() && event.getCode() == KeyCode.V)
                {
                    pasteTimeslotMap();
                    event.consume();
                }
            });
        }

        return mTimeslotFrequencyTable;
    }

    /**
     * Sets the specified timeslot frequency into the editor
     */
    private void setTimeslot(TimeslotFrequency timeslot)
    {
        //Preserve the current modified flag state since setting values in the editor will change it.
        boolean modified = modifiedProperty().get();

        getLogicalChannelNumberField().setDisable(timeslot == null);
        getDownlinkFrequencyField().setDisable(timeslot == null);
        getDeleteTimeslotFrequencyButton().setDisable(timeslot == null);

        if(timeslot != null)
        {
            getLogicalChannelNumberField().set(timeslot.getNumber());
            getDownlinkFrequencyField().set(timeslot.getDownlinkFrequency());
        }
        else
        {
            getLogicalChannelNumberField().set(0);
            getDownlinkFrequencyField().set(0);
        }

        modifiedProperty().set(modified);
    }

    private Button getAddTimeslotFrequencyButton()
    {
        if(mAddTimeslotFrequencyButton == null)
        {
            mAddTimeslotFrequencyButton = new Button("Add");
            mAddTimeslotFrequencyButton.setMaxWidth(Double.MAX_VALUE);
            mAddTimeslotFrequencyButton.setOnAction(event -> addTimeslot());
        }

        return mAddTimeslotFrequencyButton;
    }

    /**
     * Adds a new timeslot frequency value and makes a best guess of the next sequential LSN number
     */
    private void addTimeslot()
    {
        int lsn = 1;

        while(hasLSN(lsn) && lsn <= 64) //64 is an arbitrary value to keep it from going too high
        {
            lsn++;
        }

        TimeslotFrequency timeslotFrequency = new TimeslotFrequency();
        timeslotFrequency.setNumber(lsn);
        getTimeslotTable().getItems().add(timeslotFrequency);
        getTimeslotTable().scrollTo(timeslotFrequency);
        getTimeslotTable().getSelectionModel().select(timeslotFrequency);
        modifiedProperty().set(true);
    }

    /**
     * Searches the current timeslot frequency list to determine if the specified lsn is already listed
     */
    private boolean hasLSN(int lsn)
    {
        for(TimeslotFrequency timeslotFrequency: getTimeslotTable().getItems())
        {
            if(timeslotFrequency.getNumber() == lsn)
            {
                return true;
            }
        }

        return false;
    }

    private Button getDeleteTimeslotFrequencyButton()
    {
        if(mDeleteTimeslotFrequencyButton == null)
        {
            mDeleteTimeslotFrequencyButton = new Button("Delete");
            mDeleteTimeslotFrequencyButton.setDisable(true);
            mDeleteTimeslotFrequencyButton.setMaxWidth(Double.MAX_VALUE);
            mDeleteTimeslotFrequencyButton.setOnAction(new EventHandler<ActionEvent>()
            {
                @Override
                public void handle(ActionEvent event)
                {
                    TimeslotFrequency selected = getTimeslotTable().getSelectionModel().getSelectedItem();

                    if(selected != null)
                    {
                        getTimeslotTable().getItems().remove(selected);
                        modifiedProperty().set(true);
                    }
                }
            });
        }

        return mDeleteTimeslotFrequencyButton;
    }

    private Button getCopyTimeslotMapButton()
    {
        if(mCopyTimeslotMapButton == null)
        {
            mCopyTimeslotMapButton = new Button("Copy Map");
            mCopyTimeslotMapButton.setMaxWidth(Double.MAX_VALUE);
            mCopyTimeslotMapButton.setTooltip(new Tooltip("Copy all LCN mappings as tab-separated values"));
            mCopyTimeslotMapButton.setOnAction(event -> copyTimeslotMap());
        }

        return mCopyTimeslotMapButton;
    }

    private Button getPasteTimeslotMapButton()
    {
        if(mPasteTimeslotMapButton == null)
        {
            mPasteTimeslotMapButton = new Button("Paste Map");
            mPasteTimeslotMapButton.setMaxWidth(Double.MAX_VALUE);
            mPasteTimeslotMapButton.setTooltip(new Tooltip(
                "Replace the map with rows containing LCN, downlink frequency, and optional uplink frequency"));
            mPasteTimeslotMapButton.setOnAction(event -> pasteTimeslotMap());
        }

        return mPasteTimeslotMapButton;
    }

    private void copyTimeslotMap()
    {
        ClipboardContent content = new ClipboardContent();
        content.putString(formatTimeslotMap(getTimeslotTable().getItems()));
        Clipboard.getSystemClipboard().setContent(content);
    }

    private void pasteTimeslotMap()
    {
        String text = Clipboard.getSystemClipboard().getString();

        try
        {
            List<TimeslotFrequency> mappings = parseTimeslotMap(text);
            getTimeslotTable().getItems().setAll(mappings);
            getTimeslotTable().sort();
            modifiedProperty().set(true);
        }
        catch(IllegalArgumentException e)
        {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Unable to Paste DMR Channel Map");
            alert.setHeaderText("The clipboard does not contain a valid DMR channel map.");
            alert.setContentText(e.getMessage());
            alert.showAndWait();
        }
    }

    static String formatTimeslotMap(List<TimeslotFrequency> mappings)
    {
        StringBuilder output = new StringBuilder();

        for(TimeslotFrequency mapping: mappings.stream()
            .sorted(Comparator.comparingInt(TimeslotFrequency::getNumber)).toList())
        {
            if(!output.isEmpty())
            {
                output.append(System.lineSeparator());
            }

            output.append(mapping.getNumber()).append('\t').append(mapping.getDownlinkFrequency()).append('\t')
                .append(mapping.getUplinkFrequency());
        }

        return output.toString();
    }

    static List<TimeslotFrequency> parseTimeslotMap(String text)
    {
        if(text == null || text.isBlank())
        {
            throw new IllegalArgumentException("Copy one or more mapping rows before pasting.");
        }

        List<TimeslotFrequency> mappings = new ArrayList<>();
        Set<Integer> logicalChannelNumbers = new HashSet<>();
        String[] lines = text.split("\\R");

        for(int lineIndex = 0; lineIndex < lines.length; lineIndex++)
        {
            String line = lines[lineIndex].trim();

            if(line.isEmpty())
            {
                continue;
            }

            String lower = line.toLowerCase(Locale.ROOT);
            if((lower.contains("lcn") || lower.contains("lsn")) && mappings.isEmpty())
            {
                continue;
            }

            String[] columns = line.contains("\t") || line.contains(",") || line.contains(";") ?
                line.split("\\s*[\\t,;]\\s*") : line.split("\\s+");

            if(columns.length < 2 || columns.length > 3)
            {
                throw new IllegalArgumentException("Line " + (lineIndex + 1) +
                    " must contain LCN, downlink frequency, and optional uplink frequency.");
            }

            try
            {
                int lcn = Integer.parseInt(columns[0]);
                if(lcn < 1)
                {
                    throw new IllegalArgumentException("Line " + (lineIndex + 1) + " has an LCN below 1.");
                }

                if(!logicalChannelNumbers.add(lcn))
                {
                    throw new IllegalArgumentException("Line " + (lineIndex + 1) + " repeats LCN " + lcn + ".");
                }

                TimeslotFrequency mapping = new TimeslotFrequency();
                mapping.setNumber(lcn);
                mapping.setDownlinkFrequency(parseFrequency(columns[1], lineIndex + 1));
                mapping.setUplinkFrequency(columns.length == 3 ? parseFrequency(columns[2], lineIndex + 1) : 0);
                mappings.add(mapping);
            }
            catch(NumberFormatException e)
            {
                throw new IllegalArgumentException("Line " + (lineIndex + 1) + " contains a non-numeric value.", e);
            }
        }

        if(mappings.isEmpty())
        {
            throw new IllegalArgumentException("No mapping rows were found.");
        }

        mappings.sort(Comparator.comparingInt(TimeslotFrequency::getNumber));
        return mappings;
    }

    private static long parseFrequency(String value, int lineNumber)
    {
        BigDecimal frequency = new BigDecimal(value.trim());

        if(frequency.signum() < 0)
        {
            throw new IllegalArgumentException("Line " + lineNumber + " contains a negative frequency.");
        }

        if(frequency.signum() > 0 && frequency.compareTo(BigDecimal.valueOf(1_000_000)) < 0)
        {
            frequency = frequency.multiply(BigDecimal.valueOf(1_000_000));
        }

        try
        {
            return frequency.setScale(0, RoundingMode.HALF_UP).longValueExact();
        }
        catch(ArithmeticException e)
        {
            throw new IllegalArgumentException("Line " + lineNumber + " contains a frequency that is too large.", e);
        }
    }

    private IntegerTextField getLogicalChannelNumberField()
    {
        if(mLogicalChannelNumberField == null)
        {
            mLogicalChannelNumberField = new IntegerTextField();
            mLogicalChannelNumberField.setDisable(true);
            mLogicalChannelNumberField.setPrefWidth(65);
            mLogicalChannelNumberField.textProperty().addListener((observable, oldValue, newValue) -> {
                TimeslotFrequency selected = getTimeslotTable().getSelectionModel().getSelectedItem();

                if(selected != null)
                {
                    Integer value = mLogicalChannelNumberField.get();

                    if(value != null)
                    {
                        selected.setNumber(value);
                    }
                }

                modifiedProperty().set(true);
            });
        }

        return mLogicalChannelNumberField;
    }

    private FrequencyField getDownlinkFrequencyField()
    {
        if(mDownlinkFrequencyField == null)
        {
            mDownlinkFrequencyField = new FrequencyField();
            mDownlinkFrequencyField.setDisable(true);
            mDownlinkFrequencyField.textProperty().addListener(new ChangeListener<String>()
            {
                @Override
                public void changed(ObservableValue<? extends String> observable, String oldValue, String newValue)
                {
                    TimeslotFrequency selected = getTimeslotTable().getSelectionModel().getSelectedItem();

                    if(selected != null)
                    {
                        selected.setDownlinkFrequency(mDownlinkFrequencyField.get());
                    }

                    modifiedProperty().set(true);
                }
            });
        }

        return mDownlinkFrequencyField;
    }

    private ToggleSwitch getIgnoreDataCallsButton()
    {
        if(mIgnoreDataCallsButton == null)
        {
            mIgnoreDataCallsButton = new ToggleSwitch();
            mIgnoreDataCallsButton.setDisable(true);
            mIgnoreDataCallsButton.selectedProperty()
                .addListener((observable, oldValue, newValue) -> modifiedProperty().set(true));
        }

        return mIgnoreDataCallsButton;
    }

    private ToggleSwitch getIgnoreCRCChecksumsButton()
    {
        if(mIgnoreCRCChecksumsButton == null)
        {
            mIgnoreCRCChecksumsButton = new ToggleSwitch();
            mIgnoreCRCChecksumsButton.setDisable(true);
            mIgnoreCRCChecksumsButton.selectedProperty()
                .addListener((observable, oldValue, newValue) -> modifiedProperty().set(true));
        }

        return mIgnoreCRCChecksumsButton;
    }

    /**
     * Use compressed talkgroups toggle switch.  Let's the user select to turn on compressed talkgroups for Hytera
     * Tier-III systems.
     * @return toggle.
     */
    private ToggleSwitch getUseCompressedTalkgroupsToggle()
    {
        if(mUseCompressedTalkgroupsToggle == null)
        {
            mUseCompressedTalkgroupsToggle = new ToggleSwitch();
            mUseCompressedTalkgroupsToggle.setTooltip(new Tooltip("Use Compressed Talkgroups.  This is only for Hytera Tier-III Trunked Systems"));
            mUseCompressedTalkgroupsToggle.setDisable(true);
            mUseCompressedTalkgroupsToggle.selectedProperty().addListener((observable, oldValue, newValue) -> modifiedProperty().set(true));
        }

        return mUseCompressedTalkgroupsToggle;
    }

    private Spinner<Integer> getTrafficChannelPoolSizeSpinner()
    {
        if(mTrafficChannelPoolSizeSpinner == null)
        {
            mTrafficChannelPoolSizeSpinner = new Spinner<>();
            mTrafficChannelPoolSizeSpinner.setDisable(true);
            mTrafficChannelPoolSizeSpinner.setTooltip(
                new Tooltip("Maximum number of traffic channels that can be created by the decoder"));
            mTrafficChannelPoolSizeSpinner.getStyleClass().add(Spinner.STYLE_CLASS_SPLIT_ARROWS_HORIZONTAL);
            SpinnerValueFactory<Integer> svf = new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 50);
            mTrafficChannelPoolSizeSpinner.setValueFactory(svf);
            mTrafficChannelPoolSizeSpinner.getValueFactory().valueProperty()
                .addListener((observable, oldValue, newValue) -> modifiedProperty().set(true));
        }

        return mTrafficChannelPoolSizeSpinner;
    }

    /**
     * Channel rotation monitor delay value.  This dictates how long the decoder will remain on each frequency before
     * rotating to the next frequency in the list
     * @return spinner
     */
    private Spinner<Integer> getChannelRotationDelaySpinner()
    {
        if(mChannelRotationDelaySpinner == null)
        {
            mChannelRotationDelaySpinner = new Spinner<>();
            mChannelRotationDelaySpinner.setDisable(true);
            mChannelRotationDelaySpinner.setTooltip(
                new Tooltip("Delay on each frequency before rotating to next when seeking to next active channel frequency"));
            mChannelRotationDelaySpinner.getStyleClass().add(Spinner.STYLE_CLASS_SPLIT_ARROWS_HORIZONTAL);
            SpinnerValueFactory<Integer> svf = new SpinnerValueFactory.IntegerSpinnerValueFactory(200, 2000, 200, 50);
            mChannelRotationDelaySpinner.setValueFactory(svf);
            mChannelRotationDelaySpinner.getValueFactory().valueProperty()
                .addListener((observable, oldValue, newValue) -> modifiedProperty().set(true));
        }

        return mChannelRotationDelaySpinner;
    }

    private RecordConfigurationEditor getRecordConfigurationEditor()
    {
        if(mRecordConfigurationEditor == null)
        {
            List<RecorderType> types = new ArrayList<>();
            types.add(RecorderType.BASEBAND);
            types.add(RecorderType.DEMODULATED_BIT_STREAM);
            types.add(RecorderType.MBE_CALL_SEQUENCE);
            types.add(RecorderType.TRAFFIC_BASEBAND);
            types.add(RecorderType.TRAFFIC_DEMODULATED_BIT_STREAM);
            types.add(RecorderType.TRAFFIC_MBE_CALL_SEQUENCE);
            mRecordConfigurationEditor = new RecordConfigurationEditor(types);
            mRecordConfigurationEditor.setDisable(true);
            mRecordConfigurationEditor.modifiedProperty()
                .addListener((observable, oldValue, newValue) -> modifiedProperty().set(true));
        }

        return mRecordConfigurationEditor;
    }

    @Override
    protected void setDecoderConfiguration(DecodeConfiguration config)
    {
        getIgnoreCRCChecksumsButton().setDisable(config == null);
        getIgnoreDataCallsButton().setDisable(config == null);
        getUseCompressedTalkgroupsToggle().setDisable(config == null);
        getTrafficChannelPoolSizeSpinner().setDisable(config == null);
        getTimeslotTable().getItems().clear();
        getTimeslotTable().setDisable(config == null);
        getAddTimeslotFrequencyButton().setDisable(config == null);
        getCopyTimeslotMapButton().setDisable(config == null);
        getPasteTimeslotMapButton().setDisable(config == null);
        getDeleteTimeslotFrequencyButton().setDisable(true);
        getLogicalChannelNumberField().set(0);
        getLogicalChannelNumberField().setDisable(true);
        getDownlinkFrequencyField().set(0);
        getDownlinkFrequencyField().setDisable(true);
        getChannelRotationDelaySpinner().setDisable(config == null);

        if(config instanceof DecodeConfigDMR)
        {
            DecodeConfigDMR decodeConfig = (DecodeConfigDMR)config;

            getIgnoreDataCallsButton().setSelected(decodeConfig.getIgnoreDataCalls());
            getIgnoreCRCChecksumsButton().setSelected(decodeConfig.getIgnoreCRCChecksums());
            getUseCompressedTalkgroupsToggle().setSelected(decodeConfig.isUseCompressedTalkgroups());
            getTrafficChannelPoolSizeSpinner().getValueFactory().setValue(decodeConfig.getTrafficChannelPoolSize());

            for(TimeslotFrequency timeslotFrequency: decodeConfig.getTimeslotMap())
            {
                getTimeslotTable().getItems().add(timeslotFrequency.copy());
            }
        }
        else
        {
            getIgnoreCRCChecksumsButton().setSelected(false);
            getIgnoreDataCallsButton().setSelected(false);
            getUseCompressedTalkgroupsToggle().setSelected(false);
            getTrafficChannelPoolSizeSpinner().getValueFactory().setValue(0);
            getChannelRotationDelaySpinner().getValueFactory().setValue(200);
        }
    }

    @Override
    protected void saveDecoderConfiguration()
    {
        DecodeConfigDMR config;

        if(getItem().getDecodeConfiguration() instanceof DecodeConfigDMR)
        {
            config = (DecodeConfigDMR)getItem().getDecodeConfiguration();
        }
        else
        {
            config = new DecodeConfigDMR();
        }

        config.setIgnoreCRCChecksums(getIgnoreCRCChecksumsButton().isSelected());
        config.setIgnoreDataCalls(getIgnoreDataCallsButton().isSelected());
        config.setTrafficChannelPoolSize(getTrafficChannelPoolSizeSpinner().getValue());
        config.setUseCompressedTalkgroups(getUseCompressedTalkgroupsToggle().isSelected());
        config.setTimeslotMap(new ArrayList<>(getTimeslotTable().getItems()));
        getItem().setDecodeConfiguration(config);
    }

    @Override
    protected void setEventLogConfiguration(EventLogConfiguration config)
    {
        getEventLogConfigurationEditor().setItem(config);
    }

    @Override
    protected void saveEventLogConfiguration()
    {
        getEventLogConfigurationEditor().save();

        if(getEventLogConfigurationEditor().getItem().getLoggers().isEmpty())
        {
            getItem().setEventLogConfiguration(null);
        }
        else
        {
            getItem().setEventLogConfiguration(getEventLogConfigurationEditor().getItem());
        }
    }

    @Override
    protected void setAuxDecoderConfiguration(AuxDecodeConfiguration config)
    {
        //no-op
    }

    @Override
    protected void saveAuxDecoderConfiguration()
    {
        //no-op
    }

    @Override
    protected void setRecordConfiguration(RecordConfiguration config)
    {
        getRecordConfigurationEditor().setDisable(config == null);
        getRecordConfigurationEditor().setItem(config);
    }

    @Override
    protected void saveRecordConfiguration()
    {
        getRecordConfigurationEditor().save();
        RecordConfiguration config = getRecordConfigurationEditor().getItem();
        getItem().setRecordConfiguration(config);
    }

    @Override
    protected void setSourceConfiguration(SourceConfiguration config)
    {
        getSourceConfigurationEditor().setSourceConfiguration(config);
    }

    @Override
    protected void saveSourceConfiguration()
    {
        getSourceConfigurationEditor().save();
        SourceConfiguration sourceConfiguration = getSourceConfigurationEditor().getSourceConfiguration();
        getItem().setSourceConfiguration(sourceConfiguration);
    }

    /**
     * Channel tuner channel source frequencies value factory
     */
    public class FrequencyCellValueFactory implements Callback<TableColumn.CellDataFeatures<TimeslotFrequency, String>,
            ObservableValue<String>>
    {
        private SimpleStringProperty mFrequency = new SimpleStringProperty();
        private boolean mIsDownlink;

        public FrequencyCellValueFactory(boolean isDownlink)
        {
            mIsDownlink = isDownlink;
        }

        @Override
        public ObservableValue<String> call(TableColumn.CellDataFeatures<TimeslotFrequency, String> param)
        {
            if(param.getValue() != null)
            {
                long frequency = (mIsDownlink ? param.getValue().getDownlinkFrequency() : param.getValue().getUplinkFrequency());
                mFrequency.set(String.valueOf(frequency / 1E6));
            }
            else
            {
                mFrequency.set(null);
            }

            return mFrequency;
        }
    }

    public class DownlinkPropertyValueFactory extends PropertyValueFactory<TimeslotFrequency,String>
    {
        private StringProperty mStringProperty = new SimpleStringProperty();

        public DownlinkPropertyValueFactory()
        {
            super("downlinkFrequency");
        }

        @Override
        public ObservableValue<String> call(TableColumn.CellDataFeatures<TimeslotFrequency,String> param)
        {
            if(param.getValue() != null)
            {
                mStringProperty.set(String.valueOf(param.getValue().getDownlinkFrequency() / 1E6));
            }
            else
            {
                mStringProperty.setValue(null);
            }

            return mStringProperty;
        }
    }

}
