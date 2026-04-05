package io.github.dsheirer.spectrum.menu;

import io.github.dsheirer.spectrum.DFTSize;
import io.github.dsheirer.spectrum.IDFTWidthChangeProcessor;

import javax.swing.*;

public class DFTSizeItem extends JCheckBoxMenuItem
{
    private static final long serialVersionUID = 1L;

    private IDFTWidthChangeProcessor mDFTProcessor;
    private DFTSize mDFTSize;
    
    public DFTSizeItem( IDFTWidthChangeProcessor processor, DFTSize size )
    {
    	super( size.getLabel() );
    	
    	mDFTProcessor = processor;
    	mDFTSize = size;

    	if( processor.getDFTSize() == mDFTSize )
    	{
    		setSelected( true );
    	}
    	
    	addActionListener( event -> mDFTProcessor.setDFTSize( mDFTSize ) );
    }
}
