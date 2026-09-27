/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.map;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The bundled map icons that may be sent to a browser.  This deliberately does not read paths from persisted icon
 * records or accept user-supplied paths.  Custom icon delivery is a separate product decision.
 */
public enum StandardMapIconCatalog
{
    NO_ICON("No Icon", "no-icon", "images/no_icon.png"),
    AMBULANCE("Ambulance", "ambulance", "images/ambulance.png"),
    BLOCK_TRUCK("Block Truck", "block-truck", "images/concrete_block_truck.png"),
    CWID("CWID", "cwid", "images/cwid.png"),
    DISPATCHER("Dispatcher", "dispatcher", "images/dispatcher.png"),
    DUMP_TRUCK("Dump Truck", "dump-truck", "images/dump_truck_red.png"),
    FIRE_TRUCK("Fire Truck", "fire-truck", "images/fire_truck.png"),
    GARBAGE_TRUCK("Garbage Truck", "garbage-truck", "images/garbage_truck.png"),
    LOADER("Loader", "loader", "images/loader.png"),
    POLICE("Police", "police", "images/police.png"),
    PROPANE_TRUCK("Propane Truck", "propane-truck", "images/propane_truck.png"),
    RESCUE_TRUCK("Rescue Truck", "rescue-truck", "images/rescue_truck.png"),
    SCHOOL_BUS("School Bus", "school-bus", "images/school_bus.png"),
    TAXI("Taxi", "taxi", "images/taxi.png"),
    TRAIN("Train", "train", "images/train.png"),
    TRANSPORT_BUS("Transport Bus", "transport-bus", "images/opt_bus.png"),
    VAN("Van", "van", "images/van.png");

    private static final Map<String,StandardMapIconCatalog> BY_NAME =
        Stream.of(values()).collect(Collectors.toUnmodifiableMap(StandardMapIconCatalog::aliasName, value -> value));
    private static final Map<String,StandardMapIconCatalog> BY_SLUG =
        Stream.of(values()).collect(Collectors.toUnmodifiableMap(StandardMapIconCatalog::slug, value -> value));

    private final String mAliasName;
    private final String mSlug;
    private final String mResourcePath;

    StandardMapIconCatalog(String aliasName, String slug, String resourcePath)
    {
        mAliasName = aliasName;
        mSlug = slug;
        mResourcePath = resourcePath;
    }

    public String aliasName()
    {
        return mAliasName;
    }

    public String slug()
    {
        return mSlug;
    }

    public String resourcePath()
    {
        return mResourcePath;
    }

    public static StandardMapIconCatalog forAliasName(String aliasName)
    {
        return BY_NAME.getOrDefault(aliasName, NO_ICON);
    }

    public static StandardMapIconCatalog forSlug(String slug)
    {
        return BY_SLUG.get(slug);
    }
}
