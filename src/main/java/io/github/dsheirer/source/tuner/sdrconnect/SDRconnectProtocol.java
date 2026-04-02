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

package io.github.dsheirer.source.tuner.sdrconnect;

/**
 * SDRconnect WebSocket protocol constants shared across controller, monitor, and property handling.
 */
class SDRconnectProtocol
{
    static final String JSON_EVENT_TYPE = "event_type";
    static final String JSON_PROPERTY = "property";
    static final String JSON_VALUE = "value";

    static final String EVENT_GET_PROPERTY = "get_property";
    static final String EVENT_GET_PROPERTY_RESPONSE = "get_property_response";
    static final String EVENT_SET_PROPERTY = "set_property";
    static final String EVENT_PROPERTY_CHANGED = "property_changed";
    static final String EVENT_ERROR = "error";
    static final String EVENT_DEVICE_STREAM_ENABLE = "device_stream_enable";
    static final String EVENT_IQ_STREAM_ENABLE = "iq_stream_enable";
    static final String EVENT_SELECTED_DEVICE_NAME = "selected_device_name";

    static final String PROPERTY_VALID_DEVICES = "valid_devices";
    static final String PROPERTY_ACTIVE_DEVICE = "active_device";
    static final String PROPERTY_DEVICE_SAMPLE_RATE = "device_sample_rate";
    static final String PROPERTY_DEVICE_CENTER_FREQUENCY = "device_center_frequency";
    static final String PROPERTY_VALID_ANTENNAS = "valid_antennas";
    static final String PROPERTY_ACTIVE_ANTENNA = "active_antenna";
    static final String PROPERTY_STARTED = "started";

    private SDRconnectProtocol()
    {
    }
}
