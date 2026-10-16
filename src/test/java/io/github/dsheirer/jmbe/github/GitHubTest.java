package io.github.dsheirer.jmbe.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class GitHubTest
{
    @Test
    void optionalAudioBuildDoesNotReplaceTheStandardPublicRelease()
    {
        Release release = GitHub.parseResponse("""
            [
              {"tag_name":"v1.0.17-16khz.1","draft":false,"prerelease":true},
              {"tag_name":"v1.0.17","draft":false,"prerelease":false}
            ]
            """);

        assertEquals("v1.0.17", release.getTagName());
        assertEquals(Version.fromString("1.0.17"), release.getVersion());
    }

    @Test
    void selectsHighestStableVersionRegardlessOfReleaseOrder()
    {
        Release release = GitHub.parseResponse("""
            [
              {"tag_name":"v1.0.9","draft":false,"prerelease":false},
              {"tag_name":"v2.0.0","draft":false,"prerelease":true},
              {"tag_name":"v3.0.0","draft":true,"prerelease":false},
              {"tag_name":"v1.0.12a","draft":false,"prerelease":false},
              {"tag_name":"v1.0.12","draft":false,"prerelease":false}
            ]
            """);

        assertEquals(Version.fromString("1.0.12a"), release.getVersion());
        assertEquals("v1.0.12a", release.getTagName());
    }

    @Test
    void skipsUnrecognizedVersionsAndMalformedReleaseEntries()
    {
        Release release = GitHub.parseResponse("""
            [
              null,
              42,
              {"tag_name":null},
              {"tag_name":{"version":"v5.0.0"}},
              {"tag_name":"v4.0.0","prerelease":"false"},
              {"tag_name":"v3.0.0","draft":null},
              {"tag_name":"v2.0.0-beta"},
              {"tag_name":"v1.0.12"}
            ]
            """);

        assertEquals(Version.fromString("1.0.12"), release.getVersion());
    }

    @Test
    void returnsNoReleaseForFailedOrUnusableResponses()
    {
        assertNull(GitHub.parseResponse(null));
        assertNull(GitHub.parseResponse(""));
        assertNull(GitHub.parseResponse("<html>Service unavailable</html>"));
        assertNull(GitHub.parseResponse("[{\"tag_name\":\"v1.0.12\"}"));
        assertNull(GitHub.parseResponse("{\"message\":\"API rate limit exceeded\"}"));
        assertNull(GitHub.parseResponse("[]"));
        assertNull(GitHub.parseResponse("[{\"tag_name\":\"v2.0.0\",\"prerelease\":true}]"));
    }

    @Test
    void returnsNoReleaseForInvalidLookupLocations()
    {
        assertNull(GitHub.getLatestRelease(null));
        assertNull(GitHub.getLatestRelease("invalid repository URL"));
    }
}
