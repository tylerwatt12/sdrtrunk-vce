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

package io.github.dsheirer.gui.configuration.radioreference;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.gui.configuration.alias.AliasMutationUi;
import io.github.dsheirer.gui.configuration.alias.ViewAliasRequest;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.rrapi.type.System;
import io.github.dsheirer.rrapi.type.Talkgroup;
import io.github.dsheirer.rrapi.type.TalkgroupCategory;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javafx.geometry.HPos;
import javafx.geometry.Orientation;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;

public class TalkgroupEditor extends GridPane
{
    private ConfigurationManager mConfigurationManager;
    private TextField mAlphaTagTextField;
    private TextField mDescriptionTextField;
    private TextField mTalkgroupTextField;
    private TextField mModeTextField;
    private Button mEditAliasButton;
    private TextField mAliasNameTextField;
    private TextField mAliasDescriptionTextField;
    private TextField mAliasGroupTextField;
    private Button mCreateAliasButton;
    private Label mCreateLabel;
    private Label mNameLabel;
    private Label mAliasDescriptionLabel;
    private Label mGroupLabel;
    private Label mNotSupportedLabel;

    private RadioReferenceDecoder mRadioReferenceDecoder;
    private String mAliasListName;
    private System mSystem;
    private Talkgroup mTalkgroup;
    private TalkgroupCategory mTalkgroupCategory;
    private Alias mAlias;
    private SystemTalkgroupSelectionEditor.ImportStatus mImportStatus;

    public TalkgroupEditor(ConfigurationManager configurationManager)
    {
        mConfigurationManager = configurationManager;

        setHgap(5);
        setVgap(5);

        int row = 0;

        Label radioReferenceLabel = new Label("Radio Reference Talkgroup Details");
        GridPane.setConstraints(radioReferenceLabel, 1, row);
        GridPane.setHalignment(radioReferenceLabel, HPos.LEFT);
        getChildren().add(radioReferenceLabel);

        Label talkgroupLabel = new Label("Talkgroup");
        GridPane.setConstraints(talkgroupLabel, 0, ++row);
        GridPane.setHalignment(talkgroupLabel, HPos.RIGHT);
        getChildren().add(talkgroupLabel);

        GridPane.setHgrow(getTalkgroupTextField(), Priority.ALWAYS);
        GridPane.setConstraints(getTalkgroupTextField(), 1, row);
        getChildren().add(getTalkgroupTextField());

        Label alphaLabel = new Label("Alpha Tag");
        GridPane.setConstraints(alphaLabel, 0, ++row);
        GridPane.setHalignment(alphaLabel, HPos.RIGHT);
        getChildren().add(alphaLabel);

        GridPane.setHgrow(getAlphaTagTextField(), Priority.ALWAYS);
        GridPane.setConstraints(getAlphaTagTextField(), 1, row);
        getChildren().add(getAlphaTagTextField());

        Label descriptionLabel = new Label("RadioReference Description");
        GridPane.setConstraints(descriptionLabel, 0, ++row);
        GridPane.setHalignment(descriptionLabel, HPos.RIGHT);
        getChildren().add(descriptionLabel);

        GridPane.setHgrow(getDescriptionTextField(), Priority.ALWAYS);
        GridPane.setConstraints(getDescriptionTextField(), 1, row);
        getChildren().add(getDescriptionTextField());

        Label modeLabel = new Label("Mode");
        GridPane.setConstraints(modeLabel, 0, ++row);
        GridPane.setHalignment(modeLabel, HPos.RIGHT);
        getChildren().add(modeLabel);

        GridPane.setHgrow(getModeTextField(), Priority.ALWAYS);
        GridPane.setConstraints(getModeTextField(), 1, row);
        getChildren().add(getModeTextField());

        Separator separator2 = new Separator(Orientation.HORIZONTAL);
        GridPane.setConstraints(separator2, 0, ++row, 2,1);
        getChildren().add(separator2);

        //The following controls co-exist/overlap in the grid pane - visibility is controlled by the setTalkgroup()
        // method so that only one set is visible at any time.
        GridPane.setConstraints(getEditAliasButton(), 1, ++row);
        getChildren().add(getEditAliasButton());

        GridPane.setConstraints(getCreateLabel(), 1, row);
        GridPane.setHalignment(getCreateLabel(), HPos.LEFT);
        getChildren().add(getCreateLabel());

        GridPane.setConstraints(getNotSupportedLabel(), 1, row);
        GridPane.setHalignment(getNotSupportedLabel(), HPos.LEFT);
        getChildren().add(getNotSupportedLabel());

        GridPane.setConstraints(getNameLabel(), 0, ++row);
        GridPane.setHalignment(getNameLabel(), HPos.RIGHT);
        getChildren().add(getNameLabel());

        GridPane.setConstraints(getAliasNameTextField(), 1, row);
        getChildren().add(getAliasNameTextField());

        GridPane.setConstraints(getAliasDescriptionLabel(), 0, ++row);
        GridPane.setHalignment(getAliasDescriptionLabel(), HPos.RIGHT);
        getChildren().add(getAliasDescriptionLabel());

        GridPane.setConstraints(getAliasDescriptionTextField(), 1, row);
        getChildren().add(getAliasDescriptionTextField());

        GridPane.setConstraints(getGroupLabel(), 0, ++row);
        GridPane.setHalignment(getGroupLabel(), HPos.RIGHT);
        getChildren().add(getGroupLabel());

        GridPane.setConstraints(getAliasGroupTextField(), 1, row);
        getChildren().add(getAliasGroupTextField());

        GridPane.setConstraints(getCreateAliasButton(), 1, ++row);
        getChildren().add(getCreateAliasButton());
    }


    public void setTalkgroup(Talkgroup talkgroup, System system, RadioReferenceDecoder decoder, Alias alias,
                             String aliasListName, TalkgroupCategory talkgroupCategory,
                             SystemTalkgroupSelectionEditor.ImportStatus importStatus)
    {
        mRadioReferenceDecoder = decoder;
        mTalkgroup = talkgroup;
        mSystem = system;
        mAliasListName = aliasListName;
        mTalkgroupCategory = talkgroupCategory;
        mAlias = alias;
        mImportStatus = importStatus;

        if(talkgroup != null)
        {
            getTalkgroupTextField().setText(decoder.format(talkgroup, system));
            getAlphaTagTextField().setText(talkgroup.getAlphaTag());
            getAliasNameTextField().setText(talkgroup.getAlphaTag());
            getDescriptionTextField().setText(talkgroup.getDescription());
            getAliasDescriptionTextField().setText(talkgroup.getDescription());

            TalkgroupMode talkgroupMode = TalkgroupMode.lookup(talkgroup.getMode());
            TalkgroupEncryption talkgroupEncryption = TalkgroupEncryption.lookup(talkgroup.getEncryptionState());
            getModeTextField().setText(talkgroupMode.toString() + (talkgroupEncryption != TalkgroupEncryption.UNENCRYPTED ?
                " - " + talkgroupEncryption.toString() : ""));
        }
        else
        {
            getTalkgroupTextField().setText(null);
            getAlphaTagTextField().setText(null);
            getDescriptionTextField().setText(null);
            getAliasDescriptionTextField().setText(null);
            getModeTextField().setText(null);
            getAliasNameTextField().setText(null);
        }

        boolean supported = decoder != null && system != null && decoder.hasSupportedProtocol(system);
        boolean compatible = supported &&
            mImportStatus != SystemTalkgroupSelectionEditor.ImportStatus.NOT_COMPATIBLE;

        getEditAliasButton().setText(mImportStatus == SystemTalkgroupSelectionEditor.ImportStatus.DIFFERENT ?
            "Update from RadioReference" : "View Alias");
        getEditAliasButton().setVisible(mAlias != null && compatible);
        getCreateAliasButton().setVisible(mTalkgroup != null && mAlias == null && compatible);
        getNameLabel().setVisible(mTalkgroup != null && mAlias == null && compatible);
        getAliasNameTextField().setVisible(mTalkgroup != null && mAlias == null && compatible);
        getAliasDescriptionLabel().setVisible(mTalkgroup != null && mAlias == null && compatible);
        getAliasDescriptionTextField().setVisible(mTalkgroup != null && mAlias == null && compatible);
        getGroupLabel().setVisible(mTalkgroup != null && mAlias == null && compatible);
        getAliasGroupTextField().setText(mTalkgroupCategory != null ? mTalkgroupCategory.getName() : null);
        getAliasGroupTextField().setVisible(mTalkgroup != null && mAlias == null && compatible);
        getCreateLabel().setVisible(mTalkgroup != null && mAlias == null && compatible);
        getNotSupportedLabel().setVisible(mTalkgroup != null && !compatible);
    }

    private Label getNotSupportedLabel()
    {
        if(mNotSupportedLabel == null)
        {
            mNotSupportedLabel = new Label("Protocol Not Supported");
            mNotSupportedLabel.setVisible(false);
        }

        return mNotSupportedLabel;
    }

    private Label getCreateLabel()
    {
        if(mCreateLabel == null)
        {
            mCreateLabel = new Label("Create Talkgroup Alias");
            mCreateLabel.setVisible(false);
        }

        return mCreateLabel;
    }

    private Label getNameLabel()
    {
        if(mNameLabel == null)
        {
            mNameLabel = new Label("Name");
            mNameLabel.setVisible(false);
        }

        return mNameLabel;
    }

    private Label getGroupLabel()
    {
        if(mGroupLabel == null)
        {
            mGroupLabel = new Label("Group");
            mGroupLabel.setVisible(false);
        }

        return mGroupLabel;
    }

    private Label getAliasDescriptionLabel()
    {
        if(mAliasDescriptionLabel == null)
        {
            mAliasDescriptionLabel = new Label("Saved Description");
            mAliasDescriptionLabel.setVisible(false);
        }

        return mAliasDescriptionLabel;
    }

    private TextField getAliasNameTextField()
    {
        if(mAliasNameTextField == null)
        {
            mAliasNameTextField = new TextField();
            mAliasNameTextField.setMaxWidth(Double.MAX_VALUE);
            mAliasNameTextField.setVisible(false);
        }

        return mAliasNameTextField;
    }

    private TextField getAliasGroupTextField()
    {
        if(mAliasGroupTextField == null)
        {
            mAliasGroupTextField = new TextField();
            mAliasGroupTextField.setMaxWidth(Double.MAX_VALUE);
            mAliasGroupTextField.setVisible(false);
        }

        return mAliasGroupTextField;
    }

    private TextField getAliasDescriptionTextField()
    {
        if(mAliasDescriptionTextField == null)
        {
            mAliasDescriptionTextField = new TextField();
            mAliasDescriptionTextField.setMaxWidth(Double.MAX_VALUE);
            mAliasDescriptionTextField.setVisible(false);
        }

        return mAliasDescriptionTextField;
    }

    private Button getCreateAliasButton()
    {
        if(mCreateAliasButton == null)
        {
            mCreateAliasButton = new Button("Create Talkgroup Alias");
            mCreateAliasButton.setVisible(false);
            mCreateAliasButton.setOnAction(event -> {
                if(mAliasListName == null)
                {
                    Alert alert = new Alert(Alert.AlertType.INFORMATION, "Please select an Alias List",
                        ButtonType.OK);
                    alert.setTitle("Alias List Required");
                    alert.setHeaderText("An alias list is required to create aliases");
                    alert.initOwner((getCreateAliasButton()).getScene().getWindow());
                    alert.showAndWait();
                    MyEventBus.getGlobalEventBus().post(new FlashAliasListComboBoxRequest());
                }
                else if(mRadioReferenceDecoder != null && mTalkgroup != null && mSystem != null)
                {
                    Alias currentAlias = resolveCurrentAlias();
                    SystemTalkgroupSelectionEditor.ImportStatus currentStatus = getCurrentImportStatus(currentAlias);

                    if(currentAlias != null)
                    {
                        setTalkgroup(mTalkgroup, mSystem, mRadioReferenceDecoder, currentAlias, mAliasListName,
                            mTalkgroupCategory, currentStatus);
                        MyEventBus.getGlobalEventBus().post(new ViewAliasRequest(currentAlias));
                        return;
                    }

                    AliasListDefinition definition =
                        mConfigurationManager.getAliasModel().getAliasListDefinition(mAliasListName);

                    if(!isCurrentProtocolCompatible(definition))
                    {
                        Alert alert = new Alert(Alert.AlertType.INFORMATION,
                            "Please select an Alias List with a compatible protocol.", ButtonType.OK);
                        alert.setTitle("Alias List Required");
                        alert.setHeaderText("A compatible alias list is required to create aliases");
                        alert.initOwner(getCreateAliasButton().getScene().getWindow());
                        alert.showAndWait();
                        MyEventBus.getGlobalEventBus().post(new FlashAliasListComboBoxRequest());
                        return;
                    }

                    Alias alias = mRadioReferenceDecoder.createAlias(mTalkgroup, mSystem, definition,
                        getAliasGroupTextField().getText());
                    alias.setName(getAliasNameTextField().getText());
                    alias.setDescription(getAliasDescriptionTextField().getText());
                    long revision = mConfigurationManager.getAliasAdministrationService().currentRevision();

                    AliasMutationUi.execute(getCreateAliasButton(), "Create RadioReference Alias", () ->
                    {
                        if(TalkgroupEncryption.lookup(mTalkgroup.getEncryptionState()) == TalkgroupEncryption.FULL)
                        {
                            alias.setRecordable(false);
                            alias.setBroadcastChannels(List.of());
                            return mConfigurationManager.getAliasAdministrationService()
                                .createAlias(alias, Set.of(), revision);
                        }
                        return mConfigurationManager.getAliasAdministrationService().createAlias(alias, revision);
                    });
                }
            });
        }

        return mCreateAliasButton;
    }

    private boolean isCurrentProtocolCompatible(AliasListDefinition definition)
    {
        return SystemTalkgroupSelectionEditor.isRadioReferenceListCompatible(definition,
            mSystem != null && mRadioReferenceDecoder != null ?
                mRadioReferenceDecoder.getDecoderType(mSystem) : null);
    }

    private Alias resolveCurrentAlias()
    {
        if(mAliasListName == null || mRadioReferenceDecoder == null || mTalkgroup == null || mSystem == null)
        {
            return null;
        }

        AliasList aliasList = mConfigurationManager.getAliasModel().getAliasList(mAliasListName);
        return SystemTalkgroupSelectionEditor.findExactAlias(aliasList, mRadioReferenceDecoder, mTalkgroup, mSystem);
    }

    private SystemTalkgroupSelectionEditor.ImportStatus getCurrentImportStatus(Alias alias)
    {
        AliasListDefinition definition =
            mConfigurationManager.getAliasModel().getAliasListDefinition(mAliasListName);
        boolean compatible = mRadioReferenceDecoder != null && mSystem != null &&
            mRadioReferenceDecoder.hasSupportedProtocol(mSystem) && isCurrentProtocolCompatible(definition);
        return SystemTalkgroupSelectionEditor.getImportStatus(compatible, alias, mTalkgroup, mTalkgroupCategory);
    }

    private Button getEditAliasButton()
    {
        if(mEditAliasButton == null)
        {
            mEditAliasButton = new Button("View/Edit Alias");
            mEditAliasButton.setVisible(false);
            mEditAliasButton.setOnAction(event -> {
                Alias alias = resolveCurrentAlias();
                SystemTalkgroupSelectionEditor.ImportStatus status = getCurrentImportStatus(alias);

                if(alias != mAlias || status != mImportStatus)
                {
                    setTalkgroup(mTalkgroup, mSystem, mRadioReferenceDecoder, alias, mAliasListName,
                        mTalkgroupCategory, status);
                }

                if(alias != null && status != SystemTalkgroupSelectionEditor.ImportStatus.NOT_COMPATIBLE)
                {
                    if(status == SystemTalkgroupSelectionEditor.ImportStatus.DIFFERENT)
                    {
                        Talkgroup talkgroup = mTalkgroup;
                        TalkgroupCategory category = mTalkgroupCategory;
                        long revision = mConfigurationManager.getAliasAdministrationService().currentRevision();
                        List<SystemTalkgroupSelectionEditor.ImportedFieldChange> changes =
                            SystemTalkgroupSelectionEditor.getImportedFieldChanges(alias, talkgroup, category);
                        ButtonType update = new ButtonType("Update", ButtonBar.ButtonData.OK_DONE);
                        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION,
                            changes.stream().map(SystemTalkgroupSelectionEditor.ImportedFieldChange::display)
                                .collect(Collectors.joining("\n")),
                            update, ButtonType.CANCEL);
                        confirmation.setTitle("Update Alias from RadioReference");
                        confirmation.setHeaderText("Update imported Alias fields?");
                        confirmation.initOwner(getEditAliasButton().getScene().getWindow());

                        if(confirmation.showAndWait().filter(update::equals).isPresent())
                        {
                            Alias replacement = SystemTalkgroupSelectionEditor.createRadioReferenceReplacement(
                                alias, talkgroup, category);
                            AliasMutationUi.execute(getEditAliasButton(), "Update RadioReference Alias", () ->
                                mConfigurationManager.getAliasAdministrationService()
                                    .replaceAlias(alias.getId(), replacement, revision));
                        }
                    }
                    else
                    {
                        MyEventBus.getGlobalEventBus().post(new ViewAliasRequest(alias));
                    }
                }
            });
        }

        return mEditAliasButton;
    }

    public TextField getAlphaTagTextField()
    {
        if(mAlphaTagTextField == null)
        {
            mAlphaTagTextField = new TextField();
            mAlphaTagTextField.setMaxWidth(Double.MAX_VALUE);
            mAlphaTagTextField.setDisable(true);
        }

        return mAlphaTagTextField;
    }

    public TextField getDescriptionTextField()
    {
        if(mDescriptionTextField == null)
        {
            mDescriptionTextField = new TextField();
            mDescriptionTextField.setMaxWidth(Double.MAX_VALUE);
            mDescriptionTextField.setDisable(true);
        }

        return mDescriptionTextField;
    }

    public TextField getTalkgroupTextField()
    {
        if(mTalkgroupTextField == null)
        {
            mTalkgroupTextField = new TextField();
            mTalkgroupTextField.setMaxWidth(Double.MAX_VALUE);
            mTalkgroupTextField.setDisable(true);
        }

        return mTalkgroupTextField;
    }

    public TextField getModeTextField()
    {
        if(mModeTextField == null)
        {
            mModeTextField = new TextField();
            mModeTextField.setMaxWidth(Double.MAX_VALUE);
            mModeTextField.setPrefWidth(25);
            mModeTextField.setDisable(true);
        }

        return mModeTextField;
    }
}
