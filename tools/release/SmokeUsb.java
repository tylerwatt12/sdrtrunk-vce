/* Copyright (C) 2026. Distributed under the GNU General Public License, version 3 or later. */
import org.usb4java.Context;
import org.usb4java.DeviceList;
import org.usb4java.LibUsb;

/** Checks packaged JNI loading and USB discovery without opening or claiming any device. */
public class SmokeUsb
{
    public static void main(String[] args)
    {
        Context context = new Context();
        int initialized = LibUsb.init(context);
        if(initialized != LibUsb.SUCCESS)
        {
            throw new IllegalStateException("USB native initialization failed: " + initialized);
        }

        try
        {
            DeviceList devices = new DeviceList();
            int count = LibUsb.getDeviceList(context, devices);
            if(count < 0)
            {
                throw new IllegalStateException("USB device enumeration failed: " + count);
            }
            LibUsb.freeDeviceList(devices, true);
        }
        finally
        {
            LibUsb.exit(context);
        }
        System.out.println("USB native runtime ready");
    }
}
