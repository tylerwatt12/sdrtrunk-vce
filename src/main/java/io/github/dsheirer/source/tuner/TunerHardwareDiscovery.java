package io.github.dsheirer.source.tuner;

import io.github.dsheirer.source.tuner.sdrplay.api.SDRplay;
import io.github.dsheirer.source.tuner.sdrplay.api.SDRPlayLibraryHelper;
import io.github.dsheirer.source.tuner.sdrplay.api.Status;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.usb4java.Context;
import org.usb4java.Device;
import org.usb4java.DeviceDescriptor;
import org.usb4java.DeviceList;
import org.usb4java.LibUsb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Descriptor-only inventory. Never creates a tuner/controller, selects an RSP, or acquires IQ. */
public final class TunerHardwareDiscovery
{
    private static final Logger LOG = LoggerFactory.getLogger(TunerHardwareDiscovery.class);

    public record Detected(String model, String identity) {}
    public record Inventory(List<Detected> devices, List<String> errors, List<String> notices)
    {
        public Inventory
        {
            devices = List.copyOf(devices);
            errors = List.copyOf(errors);
            notices = List.copyOf(notices);
        }
    }

    @FunctionalInterface
    interface UsbDiscovery
    {
        void scan(List<Detected> devices, List<String> errors, BooleanSupplier cancelled);
    }

    interface SdrplaySession extends AutoCloseable
    {
        boolean isAvailable();
        List<Detected> devices() throws Exception;
        @Override void close() throws Exception;
    }

    interface SdrplayAccess
    {
        SDRPlayLibraryHelper.LoadState availability();
        SdrplaySession open() throws Exception;
    }

    private final UsbDiscovery usbDiscovery;
    private final SdrplayAccess sdrplayAccess;

    public TunerHardwareDiscovery()
    {
        this(TunerHardwareDiscovery::scanUsb, new SdrplayAccess()
        {
            @Override public SDRPlayLibraryHelper.LoadState availability() { return SDRPlayLibraryHelper.LOAD_STATE; }
            @Override public SdrplaySession open() throws Exception
            {
                SDRplay api = new SDRplay();
                return new SdrplaySession()
                {
                    @Override public boolean isAvailable() { return api.isAvailable(); }
                    @Override public List<Detected> devices() throws Exception
                    {
                        return api.getDeviceInfos().stream().map(info ->
                            new Detected(info.getDeviceType().toString(), info.getSerialNumber())).toList();
                    }
                    @Override public void close() throws Exception
                    {
                        if(api.close() == Status.FAIL) throw new IllegalStateException("SDRplay API close failed");
                    }
                };
            }
        });
    }

    /** Enumeration seams keep tests independent of drivers, radio ownership, and native libraries. */
    TunerHardwareDiscovery(UsbDiscovery usbDiscovery, SdrplayAccess sdrplayAccess)
    {
        this.usbDiscovery = usbDiscovery;
        this.sdrplayAccess = sdrplayAccess;
    }

    public Inventory scan(BooleanSupplier cancelled)
    {
        List<Detected> devices = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<String> notices = new ArrayList<>();
        if(!cancelled.getAsBoolean()) usbDiscovery.scan(devices, errors, cancelled);
        if(!cancelled.getAsBoolean()) scanSdrplay(devices, errors, notices, cancelled);
        return new Inventory(devices, errors, notices);
    }

    private static void scanUsb(List<Detected> devices, List<String> errors, BooleanSupplier cancelled)
    {
        Context context = new Context();
        boolean initialized = false;
        try
        {
            int status = LibUsb.init(context);
            if(status != LibUsb.SUCCESS)
            {
                errors.add("We couldn't check USB radios. Check their drivers, then choose Rescan.");
                LOG.warn("USB discovery initialization failed with status {}", status);
                return;
            }
            initialized = true;
            DeviceList list = new DeviceList();
            long count = LibUsb.getDeviceList(context, list);
            if(count < 0)
            {
                errors.add("We couldn't check USB radios. Reconnect your radio, then choose Rescan.");
                LOG.warn("USB device enumeration failed with status {}", count);
                return;
            }
            try
            {
                for(Device device: list)
                {
                    if(cancelled.getAsBoolean()) break;
                    DeviceDescriptor descriptor = new DeviceDescriptor();
                    if(LibUsb.getDeviceDescriptor(device, descriptor) != LibUsb.SUCCESS)
                    {
                        String message = "A connected USB device couldn't be identified. If your radio is missing, " +
                            "reconnect it and choose Rescan.";
                        if(!errors.contains(message)) errors.add(message);
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
        catch(Exception | LinkageError e)
        {
            errors.add("We couldn't check USB radios. Check their drivers, then choose Rescan. You can also continue setup.");
            LOG.warn("USB discovery failed", e);
        }
        finally { if(initialized) LibUsb.exit(context); }
    }

    private void scanSdrplay(List<Detected> devices, List<String> errors, List<String> notices, BooleanSupplier cancelled)
    {
        try
        {
            switch(sdrplayAccess.availability())
            {
                case NOT_INSTALLED ->
                {
                    notices.add("Using an SDRplay RSP radio? Install the SDRplay API software to detect it here. " +
                        "If you use another radio, you don't need this software and can continue.");
                    return;
                }
                case LOAD_FAILED ->
                {
                    errors.add("The SDRplay software couldn't load. Repair or reinstall the SDRplay API " +
                        "software, then restart sdrtrunk-vce. Other radios are not affected.");
                    return;
                }
                case AVAILABLE -> { }
            }
            if(cancelled.getAsBoolean()) return;
            try(SdrplaySession session = sdrplayAccess.open())
            {
                if(!session.isAvailable())
                {
                    errors.add("The installed SDRplay software isn't compatible with this version of sdrtrunk-vce. " +
                        "Update the SDRplay API software, then restart sdrtrunk-vce.");
                    return;
                }
                if(cancelled.getAsBoolean()) return;
                List<Detected> found = session.devices();
                if(!cancelled.getAsBoolean()) devices.addAll(found);
            }
        }
        catch(Exception | LinkageError e)
        {
            errors.add("We couldn't check SDRplay radios. Make sure the SDRplay API service is running, then choose " +
                "Rescan. You can still continue setup or use other radios.");
            LOG.warn("SDRplay discovery failed", e);
        }
    }
}
