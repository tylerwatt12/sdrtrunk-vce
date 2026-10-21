/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.sample.complex;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class ComplexSerializationTest
{
    // Serialized by the pre-cleanup Complex implementation at 4b2ad36fc.
    private static final String LEGACY_SAMPLE =
        "rO0ABXNyAClpby5naXRodWIuZHNoZWlyZXIuc2FtcGxlLmNvbXBsZXguQ29tcGxleAAAAAAAAAABAgACRgAFbUxlZnRGAAZtUmlnaHR4cD+gAADAIAAA";

    @Test
    void readsAndWritesThePreCleanupSerializedFormat() throws Exception
    {
        byte[] legacy = Base64.getDecoder().decode(LEGACY_SAMPLE);
        Complex sample;
        try(ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(legacy)))
        {
            sample = assertInstanceOf(Complex.class, input.readObject());
        }

        assertEquals(1L, ObjectStreamClass.lookup(Complex.class).getSerialVersionUID());
        assertEquals(1.25f, sample.inphase());
        assertEquals(-2.5f, sample.quadrature());

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try(ObjectOutputStream output = new ObjectOutputStream(bytes))
        {
            output.writeObject(sample);
        }
        assertArrayEquals(legacy, bytes.toByteArray());
    }
}
