/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.gui.configuration.alias;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.javafx.IconNode;

/** Reusable available/selected chooser for Alias scan-list and streaming routing. */
final class AliasRoutingSelectionPane<T> extends HBox
{
    private final ObservableList<T> mAvailable = FXCollections.observableArrayList();
    private final ObservableList<T> mSelected = FXCollections.observableArrayList();
    private final ListView<T> mAvailableView = new ListView<>(mAvailable);
    private final ListView<T> mSelectedView = new ListView<>(mSelected);
    private Runnable mChangeListener = () -> {};
    private boolean mLoading;

    AliasRoutingSelectionPane()
    {
        setSpacing(10);
        VBox available = column("Available", mAvailableView);
        VBox selected = column("Selected", mSelectedView);
        VBox buttons = new VBox(5);
        buttons.setAlignment(Pos.CENTER);
        Button add = new Button("", new IconNode(FontAwesome.ANGLE_RIGHT));
        Button remove = new Button("", new IconNode(FontAwesome.ANGLE_LEFT));
        add.disableProperty().bind(Bindings.isNull(mAvailableView.getSelectionModel().selectedItemProperty()));
        remove.disableProperty().bind(Bindings.isNull(mSelectedView.getSelectionModel().selectedItemProperty()));
        add.setOnAction(event -> move(mAvailableView.getSelectionModel().getSelectedItem(), mAvailable, mSelected));
        remove.setOnAction(event -> move(mSelectedView.getSelectionModel().getSelectedItem(), mSelected, mAvailable));
        buttons.getChildren().addAll(new Label(" "), add, remove);
        HBox.setHgrow(available, Priority.ALWAYS);
        HBox.setHgrow(selected, Priority.ALWAYS);
        getChildren().addAll(available, buttons, selected);
    }

    void setValues(Collection<T> all, Collection<T> selected)
    {
        mLoading = true;
        try
        {
            Set<T> selectedSet = selected != null ? new LinkedHashSet<>(selected) : Set.of();
            mSelected.setAll(selectedSet);
            mAvailable.setAll((all != null ? all : List.<T>of()).stream()
                .filter(value -> !selectedSet.contains(value)).toList());
        }
        finally
        {
            mLoading = false;
        }
    }

    List<T> selectedValues()
    {
        return List.copyOf(mSelected);
    }

    void setChangeListener(Runnable listener)
    {
        mChangeListener = listener != null ? listener : () -> {};
    }

    void setChooserDisabled(boolean disabled)
    {
        setDisable(disabled);
    }

    private void move(T value, ObservableList<T> source, ObservableList<T> target)
    {
        if(value != null && source.remove(value))
        {
            target.add(value);
            if(!mLoading)
            {
                mChangeListener.run();
            }
        }
    }

    private static <T> VBox column(String title, ListView<T> view)
    {
        view.setPrefHeight(90);
        VBox box = new VBox(new Label(title), view);
        VBox.setVgrow(view, Priority.ALWAYS);
        return box;
    }
}
