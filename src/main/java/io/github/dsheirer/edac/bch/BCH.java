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

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.apache.commons.lang3.ArrayUtils;

/**
 * Bose-Chaudhuri-Hocquenghem (BCH) decoder.  Note: does NOT include the encoder from the original implementation.
 *
 * Based on the GPL2 Linux BCH decoder implementation here:
 * https://github.com/Parrot-Developers/bch/blob/master/include/linux/bch.h
 *
 * Note: original implementation method and variable names were largely used/retained in porting to Java.
 */
public abstract class BCH
{
    public static final int MESSAGE_NOT_CORRECTED = -1;
    public static final int PRIMITIVE_POLYNOMIAL_GF_3 = 0x7;
    public static final int PRIMITIVE_POLYNOMIAL_GF_7 = 0xB;
    public static final int PRIMITIVE_POLYNOMIAL_GF_15 = 0x13;
    public static final int PRIMITIVE_POLYNOMIAL_GF_31 = 0x25;
    public static final int PRIMITIVE_POLYNOMIAL_GF_63 = 0x43;
    public static final int PRIMITIVE_POLYNOMIAL_GF_127 = 0x83;
    public static final int PRIMITIVE_POLYNOMIAL_GF_255 = 0x11D;

    /**
     * Primitive polynomials for GF(2) to GF(8) are defined in this class via the PRIMITIVE_POLYNOMIAL_GF_xx constants.
     * This is different from the generator polynomial that is used to create message codewords for the BCH code, where
     * the generator polynomial dictates the error correcting capacity value T for the BCH finite set.  The base
     * primitive polynomial is used to calculate the syndromes for the BCH code regardless of the generator polynomial
     * that is used to generate the codewords and the specified value of T dictates how many syndromes are calculated
     * and therefore how many bit errors can be detected and corrected.
     */
    private int mPrimitivePolynomial;

    /**
     * Galois Field size: GF(2^m)
     */
    private int mM;

    /**
     * Codeword size where: N = 2 ^ M - 1
     */
    private int mN;

    /**
     * Error detection and correction capacity of the BCH code.  This is the maximum number of bit errors that can
     * be detected and corrected.
     */
    private int mT;

    /**
     * Lookup tables.  Note: naming convention is from the original C++ implementation.
     */
    private int[] a_pow_tab;
    private int[] a_log_tab;
    private int[] xi_tab;

    /**
     * Constructs an instance of a BCH decoder with the following design parameters:
     *
     * @param m Galois Field (2^m) where the BCH code is 2^m-1
     * @param k message data bits
     * @param t maximum correctable errors.  This is a design parameter of the generator polynomial used to form the
     * codewords
     * @param primitivePolynomial for the GF(2).  Note: use one of the PRIMITIVE_POLYNOMIAL_GF_xxx constants defined in
     * this class which cover use cases for M: 2-8.
     */
    protected BCH(int m, int k, int t, int primitivePolynomial)
    {
        mM = m;
        mN = (1 << m) - 1;
        mT = t;
        mPrimitivePolynomial = primitivePolynomial;
        initTables();
    }

    /**
     * Maximum bit error detection and correction capacity (T) of this decoder.
     * @return maximum bit error correction.
     */
    public int getMaxErrorCorrection()
    {
        return mT;
    }

    /*
     * Creates lookup table for finding the roots of a degree 2 error locator polynomial.
     */
    private void buildDegree2Base()
    {
        xi_tab = new int[mM];

        int i;
        int j;
        int r;
        int sum;
        int x;
        int y;
        int remaining;
        int ak = 0;
        int[] xi = new int[mM];

        /* find k s.t. Tr(a^k) = 1 and 0 <= k < m */
        for(i = 0; i < mM; i++)
        {
            sum = 0;
            for(j = 0; j < mM; j++)
            {
                sum ^= aPow(i * (1 << j));
            }

            if(sum != 0)
            {
                ak = a_pow_tab[i];
                break;
            }
        }

        /* find xi, i=0..m-1 such that xi^2+xi = a^i+Tr(a^i).a^k */
        remaining = mM;

        for(x = 0; (x <= mN) && remaining != 0; x++)
        {
            y = gfSqr(x) ^ x;
            for(i = 0; i < 2; i++)
            {
                r = aLog(y);

                if(y != 0 && (r < mM) && xi[r] == 0)
                {
                    xi_tab[r] = x;
                    xi[r] = 1;
                    remaining--;
                    break;
                }

                y ^= ak;
            }
        }

        if(remaining != 0)
        {
            throw new IllegalStateException("Unexpected remaining value: " + remaining);
        }
    }

    /*
     * Finds the error roots of the error locator polynomial, using BTZ algorithm
     */
    public int[] findPolyRoots(GFPoly poly, int k)
    {
        int[] roots = new int[0];

        switch(poly.mDegree)
        {
            /* handle low degree polynomials with ad hoc techniques */
            case 1:
                roots = findPolyDeg1Roots(poly);
                break;
            case 2:
                roots = findPolyDeg2Roots(poly);
                break;
            case 3:
                roots = findPolyDeg3Roots(poly);
                break;
            case 4:
                roots = findPolyDeg4Roots(poly);
                break;
            default:
                /* factor polynomial using Berlekamp Trace Algorithm (BTA) */
                if (poly.mDegree != 0 && (k <= mM))
                {
                    GFPoly[] factors = factorPolynomial(k, poly);

                    if (factors != null && factors.length == 2)
                    {
                        int[] aRoots = findPolyRoots(factors[0], k + 1);
                        int[] bRoots = findPolyRoots(factors[1], k + 1);

                        roots = new int[aRoots.length + bRoots.length];

                        System.arraycopy(aRoots, 0, roots, 0, aRoots.length);
                        System.arraycopy(bRoots, 0, roots, aRoots.length, bRoots.length);
                    }
                    else if (factors != null && factors.length > 0)
                    {
                        roots = findPolyRoots(factors[0], k + 1);
                    }
                }

                break;
        }

        return roots;
    }

    /*
     * Factors a polynomial using Berlekamp Trace algorithm (BTA)
     */
    public GFPoly[] factorPolynomial(int k, GFPoly f)
    {
        GFPoly f2 = new GFPoly(2 * mT);
        GFPoly q = new GFPoly(2 * mT);
        GFPoly z = new GFPoly(2 * mT);
        GFPoly tk;
        GFPoly gcd;

        GFPoly g = new GFPoly(1);
        f.copyTo(g);

        /* tk = Tr(a^k.X) mod f */
        tk = computeTraceBkMod(k, f, z);

        if (tk.mDegree > 0)
        {
            f.copyTo(f2);

            /* compute g = gcd(f, tk) (destructive operation) */
            gcd = gfPolyGcd(f2, tk);

            if (gcd.mDegree < f.mDegree)
            {
                /* compute h=f/gcd(f,tk); this will modify f and q */
                gfPolyDiv(f, gcd, q);
                GFPoly[] results = new GFPoly[2];
                results[0] = gcd;
                results[1] = q;
                return results;
            }
        }

        GFPoly[] results = new GFPoly[1];
        results[0] = g;
        return results;
    }

    /*
     * Compute polynomial Euclidean division quotient in GF(2^m)[X]
     */
    public void gfPolyDiv(GFPoly a, GFPoly b, GFPoly q)
    {
        if (a.mDegree >= b.mDegree)
        {
            q.mDegree = a.mDegree - b.mDegree;
            /* compute a mod b (modifies a) */
            gfPolyMod(a, b);
            /* quotient is stored in upper part of polynomial a */
            System.arraycopy(a.mC, b.mDegree, q.mC, 0, 1 + q.mDegree);
        }
        else
        {
            q.mDegree = 0;
            q.mC[0] = 0;
        }
    }


    /*
     * Compute polynomial GCD (Greatest Common Divisor) in GF(2^m)[X]
     */
    public GFPoly gfPolyGcd(GFPoly a, GFPoly b)
    {
        GFPoly tmp;

        if (a.mDegree < b.mDegree)
        {
            tmp = b;
            b = a;
            a = tmp;
        }

        while (b.mDegree > 0)
        {
            gfPolyMod(a, b);
            tmp = b;
            b = a;
            a = tmp;
        }

        return a;
    }


    /*
     * Given a polynomial f and an integer k, compute Tr(a^kX) mod f
     * This is used in Berlekamp Trace algorithm for splitting polynomials
     */
    public GFPoly computeTraceBkMod(int k, GFPoly f, GFPoly z)
    {
        int m = mM;
        int i;
        int j;

        /* z contains z^2j mod f */
        z.mDegree = 1;
        z.mC[0] = 0;
        z.mC[1] = a_pow_tab[k];

        GFPoly outTK = new GFPoly(f.mC.length);
        outTK.mDegree = 0;

        for (i = 0; i < m; i++)
        {
            /* add a^(k*2^i)(z^(2^i) mod f) and compute (z^(2^i) mod f)^2 */
            for (j = z.mDegree; j >= 0; j--)
            {
                if(2 * j >= z.mC.length)
                {
                    //Fail ... set degree to 0 to signal fail
                    outTK.mDegree = 0;
                    return outTK;
                }

                outTK.mC[j] ^= z.mC[j];
                z.mC[2 * j] = gfSqr(z.mC[j]);
                z.mC[2 * j + 1] = 0;
            }

            if (z.mDegree > outTK.mDegree)
            {
                outTK.mDegree = z.mDegree;
            }

            if (i < m-1)
            {
                z.mDegree *= 2;
                /* z^(2(i+1)) mod f = (z^(2^i) mod f)^2 mod f */
                gfPolyMod(z, f);
            }
        }

        while (outTK.mC[outTK.mDegree] == 0 && outTK.mDegree != 0)
        {
            outTK.mDegree--;
        }

        return outTK;
    }

    /*
     * compute polynomial Euclidean division remainder in GF(2^m)[X]
     */
    public void gfPolyMod(GFPoly a, GFPoly b)
    {
        if (a.mDegree < b.mDegree)
        {
            return;
        }

        int la;
        int p;
        int m;
        int i;
        int j;
        int[] c = Arrays.copyOf(a.mC, a.mC.length);
        int d = b.mDegree;

        int[] rep = gfPolyLogRep(b);

        for (j = a.mDegree; j >= d; j--)
        {
            if (c[j] != 0)
            {
                la = aLog(c[j]);
                p = j-d;

                for (i = 0; i < d; i++, p++)
                {
                    m = rep[i];

                    if (m >= 0)
                    {
                        c[p] ^= a_pow_tab[modS(m + la)];
                    }
                }
            }
        }

        a.mDegree = d - 1;

        while (c[a.mDegree] == 0 && a.mDegree != 0)
        {
            a.mDegree--;
        }

        //Reassign c back to a's polynomial
        a.mC = c;
    }


    /*
     * Build monic, log-based representation of a polynomial
     */
    public int[] gfPolyLogRep(GFPoly a)
    {
        int i;
        int d = a.mDegree;
        int l = mN - aLog(a.mC[a.mDegree]);

        int[] rep = new int[d];

        /* represent 0 values with -1; warning, rep[d] is not set to 1 */
        for (i = 0; i < d; i++)
        {
            rep[i] = a.mC[i] != 0 ? modS(aLog(a.mC[i]) + l) : -1;
        }

        return rep;
    }

    /*
     * Compute root r of a degree 1 polynomial over GF(2^m) (returned as log(1/r))
     */
    public int[] findPolyDeg1Roots(GFPoly poly)
    {
        int[] roots = new int[1];

        if(poly.mC[0] != 0)
        {
            /* poly[X] = bX+c with c!=0, root=c/b */
            roots[0] = modS(mN - a_log_tab[poly.mC[0]] + a_log_tab[poly.mC[1]]);
        }

        return roots;
    }

    /*
     * Compute roots of a degree 2 polynomial over GF(2^m)
     */
    public int[] findPolyDeg2Roots(GFPoly poly)
    {
        int[] roots = new int[poly.mDegree];

        int n = 0;
        int i;
        int l0;
        int l1;
        int l2;
        int u;
        int v;
        int r;

        if(poly.mC[0] > 0 && poly.mC[1] > 0)
        {
            l0 = a_log_tab[poly.mC[0]];
            l1 = a_log_tab[poly.mC[1]];
            l2 = a_log_tab[poly.mC[2]];

            /* using z=a/bX, transform aX^2+bX+c into z^2+z+u (u=ac/b^2) */
            u = aPow(l0 + l2 + 2 * (mN - l1));
            /*
             * let u = sum(li.a^i) i=0..m-1; then compute r = sum(li.xi):
             * r^2+r = sum(li.(xi^2+xi)) = sum(li.(a^i+Tr(a^i).a^k)) =
             * u + sum(li.Tr(a^i).a^k) = u+a^k.Tr(sum(li.a^i)) = u+a^k.Tr(u)
             * i.e. r and r+1 are roots iff Tr(u)=0
             */
            r = 0;
            v = u;
            while(v != 0)
            {
                i = deg(v);
                r ^= xi_tab[i];
                v ^= (1 << i);
            }

            /* verify root */
            if((gfSqr(r) ^ r) == u)
            {
                /* reverse z=a/bX transformation and compute log(1/r) */
                roots[n++] = modulo(2 * mN - l1 - a_log_tab[r] + l2);
                roots[n] = modulo(2 * mN - l1 - a_log_tab[r ^ 1] + l2);
            }
        }

        return roots;
    }

    /*
     * Compute roots of a degree 3 polynomial over GF(2^m)
     */
    public int[] findPolyDeg3Roots(GFPoly poly)
    {
        int[] roots = new int[poly.mDegree];

        int i;
        int n = 0;
        int a;
        int b;
        int c;
        int a2;
        int b2;
        int c2;
        int e3;
        int[] tmp = new int[4];

        if(poly.mC[0] != 0)
        {
            /* transform polynomial into monic X^3 + a2X^2 + b2X + c2 */
            e3 = poly.mC[3];
            c2 = gfDiv(poly.mC[0], e3);
            b2 = gfDiv(poly.mC[1], e3);
            a2 = gfDiv(poly.mC[2], e3);

            /* (X+a2)(X^3+a2X^2+b2X+c2) = X^4+aX^2+bX+c (affine) */
            c = gfMul(a2, c2);           /* c = a2c2      */
            b = gfMul(a2, b2) ^ c2;        /* b = a2b2 + c2 */
            a = gfSqr(a2) ^ b2;            /* a = a2^2 + b2 */

            /* find the 4 roots of this affine polynomial */
            if(findAffine4Roots(a, b, c, tmp) == 4)
            {
                /* remove a2 from final list of roots */
                for(i = 0; i < 4; i++)
                {
                    if(tmp[i] != a2)
                    {
                        roots[n++] = aILog(tmp[i]);
                    }
                }
            }
        }

        return roots;
    }

    /*
     * Compute roots of a degree 4 polynomial over GF(2^m)
     */
    public int[] findPolyDeg4Roots(GFPoly poly)
    {
        int[] roots = new int[poly.mDegree];

        int i;
        int l;
        int a;
        int b;
        int c;
        int d;
        int e = 0;
        int f;
        int a2;
        int b2;
        int c2;
        int e4;

        if (poly.mC[0] == 0)
        {
            return new int[0];
        }

        /* transform polynomial into monic X^4 + aX^3 + bX^2 + cX + d */
        e4 = poly.mC[4];
        d = gfDiv(poly.mC[0], e4);
        c = gfDiv(poly.mC[1], e4);
        b = gfDiv(poly.mC[2], e4);
        a = gfDiv(poly.mC[3], e4);

        /* use Y=1/X transformation to get an affine polynomial */
        if (a != 0)
        {
            /* first, eliminate cX by using z=X+e with ae^2+c=0 */
            if (c != 0)
            {
                /* compute e such that e^2 = c/a */
                f = gfDiv(c, a);
                l = aLog(f);
                l += ((l & 1) != 0) ? mN : 0;
                e = aPow(l / 2);

                /*
                 * use transformation z=X+e:
                 * z^4+e^4 + a(z^3+ez^2+e^2z+e^3) + b(z^2+e^2) +cz+ce+d
                 * z^4 + az^3 + (ae+b)z^2 + (ae^2+c)z+e^4+be^2+ae^3+ce+d
                 * z^4 + az^3 + (ae+b)z^2 + e^4+be^2+d
                 * z^4 + az^3 +     b'z^2 + d'
                 */
                d = aPow(2 * l) ^ gfMul(b, f) ^ d;
                b = gfMul(a, e) ^ b;
            }

            /* now, use Y=1/X to get Y^4 + b/dY^2 + a/dY + 1/d */
            if (d == 0)
            {
                /* assume all roots have multiplicity 1 */
                return new int[0];
            }

            c2 = gfInv(d);
            b2 = gfDiv(a, d);
            a2 = gfDiv(b, d);
        } else {
            /* polynomial is already affine */
            c2 = d;
            b2 = c;
            a2 = b;
        }
        /* find the 4 roots of this affine polynomial */
        if (findAffine4Roots(a2, b2, c2, roots) == 4)
        {
            for (i = 0; i < 4; i++)
            {
                /* post-process roots (reverse transformations) */
                f = a != 0 ? gfInv(roots[i]) : roots[i];
                roots[i] = aILog(f ^ e);
            }
        }

        return roots;
    }

    /*
     * This function builds and solves a linear system for finding roots of a degree
     * 4 affine monic polynomial X^4+aX^2+bX+c over GF(2^m).
     */
    public int findAffine4Roots(int a, int b, int c, int[] roots)
    {
        int i;
        int j;
        int k;
        int m = mM;
        int mask = 0xff;
        int t;
        int[] rows = new int[16];

        j = aLog(b);
        k = aLog(a);
        rows[0] = c;

        /* buid linear system to solve X^4+aX^2+bX+c = 0 */
        for(i = 0; i < m; i++)
        {
            rows[i + 1] = a_pow_tab[4 * i] ^ (a != 0 ? a_pow_tab[modS(k)] : 0) ^ (b != 0 ? a_pow_tab[modS(j)] : 0);
            j++;
            k += 2;
        }

        /*
         * transpose 16x16 matrix before passing it to linear solver
         * warning: this code assumes m < 16
         */
        for(j = 8; j != 0; j >>= 1, mask ^= (mask << j))
        {
            for(k = 0; k < 16; k = (k + j + 1) & ~j)
            {
                t = ((rows[k] >> j) ^ rows[k + j]) & mask;
                rows[k] ^= (t << j);
                rows[k + j] ^= t;
            }
        }

        return solveLinearSystem(rows, roots, 4);
    }

    /*
     * Solve an m x m linear system in GF(2) with an expected number of solutions,
     * and return the number of found solutions
     */
    public int solveLinearSystem(int[] rows, int[] sol, int nsol)
    {
        int m = mM;
        int tmp;
        int mask;
        int rem;
        int c;
        int r;
        int p;
        int k;
        int[] param = new int[m];

        k = 0;
        mask = 1 << m;

        /* Gaussian elimination */
        for(c = 0; c < m; c++)
        {
            rem = 0;
            p = c - k;

            /* find suitable row for elimination */
            for(r = p; r < m; r++)
            {
                if((rows[r] & mask) != 0)
                {
                    if(r != p)
                    {
                        tmp = rows[r];
                        rows[r] = rows[p];
                        rows[p] = tmp;
                    }

                    rem = r + 1;
                    break;
                }
            }
            if(rem != 0)
            {
                /* perform elimination on remaining rows */
                tmp = rows[p];
                for(r = rem; r < m; r++)
                {
                    if((rows[r] & mask) != 0)
                    {
                        rows[r] ^= tmp;
                    }
                }
            }
            else
            {
                /* elimination not needed, store defective row index */
                param[k++] = c;
            }
            mask >>= 1;
        }
        /* rewrite system, inserting fake parameter rows */
        if(k > 0)
        {
            p = k;
            for(r = m - 1; r >= 0; r--)
            {
                if((r > m - 1 - k) && rows[r] != 0)
                {
                    /* system has no solution */
                    return 0;
                }

                if(p != 0 && (r == param[p - 1]))
                {
                    p--;
                    rows[r] = 1 << (m - r);
                }
                else
                {
                    rows[r] = rows[r - p];
                }
            }
        }

        if(nsol != (1 << k))
        {
            /* unexpected number of solutions */
            return 0;
        }

        for(p = 0; p < nsol; p++)
        {
            /* set parameters for p-th solution */
            for(c = 0; c < k; c++)
            {
                rows[param[c]] = (rows[param[c]] & ~1) | ((p >> c) & 1);
            }

            /* compute unique solution */
            tmp = 0;
            for(r = m - 1; r >= 0; r--)
            {
                mask = rows[r] & (tmp | 1);
                tmp |= parity(mask) << (m - r);
            }

            sol[p] = tmp >> 1;
        }

        return nsol;
    }

    /**
     * Calculates the parity of an integer as the number of set bits mod 2.
     * @param x to calculate
     * @return parity of x
     */
    public static int parity(int x)
    {
        return Integer.bitCount(x) % 2;
    }

    /**
     * Calculate the error locator polynomial from the syndromes.
     *
     * @param syn syndromes
     * @return error locator polynomial (elp)
     */
    public GFPoly computeErrorLocatorPolynomial(int[] syn)
    {
        int i;
        int j;
        int tmp;
        int l;
        int pd = 1;
        int d = syn[0];
        int k;
        int pp = -1;

        GFPoly elp = new GFPoly(2 * mT + 1);
        GFPoly pelp = new GFPoly(2 * mT + 1);
        GFPoly elp_copy = new GFPoly(2 * mT + 1);

        pelp.mDegree = 0;
        pelp.mC[0] = 1;
        elp.mDegree = 0;
        elp.mC[0] = 1;

        /* use simplified binary Berlekamp-Massey algorithm */
        for(i = 0; (i < mT) && (elp.mDegree <= mT); i++)
        {
            if(d != 0)
            {
                k = 2 * i - pp;
                elp.copyTo(elp_copy); // reimplemented
                /* e[i+1](X) = e[i](X)+di*dp^-1*X^2(i-p)*e[p](X) */
                tmp = aLog(d) + mN - aLog(pd);
                for(j = 0; j <= pelp.mDegree; j++)
                {
                    if(pelp.mC[j] > 0)
                    {
                        l = aLog(pelp.mC[j]);
                        elp.mC[j + k] ^= aPow(tmp + l);
                    }
                }
                /* compute l[i+1] = max(l[i]->c[l[p]+2*(i-p]) */
                tmp = pelp.mDegree + k;
                if(tmp > elp.mDegree)
                {
                    elp.mDegree = tmp;
                    elp_copy.copyTo(pelp); //reimplemented
                    pd = d;
                    pp = 2 * i;
                }
            }
            /* di+1 = S(2i+3)+elp[i+1].1*S(2i+2)+...+elp[i+1].lS(2i+3-l) */
            if(i < mT - 1)
            {
                d = syn[2 * i + 2];
                for(j = 1; j <= elp.mDegree; j++)
                {
                    d ^= mul(elp.mC[j], syn[2 * i + 2 - j]);
                }
            }
        }

        return elp;
    }

    /**
     * Multiplies two integer values using mod N polynomial math.
     *
     * @param a first value
     * @param b second value
     * @return product of (a * b) % N
     */
    private int mul(int a, int b)
    {
        if(a == 0 || b == 0)
        {
            return 0;
        }

        return a_pow_tab[(a_log_tab[a] + a_log_tab[b]) % mN];
    }

    private int gfSqr(int a)
    {
        return a > 0 ? a_pow_tab[modS(2 * a_log_tab[a])] : 0;
    }

    private int gfMul(int a, int b)
    {
        return (a != 0 && b != 0) ? a_pow_tab[modS(a_log_tab[a] + a_log_tab[b])] : 0;
    }

    private int gfDiv(int a, int b)
    {
        return a != 0 ? a_pow_tab[modS(a_log_tab[a] + mN - a_log_tab[b])] : 0;
    }

    private int gfInv(int a)
    {
        return a_pow_tab[mN - a_log_tab[a]];
    }

    /**
     * Polynomial representation.
     */
    public static class GFPoly
    {
        private int mDegree = 0;
        private int[] mC; //Coefficients

        /**
         * Constructs an instance with the specified size, or number of coefficients.
         * @param size of the coefficients array
         */
        public GFPoly(int size)
        {
            mC = new int[size];
        }

        /**
         * Copies the degree and coefficients of this polynomial onto the argument polynomial.
         * @param copyTo target of the copy operation.
         */
        public void copyTo(GFPoly copyTo)
        {
            copyTo.mDegree = mDegree;
            copyTo.mC = Arrays.copyOf(mC, mC.length);
        }

        /**
         * Pretty print of the contents of this polynomial.
         */
        @Override
        public String toString()
        {
            return "Polynomial Degree: " + mDegree + " Coefficients: " + Arrays.toString(mC);
        }
    }

    /**
     * Computes the syndromes for the message.
     * @param message to check
     * @return array of syndromes of size (2 * T)
     */
    public int[] computeSyndromes(CorrectedBinaryMessage message)
    {
        int j;
        int twoT = 2 * mT;
        int nMinus1 = mN - 1;
        int[] syndromes = new int[twoT];

        //Use the binary message feature to iterate across the set bit positions to calculate the syndromes for each
        //set bit.  We iterate the bit positions in order from MSB to LSB, however we calculate the syndrome as if each
        //set bit is accessed in reverse order by subtracting the iterated bit position from (N-1).
        for(int i = message.nextSetBit(0); i >= 0 && i < mN; i = message.nextSetBit(i + 1))
        {
            for(j = 0; j < twoT; j += 2)
            {
                syndromes[j] ^= aPow((j + 1) * (nMinus1 - i));
            }
        }

        //Calculate the even syndromes as squaring of the odd syndromes: v(a^(2j)) = v(a^j)^2
        for(j = 0; j < mT; j++)
        {
            syndromes[2 * j + 1] = gfSqr(syndromes[j]);
        }

        return syndromes;
    }

    /**
     * Calculates the degree of the polynomial, represented as the most significant bit index
     *
     * @param poly to inspect
     * @return degree
     */
    public static int deg(int poly)
    {
        /* polynomial degree is the most-significant bit index */
        int highestSetBit = Integer.highestOneBit(poly);
        return Integer.numberOfTrailingZeros(highestSetBit);
        //return fls(poly)-1;  << why minus 1?
    }

    /**
     * Initializes the Galois field lookup tables.
     *
     * Modeled on bch.1 build_gf_tables() method.
     */
    public void initTables()
    {
        int i;
        int x = 1;
        int k = 1 << mM;

        a_pow_tab = new int[k];
        a_log_tab = new int[k];

        for(i = 0; i < k - 1; i++)
        {
            a_pow_tab[i] = x;
            a_log_tab[x] = i;
            x <<= 1;
            if((x & k) != 0)
            {
                x ^= mPrimitivePolynomial;
            }
        }
        a_pow_tab[k - 1] = 1;
        a_log_tab[0] = 0;

        buildDegree2Base();
    }

    public int aLog(int value)
    {
        return a_log_tab[value];
    }

    public int aILog(int x)
    {
        return modS(mN - a_log_tab[x]);
    }


    public int aPow(int value)
    {
        return a_pow_tab[modulo(value)];
    }

    public int modulo(int v)
    {
        while(v >= mN)
        {
            v -= mN;
            v = (v & mN) + (v >> mM);
        }

        return v;
    }

    public int modS(int v)
    {
        return (v < mN ? v : v - mN);
    }

    /**
     * Decodes the BCH protected message.
     * @param message where the BCH protected codeword is located at bit indices 0 to (N-1)
     */
    public void decode(CorrectedBinaryMessage message)
    {
        int[] syndromes = computeSyndromes(message);
        GFPoly elp = computeErrorLocatorPolynomial(syndromes);
        int elpDegree = elp.mDegree;
        int k = 1; //Recursive call argument
        int[] roots = findPolyRoots(elp, k);

        if(roots.length != elpDegree)
        {
            message.setCorrectedBitCount(MESSAGE_NOT_CORRECTED);
            return;
        }

        //Create a set from the roots to eliminate and then detect if there are duplicates
        Set<Integer> rootSet = new HashSet<>(Arrays.asList(ArrayUtils.toObject(roots)));

        if(rootSet.size() != roots.length)
        {
            message.setCorrectedBitCount(MESSAGE_NOT_CORRECTED);
            return;
        }

        //Invert the error roots because we process the message backwards/inverted, so the calculated roots then also
        //need to be un-inverted to reference the correct message indices.
        for(int x = 0; x < roots.length; x++)
        {
            roots[x] = mN - 1 - roots[x];
        }

        //Correct the errors in the original message.
        for(int error: roots)
        {
            message.flip(error);
        }

        //Set the number of errors corrected on the original message.
        message.setCorrectedBitCount(roots.length);
    }
}
