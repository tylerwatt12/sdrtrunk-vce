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
import io.github.dsheirer.module.decode.p25.phase1.message.SymbolMessage;
import java.util.Arrays;

/**
 * Abstract base for unquantized maximum-likelihood Viterbi decoding of QPSK trellis-coded modulation (TCM).
 *
 * <h2>Algorithm</h2>
 * <p>This is a standard Viterbi decoder operating on a rate-constrained trellis. At each symbol
 * interval the decoder evaluates all valid state transitions, computes a branch metric for each,
 * accumulates path metrics, and retains only the surviving path into each candidate next state
 * (the Viterbi "add-compare-select" step). After processing all symbols, the decoder traces back
 * through the survivor array to recover the most-likely input sequence.</p>
 *
 * <h2>Branch Metric</h2>
 * <p>Each trellis symbol spans two QPSK dibits. The branch metric for a candidate transition is
 * the sum of the absolute angular distances between the two observed demodulated phase angles and
 * the two ideal QPSK constellation phases prescribed by the transition's expected output symbol.
 * Angular distance is computed on the unit circle, wrapping at ±π, so the metric is bounded
 * and symmetric regardless of phase offset.</p>
 *
 * <p>This is an <em>unquantized</em> branch metric. Classical soft-decision Viterbi implementations
 * discretize the channel reliability information to 3 or 4 bits before decoding in order to use
 * integer arithmetic and lookup tables. This implementation operates directly on the raw
 * single-precision floating-point phase angles delivered by the demodulator, eliminating
 * quantization loss entirely. Path metrics are accumulated as double-precision floating-point
 * values to avoid rounding accumulation over the trellis depth.</p>
 *
 * <h2>Trellis Structure</h2>
 * <p>The trellis is defined by a state transition matrix supplied by each concrete subclass.
 * The matrix entry {@code TRANSITION_MATRIX[previousState][inputValue]} gives the 4-bit output
 * symbol (two dibits) that the encoder would have produced for that state/input combination.
 * The number of states equals the number of valid input values, and is determined by the matrix
 * dimension. The number of input bits per symbol ({@code inputBitLength}) determines the output
 * message length: {@code (symbolCount - 1) * inputBitLength} bits, where the final symbol is a
 * flush symbol that drives the encoder back to the zero state.</p>
 *
 * <h2>Subclassing</h2>
 * <p>Concrete subclasses provide a P25-rate-specific transition matrix and input bit length:</p>
 * <ul>
 *   <li>{@link QPSKViterbiDecoder_1_2_P25} — 1/2-rate, 4 states, 2 input bits/symbol.
 *       Used for TSBK, PDU headers, and unconfirmed data blocks.</li>
 *   <li>{@link QPSKViterbiDecoder_3_4_P25} — 3/4-rate, 8 states, 3 input bits/symbol.
 *       Used for confirmed data blocks.</li>
 * </ul>
 *
 * <h2>Output</h2>
 * <p>The decoded {@link io.github.dsheirer.bits.CorrectedBinaryMessage} is returned with
 * {@code correctedBitCount} set to zero. Downstream CRC correction adds its own count. The
 * caller is responsible for CRC checking and for interpreting the corrected bit count.</p>
 */
public abstract class QPSKViterbiDecoder
{
    private static final Dibit[] DIBITS = new Dibit[]{
        Dibit.D00_PLUS_1, Dibit.D01_PLUS_3, Dibit.D10_MINUS_1, Dibit.D11_MINUS_3
    };

    private final int mInputBitLength;
    private final int[][] mTransitionMatrix;

    /**
     * Constructs an instance.
     * @param inputBitLength number of input bits per trellis symbol (e.g. 2 for 1/2-rate, 3 for 3/4-rate)
     * @param transitionMatrix state transition table where entry [state][input] gives the expected 4-bit output symbol
     */
    protected QPSKViterbiDecoder(int inputBitLength, int[][] transitionMatrix)
    {
        mInputBitLength = inputBitLength;
        mTransitionMatrix = transitionMatrix;
    }

    /**
     * Decodes a deinterleaved symbol message using unquantized Viterbi decoding.
     * @param symbols deinterleaved symbol phase angles, two per trellis symbol (each dibit pair is one TCM symbol)
     * @return decoded binary message with corrected bit count set to zero (CRC correction adds its own count)
     */
    public CorrectedBinaryMessage decode(SymbolMessage symbols)
    {
        int symbolCount = symbols.size() / 2;
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
                    double metric = baseMetric + getBranchMetric(previousStateIndex, candidateInput, symbolIndex, symbols);

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

            double metric = baseMetric + getBranchMetric(previousStateIndex, 0, finalSymbolIndex, symbols);

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

    /**
     * Computes the branch metric as the sum of angular distances between observed symbol phase angles
     * and the ideal constellation phases for the expected output symbol of the candidate transition.
     */
    private double getBranchMetric(int previousState, int candidateInput, int symbolIndex, SymbolMessage symbols)
    {
        int expectedOutput = mTransitionMatrix[previousState][candidateInput];
        int dibitIndex = symbolIndex * 2;
        float firstObservedPhase = symbols.get(dibitIndex);
        float secondObservedPhase = symbols.get(dibitIndex + 1);
        Dibit firstExpected = DIBITS[(expectedOutput >> 2) & 0x3];
        Dibit secondExpected = DIBITS[expectedOutput & 0x3];
        return angularDistance(firstObservedPhase, firstExpected.getIdealPhase()) +
            angularDistance(secondObservedPhase, secondExpected.getIdealPhase());
    }

    /**
     * Computes the absolute angular distance between two phase values, wrapping at ±π.
     */
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

        // Corrected bit count starts at zero; CRC correction contributes its own count downstream.
        message.setCorrectedBitCount(0);
        return message;
    }
}
