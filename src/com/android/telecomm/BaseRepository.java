/*
 * Copyright (C) 2014 The Android Open Source Project
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

import com.google.common.collect.Maps;

import java.util.Collection;
import java.util.Map;

/**
 * Looks up, caches and returns binder services. Ensures that there is only one instance of a
 * service per component.
 */
abstract class BaseRepository<ServiceClass extends ServiceBinder<?>>
        implements ServiceBinder.Listener<ServiceClass> {

    /**
     * Callback interface for notifying when a lookup has completed.
     */
    interface LookupCallback<ServiceClass> {
        void onComplete(Collection<ServiceClass> services);
    }

    /** Map of cached services. */
    private final Map<ComponentName, ServiceClass> mServiceCache = Maps.newHashMap();

    /**
     * Removes the specified service from the cache when the service unbinds.
     *
     * {@inheritDoc}
     */
    @Override
    public void onUnbind(ServiceClass service) {
        mServiceCache.remove(service.getComponentName());
    }

    /**
     * Looks up service implementations.
     *
     * @param callback The callback on which to return execution when complete.
     */
    final void lookupServices(final LookupCallback<ServiceClass> callback) {
        onLookupServices(callback);
    }

    /**
     * Performs the grunt work of the lookup, to be implemented by the subclass.
     *
     * @param callback The callback on which to return execution when complete.
     */
    protected abstract void onLookupServices(LookupCallback<ServiceClass> callback);

    /**
     * Creates a new service implementation for the specified component.
     *
     * @param componentName The component name for the service.
     * @param param Sub-class-provided parameter, see {@link #getService}.
     */
    protected abstract ServiceClass onCreateNewServiceWrapper(
            ComponentName componentName, Object param);

    /**
     * Returns a cached implementation of the service. If no cached version exists, makes a
     * request to the subclass for a new implementation which will then be cached and returned.
     *
     * @param componentName The component name for the service.
     * @param param Sub-class-provided parameter.
     */
    protected final ServiceClass getService(ComponentName componentName, Object param) {
        ServiceClass service = mServiceCache.get(componentName);
        if (service == null) {
            service = onCreateNewServiceWrapper(componentName, param);
            service.addListener(this);
            mServiceCache.put(componentName, service);
        }
        return service;
    }
}
