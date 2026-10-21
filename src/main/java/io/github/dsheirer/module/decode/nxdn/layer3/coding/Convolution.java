/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
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

package io.github.dsheirer.module.decode.nxdn.layer3.coding;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * NXDN Convolutional encoder and Viterbi decoder for Rate 1/2, K=5
 */
public class Convolution
{
    private static final boolean[] G1 = {false, true, false, true, false, true, false, true, true, false, true, false,
            true, false, true, false, true, false, true, false, true, false, true, false, false, true, false, true,
            false, true, false, true};
    private static final boolean[] G2 = {false, true, true, false, true, false, false, true, false, true, true, false,
        true, false, false, true, true, false, false, true, false, true, true, false, true, false, false, true, false,
            true, true, false};

    /**
     * Decodes the convolutionally encoded message and counts the true bit errors, ignoring any punctured bit errors.
     * @param encoded message to be decoded
     * @param provider to identify punctured bits
     * @return decoded message with the bit error count set.
     */
    public static CorrectedBinaryMessage decode(CorrectedBinaryMessage encoded, PunctureProvider provider)
    {
        //Create an initial path with a root state of zero and attempt to auto-decode the message
        Path primaryPath = new Path(encoded, provider);

        //Attempt to auto-decode the message.  If the message is error free, or if it only contains error in the
        //puncture bits, then there are no impactful errors and the message was decoded correctly.
        if(primaryPath.autoDecode())
        {
            return primaryPath.getMessage();
        }

        List<Path> paths = new ArrayList<>();
        paths.add(primaryPath);
        int messageLength = encoded.size() / 2 - 4; //Exclude the final 4 flushing (0) bits which we'll add separately.

        //Auto-decode leaves the message pointer at the first bit position where it encountered the error.  Explore
        //from that point to find the maximum likelihood message.
        for(int x = primaryPath.getMessagePointer(); x < messageLength; x++)
        {
            //Continue adding path clones to the collection until we have 16 total paths to cover up to
            //8 states x 2 candidates = 16 total
            if(paths.size() < 16)
            {
                List<Path> clones = new ArrayList<>();
                for(Path prototype : paths)
                {
                    Path clone = prototype.clone();
                    clones.add(clone);
                    prototype.add(false);
                    clone.add(true);
                }
                paths.addAll(clones);
            }
            else
            {
                //Sort the paths in score order and clone the 8 best scoring paths onto the 8 worst scoring paths so
                //that we can feed the next candidate 0/1 explorations into each path pair.
                Collections.sort(paths);

                int a = 0;

                for(int y = 0; y < 8; y++)
                {
                    Path goodPath  = paths.get(y);
                    Path badPath = paths.get(y + 8);
                    badPath.cloneFrom(goodPath);

                    //Send the next candidate bits (0 or 1) to each path
                    goodPath.add(false);
                    badPath.add(true);
                }
            }
        }

        //Send the final four flushing zero bits onto each path
        for(Path path : paths)
        {
            path.add(false);
            path.add(false);
            path.add(false);
            path.add(false);
        }

        //Do a final score-order sort and select the lowest scoring path as the first path in the sorted collection.
        Collections.sort(paths);
        return paths.getFirst().getMessage();
    }

    /**
     * Encodes the message using NXDN convolutional encoder
     * @param message to be encoded
     * @return encoded message
     */
    public static CorrectedBinaryMessage encode(CorrectedBinaryMessage message)
    {
        CorrectedBinaryMessage encoded = new CorrectedBinaryMessage(message.size() * 2);

        int state = 0;
        int mask = 0x1F;
        int encodedPointer = 0;

        for(int x = 0; x < message.size(); x++)
        {
            state = (mask & (state << 1)) + (message.get(x) ? 1 : 0);
            encoded.set(encodedPointer++, G1[state]);
            encoded.set(encodedPointer++, G2[state]);
        }

        return encoded;
    }

}
