/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ApplicationIconTest
{
    private static final String PNG_RESOURCE = "/images/app/vce-app-icon.png";
    private static final String SVG_RESOURCE = "/images/app/vce-app-icon.svg";
    private static final String WORDMARK_RESOURCE = "/images/app/vce-wordmark.png";

    @Test
    void vceApplicationIconResourcesAreUsable() throws Exception
    {
        URL png = ApplicationIcon.class.getResource(PNG_RESOURCE);
        assertNotNull(png, PNG_RESOURCE);

        try(InputStream input = png.openStream())
        {
            BufferedImage image = ImageIO.read(input);
            assertNotNull(image, "VCE application icon PNG must be decodable");
            assertEquals(1024, image.getWidth());
            assertEquals(1024, image.getHeight());
            assertVisiblePixels(image, true);
        }

        URL svg = ApplicationIcon.class.getResource(SVG_RESOURCE);
        assertNotNull(svg, SVG_RESOURCE);

        try(InputStream input = svg.openStream())
        {
            String source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(source.contains("<svg"));
            assertTrue(source.contains("viewBox="));
            assertTrue(source.contains("<path"));
            assertFalse(source.contains("data:image"), "VCE application icon must remain a true vector source");
        }

        URL wordmark = ApplicationIcon.class.getResource(WORDMARK_RESOURCE);
        assertNotNull(wordmark, WORDMARK_RESOURCE);

        try(InputStream input = wordmark.openStream())
        {
            BufferedImage image = ImageIO.read(input);
            assertNotNull(image, "VCE wordmark PNG must be decodable");
            assertEquals(552, image.getWidth());
            assertEquals(168, image.getHeight());
            assertVisiblePixels(image, true);
        }

        BufferedImage favicon = ImageIO.read(Path.of("stats-web/assets/vce-icon-32.png").toFile());
        assertNotNull(favicon, "VCE 32px browser icon must be decodable");
        assertEquals(32, favicon.getWidth());
        assertEquals(32, favicon.getHeight());
        assertVisiblePixels(favicon, true);

        BufferedImage maskable = ImageIO.read(Path.of("stats-web/assets/vce-icon-maskable-512.png").toFile());
        assertNotNull(maskable, "VCE maskable browser icon must be decodable");
        assertEquals(512, maskable.getWidth());
        assertEquals(512, maskable.getHeight());
        assertVisiblePixels(maskable, false);
    }

    @Test
    void independentlyOwnedDesktopWindowsReceiveTheSharedIcon() throws Exception
    {
        String applicationIcon = source("src/main/java/io/github/dsheirer/gui/ApplicationIcon.java");
        String setupWizard = source("src/main/java/io/github/dsheirer/gui/setup/SetupWizard.java");
        String basebandRecording =
            source("src/main/java/io/github/dsheirer/gui/diagnostic/BasebandRecordingDialog.java");
        String recordingViewer =
            source("src/main/java/io/github/dsheirer/gui/viewer/MessageRecordingViewer.java");
        String migrationProgress =
            source("src/main/java/io/github/dsheirer/database/upgrade/ApplicationMigrationProgressDialog.java");
        String migrationSuccess =
            source("src/main/java/io/github/dsheirer/database/upgrade/ApplicationMigrationSuccessDialog.java");
        String copyableError = source("src/main/java/io/github/dsheirer/gui/CopyableErrorDialog.java");

        assertTrue(applicationIcon.contains("public static void apply(Window window)"));
        assertTrue(applicationIcon.contains("window.setIconImage(image)"));
        assertTrue(applicationIcon.contains("private static final String ICON_RESOURCE = \"" + PNG_RESOURCE + "\""));
        assertFalse(applicationIcon.contains("sdr-trunk-icon"));
        assertTrue(setupWizard.contains("ApplicationIcon.apply(this);"));
        assertTrue(setupWizard.contains("ApplicationIcon.applyTaskbarIcon();"));
        assertTrue(basebandRecording.contains("ApplicationIcon.apply(this);"));
        assertTrue(recordingViewer.contains("ApplicationIcon.applyTaskbarIcon();"));
        assertTrue(recordingViewer.contains("ApplicationIcon.apply(primaryStage);"));
        assertTrue(recordingViewer.contains("VCE - Message Recording Viewer (.bits)"));
        assertTrue(migrationProgress.contains("ApplicationIcon.apply(dialog);"));
        assertTrue(migrationProgress.contains("ApplicationIcon.applyTaskbarIcon();"));
        assertTrue(migrationSuccess.contains("ApplicationIcon.apply(dialog);"));
        assertTrue(migrationSuccess.contains("ApplicationIcon.applyTaskbarIcon();"));
        assertTrue(copyableError.contains("ApplicationIcon.apply(dialog);"));
        assertTrue(copyableError.contains("ApplicationIcon.applyTaskbarIcon();"));
    }

    private static String source(String path) throws Exception
    {
        return Files.readString(Path.of(path)).replace("\r\n", "\n");
    }

    private static void assertVisiblePixels(BufferedImage image, boolean expectTransparency)
    {
        long transparent = 0;
        long visible = 0;
        int minimumLuminance = 255;
        int maximumLuminance = 0;

        for(int y = 0; y < image.getHeight(); y++)
        {
            for(int x = 0; x < image.getWidth(); x++)
            {
                int argb = image.getRGB(x, y);
                int alpha = argb >>> 24;

                if(alpha == 0)
                {
                    transparent++;
                    continue;
                }

                visible++;
                int luminance = (((argb >>> 16) & 0xFF) * 2126 + ((argb >>> 8) & 0xFF) * 7152 +
                    (argb & 0xFF) * 722) / 10_000;
                minimumLuminance = Math.min(minimumLuminance, luminance);
                maximumLuminance = Math.max(maximumLuminance, luminance);
            }
        }

        if(transparent > 0)
        {
            minimumLuminance = 0;
        }

        assertTrue(visible > image.getWidth() * image.getHeight() / 20, "brand asset must contain visible pixels");
        assertTrue(maximumLuminance - minimumLuminance > 80, "brand asset must contain contrasting artwork");

        if(expectTransparency)
        {
            assertTrue(transparent > 0, "brand asset must retain transparent padding");
        }
    }
}
