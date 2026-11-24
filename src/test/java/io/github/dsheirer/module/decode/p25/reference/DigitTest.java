package io.github.dsheirer.module.decode.p25.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DigitTest
{
    private static final String[] ORDINARY =
        {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "#", "-", "HOOK FLASH", "PAUSE"};
    private static final String[] ESCAPED =
        {"A", "B", "C", "D", "-", "-", "-", "-", "-", "-", "-", "-", "-", "-", "-"};

    @Test
    void consumesNullPairsAtTheStartMiddleAndEnd()
    {
        assertEquals("503", Digit.decode(List.of(15, 15, 5, 0, 3)));
        assertEquals("503", Digit.decode(List.of(5, 15, 15, 0, 3)));
        assertEquals("503", Digit.decode(List.of(5, 0, 3, 15, 15)));
        assertEquals("503", Digit.decode(List.of(15, 15, 15, 15, 5, 0, 3)));
    }

    @Test
    void preservesOrdinaryAndEscapedValuesIncludingReservedPlaceholders()
    {
        for(int value = 0; value < ORDINARY.length; value++)
        {
            assertEquals(ORDINARY[value], Digit.decode(List.of(value)));
            assertEquals(ESCAPED[value], Digit.decode(List.of(15, value)));
            assertEquals(ORDINARY[value], Digit.decode(List.of(15, 15, value)));
        }
        assertEquals("?", Digit.decode(List.of(-1)));
        assertEquals("?", Digit.decode(List.of(16)));
        assertEquals("?", Digit.decode(List.of(15, -1)));
        assertEquals("?", Digit.decode(List.of(15, 15, 16)));
    }

    @Test
    void handlesOddAndEvenEscapeRunsWithoutCarryingStateBetweenNumbers()
    {
        assertEquals("", Digit.decode(List.of()));
        for(int count = 0; count <= 10; count++)
        {
            List<Integer> values = new ArrayList<>();
            for(int x = 0; x < count; x++)
            {
                values.add(15);
            }
            assertEquals("", Digit.decode(values));
            values.add(0);
            assertEquals(count % 2 == 0 ? "0" : "A", Digit.decode(values));
        }
        assertEquals("503", Digit.decode(List.of(5, 0, 3, 15)));
        assertEquals("0", Digit.decode(List.of(0)));
    }

    /** Null insertion must preserve complete digit/escaped tokens on either side. */
    @Test
    void nullPairsDoNotChangeAnyCombinationOfOrdinaryAndEscapedTokens()
    {
        for(int left = 0; left < 30; left++)
        {
            for(int right = 0; right < 30; right++)
            {
                List<Integer> values = new ArrayList<>(token(left));
                values.addAll(List.of(15, 15));
                values.addAll(token(right));
                assertEquals(text(left) + text(right), Digit.decode(values), values::toString);
            }
        }
    }

    private static List<Integer> token(int index)
    {
        return index < 15 ? List.of(index) : List.of(15, index - 15);
    }

    private static String text(int index)
    {
        return index < 15 ? ORDINARY[index] : ESCAPED[index - 15];
    }
}
