/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class StatsRequestTest
{
    @Test
    void parsesOnlyNonNegativeDecimalIdentifiers()
    {
        StatsRequest request = StatsRequest.from(URI.create("/?zero=0&maximum=2147483647"));
        assertEquals(0, request.requiredIdentifier("zero"));
        assertEquals(Integer.MAX_VALUE, request.requiredIdentifier("maximum"));
        assertDoesNotThrow(request::requireFullyConsumed);

        assertInvalidParameter("identifier", () -> StatsRequest.from(
            URI.create("/?identifier=0x348")).requiredIdentifier("identifier"));
        assertInvalidParameter("identifier", () -> StatsRequest.from(
            URI.create("/?identifier=-1")).requiredIdentifier("identifier"));
        assertInvalidParameter("identifier", () -> StatsRequest.from(
            URI.create("/?identifier=%2B12")).requiredIdentifier("identifier"));
        assertInvalidParameter("identifier", () -> StatsRequest.from(
            URI.create("/?identifier=2147483648")).requiredIdentifier("identifier"));
    }

    @Test
    void rejectsDuplicateUnknownAndMalformedParameters()
    {
        assertInvalidParameter("limit", () -> StatsRequest.from(URI.create("/?limit=10&limit=20")));
        assertInvalidParameter("query", () -> StatsRequest.from(URI.create("/?q=dispatch&&limit=10")));
        assertInvalidParameter("q", () -> StatsRequest.from(URI.create("/?q=%C3%28")));

        StatsRequest request = StatsRequest.from(URI.create("/?limit=10&unexpected=true"));
        assertEquals(10, request.limit());
        StatsApiException exception = assertThrows(StatsApiException.class, request::requireFullyConsumed);
        assertAll(
            () -> assertEquals(400, exception.status()),
            () -> assertEquals("unknown_parameter", exception.code()),
            () -> assertEquals("unexpected", exception.field()),
            () -> assertEquals("unexpected is not supported", exception.getMessage()));
    }

    @Test
    void rejectsOverlongQueriesNamesValuesSearchesAndParameterCounts()
    {
        assertInvalidParameter("query", () -> StatsRequest.from(
            URI.create("/?q=" + "x".repeat(StatsRequest.MAX_QUERY_LENGTH))));
        assertInvalidParameter("query", () -> StatsRequest.from(
            URI.create("/?" + "n".repeat(StatsRequest.MAX_PARAMETER_NAME_LENGTH + 1) + "=1")));
        assertInvalidParameter("value", () -> StatsRequest.from(
            URI.create("/?value=" + "v".repeat(StatsRequest.MAX_PARAMETER_VALUE_LENGTH + 1))));
        assertInvalidParameter("q", () -> StatsRequest.from(
            URI.create("/?q=" + "s".repeat(StatsRequest.MAX_SEARCH_LENGTH + 1))).search());

        String parameters = IntStream.range(0, StatsRequest.MAX_PARAMETER_COUNT + 1)
            .mapToObj(index -> "p" + index + "=" + index)
            .collect(Collectors.joining("&"));
        assertInvalidParameter("query", () -> StatsRequest.from(URI.create("/?" + parameters)));
    }

    @Test
    void appliesStrictPagingCursorBooleanSearchAndSortRules()
    {
        StatsRequest defaults = StatsRequest.from(URI.create("/"));
        assertAll(
            () -> assertEquals(StatsRequest.DEFAULT_LIMIT, defaults.limit()),
            () -> assertEquals(0, defaults.offset()),
            () -> assertEquals(Long.MAX_VALUE, defaults.beforeId()),
            () -> assertTrue(defaults.booleanValue("include_history", true)),
            () -> assertNull(defaults.optionalBoolean("affiliated")),
            () -> assertEquals("last_seen", defaults.sort("last_seen")),
            () -> assertTrue(defaults.descending()));
        assertDoesNotThrow(defaults::requireFullyConsumed);

        StatsRequest bounds = StatsRequest.from(URI.create("/?limit=500&offset=100000&before_id=1" +
            "&include_history=false&affiliated=true&q=county%20system&sort=first_seen&direction=asc"));
        assertAll(
            () -> assertEquals(StatsRequest.MAX_LIMIT, bounds.limit()),
            () -> assertEquals(StatsRequest.MAX_OFFSET, bounds.offset()),
            () -> assertEquals(1, bounds.beforeId()),
            () -> assertFalse(bounds.booleanValue("include_history", true)),
            () -> assertEquals(Boolean.TRUE, bounds.optionalBoolean("affiliated")),
            () -> assertEquals("county system", bounds.search()),
            () -> assertEquals("first_seen", bounds.sort("last_seen")),
            () -> assertFalse(bounds.descending()));
        assertDoesNotThrow(bounds::requireFullyConsumed);

        assertInvalidParameter("limit", () -> StatsRequest.from(URI.create("/?limit=0")).limit());
        assertInvalidParameter("limit", () -> StatsRequest.from(URI.create("/?limit=501")).limit());
        assertInvalidParameter("limit", () -> StatsRequest.from(URI.create("/?limit=all")).limit());
        assertInvalidParameter("offset", () -> StatsRequest.from(URI.create("/?offset=-1")).offset());
        assertInvalidParameter("offset", () -> StatsRequest.from(URI.create("/?offset=100001")).offset());
        assertInvalidParameter("before_id", () -> StatsRequest.from(URI.create("/?before_id=0")).beforeId());
        assertInvalidParameter("enabled", () -> StatsRequest.from(
            URI.create("/?enabled=1")).booleanValue("enabled", false));
        assertInvalidParameter("affiliated", () -> StatsRequest.from(
            URI.create("/?affiliated=unknown")).optionalBoolean("affiliated"));
        assertInvalidParameter("sort", () -> StatsRequest.from(
            URI.create("/?sort=LastSeen")).sort("last_seen"));
        assertInvalidParameter("sort", () -> StatsRequest.from(
            URI.create("/?sort=last_seen%20desc")).sort("last_seen"));
        assertInvalidParameter("direction", () -> StatsRequest.from(
            URI.create("/?direction=sideways")).descending());
    }

    @Test
    void keepsPathIdentifiersOutOfTheQueryContract()
    {
        StatsRequest request = StatsRequest.from(URI.create("/?limit=20"))
            .withPathParameter("site_guid", 1234);
        assertEquals(1234, request.requiredIdentifier("site_guid"));
        assertEquals(20, request.limit());
        assertDoesNotThrow(request::requireFullyConsumed);

        assertInvalidParameter("site_guid", () -> StatsRequest.from(URI.create("/?site_guid=1234"))
            .withPathParameter("site_guid", 1234));
    }

    @Test
    void preflightsEndpointSpecificParametersWithoutMarkingThemConsumed()
    {
        StatsRequest accepted = StatsRequest.from(URI.create("/?limit=20&sort=name"));
        assertDoesNotThrow(() -> accepted.requireOnly("limit", "sort", "direction"));
        StatsApiException unconsumed = assertThrows(StatsApiException.class, accepted::requireFullyConsumed);
        assertEquals("limit", unconsumed.field());

        StatsRequest rejected = StatsRequest.from(URI.create("/?limit=20&surprise=true"));
        StatsApiException unknown = assertThrows(StatsApiException.class,
            () -> rejected.requireOnly("limit", "offset"));
        assertAll(
            () -> assertEquals(400, unknown.status()),
            () -> assertEquals("unknown_parameter", unknown.code()),
            () -> assertEquals("surprise", unknown.field()));
    }

    @Test
    void decodesPathSegmentsStrictlyWithoutFormEncodingRules()
    {
        assertEquals("scope+name", StatsRequest.decodePathSegment("scope+name"));
        assertEquals("scope:name", StatsRequest.decodePathSegment("scope%3Aname"));
        assertInvalidParameter("path", () -> StatsRequest.decodePathSegment("%C3%28"));
        assertInvalidParameter("path", () -> StatsRequest.decodePathSegment("line%0Abreak"));
    }

    private static void assertInvalidParameter(String field, org.junit.jupiter.api.function.Executable executable)
    {
        StatsApiException exception = assertThrows(StatsApiException.class, executable);
        assertAll(
            () -> assertEquals(400, exception.status()),
            () -> assertEquals("invalid_parameter", exception.code()),
            () -> assertEquals(field, exception.field()));
    }
}
