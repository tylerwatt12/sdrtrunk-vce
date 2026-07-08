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

package io.github.dsheirer.gui.configuration.streaming;

import io.github.dsheirer.audio.broadcast.BroadcastServerType;
import io.github.dsheirer.audio.broadcast.radioresolve.RadioResolveConfiguration;
import io.github.dsheirer.gui.control.IntegerTextField;
import io.github.dsheirer.configuration.ConfigurationManager;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javafx.collections.FXCollections;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;

/**
 * RadioResolve calls and metadata configuration editor.
 */
public class RadioResolveEditor extends AbstractBroadcastEditor<RadioResolveConfiguration>
{
    private GridPane mEditorPane;
    private TextField mHostTextField;
    private PasswordField mApiKeyTextField;
    private TextField mNodeNameTextField;
    private ComboBox<String> mNodeTimezoneComboBox;
    private IntegerTextField mMaxAgeTextField;
    private IntegerTextField mConcurrentUploadsTextField;
    private ComboBox<RadioResolveConfiguration.Mode> mModeComboBox;
    private CheckBox mIgnoreCertificateErrorsCheckBox;

    public RadioResolveEditor(ConfigurationManager configurationManager)
    {
        super(configurationManager, RadioResolveConfiguration.class);
    }

    @Override
    public void setItem(RadioResolveConfiguration item)
    {
        super.setItem(item);

        getHostTextField().setDisable(item == null);
        getApiKeyTextField().setDisable(item == null);
        getNodeNameTextField().setDisable(item == null);
        getNodeTimezoneComboBox().setDisable(item == null);
        getMaxAgeTextField().setDisable(item == null);
        getConcurrentUploadsTextField().setDisable(item == null);
        getModeComboBox().setDisable(item == null);
        getIgnoreCertificateErrorsCheckBox().setDisable(item == null);

        if(item != null)
        {
            getHostTextField().setText(item.getHost());
            getApiKeyTextField().setText(item.getApiKey());
            getNodeNameTextField().setText(item.getNodeName());
            getNodeTimezoneComboBox().getSelectionModel().select(getValidTimezone(item.getNodeTimezone()));
            getMaxAgeTextField().set((int)(item.getMaximumRecordingAge() / 1000));
            getConcurrentUploadsTextField().set(item.getConcurrentUploads());
            getModeComboBox().getSelectionModel().select(item.getMode());
            getIgnoreCertificateErrorsCheckBox().setSelected(item.getIgnoreCertificateErrors());
        }
        else
        {
            getHostTextField().setText(null);
            getApiKeyTextField().setText(null);
            getNodeNameTextField().setText(null);
            getNodeTimezoneComboBox().getSelectionModel().clearSelection();
            getMaxAgeTextField().set(0);
            getConcurrentUploadsTextField().set(RadioResolveConfiguration.DEFAULT_CONCURRENT_UPLOADS);
            getModeComboBox().getSelectionModel().select(RadioResolveConfiguration.Mode.CALLS_AND_METADATA);
            getIgnoreCertificateErrorsCheckBox().setSelected(false);
        }

        modifiedProperty().set(false);
    }

    @Override
    public void dispose()
    {
        //No resources.
    }

    @Override
    public void save()
    {
        if(getItem() != null)
        {
            getItem().setHost(getHostTextField().getText());
            getItem().setApiKey(getApiKeyTextField().getText());
            getItem().setNodeName(getNodeNameTextField().getText());
            getItem().setNodeTimezone(getValidTimezone(getNodeTimezoneComboBox().getSelectionModel().getSelectedItem()));
            Integer maxAge = getMaxAgeTextField().get();
            getItem().setMaximumRecordingAge((maxAge != null ? maxAge : 0) * 1000L);
            Integer concurrentUploads = getConcurrentUploadsTextField().get();
            getItem().setConcurrentUploads(concurrentUploads != null && concurrentUploads > 0 ?
                concurrentUploads : RadioResolveConfiguration.DEFAULT_CONCURRENT_UPLOADS);
            getItem().setMode(getModeComboBox().getSelectionModel().getSelectedItem());
            getItem().setIgnoreCertificateErrors(getIgnoreCertificateErrorsCheckBox().isSelected());
        }

        super.save();
    }

    @Override
    public BroadcastServerType getBroadcastServerType()
    {
        return BroadcastServerType.RADIORESOLVE;
    }

    @Override
    protected GridPane getEditorPane()
    {
        if(mEditorPane == null)
        {
            mEditorPane = new GridPane();
            mEditorPane.setPadding(new Insets(10, 5, 10, 10));
            mEditorPane.setVgap(10);
            mEditorPane.setHgap(5);

            int row = 0;
            addLabel("Format", row);
            GridPane.setConstraints(getFormatField(), 1, row);
            mEditorPane.getChildren().add(getFormatField());

            Label enabledLabel = new Label("Enabled");
            GridPane.setHalignment(enabledLabel, HPos.RIGHT);
            GridPane.setConstraints(enabledLabel, 2, row);
            mEditorPane.getChildren().add(enabledLabel);
            GridPane.setConstraints(getEnabledSwitch(), 3, row);
            mEditorPane.getChildren().add(getEnabledSwitch());

            addLabel("Name", ++row);
            GridPane.setConstraints(getNameTextField(), 1, row);
            mEditorPane.getChildren().add(getNameTextField());

            addLabel("Mode", ++row);
            GridPane.setConstraints(getModeComboBox(), 1, row);
            mEditorPane.getChildren().add(getModeComboBox());

            addLabel("RadioResolve URL", ++row);
            GridPane.setConstraints(getHostTextField(), 1, row);
            mEditorPane.getChildren().add(getHostTextField());

            addLabel("API Key", ++row);
            GridPane.setConstraints(getApiKeyTextField(), 1, row);
            mEditorPane.getChildren().add(getApiKeyTextField());

            addLabel("Node Name", ++row);
            GridPane.setConstraints(getNodeNameTextField(), 1, row);
            mEditorPane.getChildren().add(getNodeNameTextField());

            addLabel("Node Timezone", ++row);
            GridPane.setConstraints(getNodeTimezoneComboBox(), 1, row);
            mEditorPane.getChildren().add(getNodeTimezoneComboBox());

            addLabel("Max Recording Age (seconds)", ++row);
            GridPane.setConstraints(getMaxAgeTextField(), 1, row);
            mEditorPane.getChildren().add(getMaxAgeTextField());

            addLabel("Concurrent Uploads", ++row);
            GridPane.setConstraints(getConcurrentUploadsTextField(), 1, row);
            mEditorPane.getChildren().add(getConcurrentUploadsTextField());

            addLabel("Ignore Certificate Errors", ++row);
            GridPane.setConstraints(getIgnoreCertificateErrorsCheckBox(), 1, row);
            mEditorPane.getChildren().add(getIgnoreCertificateErrorsCheckBox());
        }

        return mEditorPane;
    }

    private void addLabel(String text, int row)
    {
        Label label = new Label(text);
        GridPane.setHalignment(label, HPos.RIGHT);
        GridPane.setConstraints(label, 0, row);
        mEditorPane.getChildren().add(label);
    }

    private TextField getHostTextField()
    {
        if(mHostTextField == null)
        {
            mHostTextField = new TextField();
            mHostTextField.setDisable(true);
            mHostTextField.textProperty().addListener(mEditorModificationListener);
        }

        return mHostTextField;
    }

    private PasswordField getApiKeyTextField()
    {
        if(mApiKeyTextField == null)
        {
            mApiKeyTextField = new PasswordField();
            mApiKeyTextField.setDisable(true);
            mApiKeyTextField.textProperty().addListener(mEditorModificationListener);
        }

        return mApiKeyTextField;
    }

    private TextField getNodeNameTextField()
    {
        if(mNodeNameTextField == null)
        {
            mNodeNameTextField = new TextField();
            mNodeNameTextField.setDisable(true);
            mNodeNameTextField.textProperty().addListener(mEditorModificationListener);
        }

        return mNodeNameTextField;
    }

    private ComboBox<String> getNodeTimezoneComboBox()
    {
        if(mNodeTimezoneComboBox == null)
        {
            mNodeTimezoneComboBox = new ComboBox<>(FXCollections.observableArrayList(getTimezoneIds()));
            mNodeTimezoneComboBox.setDisable(true);
            mNodeTimezoneComboBox.setMaxWidth(Double.MAX_VALUE);
            mNodeTimezoneComboBox.getSelectionModel().select(getValidTimezone(null));
            mNodeTimezoneComboBox.valueProperty().addListener(mEditorModificationListener);
        }

        return mNodeTimezoneComboBox;
    }

    private List<String> getTimezoneIds()
    {
        List<String> timezoneIds = new ArrayList<>(ZoneId.getAvailableZoneIds());
        Collections.sort(timezoneIds);
        return timezoneIds;
    }

    private String getValidTimezone(String timezone)
    {
        if(timezone != null && ZoneId.getAvailableZoneIds().contains(timezone))
        {
            return timezone;
        }

        String defaultTimezone = RadioResolveConfiguration.getDefaultNodeTimezone();

        if(ZoneId.getAvailableZoneIds().contains(defaultTimezone))
        {
            return defaultTimezone;
        }

        return "UTC";
    }

    private IntegerTextField getMaxAgeTextField()
    {
        if(mMaxAgeTextField == null)
        {
            mMaxAgeTextField = new IntegerTextField();
            mMaxAgeTextField.setDisable(true);
            mMaxAgeTextField.textProperty().addListener(mEditorModificationListener);
        }

        return mMaxAgeTextField;
    }

    private IntegerTextField getConcurrentUploadsTextField()
    {
        if(mConcurrentUploadsTextField == null)
        {
            mConcurrentUploadsTextField = new IntegerTextField();
            mConcurrentUploadsTextField.setDisable(true);
            mConcurrentUploadsTextField.textProperty().addListener(mEditorModificationListener);
        }

        return mConcurrentUploadsTextField;
    }

    private ComboBox<RadioResolveConfiguration.Mode> getModeComboBox()
    {
        if(mModeComboBox == null)
        {
            mModeComboBox = new ComboBox<>(FXCollections.observableArrayList(RadioResolveConfiguration.Mode.values()));
            mModeComboBox.setDisable(true);
            mModeComboBox.valueProperty().addListener((observable, oldValue, newValue) -> modifiedProperty().set(true));
        }

        return mModeComboBox;
    }

    private CheckBox getIgnoreCertificateErrorsCheckBox()
    {
        if(mIgnoreCertificateErrorsCheckBox == null)
        {
            mIgnoreCertificateErrorsCheckBox = new CheckBox();
            mIgnoreCertificateErrorsCheckBox.setDisable(true);
            mIgnoreCertificateErrorsCheckBox.selectedProperty()
                .addListener((observable, oldValue, newValue) -> modifiedProperty().set(true));
        }

        return mIgnoreCertificateErrorsCheckBox;
    }
}
