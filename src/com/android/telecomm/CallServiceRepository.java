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
import android.telecomm.CallServiceInfo;
import android.telecomm.ICallService;
import android.telecomm.ICallServiceLookupResponse;
import android.telecomm.ICallServiceProvider;
import android.util.Log;

import com.android.telecomm.ServiceBinder.BindCallback;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Uses package manager to find all implementations of {@link ICallServiceProvider} and uses them to
 * get a list of bindable {@link ICallServices}. Ultimately returns a list of call services as
 * {@link CallServiceWrapper}s. The resulting call services may or may not be bound at the end of the
 * lookup.
 * TODO(santoscordon): Add performance timing to async calls.
 * TODO(santoscordon): Need to unbind/remove unused call services stored in the cache.
 */
final class CallServiceRepository {

    private static final String TAG = CallServiceRepository.class.getSimpleName();

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
     * The current lookup-cycle ID, unique per invocation of {@link #initiateLookup}.
     */
    private int mLookupId = 0;

    /**
     * Map of {@link CallServiceProviderWrapper}s keyed by their ComponentName. Used as a cache for
     * call services. Entries are added to the cache as part of the lookup sequence. Every call
     * service found will have an entry in the cache. The cache is cleaned up periodically to
     * remove any call services (bound or unbound) which are no longer needed because they have no
     * associated calls. After a cleanup, the only entries in the cache should be call services
     * with existing calls. During the lookup, we will always use a cached version of a call service
     * if one exists.
     */
    private Map<ComponentName, CallServiceProviderWrapper> mProviderCache = Maps.newHashMap();

    /**
     * Map of {@link CallServiceWrapper}s keyed by their ComponentName. Used as a long-lived cache
     * in order to simplify management of service-wrapper construction/destruction.
     */
    private Map<ComponentName, CallServiceWrapper> mCallServiceCache = Maps.newHashMap();

    /**
     * Stores the names of the providers to bind to in one lookup cycle.  The set size represents
     * the number of call-service providers this repository expects to hear back from upon
     * initiating call-service lookups, see initiateLookup. Whenever all providers respond before
     * the lookup timeout occurs, the complete set of (available) call services is passed to the
     * switchboard for further processing of outgoing calls etc.  When the timeout occurs before all
     * responses are received, the partial (potentially empty) set gets passed (to the switchboard)
     * instead. Entries are removed from this set as providers are processed.
     */
    private Set<ComponentName> mOutstandingProviders;

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
     * @param switchboard The switchboard.
     * @param outgoingCallsManager Manager in charge of placing outgoing calls.
     */
    CallServiceRepository(Switchboard switchboard, OutgoingCallsManager outgoingCallsManager) {
        mSwitchboard = switchboard;
        mOutgoingCallsManager = outgoingCallsManager;
    }

    /**
     * Initiates a lookup cycle for call-service providers. Must be called from the UI thread.
     * TODO(gilad): Expand this comment to describe the lookup flow in more detail.
     *
     * @param lookupId The switchboard-supplied lookup ID.
     */
    void initiateLookup(int lookupId) {
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

        mLookupId = lookupId;
        mIsLookupInProgress = true;
        mOutstandingProviders = Sets.newHashSet();

        for (ComponentName name : providerNames) {
            mOutstandingProviders.add(name);
            bindProvider(name);
        }

        int providerCount = providerNames.size();
        int outstandingProviderCount = mOutstandingProviders.size();

        Log.i(TAG, "Found " + providerCount + " implementations of ICallServiceProvider, "
                + outstandingProviderCount + " of which are not currently processed.");

        if (outstandingProviderCount == 0) {
            // All known (provider) implementations are already processed, pass control
            // back to the switchboard.
            updateSwitchboard();
        } else {
            // Schedule a lookup terminator to run after LOOKUP_TIMEOUT_MS milliseconds.
            mHandler.removeCallbacks(mLookupTerminator);
            mHandler.postDelayed(mLookupTerminator, LOOKUP_TIMEOUT_MS);
        }
    }

    /**
     * Creates and returns the call service for the specified {@link CallServiceInfo}. Inserts newly
     * created entries into the cache, see {@link #mCallServiceCache}, or if a cached version
     * already exists, returns that instead. All newly created instances will not yet be bound,
     * however cached versions may or may not be bound.
     *
     * @param info The call service descriptor.
     * @return The call service.
     */
    CallServiceWrapper getCallService(CallServiceInfo info) {
        Preconditions.checkNotNull(info);

        // TODO(santoscordon): Rename getServiceComponent to getComponentName.
        ComponentName componentName = info.getServiceComponent();

        CallServiceWrapper callService = mCallServiceCache.get(componentName);
        if (callService == null) {
            CallServiceAdapter adapter = new CallServiceAdapter(mOutgoingCallsManager);
            callService = new CallServiceWrapper(info, adapter);
            mCallServiceCache.put(componentName, callService);
        }

        return callService;
    }

    /**
     * Attempts to bind to the specified provider before continuing to {@link #processProvider}.
     *
     * @param componentName The component name of the relevant provider.
     */
    private void bindProvider(final ComponentName componentName) {
        final CallServiceProviderWrapper provider = getProvider(componentName);

        BindCallback callback = new BindCallback() {
            @Override public void onSuccess() {
                processProvider(componentName, provider);
            }
            @Override public void onFailure() {
                abortProvider(componentName);
            }
        };

        // Some of the providers may already be bound, and in those cases the provider wrapper will
        // still invoke BindCallback.onSuccess() allowing us to treat bound and unbound providers
        // the same way.
        provider.bind(callback);
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
     * to {@link #processCallServices} which will relinquish control back to switchboard.
     *
     * @param providerName The component name of the relevant provider.
     * @param provider The provider object to process.
     */
    private void processProvider(
            final ComponentName providerName, final CallServiceProviderWrapper provider) {
        Preconditions.checkNotNull(providerName);
        Preconditions.checkNotNull(provider);

        // Query the provider for {@link ICallService} implementations.
        provider.lookupCallServices(new ICallServiceLookupResponse.Stub() {
            // TODO(santoscordon): Rename CallServiceInfo to CallServiceDescriptor and update
            // this method name to setCallServiceDescriptors.
            @Override
            public void setCallServices(final List<CallServiceInfo> callServiceInfos) {
                // TODO(santoscordon): Do we need Binder.clear/restoreCallingIdentity()?
                mHandler.post(new Runnable() {
                    @Override public void run() {
                        processCallServices(
                                providerName, provider, Sets.newHashSet(callServiceInfos));
                    }
                });
            }
        });
    }

    /**
     * Skips the processing of a provider. Called in cases where the provider was not found or
     * not connected.
     *
     * @param providerName The component name of the relevant provider.
     */
    private void abortProvider(ComponentName providerName) {
        Preconditions.checkNotNull(providerName);
        removeOutstandingProvider(providerName);
    }

    /**
     * Processes the {@link CallServiceInfo}s for the specified provider and performs the necessary
     * bookkeeping to potentially return control to the switchboard before the timeout for the
     * current lookup cycle.
     *
     * @param providerName The component name of the relevant provider.
     * @param provider The provider associated with callServices.
     * @param callServiceInfos The set of call service infos to process.
     */
    private void processCallServices(
            ComponentName providerName,
            CallServiceProviderWrapper provider,
            Set<CallServiceInfo> callServiceInfos) {

        Preconditions.checkNotNull(provider);
        Preconditions.checkNotNull(callServiceInfos);
        ThreadUtil.checkOnMainThread();

        // We only need the provider for retrieving the call-service info set, so unbind here.
        provider.unbind();

        if (mOutstandingProviders.contains(providerName)) {
            // Add all the call services from this provider to the call-service cache.
            for (CallServiceInfo info : callServiceInfos) {
                getCallService(info);
            }

            removeOutstandingProvider(providerName);
        } else {
            Log.i(TAG, "Unexpected list of call services in lookup " + mLookupId + " from " +
                    providerName);
        }
    }

    /**
     * Removes an entry from the set of outstanding providers. When the final outstanding
     * provider is removed, terminates the lookup.
     *
     * @param providerName The component name of the relevant provider.
     */
    private void removeOutstandingProvider(ComponentName providerName) {
        ThreadUtil.checkOnMainThread();

        mOutstandingProviders.remove(providerName);
        if (mOutstandingProviders.size() < 1) {
            terminateLookup();  // No other providers to wait for.
        }
    }

    /**
     * Timeouts the current lookup cycle, see LookupTerminator.
     */
    private void terminateLookup() {
        mHandler.removeCallbacks(mLookupTerminator);
        mOutstandingProviders.clear();

        updateSwitchboard();
        mIsLookupInProgress = false;
    }

    /**
     * Updates the switchboard with the call services from the latest lookup.
     */
    private void updateSwitchboard() {
        ThreadUtil.checkOnMainThread();

        Set<CallServiceWrapper> callServices = Sets.newHashSet(mCallServiceCache.values());
        mSwitchboard.setCallServices(callServices);
    }

    /**
     * Returns the call-service provider wrapper for the specified componentName. Creates a new one
     * if none is found in the cache.
     *
     * @param componentName The component name of the provider.
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
