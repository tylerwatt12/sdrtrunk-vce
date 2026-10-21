/*
 * Copyright (C) 2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.source.tuner.usb;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** The receiver uses low-level libusb, not the retired javax.usb service adapter. */
class UsbRuntimePackagingTest
{
    @Test
    void lowLevelUsbAndExceptionApiRemainWithoutServiceAdapter() throws Exception
    {
        ClassLoader loader = getClass().getClassLoader();
        assertNotNull(Class.forName("org.usb4java.LibUsb", false, loader));
        assertNotNull(Class.forName("javax.usb.UsbException", false, loader));
        assertThrows(ClassNotFoundException.class,
            () -> Class.forName("org.usb4java.javax.Services", false, loader));
        assertNull(loader.getResource("javax.usb.properties"));
    }

    @Test
    void retainsNativeLibrariesPreviouslyBroughtInByTheAdapter()
    {
        ClassLoader loader = getClass().getClassLoader();
        for(String platform: new String[]{"linux-x86", "linux-x86-64", "linux-arm", "linux-aarch64"})
        {
            assertNotNull(loader.getResource("org/usb4java/" + platform + "/libusb4java.so"), platform);
        }
        for(String platform: new String[]{"win32-x86", "win32-x86-64", "win32-aarch64"})
        {
            assertNotNull(loader.getResource("org/usb4java/" + platform + "/libusb4java.dll"), platform);
        }
        for(String platform: new String[]{"darwin-x86-64", "darwin-aarch64", "darwin-legacy-aarch64"})
        {
            assertNotNull(loader.getResource("org/usb4java/" + platform + "/libusb4java.dylib"), platform);
        }
    }
}
