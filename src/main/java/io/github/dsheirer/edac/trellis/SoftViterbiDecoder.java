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

package io.github.dsheirer.edac.trellis;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.dsp.symbol.Dibit;
import io.github.dsheirer.module.decode.p25.phase1.message.SoftDibitMessage;
import java.util.Arrays;

/**
 * Soft-decision Viterbi decoder for P25 trellis-coded dibit streams.
 */
public abstract class SoftViterbiDecoder
{
    private static final Dibit[] DIBITS = new Dibit[]{
        Dibit.D00_PLUS_1, Dibit.D01_PLUS_3, Dibit.D10_MINUS_1, Dibit.D11_MINUS_3
    };

    private final int mInputBitLength;
    private final int[][] mTransitionMatrix;

    protected SoftViterbiDecoder(int inputBitLength, int[][] transitionMatrix)
    {
        mInputBitLength = inputBitLength;
        mTransitionMatrix = transitionMatrix;
    }

    public CorrectedBinaryMessage decode(SoftDibitMessage deinterleaved)
    {
        int symbolCount = deinterleaved.size() / 2;
        int stateCount = mTransitionMatrix.length;
        double[] previousMetrics = new double[stateCount];
        double[] nextMetrics = new double[stateCount];
        int[][] previousState = new int[symbolCount][stateCount];
        int[][] inputValue = new int[symbolCount][stateCount];

        Arrays.fill(previousMetrics, Double.POSITIVE_INFINITY);
        previousMetrics[0] = 0.0d;

        for(int symbolIndex = 0; symbolIndex < symbolCount - 1; symbolIndex++)
        {
            Arrays.fill(nextMetrics, Double.POSITIVE_INFINITY);

            for(int previousStateIndex = 0; previousStateIndex < stateCount; previousStateIndex++)
            {
                double baseMetric = previousMetrics[previousStateIndex];

                if(Double.isInfinite(baseMetric))
                {
                    continue;
                }

                for(int candidateInput = 0; candidateInput < stateCount; candidateInput++)
                {
                    double metric = baseMetric + getBranchMetric(previousStateIndex, candidateInput, symbolIndex, deinterleaved);

                    if(metric < nextMetrics[candidateInput])
                    {
                        nextMetrics[candidateInput] = metric;
                        previousState[symbolIndex][candidateInput] = previousStateIndex;
                        inputValue[symbolIndex][candidateInput] = candidateInput;
                    }
                }
            }

            double[] swap = previousMetrics;
            previousMetrics = nextMetrics;
            nextMetrics = swap;
        }

        int finalSymbolIndex = symbolCount - 1;
        double bestMetric = Double.POSITIVE_INFINITY;
        int bestState = 0;

        for(int previousStateIndex = 0; previousStateIndex < stateCount; previousStateIndex++)
        {
            double baseMetric = previousMetrics[previousStateIndex];

            if(Double.isInfinite(baseMetric))
            {
                continue;
            }

            double metric = baseMetric + getBranchMetric(previousStateIndex, 0, finalSymbolIndex, deinterleaved);

            if(metric < bestMetric)
            {
                bestMetric = metric;
                bestState = 0;
                previousState[finalSymbolIndex][0] = previousStateIndex;
                inputValue[finalSymbolIndex][0] = 0;
            }
        }

        return buildMessage(previousState, inputValue, bestState, symbolCount);
    }

    private double getBranchMetric(int previousState, int candidateInput, int symbolIndex, SoftDibitMessage deinterleaved)
    {
        int expectedOutput = mTransitionMatrix[previousState][candidateInput];
        int dibitIndex = symbolIndex * 2;
        float firstObserved = deinterleaved.get(dibitIndex);
        float secondObserved = deinterleaved.get(dibitIndex + 1);
        Dibit firstExpected = DIBITS[(expectedOutput >> 2) & 0x3];
        Dibit secondExpected = DIBITS[expectedOutput & 0x3];
        return angularDistance(firstObserved, firstExpected.getIdealPhase()) +
            angularDistance(secondObserved, secondExpected.getIdealPhase());
    }

    private double angularDistance(float observed, float expected)
    {
        double difference = observed - expected;

        while(difference > Math.PI)
        {
            difference -= Math.PI * 2.0d;
        }

        while(difference < -Math.PI)
        {
            difference += Math.PI * 2.0d;
        }

        return Math.abs(difference);
    }

    private CorrectedBinaryMessage buildMessage(int[][] previousState, int[][] inputValue, int finalState, int symbolCount)
    {
        int[] decodedInputs = new int[symbolCount];
        int currentState = finalState;

        for(int symbolIndex = symbolCount - 1; symbolIndex >= 0; symbolIndex--)
        {
            decodedInputs[symbolIndex] = inputValue[symbolIndex][currentState];
            currentState = previousState[symbolIndex][currentState];
        }

        CorrectedBinaryMessage message = new CorrectedBinaryMessage((symbolCount - 1) * mInputBitLength);

        for(int symbolIndex = 0; symbolIndex < symbolCount - 1; symbolIndex++)
        {
            int decoded = decodedInputs[symbolIndex];
            int messageOffset = symbolIndex * mInputBitLength;

            for(int bit = 0; bit < mInputBitLength; bit++)
            {
                int mask = 1 << (mInputBitLength - bit - 1);

                if((decoded & mask) == mask)
                {
                    message.set(messageOffset + bit);
                }
            }
        }

        // Preserve non-negative semantics for downstream validity checks. CRC correction contributes the rest.
        message.setCorrectedBitCount(0);
        return message;
    }
}
