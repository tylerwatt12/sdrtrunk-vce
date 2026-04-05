/*******************************************************************************
 *     SDR Trunk 
 *     Copyright (C) 2014 Dennis Sheirer
 * 
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 * 
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 * 
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>
 ******************************************************************************/
package io.github.dsheirer.audio.invert;

public enum AudioType
{
	NORMAL( "Normal Audio", "Clear", 0 ),
	MUTE( "Muted Audio", "Muted", 0 ),
	INV2500(2500),
	INV2550(2550),
	INV2600(2600),
	INV2632(2632),
	INV2675(2675),
	INV2718(2718),
	INV2725(2725),
	INV2750(2750),
	INV2760(2760),
	INV2775(2775),
	INV2800(2800),
	INV2825(2825),
	INV2868(2868),
	INV3023(3023),
	INV3107(3107),
	INV3196(3196),
	INV3333(3333),
	INV3339(3339),
	INV3360(3360),
	INV3375(3375),
	INV3400(3400),
	INV3450(3450),
	INV3496(3496),
	INV3729(3729),
	INV4096(4096),
	INV4300(4300),
	INV4500(4500),
	INV4700(4700),
	INV4900(4900);

    private static final String INVERTED = "Inverted";
    
    private String mDisplayString;
    private String mShortDisplayString;
    private int mAudioInversionFrequency;
    
    AudioType( String displayString, 
    		   String shortDisplayString, 
    		   int inversionFrequency )
    {
        mDisplayString = displayString;
        mShortDisplayString = shortDisplayString;
        mAudioInversionFrequency = inversionFrequency;
    }

    AudioType(int inversionFrequency)
    {
        this("Inverted Audio " + inversionFrequency + "Hz", INVERTED, inversionFrequency);
    }
    
    public String getDisplayString()
    {
        return mDisplayString;
    }
    
    public String getShortDisplayString()
    {
    	return mShortDisplayString;
    }
    
    public int getAudioInversionFrequency()
    {
    	return mAudioInversionFrequency;
    }
    
    @Override
    public String toString()
    {
    	return mDisplayString;
    }
}
