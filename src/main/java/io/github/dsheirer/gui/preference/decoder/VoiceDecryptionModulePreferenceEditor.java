/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.gui.preference.decoder;

import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceDecryptionModuleManager;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.decoder.VoiceDecryptionModulePreference;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javafx.application.Platform;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/**
 * Selects and validates the optional voice decryption module jar.
 */
public class VoiceDecryptionModulePreferenceEditor extends VBox
{
    private final VoiceDecryptionModulePreference mPreference;
    private final Label mStatusLabel = new Label();
    private final Label mModuleLabel = new Label();
    private final Label mPathLabel = new Label();

    public VoiceDecryptionModulePreferenceEditor(UserPreferences userPreferences)
    {
        mPreference = userPreferences.getVoiceDecryptionModulePreference();
        mPreference.getModuleManager().addStatusListener((previous, current) -> updateOnFx(this::refresh));
        MyEventBus.getGlobalEventBus().register(this);
        setPadding(new Insets(10));
        setSpacing(10);

        Label title = new Label("Optional Voice Module");
        Label help = new Label("Select a compatible optional module JAR.");
        help.setWrapText(true);
        help.setMaxWidth(650);

        GridPane details = new GridPane();
        details.setHgap(10);
        details.setVgap(10);
        addRow(details, 0, "Status:", mStatusLabel);
        addRow(details, 1, "Module:", mModuleLabel);
        addRow(details, 2, "File:", mPathLabel);

        Button select = new Button("Select Module...");
        select.setOnAction(event -> selectModule(select.getScene().getWindow()));
        Button reset = new Button("Reset");
        reset.setOnAction(event -> mPreference.resetPath());
        HBox buttons = new HBox(10, select, reset);
        getChildren().addAll(title, help, details, buttons);
        refresh();
    }

    private void addRow(GridPane pane, int row, String name, Node value)
    {
        Label label = new Label(name);
        GridPane.setHalignment(label, HPos.RIGHT);
        pane.add(label, 0, row);

        if(value instanceof Label valueLabel)
        {
            valueLabel.setWrapText(true);
        }

        pane.add(value, 1, row);
    }

    private void selectModule(Window owner)
    {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select Optional Voice Module");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Java module (*.jar)", "*.jar"));
        Path current = mPreference.getPath();

        if(current != null && current.getParent() != null && Files.isDirectory(current.getParent()))
        {
            chooser.setInitialDirectory(current.getParent().toFile());
        }

        File selected = chooser.showOpenDialog(owner);

        if(selected != null)
        {
            try
            {
                if(!mPreference.setPath(selected.toPath()))
                {
                    showError(owner, "The selected file could not be used", mPreference.getModuleManager().getStatus());
                }
            }
            catch(IOException e)
            {
                showError(owner, "The selected file could not be installed",
                    "Check that the file is readable and the application data folder is writable, then try again.");
            }
        }
    }

    private void showError(Window owner, String header, String message)
    {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        alert.setTitle("Optional Voice Module");
        alert.setHeaderText(header);
        alert.initOwner(owner);
        alert.showAndWait();
    }

    private void refresh()
    {
        VoiceDecryptionModuleManager manager = mPreference.getModuleManager();
        mStatusLabel.setText(manager.getStatus());
        mModuleLabel.setText(manager.isLoaded() ? manager.getModuleName() + " " + manager.getModuleVersion() : "");
        mPathLabel.setText(mPreference.getPath() != null ? mPreference.getPath().toString() : "(not set)");
    }

    private static void updateOnFx(Runnable update)
    {
        if(Platform.isFxApplicationThread()) update.run();
        else Platform.runLater(update);
    }

    @Subscribe
    public void preferenceUpdated(PreferenceType preferenceType)
    {
        if(preferenceType == PreferenceType.VOICE_DECRYPTION_MODULE)
        {
            updateOnFx(this::refresh);
        }
    }
}
