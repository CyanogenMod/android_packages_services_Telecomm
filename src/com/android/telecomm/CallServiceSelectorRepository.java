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
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.telecomm.TelecommConstants;

import com.android.internal.telecomm.ICallServiceSelector;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import java.util.List;
import java.util.Map;

/**
 * Helper class to retrieve {@link ICallServiceSelector} implementations on the device and
 * asynchronously bind to them.
 */
final class CallServiceSelectorRepository extends BaseRepository<CallServiceSelectorWrapper> {

    /** {@inheritDoc} */
    @Override
    protected void onLookupServices(LookupCallback<CallServiceSelectorWrapper> callback) {
        ThreadUtil.checkOnMainThread();

        List<ComponentName> selectorNames = getSelectorNames();
        List<CallServiceSelectorWrapper> foundSelectors = Lists.newLinkedList();

        // Register any new selectors.
        for (ComponentName name : selectorNames) {
            CallServiceSelectorWrapper selector = getService(name, null);

            if (TelephonyUtil.isTelephonySelector(selector)) {
                // Add telephony selectors to the end to serve as a fallback.
                foundSelectors.add(selector);
            } else {
                // TODO(sail): Need a way to order selectors.
                foundSelectors.add(0, selector);
            }
        }
        Log.i(this, "Found %d implementations of ICallServiceSelector", selectorNames.size());
        callback.onComplete(foundSelectors);
    }

    /** {@inheritDoc} */
    @Override
    protected CallServiceSelectorWrapper onCreateNewServiceWrapper(
            ComponentName componentName, Object param) {

        return new CallServiceSelectorWrapper(componentName, CallsManager.getInstance());
    }

    /**
     * Returns the list containing the (component) names of all known ICallServiceSelector
     * implementations or the empty list upon no available selectors.
     */
    private List<ComponentName> getSelectorNames() {
        // The list of selector names to return to the caller, may be populated below.
        List<ComponentName> selectorNames = Lists.newArrayList();

        PackageManager packageManager = TelecommApp.getInstance().getPackageManager();
        Intent intent = new Intent(TelecommConstants.ACTION_CALL_SERVICE_SELECTOR);
        for (ResolveInfo entry : packageManager.queryIntentServices(intent, 0)) {
            ServiceInfo serviceInfo = entry.serviceInfo;
            if (serviceInfo != null) {
                // The entry resolves to a proper service, add it to the list of selector names.
                ComponentName componentName =
                        new ComponentName(serviceInfo.packageName, serviceInfo.name);
                selectorNames.add(componentName);
            }
        }

        return selectorNames;
    }
}
