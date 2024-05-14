package org.prebid.server.hooks.modules.fiftyone.devicedetection.v1.core;

import com.iab.openrtb.request.Device;
import org.prebid.server.hooks.modules.fiftyone.devicedetection.core.DeviceInfo;

@FunctionalInterface
public interface DevicePatch {
    boolean patch(Device.DeviceBuilder deviceBuilder, Device oldDevice, DeviceInfo newData);
}
