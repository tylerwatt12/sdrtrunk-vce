/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */
package io.github.dsheirer.gui.configuration.radioreference;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dsheirer.rrapi.type.Country;
import java.util.List;
import org.junit.jupiter.api.Test;

class RadioReferenceEditorTest
{
    @Test
    void sortsCommonCountriesFirstAndAlphabetizesTheRemainder()
    {
        Country germany = country(6, "Germany", "DE");
        Country unitedKingdom = country(4, "United Kingdom", "GB");
        Country canada = country(2, "Canada", "CA");
        Country unitedStates = country(1, "United States", "US");
        Country brazil = country(5, "Brazil", "BR");
        Country australia = country(3, "Australia", "AU");

        assertEquals(List.of(unitedStates, canada, australia, unitedKingdom, brazil, germany),
            RadioReferenceEditor.sortedCountries(List.of(germany, unitedKingdom, canada, unitedStates, brazil,
                australia)));
    }

    private static Country country(int id, String name, String code)
    {
        Country country = new Country();
        country.setCountryId(id);
        country.setName(name);
        country.setCountryCode(code);
        return country;
    }
}
