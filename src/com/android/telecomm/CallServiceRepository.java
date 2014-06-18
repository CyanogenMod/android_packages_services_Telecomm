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
import android.telecomm.CallServiceDescriptor;
import android.telecomm.TelecommConstants;

import com.android.internal.telecomm.ICallServiceLookupResponse;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import java.util.List;
import java.util.Set;

/**
 * Searches for and returns call services.
 */
class CallServiceRepository extends BaseRepository<CallServiceWrapper> {
    /**
     * The representation of a single lookup. Maintains lookup state and invokes the "complete"
     * callback when finished.
     */
    private final class CallServiceLookup {
        final Set<ComponentName> mOutstandingProviders = Sets.newHashSet();
        final Set<CallServiceWrapper> mServices = Sets.newHashSet();
        final LookupCallback<CallServiceWrapper> mCallback;

        CallServiceLookup(LookupCallback<CallServiceWrapper> callback) {
            mCallback = callback;
        }

        /** Starts the lookup. */
        void start() {
            List<ComponentName> providerNames = getProviderNames();
            if (providerNames.isEmpty()) {
                finishLookup();
                return;
            }

            for (ComponentName name : providerNames) {
                mOutstandingProviders.add(name);
                queryProviderForCallServices(name);
            }

            Log.i(this, "Found %d implementations of ICallServiceProvider.",
                    mOutstandingProviders.size());
        }

        /**
         * Returns the all-inclusive list of call-service-provider names.
         *
         * @return The list containing the (component) names of all known ICallServiceProvider
         *         implementations or the empty list upon no available providers.
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
         * Attempts to obtain call-service descriptors from the specified provider (asynchronously)
         * and passes the list through to {@link #processCallServices}, which then relinquishes
         * control back to the switchboard.
         *
         * @param providerName The component name of the relevant provider.
         */
        private void queryProviderForCallServices(final ComponentName providerName) {
            final CallServiceProviderWrapper provider = new CallServiceProviderWrapper(
                    providerName);

            ICallServiceLookupResponse response = new ICallServiceLookupResponse.Stub() {
                    @Override
                public void setCallServiceDescriptors(
                        final List<CallServiceDescriptor> callServiceDescriptors) {

                    mHandler.post(new Runnable() {
                            @Override
                        public void run() {
                            processCallServices(provider, Sets.newHashSet(callServiceDescriptors));
                        }
                    });
                }
            };

            Runnable errorCallback = new Runnable() {
                    @Override
                public void run() {
                    processCallServices(provider, null);
                }
            };

            provider.lookupCallServices(response, errorCallback);
        }

        /**
         * Processes the call-service descriptors provided by the specified provider.
         *
         * @param provider The call-service provider.
         * @param callServiceDescriptors The set of descriptors to process.
         */
        private void processCallServices(
                CallServiceProviderWrapper provider,
                Set<CallServiceDescriptor> callServiceDescriptors) {

            // Descriptor lookup finished, we no longer need the provider.
            provider.unbind();

            ComponentName providerName = provider.getComponentName();
            if (mOutstandingProviders.remove(providerName)) {
                if (callServiceDescriptors != null) {
                    // Add all the call services from this provider to the call-service cache.
                    for (CallServiceDescriptor descriptor : callServiceDescriptors) {
                        mServices.add(getService(descriptor.getServiceComponent(), descriptor));
                    }
                }

                if (mOutstandingProviders.isEmpty()) {
                    finishLookup();
                }
            } else {
                Log.i(this, "Unexpected call services from %s in lookup.", providerName);
            }
        }

        void finishLookup() {
            mCallback.onComplete(mServices);
        }
    }

    private final IncomingCallsManager mIncomingCallsManager;
    private final Handler mHandler = new Handler();

    /** Persists specified parameters. */
    CallServiceRepository(IncomingCallsManager incomingCallsManager) {
        mIncomingCallsManager = incomingCallsManager;
    }

    /**
     * Returns the call service implementation specified by the descriptor.
     *
     * @param descriptor The call-service descriptor.
     */
    CallServiceWrapper getService(CallServiceDescriptor descriptor) {
        return getService(descriptor.getServiceComponent(), descriptor);
    }

    /** {@inheritDoc} */
    @Override
    protected void onLookupServices(LookupCallback<CallServiceWrapper> callback) {
        new CallServiceLookup(callback).start();
    }

    @Override
    protected CallServiceWrapper onCreateNewServiceWrapper(ComponentName componentName,
            Object param) {
        return new CallServiceWrapper((CallServiceDescriptor) param, mIncomingCallsManager, this);
    }
}
