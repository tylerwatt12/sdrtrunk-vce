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

package io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.module.decode.p25.phase1.P25P1DataUnitID;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.OSPMessage;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;

/**
 * Time and date announcement carried on a Phase 1 FDMA control channel.
 */
public class TimeAndDateAnnouncement extends OSPMessage
{
    private static final int VALID_DATE_FLAG = 16;
    private static final int VALID_TIME_FLAG = 17;
    private static final int VALID_LOCAL_TIME_OFFSET_FLAG = 18;
    private static final int LOCAL_TIME_OFFSET_SIGN = 20;
    private static final IntField LOCAL_TIME_OFFSET = IntField.range(21, 31);
    private static final IntField MONTH = IntField.range(32, 35);
    private static final IntField DAY = IntField.range(36, 40);
    private static final IntField YEAR = IntField.range(41, 53);
    private static final IntField HOURS = IntField.range(56, 60);
    private static final IntField MINUTES = IntField.range(61, 66);
    private static final IntField SECONDS = IntField.range(67, 72);

    public TimeAndDateAnnouncement(P25P1DataUnitID dataUnitId, CorrectedBinaryMessage message, int nac, long timestamp)
    {
        super(dataUnitId, message, nac, timestamp);
    }

    public boolean hasValidDate()
    {
        return getMessage().get(VALID_DATE_FLAG);
    }

    public boolean hasValidTime()
    {
        return getMessage().get(VALID_TIME_FLAG);
    }

    public boolean hasLocalTimeOffset()
    {
        return getMessage().get(VALID_LOCAL_TIME_OFFSET_FLAG);
    }

    /**
     * Decoded date and time.
     *
     * @return date and time, or null when the validity flags or encoded fields are invalid
     */
    public OffsetDateTime getDateAndTime()
    {
        if(!hasValidDate() || !hasValidTime())
        {
            return null;
        }

        int offsetMinutes = hasLocalTimeOffset() ? getMessage().getInt(LOCAL_TIME_OFFSET) : 0;
        offsetMinutes *= getMessage().get(LOCAL_TIME_OFFSET_SIGN) ? -1 : 1;

        try
        {
            return OffsetDateTime.of(getMessage().getInt(YEAR), getMessage().getInt(MONTH),
                getMessage().getInt(DAY), getMessage().getInt(HOURS), getMessage().getInt(MINUTES),
                getMessage().getInt(SECONDS), 0, ZoneOffset.ofTotalSeconds(offsetMinutes * 60));
        }
        catch(DateTimeException e)
        {
            return null;
        }
    }

    @Override
    public List<Identifier> getIdentifiers()
    {
        return Collections.emptyList();
    }

    @Override
    public String toString()
    {
        OffsetDateTime dateAndTime = getDateAndTime();
        return getMessageStub() + " " + (dateAndTime != null ? dateAndTime : "INVALID DATE/TIME");
    }
}
