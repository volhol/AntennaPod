package de.danoeh.antennapod.upnp;

import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.Service;

public class MediaRenderer {

    private Device device;
    private Service avTransportService;
    private Service renderingControlService;

    private boolean canPause = false;

    public MediaRenderer(Device device) {
        this.device = device;

        // The dummy device 'local' does not have upnp services
        if (device == null) {
            return;
        }

        for (Service service : device.getServices()) {
            if (service.getServiceId().getId().contains("AVTransport")) {
                avTransportService = service;
                if (service.getAction("Pause") != null) {
                    canPause = true;
                }
            }
            else if (service.getServiceId().getId().contains("RenderingControl")) {
                renderingControlService = service;
            }
        }
    }

    public Device getDevice() {
        return device;
    }

    public Service getAvTransportService() {
        return avTransportService;
    }

    public Service getRenderingControlService() {
        return renderingControlService;
    }

    public boolean canPause() {
        return canPause;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MediaRenderer that = (MediaRenderer) o;
        return device.equals(that.device);
    }

    @Override
    public int hashCode() {
        return device.hashCode();
    }

    @Override
    public String toString() {
        if (device == null) {
            return "local";
        }

        String name =
                getDevice().getDetails() != null && getDevice().getDetails().getFriendlyName() != null
                        ? getDevice().getDetails().getFriendlyName()
                        : getDevice().getDisplayString();

        return name;
    }
}