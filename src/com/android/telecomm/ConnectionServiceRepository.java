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
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.telecomm.TelecommConstants;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;

/**
 * Searches for and returns connection services.
 */
final class ConnectionServiceRepository
        implements ServiceBinder.Listener<ConnectionServiceWrapper> {
    private final HashMap<ComponentName, ConnectionServiceWrapper> mServiceCache =
            new HashMap<ComponentName, ConnectionServiceWrapper>();

    ConnectionServiceRepository() {
    }

    Collection<ConnectionServiceWrapper> lookupServices() {
        PackageManager packageManager = TelecommApp.getInstance().getPackageManager();
        Intent intent = new Intent(TelecommConstants.ACTION_CONNECTION_SERVICE);
        ArrayList<ConnectionServiceWrapper> services = new ArrayList<>();

        for (ResolveInfo entry : packageManager.queryIntentServices(intent, 0)) {
            ServiceInfo serviceInfo = entry.serviceInfo;
            if (serviceInfo != null) {
                services.add(getService(new ComponentName(
                        serviceInfo.packageName, serviceInfo.name)));
            }
        }
        return services;
    }

    ConnectionServiceWrapper getService(ComponentName componentName) {
        ConnectionServiceWrapper service = mServiceCache.get(componentName);
        if (service == null) {
            service = new ConnectionServiceWrapper(componentName, this);
            service.addListener(this);
            mServiceCache.put(componentName, service);
        }
        return service;
    }

    /**
     * Removes the specified service from the cache when the service unbinds.
     *
     * {@inheritDoc}
     */
    @Override
    public void onUnbind(ConnectionServiceWrapper service) {
        mServiceCache.remove(service.getComponentName());
    }
}
