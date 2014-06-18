/*
 * Copyright 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.telecomm;

import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.telecomm.CallServiceDescriptor;

/**
 * Utilities to deal with the system telephony services. The system telephony services are treated
 * differently from 3rd party services in some situations (emergency calls, audio focus, etc...).
 */
public final class TelephonyUtil {
    private static final String TAG = TelephonyUtil.class.getSimpleName();

    private static final String TELEPHONY_PACKAGE_NAME =
            "com.android.phone";

    private static final String PSTN_CALL_SERVICE_CLASS_NAME =
            "com.android.services.telephony.PstnConnectionService";

    private TelephonyUtil() {}

    static boolean isPstnCallService(CallServiceDescriptor descriptor) {
        ComponentName componentName = descriptor.getServiceComponent();
        if (TELEPHONY_PACKAGE_NAME.equals(componentName.getPackageName())) {
            String className = componentName.getClassName();
            return PSTN_CALL_SERVICE_CLASS_NAME.equals(className);
        }

        return false;
    }

    /**
     * Returns whether or not the call is currently connected as a cellular call (through the
     * device's cellular radio).
     */
    static boolean isCurrentlyPSTNCall(Call call) {
        if (Log.DEBUG) {
            verifyCallServiceExists(PSTN_CALL_SERVICE_CLASS_NAME);
        }

        CallServiceWrapper callService = call.getCallService();
        if (callService == null) {
            return false;
        }
        return isPstnCallService(callService.getDescriptor());
    }

    private static void verifyCallServiceExists(String serviceName) {
        PackageManager packageManager = TelecommApp.getInstance().getPackageManager();
        try {
            ServiceInfo info = packageManager.getServiceInfo(
                    new ComponentName(TELEPHONY_PACKAGE_NAME, serviceName), 0);
            if (info == null) {
                Log.wtf(TAG, "Error, unable to find call service: %s", serviceName);
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.wtf(TAG, e, "Error, exception while trying to find call service: %s", serviceName);
        }
    }
}
