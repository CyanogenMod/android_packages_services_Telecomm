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
import android.os.Looper;
import android.telecomm.CallServiceDescriptor;
import android.telecomm.TelecommConstants;

import com.android.internal.telecomm.ICallServiceLookupResponse;
import com.android.internal.telecomm.ICallServiceProvider;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Uses package manager to find all implementations of {@link ICallServiceProvider} and uses them to
 * get the corresponding list of call-service descriptor. Ultimately provides the up-to-date list of
 * call services as {@link CallServiceWrapper}s. The resulting call services may or may not be bound
 * at the time {@link Switchboard#setCallServices} is invoked.
 * TODO(santoscordon): Add performance timing to async calls.
 * TODO(santoscordon): Need to unbind/remove unused call services stored in the cache.
 */
final class CallServiceRepository {

    private final Switchboard mSwitchboard;

    private final OutgoingCallsManager mOutgoingCallsManager;

    private final IncomingCallsManager mIncomingCallsManager;

    /** Used to run code (e.g. messages, Runnables) on the main (UI) thread. */
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private final Runnable mTimeoutLookupTerminator = new Runnable() {
        @Override
        public void run() {
            Log.d(CallServiceRepository.this, "Timed out processing providers");
            terminateLookup();
        }
    };

    /**
     * The current lookup-cycle ID, unique per invocation of {@link #initiateLookup}.
     */
    private int mLookupId = -1;

    /**
     * Determines whether or not a lookup cycle is already running.
     */
    private boolean mIsLookupInProgress = false;

    /**
     * Stores the names of the providers to bind to in one lookup cycle. During lookup two things
     * can happen:
     *    - lookup can succeed, in this case this set will be empty at the end of the lookup.
     *    - lookup can timeout, in this case any outstanding providers will be discarded.
     */
    private final Set<ComponentName> mOutstandingProviders = Sets.newHashSet();

    /**
     * The map of call-service wrappers keyed by their ComponentName. This is passed back to the
     * switchboard once lookup is complete.
     */
    private final Map<ComponentName, CallServiceWrapper> mCallServices = Maps.newHashMap();

    /**
     * Persists the specified parameters.
     *
     * @param switchboard The switchboard.
     * @param outgoingCallsManager Manages the placing of outgoing calls.
     * @param incomingCallsManager Manages the incoming call initialization flow.
     */
    CallServiceRepository(
            Switchboard switchboard,
            OutgoingCallsManager outgoingCallsManager,
            IncomingCallsManager incomingCallsManager) {

        mSwitchboard = switchboard;
        mOutgoingCallsManager = outgoingCallsManager;
        mIncomingCallsManager = incomingCallsManager;
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
            Log.i(this, "No ICallServiceProvider implementations found, bailing out.");
            return;
        }

        mLookupId = lookupId;
        mIsLookupInProgress = true;

        mOutstandingProviders.clear();
        for (ComponentName name : providerNames) {
            mOutstandingProviders.add(name);
            lookupCallServices(name);
        }

        Log.i(this, "Found %d implementations of ICallServiceProvider.",
                mOutstandingProviders.size());

        // Schedule a timeout.
        mHandler.postDelayed(mTimeoutLookupTerminator, Timeouts.getProviderLookupMs());
    }

    /**
     * Creates the requested call service or pulls the previously-created entry from memory.
     *
     * @param descriptor The call service descriptor.
     * @return The corresponding call-service wrapper or null upon failure to retrieve it.
     */
    CallServiceWrapper getCallService(CallServiceDescriptor descriptor) {
        // Create the new call-service wrapper and update {@link #mCallServices}.
        registerCallService(descriptor);

        return mCallServices.get(descriptor.getServiceComponent());
    }

    /**
     * Iterates through the map of active services and removes the ones that are not associated
     * with active calls.
     * TODO(gilad): Invoke this from Switchboard upon resource deallocation cycles.
     */
    void purgeInactiveCallServices() {
        Iterator<ComponentName> iterator = mCallServices.keySet().iterator();
        while (iterator.hasNext()) {
            ComponentName callServiceName = iterator.next();
            CallServiceWrapper callService = mCallServices.get(callServiceName);

            // TODO(gilad): Either add ICallService.getActiveCallCount() or have this tracked by the
            // Switchboard if we rather not rely on 3rd-party code to do the bookkeeping for us. If
            // we prefer the latter, we can also have purgeInactiveCallService(descriptor). Otherwise
            // this might look something like:
            //
            // if (callService.getActiveCallCount() < 1) {
            //     mCallServices.remove(callServiceName);
            // }
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
        Intent intent = new Intent(TelecommConstants.ACTION_CALL_SERVICE_PROVIDER);
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
     * Attempts to obtain call-service descriptors from the specified provider (asynchronously) and
     * passes the list through to {@link #processCallServices}, which then relinquishes control back
     * to the switchboard.
     *
     * @param providerName The component name of the relevant provider.
     */
    private void lookupCallServices(final ComponentName providerName) {
        final CallServiceProviderWrapper provider = new CallServiceProviderWrapper(providerName);

        ICallServiceLookupResponse response = new ICallServiceLookupResponse.Stub() {
            @Override
            public void setCallServiceDescriptors(
                    final List<CallServiceDescriptor> callServiceDescriptors) {

                // TODO(santoscordon): Do we need Binder.clear/restoreCallingIdentity()?
                mHandler.post(new Runnable() {
                    @Override public void run() {
                        if (mIsLookupInProgress) {
                            processCallServices(provider, Sets.newHashSet(callServiceDescriptors));
                        }
                    }
                });
            }
        };

        Runnable errorCallback = new Runnable() {
            @Override public void run() {
                removeOutstandingProvider(providerName);
            }
        };

        provider.lookupCallServices(response, errorCallback);
    }

    /**
     * Creates {@link CallServiceWrapper}s from the given {@link CallServiceDescriptor}s.
     *
     * @param provider The provider associated with call services.
     * @param callServiceDescriptors The set of call service descriptors to process.
     */
    private void processCallServices(
            CallServiceProviderWrapper provider,
            Set<CallServiceDescriptor> callServiceDescriptors) {

        ThreadUtil.checkOnMainThread();

        Preconditions.checkNotNull(provider);
        Preconditions.checkNotNull(callServiceDescriptors);

        // The set of call-service descriptors is available, unbind the provider.
        provider.unbind();

        ComponentName providerName = provider.getComponentName();
        if (mOutstandingProviders.contains(providerName)) {
            // Add all the call services from this provider to the call-service cache.
            for (CallServiceDescriptor descriptor : callServiceDescriptors) {
                registerCallService(descriptor);
            }

            removeOutstandingProvider(providerName);
        } else {
            Log.i(this, "Unexpected call services from %s in lookup %s", providerName, mLookupId);
        }
    }

    /**
     * Creates a call-service wrapper from the given call-service descriptor if no cached instance
     * exists.
     *
     * @param descriptor The call-service descriptor.
     */
    private void registerCallService(CallServiceDescriptor descriptor) {
        Preconditions.checkNotNull(descriptor);

        // TODO(santoscordon): Rename getServiceComponent to getComponentName.
        ComponentName callServiceName = descriptor.getServiceComponent();

        CallServiceWrapper callService = mCallServices.get(callServiceName);
        if (callService == null) {
            CallServiceAdapter adapter =
                    new CallServiceAdapter(mOutgoingCallsManager, mIncomingCallsManager);
            mCallServices.put(callServiceName, new CallServiceWrapper(descriptor, adapter));
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

        if (mIsLookupInProgress) {
            mOutstandingProviders.remove(providerName);
            if (mOutstandingProviders.size() < 1) {
                terminateLookup();  // No other providers to wait for.
            }
        }
    }

    /**
     * Terminates the current lookup cycle, either due to a timeout or completed lookup.
     */
    private void terminateLookup() {
        mHandler.removeCallbacks(mTimeoutLookupTerminator);
        mOutstandingProviders.clear();

        updateSwitchboard();
        mIsLookupInProgress = false;
    }

    /**
     * Updates the switchboard with the call services from the latest lookup.
     */
    private void updateSwitchboard() {
        ThreadUtil.checkOnMainThread();

        Set<CallServiceWrapper> callServices = Sets.newHashSet(mCallServices.values());
        mSwitchboard.setCallServices(callServices);
    }
}
