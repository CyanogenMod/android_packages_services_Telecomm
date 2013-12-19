/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.telecomm;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.telecomm.ICallService;
import android.telecomm.ICallServiceProvider;
import android.util.Log;

import com.android.telecomm.CallServiceProviderProxy.CallServiceProviderConnectionCallback;
import com.google.common.collect.Lists;

import java.util.List;

/**
 * Finds {@link ICallService} and {@link ICallServiceProvider} implementations on the device.
 * Uses binder APIs to find ICallServiceProviders and calls method on ICallServiceProvider to
 * find ICallService implementations.
 * TODO(santoscordon): Add performance timing to async calls.
 */
final class CallServiceFinder {
    /**
     * Implemented by classes which want to receive the final list of {@link CallService}s found.
     */
    interface CallServiceSearchCallback {
        /**
         * Method called after search has completed.
         *
         * @param callServices List of {@link ICallServices} found in the search.
         */
        public void onSearchComplete(List<ICallService> callServices);
    }

    /** Used to identify log entries by this class */
    static final String TAG = CallServiceFinder.class.getSimpleName();

    /** Private constructor to prevent instances being made. */
    private CallServiceFinder() {}

    /**
     * Asynchronously finds {@link ICallService} implementations and returns them asynchronously
     * through the callback parameter.
     *
     * @param searchCallback The callback executed when the search is complete.
     */
    public static void findCallServices(Context context,
            final CallServiceSearchCallback searchCallback) {
        List<ComponentName> components = getAllProviderComponents(context);

        Log.i(TAG, "Found " + components.size() + " implementations for ICallServiceProvider");

        for (ComponentName componentName : components) {
            CallServiceProviderProxy proxy = new CallServiceProviderProxy(componentName, context);
            CallServiceProviderConnectionCallback onProviderFoundCallback =
                    new CallServiceProviderConnectionCallback() {
                        @Override public void onConnected(ICallServiceProvider serviceProvider) {
                            onProviderFound(serviceProvider, searchCallback);
                        }
                    };

            proxy.connect(onProviderFoundCallback);
        }
    }

    /**
     * Called after a {@link CallServiceProviderProxy} attempts to bind to its
     * {@link ICallServiceProvider} counterpart. When this method is called, the proxy should
     * have either made a successful connection or an error occurred.
     *
     * @param serviceProvider The instance of ICallServiceProvider.
     */
    private static void onProviderFound(ICallServiceProvider serviceProvider,
            CallServiceSearchCallback searchCallback) {
        if (serviceProvider == null) {
            // TODO(santoscordon): Handle error.
        }

        Log.i(TAG, "Found a service Provider: " + serviceProvider);

        // TODO(santoscordon): asynchronously retrieve ICallService interfaces.
        // TODO(santoscordon): Filter the list by only those which the user has allowed.
    }

    private static List<ComponentName> getAllProviderComponents(Context context) {
        Intent serviceIntent = getICallServiceProviderIntent();

        PackageManager packageManager = context.getPackageManager();
        List<ResolveInfo> resolveInfos = packageManager.queryIntentServices(serviceIntent, 0);

        List<ComponentName> components = Lists.newArrayList();
        for (ResolveInfo resolveInfo : resolveInfos) {
            ServiceInfo serviceInfo = resolveInfo.serviceInfo;
            // Ignore anything that didn't resolve to a proper service.
            if (serviceInfo == null) {
                continue;
            }

            ComponentName componentName = new ComponentName(serviceInfo.packageName,
                    serviceInfo.name);
            components.add(componentName);
        }

        return components;
    }

    /**
     * Returns the intent used to resolve all registered {@link ICallService}s.
     */
    private static Intent getICallServiceProviderIntent() {
       return new Intent(ICallServiceProvider.class.getName());
    }
}
