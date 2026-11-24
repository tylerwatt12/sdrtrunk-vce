package io.github.dsheirer.module.decode.p25.reference;

import java.util.List;

public enum Digit
{
    D0( "0", "A" ),
    D1( "1", "B" ),
    D2( "2", "C" ),
    D3( "3", "D" ),
    D4( "4", "-" ),
    D5( "5", "-" ),
    D6( "6", "-" ),
    D7( "7", "-" ),
    D8( "8", "-" ),
    D9( "9", "-" ),
    D10( "*", "-" ),
    D11( "#", "-" ),
    D12( "-", "-" ),
    D13( "HOOK FLASH", "-" ),
    D14( "PAUSE", "-" ),
    D15( "ESC", "NULL" ),
    UNKNOWN( "?", "?" );
    
    private String mValue;
    private String mEscapedValue;
    
    private Digit( String value, String escapedValue )
    {
        mValue = value;
        mEscapedValue = escapedValue;
    }
    
    public String getValue()
    {
        return mValue;
    }
    
    public String getEscapedValue()
    {
        return mEscapedValue;
    }
    
    public static Digit fromValue( int value )
    {
        if( 0 <= value && value <= 15 )
        {
            return values()[ value ];
        }
        
        return UNKNOWN;
    }
    
    public static String decode( List<Integer> values )
    {
        StringBuilder sb = new StringBuilder();
        
        boolean escape = false;
        
        for( Integer value: values )
        {
            Digit d = Digit.fromValue( value );

            if( escape )
            {
                //TIA-102.AABC-E Table 2.3.13-1: a completed escape/null pair emits no digit.
                if( d != Digit.D15 )
                {
                    sb.append( d.getEscapedValue() );
                }
                escape = false;
            }
            else if( d == Digit.D15 )
            {
                escape = true;
            }
            else
            {
                sb.append( d.getValue() );
            }
        }
        
        return sb.toString();
    }
}
