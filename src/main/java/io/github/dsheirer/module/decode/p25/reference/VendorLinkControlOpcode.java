package io.github.dsheirer.module.decode.p25.reference;

public enum VendorLinkControlOpcode
{
	RESERVED_00( "OPCODE_00", 0 ),
	RESERVED_01( "OPCODE_01", 1 ),
	RESERVED_02( "OPCODE_02", 2 ),
	RESERVED_03( "OPCODE_03", 3 ),
	RESERVED_04( "OPCODE_04", 4 ),
	RESERVED_05( "OPCODE_05", 5 ),
	RESERVED_06( "OPCODE_06", 6 ),
	RESERVED_07( "OPCODE_07", 7 ),
	RESERVED_08( "OPCODE_08", 8 ),
	RESERVED_09( "OPCODE_09", 9 ),
	RESERVED_0A( "OPCODE_0A", 10 ),
	RESERVED_0B( "OPCODE_0B", 11 ),
	RESERVED_0C( "OPCODE_0C", 12 ),
	RESERVED_0D( "OPCODE_0D", 13 ),
	RESERVED_0E( "OPCODE_0E", 14 ),
	RESERVED_0F( "OPCODE_0F", 15 ),
	RESERVED_10( "OPCODE_10", 16 ),
	RESERVED_11( "OPCODE_11", 17 ),
	RESERVED_12( "OPCODE_12", 18 ),
	RESERVED_13( "OPCODE_13", 19 ),
	RESERVED_14( "OPCODE_14", 20 ),
	RESERVED_15( "OPCODE_15", 21 ),
	RESERVED_16( "OPCODE_16", 22 ),
	RESERVED_17( "OPCODE_17", 23 ),
	RESERVED_18( "OPCODE_18", 24 ),
	RESERVED_19( "OPCODE_19", 25 ),
	RESERVED_1A( "OPCODE_1A", 26 ),
	RESERVED_1B( "OPCODE_1B", 27 ),
	RESERVED_1C( "OPCODE_1C", 28 ),
	RESERVED_1D( "OPCODE_1D", 29 ),
	RESERVED_1E( "OPCODE_1E", 30 ),
	RESERVED_1F( "OPCODE_1F", 31 ),
	RESERVED_20( "OPCODE_20", 32 ),
	RESERVED_21( "OPCODE_21", 33 ),
	RESERVED_22( "OPCODE_22", 34 ),
	RESERVED_23( "OPCODE_23", 35 ),
	RESERVED_24( "OPCODE_24", 36 ),
	RESERVED_25( "OPCODE_25", 37 ),
	RESERVED_26( "OPCODE_26", 38 ),
	RESERVED_27( "OPCODE_27", 39 ),
	RESERVED_28( "OPCODE_28", 40 ),
	RESERVED_29( "OPCODE_29", 41 ),
	RESERVED_2A( "OPCODE_2A", 42 ),
	RESERVED_2B( "OPCODE_2B", 43 ),
	RESERVED_2C( "OPCODE_2C", 44 ),
	RESERVED_2D( "OPCODE_2D", 45 ),
	RESERVED_2E( "OPCODE_2E", 46 ),
	RESERVED_2F( "OPCODE_2F", 47 ),
	RESERVED_30( "OPCODE_30", 48 ),
	RESERVED_31( "OPCODE_31", 49 ),
	RESERVED_32( "OPCODE_32", 50 ),
	RESERVED_33( "OPCODE_33", 51 ),
	RESERVED_34( "OPCODE_34", 52 ),
	RESERVED_35( "OPCODE_35", 53 ),
	RESERVED_36( "OPCODE_36", 54 ),
	RESERVED_37( "OPCODE_37", 55 ),
	RESERVED_38( "OPCODE_38", 56 ),
	RESERVED_39( "OPCODE_39", 57 ),
	RESERVED_3A( "OPCODE_3A", 58 ),
	RESERVED_3B( "OPCODE_3B", 59 ),
	RESERVED_3C( "OPCODE_3C", 60 ),
	RESERVED_3D( "OPCODE_3D", 61 ),
	RESERVED_3E( "OPCODE_3E", 62 ),
	RESERVED_3F( "OPCODE_3F", 63 ),
	UNKNOWN( "UNKNOWN OPCODE", "UNKNOWN", -1 );

	private String mLabel;
	private String mDescription;
	private int mCode;
	
	private VendorLinkControlOpcode( String label, int code )
	{
		this(label, "RESERVED", code);
	}

	private VendorLinkControlOpcode( String label, String description, int code )
	{
		mLabel = label;
		mDescription = description;
		mCode = code;
	}
	
	public String getLabel()
	{
		return mLabel;
	}
	
	public String getDescription()
	{
		return mDescription;
	}
	
	public int getCode()
	{
		return mCode;
	}
	
	public static VendorLinkControlOpcode fromValue( int value )
	{
		if( 0 <= value && value <= 63 )
		{
			return values()[ value ];
		}
		
		return UNKNOWN;
	}
}
