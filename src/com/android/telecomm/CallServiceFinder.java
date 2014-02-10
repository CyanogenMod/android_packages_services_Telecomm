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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.telecomm.ICallService;
import android.telecomm.ICallServiceLookupResponse;
import android.telecomm.ICallServiceProvider;
import android.util.Log;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds {@link ICallService} and {@link ICallServiceProvider} implementations on the device.
 * Uses binder APIs to find ICallServiceProviders and calls method on ICallServiceProvider to
 * find ICallService implementations.
 * TODO(santoscordon): Add performance timing to async calls.
 */
final class CallServiceFinder {

    private static final String TAG = CallServiceFinder.class.getSimpleName();

    /**
     * The longest period in milliseconds each lookup cycle is allowed to span over, see
     * {@link #mLookupTerminator}.
     * TODO(gilad): Likely requires tuning.
     */
    private static final int LOOKUP_TIMEOUT_MS = 100;

    private final Switchboard mSwitchboard;

    private final OutgoingCallsManager mOutgoingCallsManager;

    /**
     * Determines whether or not a lookup cycle is already running.
     */
    private boolean mIsLookupInProgress = false;

    /**
     * The current lookup-cycle ID. Incremented upon initiateLookup calls.
     * TODO(gilad): If at all useful, consider porting the cycle ID concept to switchboard and
     * have it centralized/shared between the two finders.
     */
    private int mLookupId = 0;

    /**
     * The set of bound call-services. Only populated via initiateLookup scenarios. Entries should
     * only be removed upon unbinding.
     * TODO(gilad): Add the necessary logic to keep this set up to date.
     */
    private Set<ICallService> mCallServiceRegistry = Sets.newHashSet();

    /**
     * The set of bound call-service providers.  Only populated via initiateLookup scenarios.
     * Providers should only be removed upon unbinding.
     * TODO(santoscordon): This can be removed once this class starts using CallServiceWrapper
     * since we'll be able to unbind the providers within registerProvider().
     */
    private Set<CallServiceProviderWrapper> mProviderRegistry = Sets.newHashSet();

    /**
     * Map of {@link CallServiceProviderWrapper}s keyed by their ComponentName. Used as a long-lived
     * cache in order to simplify management of service-wrapper construction/destruction.
     */
    private Map<ComponentName, CallServiceProviderWrapper> mProviderCache = Maps.newHashMap();

    /**
     * Stores the names of the providers to bind to in one lookup cycle.  The set size represents
     * the number of call-service providers this finder expects to hear back from upon initiating
     * call-service lookups, see initiateLookup. Whenever all providers respond before the lookup
     * timeout occurs, the complete set of (available) call services is passed to the switchboard
     * for further processing of outgoing calls etc.  When the timeout occurs before all responses
     * are received, the partial (potentially empty) set gets passed (to the switchboard) instead.
     * Entries are removed from this set as providers register.
     */
    private Set<ComponentName> mUnregisteredProviders;

    /**
     * Used to interrupt lookup cycles that didn't terminate naturally within the allowed
     * period, see LOOKUP_TIMEOUT.
     */
    private final Runnable mLookupTerminator = new Runnable() {
        @Override
        public void run() {
            terminateLookup();
        }
    };

    /** Used to run code (e.g. messages, Runnables) on the main (UI) thread. */
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    /**
     * Persists the specified parameters.
     *
     * @param switchboard The switchboard for this finder to work against.
     * @param outgoingCallsManager Manager in charge of placing outgoing calls.
     */
    CallServiceFinder(Switchboard switchboard, OutgoingCallsManager outgoingCallsManager) {
        mSwitchboard = switchboard;
        mOutgoingCallsManager = outgoingCallsManager;
    }

    /**
     * Initiates a lookup cycle for call-service providers. Must be called from the UI thread.
     * TODO(gilad): Expand this comment to describe the lookup flow in more detail.
     */
    void initiateLookup() {
        ThreadUtil.checkOnMainThread();
        if (mIsLookupInProgress) {
            // At most one active lookup is allowed at any given time, bail out.
            return;
        }

        List<ComponentName> providerNames = getProviderNames();
        if (providerNames.isEmpty()) {
            Log.i(TAG, "No ICallServiceProvider implementations found.");
            updateSwitchboard();
            return;
        }

        mLookupId++;
        mIsLookupInProgress = true;
        mUnregisteredProviders = Sets.newHashSet();

        for (ComponentName name : providerNames) {
            // Bind to each of the providers that were found. Some of the providers may already be
            // bound, and in those cases the provider wrapper will still invoke registerProvider()
            // allowing us to treat bound and unbound providers the same.
            getProvider(name).bind();
            mUnregisteredProviders.add(name);
        }

        int providerCount = providerNames.size();
        int unregisteredProviderCount = mUnregisteredProviders.size();

        Log.i(TAG, "Found " + providerCount + " implementations of ICallServiceProvider, "
                + unregisteredProviderCount + " of which are not currently registered.");

        if (unregisteredProviderCount == 0) {
            // All known (provider) implementations are already registered, pass control
            // back to the switchboard.
            updateSwitchboard();
        } else {
            // Schedule a lookup terminator to run after LOOKUP_TIMEOUT_MS milliseconds.
            mHandler.removeCallbacks(mLookupTerminator);
            mHandler.postDelayed(mLookupTerminator, LOOKUP_TIMEOUT_MS);
        }
    }

    /**
     * Returns the all-inclusive list of call-service-provider names.
     *
     * @return The list containing the (component) names of all known ICallServiceProvider
     *     implementations or the empty list upon no available providers.
     */
    private List<ComponentName> getProviderNames() {
        // The list of provider names to return to the caller, may be populated below.
        List<ComponentName> providerNames = Lists.newArrayList();

        PackageManager packageManager = TelecommApp.getInstance().getPackageManager();
        Intent intent = new Intent(CallServiceProviderWrapper.CALL_SERVICE_PROVIDER_ACTION);
        for (ResolveInfo entry : packageManager.queryIntentServices(intent, 0)) {
            ServiceInfo serviceInfo = entry.serviceInfo;
            if (serviceInfo != null) {
                // The entry resolves to a proper service, add it to the list of provider names.
                providerNames.add(
                        new ComponentName(serviceInfo.packageName, serviceInfo.name));
            }
        }

        return providerNames;
    }

    /**
     * Queries the supplied provider asynchronously for its CallServices and passes the list through
     * to {@link #registerCallServices} which will relinquish control back to switchboard.
     *
     * @param providerName The component name of the relevant provider.
     * @param provider The provider object to register.
     */
    void registerProvider(
            final ComponentName providerName, final CallServiceProviderWrapper provider) {

        // Query the provider for {@link ICallService} implementations.
        provider.lookupCallServices(new ICallServiceLookupResponse.Stub() {
            @Override
            public void setCallServices(final List<IBinder> binderList) {
                // TODO(santoscordon): Do we need Binder.clear/restoreCallingIdentity()?
                mHandler.post(new Runnable() {
                    @Override public void run() {
                        Set<ICallService> callServices = Sets.newHashSet();
                        for (IBinder binder : binderList) {
                            callServices.add(ICallService.Stub.asInterface(binder));
                        }
                        registerCallServices(providerName, provider, callServices);
                    }
                });
            }
        });
    }

    /**
     * Registers the {@link CallService}s for the specified provider and performs the necessary
     * bookkeeping to potentially return control to the switchboard before the timeout for the
     * current lookup cycle.
     *
     * @param providerName The component name of the relevant provider.
     * @param provider The provider associated with callServices.
     * @param callServices The {@link CallService}s to register.
     */
    private void registerCallServices(
            ComponentName providerName,
            CallServiceProviderWrapper provider,
            Set<ICallService> callServices) {
        ThreadUtil.checkOnMainThread();

        if (mUnregisteredProviders.remove(providerName)) {
            mProviderRegistry.add(provider);

            // Add all the call services from this provider to the call-service registry.
            for (ICallService callService : callServices) {
                try {
                    CallServiceAdapter adapter = new CallServiceAdapter(mOutgoingCallsManager);
                    callService.setCallServiceAdapter(adapter);
                    mCallServiceRegistry.add(callService);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to set call-service adapter.");
                }
            }

            if (mUnregisteredProviders.size() < 1) {
                terminateLookup();  // No other providers to wait for.
            }
        } else {
            Log.i(TAG, "Unexpected list of call services in lookup " + mLookupId + " from " +
                    providerName);
        }
    }

    /**
     * Unregisters the specified provider.
     *
     * @param providerName The component name of the relevant provider.
     */
    void unregisterProvider(ComponentName providerName) {
        ThreadUtil.checkOnMainThread();
        mProviderRegistry.remove(providerName);
    }

    /**
     * Timeouts the current lookup cycle, see LookupTerminator.
     */
    private void terminateLookup() {
        mHandler.removeCallbacks(mLookupTerminator);
        mUnregisteredProviders.clear();

        updateSwitchboard();
        mIsLookupInProgress = false;
    }

    /**
     * Updates the switchboard passing the relevant call services (as opposed
     * to call-service providers).
     */
    private void updateSwitchboard() {
        ThreadUtil.checkOnMainThread();
        mSwitchboard.setCallServices(mCallServiceRegistry);
    }

    /**
     * Returns the call-service provider wrapper for the specified componentName. Creates a new one
     * if none is found in the cache.
     *
     * @param ComponentName The component name of the provider.
     */
    private CallServiceProviderWrapper getProvider(ComponentName componentName) {
        Preconditions.checkNotNull(componentName);

        CallServiceProviderWrapper provider = mProviderCache.get(componentName);
        if (provider == null) {
            provider = new CallServiceProviderWrapper(componentName, this);
            mProviderCache.put(componentName, provider);
        }

        return provider;
    }
}
