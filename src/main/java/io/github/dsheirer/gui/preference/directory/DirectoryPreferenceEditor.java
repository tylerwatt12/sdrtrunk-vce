/*
 * *****************************************************************************
 * Copyright (C) 2014-2023 Dennis Sheirer
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

package io.github.dsheirer.gui.preference.directory;

import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import java.io.File;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;


/**
 * Preference settings for channel event view
 */
public class DirectoryPreferenceEditor extends HBox
{

    private DirectoryPreference mDirectoryPreference;
    private GridPane mEditorPane;

    private Label mApplicationLogsLabel;
    private Button mChangeApplicationLogsButton;
    private Button mResetApplicationLogsButton;
    private Label mApplicationLogsPathLabel;

    private Label mEventLogsLabel;
    private Button mChangeEventLogsButton;
    private Button mResetEventLogsButton;
    private Label mEventLogsPathLabel;

    private Label mJmbeLabel;
    private Button mChangeJmbeButton;
    private Button mResetJmbeButton;
    private Label mJmbePathLabel;

    private Label mRecordingLabel;
    private Button mChangeRecordingButton;
    private Button mResetRecordingButton;
    private Label mRecordingPathLabel;

    private Label mStreamingLabel;
    private Button mChangeStreamingButton;
    private Button mResetStreamingButton;
    private Label mStreamingPathLabel;

    private Spinner<Integer> mRecordingSpinner;
    private Spinner<Integer> mEventLogSpinner;

    public DirectoryPreferenceEditor(UserPreferences userPreferences)
    {
        mDirectoryPreference = userPreferences.getDirectoryPreference();

        //Register to receive directory preference update notifications so we can update the path labels
        MyEventBus.getGlobalEventBus().register(this);

        ScrollPane content = new ScrollPane(getEditorPane());
        content.setFitToWidth(true);
        content.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        HBox.setHgrow(content, Priority.ALWAYS);
        getChildren().add(content);
    }

    public void dispose()
    {
        MyEventBus.getGlobalEventBus().unregister(this);
    }

    private GridPane getEditorPane()
    {
        if(mEditorPane == null)
        {
            mEditorPane = new GridPane();
            mEditorPane.setPadding(new Insets(10, 10, 10, 10));
            mEditorPane.setVgap(6);
            mEditorPane.setMaxWidth(Double.MAX_VALUE);
            ColumnConstraints column = new ColumnConstraints();
            column.setHgrow(Priority.ALWAYS);
            column.setFillWidth(true);
            mEditorPane.getColumnConstraints().add(column);

            int row = 0;
            Label foldersHeading = new Label("Local output folders");
            foldersHeading.setStyle("-fx-font-weight: bold; -fx-font-size: 1.08em;");
            mEditorPane.add(foldersHeading, 0, row++);
            row = addDirectoryRow(row, getApplicationLogsLabel(), getApplicationLogsPathLabel(),
                getChangeApplicationLogsButton(), getResetApplicationLogsButton());
            row = addDirectoryRow(row, getEventLogsLabel(), getEventLogsPathLabel(),
                getChangeEventLogsButton(), getResetEventLogsButton());
            row = addDirectoryRow(row, getJmbeLabel(), getJmbePathLabel(),
                getChangeJmbeButton(), getResetJmbeButton());
            row = addDirectoryRow(row, getRecordingLabel(), getRecordingPathLabel(),
                getChangeRecordingButton(), getResetRecordingButton());
            row = addDirectoryRow(row, getStreamingLabel(), getStreamingPathLabel(),
                getChangeStreamingButton(), getResetStreamingButton());

            mEditorPane.add(new Separator(Orientation.HORIZONTAL), 0, row++);
            Label monitorLabel = new Label("Storage warning thresholds (MB)");
            monitorLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 1.08em;");
            mEditorPane.add(monitorLabel, 0, row++);
            mEditorPane.add(new HBox(8, new Label("Event logs"), getEventLogSpinner()), 0, row++);
            mEditorPane.add(new HBox(8, new Label("Recordings"), getRecordingSpinner()), 0, row);
        }

        return mEditorPane;
    }

    private int addDirectoryRow(int row, Label title, Label path, Button change, Button reset)
    {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox controls = new HBox(8, title, spacer, change, reset);
        controls.setMaxWidth(Double.MAX_VALUE);
        path.setWrapText(true);
        path.setMinWidth(0);
        path.setMaxWidth(Double.MAX_VALUE);
        path.setTooltip(new Tooltip(path.getText()));
        mEditorPane.add(controls, 0, row++);
        mEditorPane.add(path, 0, row++);
        return row;
    }

    /**
     * Recording directory maximum size threshold spinner
     * @return spinner
     */
    private Spinner<Integer> getRecordingSpinner()
    {
        if(mRecordingSpinner == null)
        {
            mRecordingSpinner = new Spinner<>(100, Integer.MAX_VALUE, mDirectoryPreference.getDirectoryMaxUsageRecordings(), 100);
            mRecordingSpinner.valueProperty().addListener((observable, oldValue, newValue) -> mDirectoryPreference
                    .setDirectoryMaxUsageRecordings(newValue));
        }

        return mRecordingSpinner;
    }

    /**
     * Event log directory maximum size threshold spinner
     * @return spinner
     */
    private Spinner<Integer> getEventLogSpinner()
    {
        if(mEventLogSpinner == null)
        {
            mEventLogSpinner = new Spinner<>(100, Integer.MAX_VALUE, mDirectoryPreference.getDirectoryMaxUsageEventLogs(), 100);
            mEventLogSpinner.setEditable(true);
            mEventLogSpinner.valueProperty().addListener((observable, oldValue, newValue) -> mDirectoryPreference
                    .setDirectoryMaxUsageEventLogs(newValue));
        }

        return mEventLogSpinner;
    }

    private Label getApplicationLogsLabel()
    {
        if(mApplicationLogsLabel == null)
        {
            mApplicationLogsLabel = new Label("Application Logs");
        }

        return mApplicationLogsLabel;
    }

    private Button getChangeApplicationLogsButton()
    {
        if(mChangeApplicationLogsButton == null)
        {
            mChangeApplicationLogsButton = createChangeButton("Select Application Logs Folder",
                mDirectoryPreference::getDirectoryApplicationLog, mDirectoryPreference::setDirectoryApplicationLogs);
        }

        return mChangeApplicationLogsButton;
    }

    private Button getResetApplicationLogsButton()
    {
        if(mResetApplicationLogsButton == null)
        {
            mResetApplicationLogsButton = createResetButton(mDirectoryPreference::resetDirectoryApplicationLogs);
        }

        return mResetApplicationLogsButton;
    }

    private Label getApplicationLogsPathLabel()
    {
        if(mApplicationLogsPathLabel == null)
        {
            mApplicationLogsPathLabel = new Label(mDirectoryPreference.getDirectoryApplicationLog().toString());
        }

        return mApplicationLogsPathLabel;
    }

    private Label getEventLogsLabel()
    {
        if(mEventLogsLabel == null)
        {
            mEventLogsLabel = new Label("Event Logs");
        }

        return mEventLogsLabel;
    }

    private Button getChangeEventLogsButton()
    {
        if(mChangeEventLogsButton == null)
        {
            mChangeEventLogsButton = createChangeButton("Select Event Logs Folder",
                mDirectoryPreference::getDirectoryEventLog, mDirectoryPreference::setDirectoryEventLogs);
        }

        return mChangeEventLogsButton;
    }

    private Button getResetEventLogsButton()
    {
        if(mResetEventLogsButton == null)
        {
            mResetEventLogsButton = createResetButton(mDirectoryPreference::resetDirectoryEventLogs);
        }

        return mResetEventLogsButton;
    }

    private Label getEventLogsPathLabel()
    {
        if(mEventLogsPathLabel == null)
        {
            mEventLogsPathLabel = new Label(mDirectoryPreference.getDirectoryEventLog().toString());
        }

        return mEventLogsPathLabel;
    }

    private Label getJmbeLabel()
    {
        if(mJmbeLabel == null)
        {
            mJmbeLabel = new Label("JMBE Libraries");
        }

        return mJmbeLabel;
    }

    private Button getChangeJmbeButton()
    {
        if(mChangeJmbeButton == null)
        {
            mChangeJmbeButton = createChangeButton("Select JMBE Folder",
                mDirectoryPreference::getDirectoryJmbe, mDirectoryPreference::setDirectoryJmbe);
        }

        return mChangeJmbeButton;
    }

    private Button getResetJmbeButton()
    {
        if(mResetJmbeButton == null)
        {
            mResetJmbeButton = createResetButton(mDirectoryPreference::resetDirectoryJmbe);
        }

        return mResetJmbeButton;
    }

    private Label getJmbePathLabel()
    {
        if(mJmbePathLabel == null)
        {
            mJmbePathLabel = new Label(mDirectoryPreference.getDirectoryJmbe().toString());
        }

        return mJmbePathLabel;
    }

    private Label getRecordingLabel()
    {
        if(mRecordingLabel == null)
        {
            mRecordingLabel = new Label("Recordings");
        }

        return mRecordingLabel;
    }

    private Button getChangeRecordingButton()
    {
        if(mChangeRecordingButton == null)
        {
            mChangeRecordingButton = createChangeButton("Select Recording Folder",
                mDirectoryPreference::getDirectoryRecording, mDirectoryPreference::setDirectoryRecording);
        }

        return mChangeRecordingButton;
    }

    private Button getResetRecordingButton()
    {
        if(mResetRecordingButton == null)
        {
            mResetRecordingButton = createResetButton(mDirectoryPreference::resetDirectoryRecording);
        }

        return mResetRecordingButton;
    }

    private Label getRecordingPathLabel()
    {
        if(mRecordingPathLabel == null)
        {
            mRecordingPathLabel = new Label(mDirectoryPreference.getDirectoryRecording().toString());
        }

        return mRecordingPathLabel;
    }

    private Label getStreamingLabel()
    {
        if(mStreamingLabel == null)
        {
            mStreamingLabel = new Label("Streaming");
        }

        return mStreamingLabel;
    }

    private Button getChangeStreamingButton()
    {
        if(mChangeStreamingButton == null)
        {
            mChangeStreamingButton = createChangeButton("Select Streaming Folder",
                mDirectoryPreference::getDirectoryStreaming, mDirectoryPreference::setDirectoryStreaming);
        }

        return mChangeStreamingButton;
    }

    private Button getResetStreamingButton()
    {
        if(mResetStreamingButton == null)
        {
            mResetStreamingButton = createResetButton(mDirectoryPreference::resetDirectoryStreaming);
        }

        return mResetStreamingButton;
    }

    private Label getStreamingPathLabel()
    {
        if(mStreamingPathLabel == null)
        {
            mStreamingPathLabel = new Label(mDirectoryPreference.getDirectoryStreaming().toString());
        }

        return mStreamingPathLabel;
    }

    private Button createChangeButton(String title, Supplier<Path> initialDirectorySupplier, Consumer<Path> directorySetter)
    {
        Button button = new Button("Change...");
        button.setOnAction(event -> {
            DirectoryChooser directoryChooser = new DirectoryChooser();
            directoryChooser.setTitle(title);
            directoryChooser.setInitialDirectory(initialDirectorySupplier.get().toFile());
            Stage stage = (Stage)button.getScene().getWindow();
            File selected = directoryChooser.showDialog(stage);

            if(selected != null)
            {
                directorySetter.accept(selected.toPath());
            }
        });
        return button;
    }

    private Button createResetButton(Runnable resetAction)
    {
        Button button = new Button("Reset");
        button.setOnAction(event -> resetAction.run());
        return button;
    }

    @Subscribe
    public void preferenceUpdated(PreferenceType preferenceType)
    {
        if(preferenceType != null && preferenceType == PreferenceType.DIRECTORY)
        {
            getApplicationLogsPathLabel().setText(mDirectoryPreference.getDirectoryApplicationLog().toString());
            getEventLogsPathLabel().setText(mDirectoryPreference.getDirectoryEventLog().toString());
            getJmbePathLabel().setText(mDirectoryPreference.getDirectoryJmbe().toString());
            getRecordingPathLabel().setText(mDirectoryPreference.getDirectoryRecording().toString());
            getStreamingPathLabel().setText(mDirectoryPreference.getDirectoryStreaming().toString());
            for(Label label: new Label[]{getApplicationLogsPathLabel(), getEventLogsPathLabel(), getJmbePathLabel(),
                getRecordingPathLabel(), getStreamingPathLabel()})
            {
                label.getTooltip().setText(label.getText());
            }
        }
    }
}
