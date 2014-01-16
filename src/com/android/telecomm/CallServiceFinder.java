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
import android.content.ServiceConnection;
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
import com.google.common.collect.Sets;

import java.util.List;
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

    /**
     * Used to retrieve all known ICallServiceProvider implementations from the framework.
     * TODO(gilad): Move to a more logical place for this to be shared.
     */
    static final String CALL_SERVICE_PROVIDER_CLASS_NAME = ICallServiceProvider.class.getName();

    private final Switchboard mSwitchboard;

    /**
     * Determines whether or not a lookup cycle is already running.
     */
    private boolean mIsLookupInProgress = false;

    /**
     * Used to generate unique lookup-cycle identifiers. Incremented upon initiateLookup calls.
     * TODO(gilad): If at all useful, consider porting the cycle ID concept to switchboard and
     * have it centralized/shared between the two finders.
     */
    private int mNextLookupId = 0;

    /**
     * The set of bound call-services. Only populated via initiateLookup scenarios. Entries should
     * only be removed upon unbinding.
     * TODO(gilad): Add the necessary logic to keep this set up to date.
     */
    private Set<ICallService> mCallServiceRegistry = Sets.newHashSet();

    /**
     * The set of bound call-service providers.  Only populated via initiateLookup scenarios.
     * Providers should only be removed upon unbinding.
     */
    private Set<ICallServiceProvider> mProviderRegistry = Sets.newHashSet();

    /**
     * Stores the names of the providers to bind to in one lookup cycle.  The set size represents
     * the number of call-service providers this finder expects to hear back from upon initiating
     * call-service lookups, see initiateLookup. Whenever all providers respond before the lookup
     * timeout occurs, the complete set of (available) call services is passed to the switchboard
     * for further processing of outgoing calls etc.  When the timeout occurs before all responds
     * are received, the partial (potentially empty) set gets passed (to the switchboard) instead.
     * Cached providers do not require finding and hence are excluded from this set.  Entries are
     * removed from this set as providers register.
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
     * @param switchboard The switchboard for this finer to work against.
     */
    CallServiceFinder(Switchboard switchboard) {
        mSwitchboard = switchboard;
    }

    /**
     * Initiates a lookup cycle for call-service providers. Must be called from the UI thread.
     * TODO(gilad): Expand this comment to describe the lookup flow in more detail.
     *
     * @param context The relevant application context.
     */
    void initiateLookup(Context context) {
        ThreadUtil.checkOnMainThread();
        if (mIsLookupInProgress) {
            // At most one active lookup is allowed at any given time, bail out.
            return;
        }

        List<ComponentName> providerNames = getProviderNames(context);
        if (providerNames.isEmpty()) {
            Log.i(TAG, "No ICallServiceProvider implementations found.");
            updateSwitchboard();
            return;
        }

        mIsLookupInProgress = true;
        mUnregisteredProviders = Sets.newHashSet();

        int lookupId = mNextLookupId++;
        for (ComponentName name : providerNames) {
            if (!mProviderRegistry.contains(name)) {
                // The provider is either not yet registered or has been unregistered
                // due to unbinding etc.
                bindProvider(name, lookupId, context);
                mUnregisteredProviders.add(name);
            }
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
     * @param context The relevant/application context to query against.
     * @return The list containing the (component) names of all known ICallServiceProvider
     *     implementations or the empty list upon no available providers.
     */
    private List<ComponentName> getProviderNames(Context context) {
        // The list of provider names to return to the caller, may be populated below.
        List<ComponentName> providerNames = Lists.newArrayList();

        PackageManager packageManager = context.getPackageManager();
        Intent intent = new Intent(CALL_SERVICE_PROVIDER_CLASS_NAME);
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
     * Attempts to bind the specified provider and have it register upon successful binding.  Also
     * performs the necessary wiring to unregister the provider upon un-binding.
     *
     * @param providerName The component name of the relevant provider.
     * @param lookupId The lookup-cycle ID.
     * @param context The relevant application context.
     */
    private void bindProvider(
            final ComponentName providerName, final int lookupId, Context context) {

        Preconditions.checkNotNull(providerName);
        Preconditions.checkNotNull(context);

        Intent serviceIntent =
                new Intent(CALL_SERVICE_PROVIDER_CLASS_NAME).setComponent(providerName);
        Log.i(TAG, "Binding to ICallServiceProvider through " + serviceIntent);

        // Connection object for the service binding.
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName className, IBinder service) {
                ICallServiceProvider provider = ICallServiceProvider.Stub.asInterface(service);
                registerProvider(lookupId, providerName, provider);
            }

            @Override
            public void onServiceDisconnected(ComponentName className) {
                unregisterProvider(providerName);
            }
        };

        if (!context.bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)) {
            // TODO(santoscordon): Handle error.
        }
    }

    /**
     * Queries the supplied provider asynchronously for its CallServices and passes the list through
     * to {@link #registerCallServices} which will relinquish control back to switchboard.
     *
     * @param lookupId The lookup-cycle ID.
     * @param providerName The component name of the relevant provider.
     * @param provider The provider object to register.
     */
    private void registerProvider(
            final int lookupId,
            final ComponentName providerName,
            final ICallServiceProvider provider) {

        // Query the provider for {@link ICallService} implementations.
        try {
            provider.lookupCallServices(new ICallServiceLookupResponse.Stub() {
                @Override
                public void setCallServices(List<IBinder> binderList) {
                    List<ICallService> callServices = Lists.newArrayList();
                    for (IBinder binder : binderList) {
                        callServices.add(ICallService.Stub.asInterface(binder));
                    }
                    registerCallServices(lookupId, providerName, provider, callServices);
                }
            });
        } catch (RemoteException e) {
            Log.e(TAG, "Could not retrieve call services from: " + providerName);
        }
    }

    /**
     * Registers the {@link CallService}s for the specified provider and performs the necessary
     * bookkeeping to potentially return control to the switchboard before the timeout for the
     * current lookup cycle.
     * TODO(santoscordon): Consider replacing this method's use of synchronized with a Handler
     * queue.
     * TODO(gilad): Santos has some POC code to make synchronized (below) unnecessary, either
     * move to use that or remove this to-do.
     * TODO(gilad): callServices should probably be a set, consider refactoring.
     *
     * @param lookupId The lookup-cycle ID.
     * @param providerName The component name of the relevant provider.
     * @param provider The provider associated with callServices.
     * @param callServices The {@link CallService}s to register.
     */
    synchronized private void registerCallServices(
            int lookupId,
            ComponentName providerName,
            ICallServiceProvider provider,
            List<ICallService> callServices) {

        // TODO(santoscordon): When saving the call services into this class, also add code to
        // unregister (remove) the call services upon disconnect. Potentially use RemoteCallbackList.

        if (mUnregisteredProviders.remove(providerName)) {
            mProviderRegistry.add(provider);
            mCallServiceRegistry.addAll(callServices);

            // TODO(gilad): Introduce a map to retain the association between call services
            // and the corresponding provider such that mCallServiceRegistry can be updated
            // upon unregisterProvider calls.

            if (mUnregisteredProviders.size() < 1) {
                terminateLookup();  // No other providers to wait for.
            }
        } else {
            Log.i(TAG, "Received multiple lists of call services in lookup " + lookupId +
                    " from " + providerName);
        }
    }

    /**
     * Unregisters the specified provider.
     *
     * @param providerName The component name of the relevant provider.
     */
    private void unregisterProvider(ComponentName providerName) {
        mProviderRegistry.remove(providerName);
    }

    /**
     * Timeouts the current lookup cycle, see LookupTerminator.
     */
    private void terminateLookup() {
        mHandler.removeCallbacks(mLookupTerminator);

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
}
