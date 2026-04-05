package io.github.dsheirer.spectrum.menu;

import io.github.dsheirer.dsp.filter.smoothing.SmoothingFilter.SmoothingType;
import io.github.dsheirer.spectrum.SpectralDisplayAdjuster;

import javax.swing.*;

public class SmoothingTypeItem extends JCheckBoxMenuItem
{
	private static final long serialVersionUID = 1L;
	
	private SpectralDisplayAdjuster mAdjuster;
	private SmoothingType mSmoothingType;
	
	public SmoothingTypeItem( SpectralDisplayAdjuster adjuster, SmoothingType type )
	{
		super( type.name() );
		
		mAdjuster = adjuster;
		mSmoothingType = type;
	
		setSelected( mAdjuster.getSmoothingType() == type );

		addActionListener( event -> mAdjuster.setSmoothingType( mSmoothingType ) );
	}
}
