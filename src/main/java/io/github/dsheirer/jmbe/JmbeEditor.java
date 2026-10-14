/*
 * *****************************************************************************
 *  Copyright (C) 2014-2020 Dennis Sheirer
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

package io.github.dsheirer.jmbe;

import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.jmbe.github.Release;
import io.github.dsheirer.jmbe.github.Version;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.PreferenceType;
import javafx.application.Platform;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Editor pane for JMBE Library creator
 */
public class JmbeEditor extends VBox
{
    private static final Logger mLog = LoggerFactory.getLogger(JmbeEditor.class);
    private UserPreferences mUserPreferences;
    private Release mCurrentRelease;
    private Label mCurrentVersionLabel;
    private Label mUpdatedVersionLabel;
    private Label mLibraryPathLabel;
    private Button mChangeDirectoryButton;
    private GridPane mLabelGridPane;
    private Path mLibraryDirectoryPath;
    private Button mCreateButton;
    private Button mUseLibraryButton;
    private Label mLibraryStatusLabel;
    private volatile JmbeCreator mActiveCreator;
    private javafx.animation.Timeline mProgressTimer;
    private volatile long mCreationGeneration;
    private Path mCreatedLibrary;
    private Path mCreatedDirectory;
    private boolean mBuildRunning;
    private boolean mInstalling;

    /** Detach a closing view and request cancellation; the worker acknowledges after stopping its child work. */
    public void cancelCreation()
    {
        mCreationGeneration++;
        if(mProgressTimer != null) mProgressTimer.stop();
        if(mActiveCreator != null) mActiveCreator.cancel();
        if(!mInstalling)
        {
            discardCreatedLibrary();
            getLibraryStatusLabel().setText("");
        }
    }
    private TextArea mConsoleTextArea;

    /**
     * Constructs an instance
     * @param userPreferences
     */
    public JmbeEditor(UserPreferences userPreferences)
    {
        mUserPreferences = userPreferences;
        MyEventBus.getGlobalEventBus().register(this);
        setPadding(new Insets(10,10,10,10));
        setSpacing(10);
        Separator separator = new Separator();
        separator.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(getConsoleTextArea(), Priority.ALWAYS);
        getChildren().addAll(getLabelGridPane(), separator,
            new HBox(10, getCreateButton(), getUseLibraryButton()), getLibraryStatusLabel(), getConsoleTextArea());
    }

    /**
     * Processes a request to configure the editor to build the release version specified in the request.
     * @param request to build a specific release version
     */
    public void process(JmbeEditorRequest request)
    {
        if(request != null)
        {
            setCurrentRelease(request.getCurrentRelease());
            refreshCurrentVersion();
        }
    }

    private TextArea getConsoleTextArea()
    {
        if(mConsoleTextArea == null)
        {
            mConsoleTextArea = new TextArea();
            mConsoleTextArea.setMaxHeight(Double.MAX_VALUE);
            mConsoleTextArea.setMaxWidth(Double.MAX_VALUE);
        }

        return mConsoleTextArea;
    }

    private Button getCreateButton()
    {
        if(mCreateButton == null)
        {
            mCreateButton = new Button("Create Library");
            mCreateButton.setOnAction(event -> {
                String noticeText = "The JMBE library is available as source code for educational purposes " +
                    "only.  It is a written description of how certain voice encoding/decoding algorithms " +
                    "could be implemented. Executable objects compiled or derived from this package may " +
                    "be covered by one or more patents. Users are strongly advised to check for any " +
                    "patent restrictions or licensing requirements before compiling or using this " +
                    "source code.\n\nClicking the YES button indicates that you are requesting to download " +
                    "the JMBE source code and compile the JMBE library.";

                Alert alert = new Alert(Alert.AlertType.WARNING, noticeText, ButtonType.YES, ButtonType.NO);
                alert.setResizable(true);

                //Workaroud for dialog sizing issue on Windows
                alert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);

                alert.setTitle("Download and Compile JMBE Library?");
                alert.setHeaderText("Do you want to continue?");
                alert.initOwner(getCreateButton().getScene().getWindow());
                alert.showAndWait().ifPresent(buttonType -> {
                    if(buttonType == ButtonType.YES)
                    {
                        createJmbeLibrary();
                    }
                });
            });
        }

        return mCreateButton;
    }

    /**
     * Builds a candidate without changing the selected library. Activation is an explicit action after success.
     */
    private void createJmbeLibrary()
    {
        if(mBuildRunning || mInstalling || mCurrentRelease == null) return;
        discardCreatedLibrary();
        mBuildRunning = true;
        updateActions();
        long generation = ++mCreationGeneration;
        Release release = mCurrentRelease;
        Path directory = getLibraryDirectoryPath();
        getLibraryStatusLabel().setText("Building JMBE " + release.getVersion() + "…");
        var output = new io.github.dsheirer.gui.setup.SetupJobOutput();
        var timer = new javafx.animation.Timeline(new javafx.animation.KeyFrame(
            javafx.util.Duration.millis(150), event -> getConsoleTextArea().setText(output.snapshot())));
        timer.setCycleCount(javafx.animation.Animation.INDEFINITE);
        mProgressTimer = timer;
        timer.play();
        io.github.dsheirer.util.ThreadPool.CACHED.execute(() -> {
            String failure = null;
            Path createdDirectory = null;
            Path createdLibrary = null;
            try
            {
                Files.createDirectories(directory);
                createdDirectory = Files.createTempDirectory(directory, ".jmbe-created-");
                JmbeCreator creator = createCreator(release, createdDirectory.resolve(getJarName(release.getVersion())));
                mActiveCreator = creator;
                if(generation != mCreationGeneration) creator.cancel();
                createdLibrary = creator.run(output);
            }
            catch(Exception e)
            {
                mLog.error("Unable to create JMBE library", e);
                failure = "JMBE could not be built. Check your internet connection and available disk space, " +
                    "then try again or select an existing library. Your current selection is unchanged.";
            }
            final String error = failure;
            final Path candidate = createdLibrary;
            final Path candidateDirectory = createdDirectory;
            javafx.application.Platform.runLater(() -> {
                timer.stop();
                mActiveCreator = null;
                mBuildRunning = false;
                if(generation != mCreationGeneration || error != null)
                {
                    cleanCreatedLibrary(candidate, candidateDirectory);
                    if(generation == mCreationGeneration)
                    {
                        getConsoleTextArea().setText(output.snapshot());
                        getLibraryStatusLabel().setText(error);
                    }
                }
                else
                {
                    mCreatedLibrary = candidate;
                    mCreatedDirectory = candidateDirectory;
                    getConsoleTextArea().setText(output.snapshot());
                    getLibraryStatusLabel().setText("JMBE " + release.getVersion() +
                        " was built successfully. Choose Use this library to select it.");
                }
                updateActions();
            });
        });
    }

    JmbeCreator createCreator(Release release, Path library)
    {
        return new JmbeCreator(release, library);
    }

    private Button getUseLibraryButton()
    {
        if(mUseLibraryButton == null)
        {
            mUseLibraryButton = new Button("Use this library");
            mUseLibraryButton.setVisible(false);
            mUseLibraryButton.setManaged(false);
            mUseLibraryButton.setOnAction(event -> useCreatedLibrary());
        }
        return mUseLibraryButton;
    }

    private Label getLibraryStatusLabel()
    {
        if(mLibraryStatusLabel == null)
        {
            mLibraryStatusLabel = new Label();
            mLibraryStatusLabel.setWrapText(true);
        }
        return mLibraryStatusLabel;
    }

    private void useCreatedLibrary()
    {
        if(mInstalling || mBuildRunning || mCreatedLibrary == null) return;
        mInstalling = true;
        updateActions();
        Path candidate = mCreatedLibrary;
        getLibraryStatusLabel().setText("Selecting your JMBE library…");
        io.github.dsheirer.util.ThreadPool.CACHED.execute(() -> {
            String failure = null;
            try
            {
                io.github.dsheirer.audio.codec.mbe.JmbeLibraryLoader.validateLibrary(candidate);
                mUserPreferences.getJmbeLibraryPreference().installLibrary(candidate);
            }
            catch(Exception e)
            {
                mLog.error("Unable to select the created JMBE library", e);
                failure = "JMBE could not be selected. Check that this installation’s data folder is writable, " +
                    "then choose Use this library to try again. Your current selection is unchanged.";
            }
            String error = failure;
            Platform.runLater(() -> {
                mInstalling = false;
                refreshCurrentVersion();
                if(error == null)
                {
                    discardCreatedLibrary();
                    getLibraryStatusLabel().setText("JMBE " +
                        mUserPreferences.getJmbeLibraryPreference().getCurrentVersion() + " is selected and ready to use.");
                }
                else getLibraryStatusLabel().setText(error);
                updateActions();
            });
        });
    }

    private void updateActions()
    {
        getCreateButton().setDisable(mBuildRunning || mInstalling || mCurrentRelease == null);
        getChangeDirectoryButton().setDisable(mBuildRunning || mInstalling);
        getUseLibraryButton().setDisable(mBuildRunning || mInstalling);
        getUseLibraryButton().setVisible(mCreatedLibrary != null);
        getUseLibraryButton().setManaged(mCreatedLibrary != null);
    }

    private void discardCreatedLibrary()
    {
        cleanCreatedLibrary(mCreatedLibrary, mCreatedDirectory);
        mCreatedLibrary = null;
        mCreatedDirectory = null;
        updateActions();
    }

    private void cleanCreatedLibrary(Path library, Path directory)
    {
        try
        {
            if(library != null) Files.deleteIfExists(library);
            if(directory != null) Files.deleteIfExists(directory);
        }
        catch(IOException e) { mLog.warn("Unable to clean temporary JMBE library", e); }
    }

    @Subscribe
    public void preferenceUpdated(PreferenceType type)
    {
        if(type == PreferenceType.JMBE_LIBRARY)
        {
            if(Platform.isFxApplicationThread()) refreshCurrentVersion();
            else Platform.runLater(this::refreshCurrentVersion);
        }
    }

    private void refreshCurrentVersion()
    {
        Version version = mUserPreferences.getJmbeLibraryPreference().getCurrentVersion();
        getCurrentVersionLabel().setText(version == null ? "(not set)" : version.toString());
    }

    /**
     * Creates a jar name for the specified release version
     * @param version of the GitHub JMBE release
     * @return JMBE library jar name
     */
    public static String getJarName(Version version)
    {
        String name = version.toString();
        name = name.replace("v", "");
        return "jmbe-" + name + ".jar";
    }

    private GridPane getLabelGridPane()
    {
        if(mLabelGridPane == null)
        {
            mLabelGridPane = new GridPane();
            mLabelGridPane.setHgap(10);
            mLabelGridPane.setVgap(10);

            int row = 0;

            Label topLabel = new Label("Create or Update JMBE Library");
            mLabelGridPane.add(topLabel, 0, row, 2, 1);

            Label currentLabel = new Label("Current Version:");
            GridPane.setHalignment(currentLabel, HPos.RIGHT);
            mLabelGridPane.add(currentLabel, 0, ++row);
            mLabelGridPane.add(getCurrentVersionLabel(), 1, row);

            Label updateLabel = new Label("Updating To Version:");
            GridPane.setHalignment(updateLabel, HPos.RIGHT);
            mLabelGridPane.add(updateLabel, 0, ++row);
            mLabelGridPane.add(getUpdatedVersionLabel(), 1, row);

            Label pathLabel = new Label("Store Library At:");
            GridPane.setHalignment(pathLabel, HPos.RIGHT);
            mLabelGridPane.add(pathLabel, 0, ++row);
            mLabelGridPane.add(getLibraryPathLabel(), 1, row);

            mLabelGridPane.add(getChangeDirectoryButton(), 1, ++row);
        }

        return mLabelGridPane;
    }

    private Path getLibraryDirectoryPath()
    {
        if(mLibraryDirectoryPath == null)
        {
            mLibraryDirectoryPath = mUserPreferences.getDirectoryPreference().getDefaultJmbeDirectory();
        }

        return mLibraryDirectoryPath;
    }

    private Button getChangeDirectoryButton()
    {
        if(mChangeDirectoryButton == null)
        {
            mChangeDirectoryButton = new Button("Change Directory ...");
            mChangeDirectoryButton.setOnAction(event -> {
                DirectoryChooser chooser = new DirectoryChooser();
                chooser.setInitialDirectory(getLibraryDirectoryPath().toFile());
                chooser.setTitle("JMBE Library Directory");
                File newDirectory = chooser.showDialog(getChangeDirectoryButton().getScene().getWindow());
                if(newDirectory != null) setLibraryDirectoryPath(newDirectory.toPath());
            });
        }

        return mChangeDirectoryButton;
    }

    /**
     * Sets the library path and updates the display label
     */
    private void setLibraryDirectoryPath(Path path)
    {
        if(path != null)
        {
            mLibraryDirectoryPath = path;
            getLibraryPathLabel().setText(mLibraryDirectoryPath.toString());
        }
    }

    private Label getLibraryPathLabel()
    {
        if(mLibraryPathLabel == null)
        {
            mLibraryPathLabel = new Label();

            if(getLibraryDirectoryPath() != null)
            {
                mLibraryPathLabel.setText(getLibraryDirectoryPath().toString());
            }
        }

        return mLibraryPathLabel;
    }

    private Label getCurrentVersionLabel()
    {
        if(mCurrentVersionLabel == null)
        {
            mCurrentVersionLabel = new Label("(empty)");

            Version currentVersion = mUserPreferences.getJmbeLibraryPreference().getCurrentVersion();

            if(currentVersion != null)
            {
                mCurrentVersionLabel.setText(currentVersion.toString());
            }
        }

        return mCurrentVersionLabel;
    }

    private Label getUpdatedVersionLabel()
    {
        if(mUpdatedVersionLabel == null)
        {
            mUpdatedVersionLabel = new Label("(empty)");
        }

        return mUpdatedVersionLabel;
    }

    /**
     * Current release version available from GitHub.
     * @return current release or null if the release hasn't been set.
     */
    private Release getCurrentRelease()
    {
        return mCurrentRelease;
    }

    /**
     * Sets the current release version available from GitHub.
     * @param release version
     */
    public void setCurrentRelease(Release release)
    {
        mCurrentRelease = release;
        refreshCurrentVersion();
        if(!mInstalling && !mBuildRunning && mCreatedLibrary != null &&
            (release == null || !release.getVersion().equals(JmbeLibraryMetadata.getVersion(mCreatedLibrary))))
        {
            discardCreatedLibrary();
            getLibraryStatusLabel().setText("");
        }

        if(release != null)
        {
            Version updateVersion = release.getVersion();

            if(updateVersion != null)
            {
                getUpdatedVersionLabel().setText(updateVersion.toString());
            }
        }
        updateActions();
    }
}
