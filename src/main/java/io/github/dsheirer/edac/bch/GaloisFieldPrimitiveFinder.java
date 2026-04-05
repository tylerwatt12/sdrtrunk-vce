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

package io.github.dsheirer.edac.bch;

/**
 * Finds primitive polynomials for a Galois Fields (GF) of size M.
 */
public class GaloisFieldPrimitiveFinder
{
    private int M;
    private int N;

    /**
     * Constructs an instance of primitive finder for m for GF(2 ^ m)
     * @param m size of galois field
     */
    public GaloisFieldPrimitiveFinder(int m)
    {
        M = m;
        N = ((1 << M) - 1);
    }

    public boolean isPrimitive(int polynomial)
    {
        int x = 1;
        int k = 1 << M;

        for(int i = 0; i < N; i++)
        {
            if(i > 0 && (x == 1))
            {
                return false;
            }

            x <<= 1;

            if((x & k) > 0)
            {
                x ^= polynomial;
            }
        }

        return (x == 1);
    }
}
