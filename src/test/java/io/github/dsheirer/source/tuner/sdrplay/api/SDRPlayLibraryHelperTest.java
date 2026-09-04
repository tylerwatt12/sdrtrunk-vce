package io.github.dsheirer.source.tuner.sdrplay.api;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;

class SDRPlayLibraryHelperTest
{
    @Test void expectedMissingLibraryProducesOneInformationalMessageWithoutStackTraceEvenAtDebugLevel()
    {
        // Initialize the helper before attaching the test appender; native loading does not open the API or hardware.
        assertNotNull(SDRPlayLibraryHelper.LOAD_STATE);
        Logger logger = (Logger)LoggerFactory.getLogger(SDRPlayLibraryHelper.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> messages = new ListAppender<>();
        messages.start();
        logger.addAppender(messages);
        logger.setLevel(Level.DEBUG);
        try
        {
            var result = SDRPlayLibraryHelper.load(Path.of("missing-sdrplay-api"), path -> false,
                new FakeLoader(missing(), null));
            assertEquals(SDRPlayLibraryHelper.LoadState.NOT_INSTALLED, result.state());
            assertFalse(result.fromPath());
            assertEquals(1, messages.list.size());
            assertEquals(Level.INFO, messages.list.getFirst().getLevel());
            assertNull(messages.list.getFirst().getThrowableProxy());
            assertTrue(messages.list.getFirst().getFormattedMessage().contains("only needed for SDRplay RSP"));
        }
        finally
        {
            logger.detachAppender(messages);
            messages.stop();
            logger.setLevel(previousLevel);
        }
    }

    @Test void loadingByNameNeedsNoFallback()
    {
        FakeLoader loader = new FakeLoader(null, null);
        var result = SDRPlayLibraryHelper.load(null, path -> false, loader);
        assertEquals(SDRPlayLibraryHelper.LoadState.AVAILABLE, result.state());
        assertFalse(result.fromPath());
        assertFalse(loader.pathAttempted);
    }

    @Test void loadingAnInstalledLibraryByPathIsSupported()
    {
        FakeLoader loader = new FakeLoader(missing(), null);
        var result = SDRPlayLibraryHelper.load(Path.of("installed-sdrplay-api"), path -> true, loader);
        assertEquals(SDRPlayLibraryHelper.LoadState.AVAILABLE, result.state());
        assertTrue(result.fromPath());
        assertTrue(loader.pathAttempted);
    }

    @Test void aFoundLibraryWithMissingDependenciesIsNotReportedAsNotInstalled()
    {
        var result = SDRPlayLibraryHelper.load(Path.of("installed-sdrplay-api"), path -> true,
            new FakeLoader(missing(), new UnsatisfiedLinkError("a dependent library is missing")));
        assertEquals(SDRPlayLibraryHelper.LoadState.LOAD_FAILED, result.state());
    }

    @Test void aLibraryFoundByNameWithTheWrongArchitectureIsNotReportedAsNotInstalled()
    {
        var result = SDRPlayLibraryHelper.load(null, path -> false,
            new FakeLoader(new UnsatisfiedLinkError("wrong architecture"), null));
        assertEquals(SDRPlayLibraryHelper.LoadState.LOAD_FAILED, result.state());
    }

    private static UnsatisfiedLinkError missing()
    {
        return new UnsatisfiedLinkError("no sdrplay_api in java.library.path: example-path");
    }

    private static class FakeLoader implements SDRPlayLibraryHelper.LibraryLoader
    {
        private final UnsatisfiedLinkError nameFailure;
        private final UnsatisfiedLinkError pathFailure;
        boolean pathAttempted;

        FakeLoader(UnsatisfiedLinkError nameFailure, UnsatisfiedLinkError pathFailure)
        {
            this.nameFailure = nameFailure;
            this.pathFailure = pathFailure;
        }
        @Override public void loadLibrary(String name) { if(nameFailure != null) throw nameFailure; }
        @Override public void load(String path)
        {
            pathAttempted = true;
            if(pathFailure != null) throw pathFailure;
        }
    }
}
