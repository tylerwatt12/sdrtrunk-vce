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

package io.github.dsheirer.dsp.filter.design;

import io.github.dsheirer.dsp.filter.FilterFactory;
import io.github.dsheirer.dsp.filter.fir.FIRFilterSpecification;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FilterViewer extends Application
{
    private static final Logger mLog = LoggerFactory.getLogger(FilterViewer.class);

    @Override
    public void start(Stage primaryStage) throws Exception
    {
        Scene scene = new Scene(new FilterView(getFilter()));

        primaryStage.setTitle("Filter Viewer");
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    public static void main(String[] args)
    {
        launch(args);
    }

    /**
     * Provides the filter to visualize.  Modify this method to visualize your filter design.
     */
    private float[] getFilter()
    {
        FIRFilterSpecification specification = FIRFilterSpecification
                .lowPassBuilder()
                .sampleRate(12500)
                .passBandCutoff(5200)
                .passBandAmplitude(1.0).passBandRipple(0.01) //.01
                .stopBandAmplitude(0.0).stopBandStart(6500) //6500
                .stopBandRipple(0.01).build();

        float[] taps = null;

        try
        {
            taps = FilterFactory.getTaps(specification);
        }
        catch(Exception fde) //FilterDesignException
        {
            mLog.error("Error creating filter taps", fde);
        }

        if(taps == null)
        {
            throw new IllegalStateException("Couldn't design filter");
        }

        return taps;
    }
}
