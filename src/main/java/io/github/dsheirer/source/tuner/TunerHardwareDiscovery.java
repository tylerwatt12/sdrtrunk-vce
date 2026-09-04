package io.github.dsheirer.source.tuner;

import io.github.dsheirer.source.tuner.sdrplay.api.SDRplay;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.usb4java.Context;
import org.usb4java.Device;
import org.usb4java.DeviceDescriptor;
import org.usb4java.DeviceList;
import org.usb4java.LibUsb;

/** Descriptor-only inventory. Never creates a tuner/controller, selects an RSP, or acquires IQ. */
public final class TunerHardwareDiscovery
{
    public record Detected(String model, String identity) {}
    public record Inventory(List<Detected> devices, List<String> errors)
    {
        public Inventory { devices = List.copyOf(devices); errors = List.copyOf(errors); }
    }

    public Inventory scan(BooleanSupplier cancelled)
    {
        List<Detected> devices = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        if(!cancelled.getAsBoolean()) scanUsb(devices, errors, cancelled);
        if(!cancelled.getAsBoolean()) scanSdrplay(devices, errors);
        return new Inventory(devices, errors);
    }

    private void scanUsb(List<Detected> devices, List<String> errors, BooleanSupplier cancelled)
    {
        Context context = new Context();
        boolean initialized = false;
        try
        {
            int status = LibUsb.init(context);
            if(status != LibUsb.SUCCESS)
            {
                errors.add("USB enumeration unavailable (status " + status + "). Check USB driver installation.");
                return;
            }
            initialized = true;
            DeviceList list = new DeviceList();
            long count = LibUsb.getDeviceList(context, list);
            if(count < 0) { errors.add("Unable to enumerate USB devices (status " + count + ")."); return; }
            try
            {
                for(Device device: list)
                {
                    if(cancelled.getAsBoolean()) break;
                    DeviceDescriptor descriptor = new DeviceDescriptor();
                    if(LibUsb.getDeviceDescriptor(device, descriptor) != LibUsb.SUCCESS)
                    {
                        errors.add("A USB device descriptor could not be read.");
                        continue;
                    }
                    TunerClass type = TunerClass.lookup(descriptor.idVendor(), descriptor.idProduct());
                    if(type.isSupportedUsbTuner()) devices.add(new Detected(type.toString(),
                        "USB bus " + LibUsb.getBusNumber(device) + ", address " + LibUsb.getDeviceAddress(device) +
                        " · " + String.format("%04x:%04x", descriptor.idVendor() & 0xffff, descriptor.idProduct() & 0xffff)));
                }
            }
            finally { LibUsb.freeDeviceList(list, true); }
        }
        catch(Exception | LinkageError e) { errors.add("USB discovery unavailable. Check native library and driver installation."); }
        finally { if(initialized) LibUsb.exit(context); }
    }

    private void scanSdrplay(List<Detected> devices, List<String> errors)
    {
        SDRplay api = null;
        try
        {
            api = new SDRplay();
            if(!api.isAvailable()) { errors.add("SDRplay API unavailable. Install its driver if you use an RSP."); return; }
            for(var info: api.getDeviceInfos()) devices.add(new Detected(info.getDeviceType().toString(), info.getSerialNumber()));
        }
        catch(Exception | LinkageError e) { errors.add("SDRplay discovery unavailable. Check the API service and driver."); }
        finally { if(api != null) api.close(); }
    }
}
