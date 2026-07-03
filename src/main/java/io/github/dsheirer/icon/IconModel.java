/*
 * *****************************************************************************
 * Copyright (C) 2014-2022 Dennis Sheirer
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
package io.github.dsheirer.icon;

import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.icon.IconDatabaseStore;
import io.github.dsheirer.util.ThreadPool;
import java.awt.Image;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.ImageIcon;

public class IconModel
{
    private static final Logger mLog = LoggerFactory.getLogger(IconModel.class);
    public static final int DEFAULT_ICON_SIZE = 12;
    public static final String DEFAULT_ICON = "No Icon";

    private IconDatabaseStore mIconDatabaseStore = new IconDatabaseStore(SdrTrunkDatabasePath.getDatabasePath());
    private AtomicBoolean mSavingIcons = new AtomicBoolean();
    private ObservableList<Icon> mIcons = FXCollections.observableArrayList(Icon.extractor());
    private Map<String,ImageIcon> mResizedIcons = new HashMap<>();
    private Icon mDefaultIcon;
    private IconSet mStandardIcons;

    public IconModel()
    {
        IconSet iconSet = load();

        if(iconSet == null)
        {
            iconSet = getStandardIconSet();
        }

        IconSet standardIcons = getStandardIconSet();

        mIcons.addAll(iconSet.getIcons());

        for(Icon icon: mIcons)
        {
            if(iconSet.getDefaultIcon() != null && iconSet.getDefaultIcon().matches(icon.getName()))
            {
                icon.setDefaultIcon(true);
                mDefaultIcon = icon;
            }

            if(standardIcons.getIcons().contains(icon))
            {
                icon.setStandardIcon(true);
            }
        }

        if(mDefaultIcon == null && !mIcons.isEmpty())
        {
            setDefaultIcon(mIcons.get(0));
        }

        //Add a change detection listener to schedule saves when the list changes.
        mIcons.addListener((ListChangeListener<Icon>)c -> scheduleSave());
    }

    /**
     * Adds the icon to this model
     */
    public void addIcon(Icon icon)
    {
        if(icon != null && !mIcons.contains(icon))
        {
            mIcons.add(icon);
        }
    }

    /**
     * Removes the icon from this model
     */
    public void removeIcon(Icon icon)
    {
        if(icon != null && !icon.getStandardIcon() && !icon.getDefaultIcon())
        {
            mIcons.remove(icon);
        }
    }

    /**
     * Sets the default icon
     */
    public void setDefaultIcon(Icon icon)
    {
        if(icon != null)
        {
            if(mDefaultIcon != null)
            {
                mDefaultIcon.setDefaultIcon(false);
            }

            mDefaultIcon = icon;
            mDefaultIcon.setDefaultIcon(true);
        }
    }

    /**
     * Lookup an icon by name.
     * @param iconName to lookup
     * @return icon if found, or the default icon
     */
    public Icon getIcon(String iconName)
    {
        if(iconName != null)
        {
            for(Icon icon: iconsProperty())
            {
                if(icon.getName() != null && icon.getName().contentEquals(iconName))
                {
                    return icon;
                }
            }
        }

        return getDefaultIcon();
    }

    /**
     * Current set of icons managed by this model
     */
    public ObservableList<Icon> iconsProperty()
    {
        return mIcons;
    }

    public Icon getDefaultIcon()
    {
        return mDefaultIcon;
    }

    /**
     * Returns named icon scaled to the specified height.  Utilizes an internal map to retain scaled icons so that they
     * are only scaled/generated once.
     *
     * @param name - name of icon
     * @param height - height of icon in pixels
     * @return - scaled named icon (if it exists) or a scaled version of the default icon
     */
    public ImageIcon getIcon(String name, int height)
    {
        if(name == null)
        {
            name = getDefaultIcon().getName();
        }

        String scaledIconName = name + height;

        ImageIcon mapValue = mResizedIcons.get(scaledIconName);
        if (mapValue != null)
        {
            return mapValue;
        }

        Icon icon = getIcon(name);

        ImageIcon scaledIcon = getScaledIcon(icon.getIcon(), height);

        if(scaledIcon != null)
        {
            mResizedIcons.put(scaledIconName, scaledIcon);
        }

        return scaledIcon;
    }

    /**
     * Scales the icon to the new pixel height value
     *
     * @param original image icon
     * @param height new height to scale the image (width will be scaled accordingly)
     * @return
     */
    public static ImageIcon getScaledIcon(ImageIcon original, int height)
    {
        if(original != null)
        {
            double scale = (double) original.getIconHeight() / (double) height;

            int scaledWidth = (int) (original.getIconWidth() / scale);

            Image scaledImage = original.getImage().getScaledInstance(scaledWidth,
                height, java.awt.Image.SCALE_SMOOTH);

            return new ImageIcon(scaledImage);
        }

        return null;
    }

    /**
     * Constructs an icon and scales it to the specified height
     * @param path
     * @param height
     * @return
     */
    public static ImageIcon getScaledIcon(String path, int height)
    {
        if(path != null)
        {
            Icon icon = new Icon("", path);
            return getScaledIcon(icon.getIcon(), height);
        }

        return null;
    }

    /**
     * Loads icons from SQLite.
     */
    public IconSet load()
    {
        try
        {
            if(mIconDatabaseStore.isInitialized())
            {
                IconSet iconSet = mIconDatabaseStore.loadIcons();
                mLog.info("Loaded icons from SQLite [{}]: icons [{}], default [{}]",
                    mIconDatabaseStore.getDatabasePath(), iconSet.getIcons().size(), iconSet.getDefaultIcon());
                return iconSet;
            }

            IconSet iconSet = getStandardIconSet();
            mIconDatabaseStore.replaceIcons(iconSet);
            mLog.info("Initialized icons in SQLite [{}]: icons [{}], default [{}]",
                mIconDatabaseStore.getDatabasePath(), iconSet.getIcons().size(), iconSet.getDefaultIcon());
            return iconSet;
        }
        catch(Exception e)
        {
            mLog.error("Error loading icons from SQLite database [" + mIconDatabaseStore.getDatabasePath() + "]", e);
        }

        return getStandardIconSet();
    }

    /**
     * Creates a default icon set
     */
    private IconSet getStandardIconSet()
    {
        if(mStandardIcons == null)
        {
            mStandardIcons = new IconSet();

            Icon defaultIcon = new Icon(DEFAULT_ICON, "images/no_icon.png");
            mStandardIcons.add(defaultIcon);
            mStandardIcons.setDefaultIcon(defaultIcon.getName());

            mStandardIcons.add(new Icon("Ambulance", "images/ambulance.png"));
            mStandardIcons.add(new Icon("Block Truck", "images/concrete_block_truck.png"));
            mStandardIcons.add(new Icon("CWID", "images/cwid.png"));
            mStandardIcons.add(new Icon("Dispatcher", "images/dispatcher.png"));
            mStandardIcons.add(new Icon("Dump Truck", "images/dump_truck_red.png"));
            mStandardIcons.add(new Icon("Fire Truck", "images/fire_truck.png"));
            mStandardIcons.add(new Icon("Garbage Truck", "images/garbage_truck.png"));
            mStandardIcons.add(new Icon("Loader", "images/loader.png"));
            mStandardIcons.add(new Icon("Police", "images/police.png"));
            mStandardIcons.add(new Icon("Propane Truck", "images/propane_truck.png"));
            mStandardIcons.add(new Icon("Rescue Truck", "images/rescue_truck.png"));
            mStandardIcons.add(new Icon("School Bus", "images/school_bus.png"));
            mStandardIcons.add(new Icon("Taxi", "images/taxi.png"));
            mStandardIcons.add(new Icon("Train", "images/train.png"));
            mStandardIcons.add(new Icon("Transport Bus", "images/opt_bus.png"));
            mStandardIcons.add(new Icon("Van", "images/van.png"));
        }

        return mStandardIcons;
    }

    /**
     * Schedules an icon file save task.  Subsequent calls to this method will be ignored until the save event occurs,
     * thus limiting repetitive saving to a minimum.
     */
    private void scheduleSave()
    {
        if(mSavingIcons.compareAndSet(false, true))
        {
            ThreadPool.SCHEDULED.schedule(new IconSaveTask(), 2, TimeUnit.SECONDS);
        }
    }

    /**
     * Resets the configuration save pending flag to false and proceeds to save configuration state.
     */
    public class IconSaveTask implements Runnable
    {
        @Override
        public void run()
        {
            IconSet iconSet = new IconSet();
            iconSet.setDefaultIcon(getDefaultIcon().getName());
            iconSet.setIcons(new ArrayList<>(mIcons));

            try
            {
                mIconDatabaseStore.replaceIcons(iconSet);
                mLog.debug("Saved icons to SQLite [{}]", mIconDatabaseStore.getDatabasePath());
            }
            catch(Exception e)
            {
                mLog.error("Error while saving icons to SQLite [" + mIconDatabaseStore.getDatabasePath() + "]", e);
            }

            mSavingIcons.set(false);
        }
    }
}
