/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
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
package io.github.dsheirer.gui.preference.nowplaying;

import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.nowplaying.NowPlayingPreference;
import io.github.dsheirer.preference.nowplaying.NowPlayingPreference.JavaInterfaceView;
import javafx.geometry.Insets;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Preference settings for optional Java desktop views. Browser Live activity settings are administrator-managed in
 * the web interface.
 */
public class NowPlayingPreferenceEditor extends HBox
{
    private final NowPlayingPreference mNowPlayingPreference;
    private GridPane mEditorPane;

    /**
     * Constructs an instance.
     */
    public NowPlayingPreferenceEditor(UserPreferences userPreferences)
    {
        mNowPlayingPreference = userPreferences.getNowPlayingPreference();
        setMaxWidth(Double.MAX_VALUE);

        VBox vbox = new VBox();
        vbox.setMaxHeight(Double.MAX_VALUE);
        vbox.setMaxWidth(Double.MAX_VALUE);
        vbox.getChildren().add(getEditorPane());
        HBox.setHgrow(vbox, Priority.ALWAYS);
        getChildren().add(vbox);
    }

    private GridPane getEditorPane()
    {
        if(mEditorPane == null)
        {
            int row = 0;
            mEditorPane = new GridPane();
            mEditorPane.setMaxWidth(Double.MAX_VALUE);
            mEditorPane.setVgap(10);
            mEditorPane.setHgap(3);
            mEditorPane.setPadding(new Insets(10, 10, 10, 10));

            Label tabsLabel = new Label("Java Interface Views");
            tabsLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 1.08em;");
            mEditorPane.add(tabsLabel, 0, row++, 3, 1);
            mEditorPane.add(new Label("Show"), 0, row);
            mEditorPane.add(getViewVisibilityControls(), 1, row++, 2, 1);

            ColumnConstraints c1 = new ColumnConstraints();
            c1.setPercentWidth(30);
            ColumnConstraints c2 = new ColumnConstraints();
            c2.setHgrow(Priority.ALWAYS);
            mEditorPane.getColumnConstraints().addAll(c1, c2);
        }

        return mEditorPane;
    }

    private HBox getViewVisibilityControls()
    {
        HBox controls = new HBox(12);

        JavaInterfaceView view = JavaInterfaceView.MAP;
        CheckBox checkBox = new CheckBox(view.getLabel());
        checkBox.setSelected(mNowPlayingPreference.isJavaInterfaceViewEnabled(view));
        checkBox.setOnAction(event ->
            mNowPlayingPreference.setJavaInterfaceViewEnabled(view, checkBox.isSelected()));
        controls.getChildren().add(checkBox);

        return controls;
    }
}
