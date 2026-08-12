/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.am;

import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.string.SimpleStringIdentifier;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.analog.AnalogDecoderState;

/**
 * AM conventional decoder state.
 */
public class AMDecoderState extends AnalogDecoderState
{
    private final Identifier mChannelNameIdentifier;
    private final Identifier mTalkgroupIdentifier;

    public AMDecoderState(String channelName, DecodeConfigAM config)
    {
        String name = channelName != null && !channelName.isBlank() ? channelName : "AM CHANNEL";
        mChannelNameIdentifier = new SimpleStringIdentifier(name, IdentifierClass.CONFIGURATION,
            Form.CHANNEL_NAME, Role.ANY);
        mTalkgroupIdentifier = new AMTalkgroup(config.getTalkgroup());
    }

    @Override
    public DecoderType getDecoderType()
    {
        return DecoderType.AM;
    }

    @Override
    protected Identifier getChannelNameIdentifier()
    {
        return mChannelNameIdentifier;
    }

    @Override
    protected Identifier getTalkgroupIdentifier()
    {
        return mTalkgroupIdentifier;
    }
}
