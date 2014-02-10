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
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.telecomm.ICallServiceLookupResponse;
import android.telecomm.ICallServiceProvider;
import android.util.Log;

/**
 * Wrapper for {@link ICallServiceProvider}s, handles binding to {@link ICallServiceProvider} and
 * keeps track of when the object can safely be unbound. Other classes should not use
 * {@link ICallServiceProvider} directly and instead should use this class to invoke methods of
 * {@link ICallServiceProvider}.
 * TODO(santoscordon): Keep track of when the service can be safely unbound.
 * TODO(santoscordon): Look into combining with android.telecomm.CallServiceProvider.
 */
public class CallServiceProviderWrapper extends ServiceBinder<ICallServiceProvider> {
    /**
     * The service action used to bind to ICallServiceProvider implementations.
     * TODO(santoscordon): Move this to TelecommConstants.
     */
    static final String CALL_SERVICE_PROVIDER_ACTION = ICallServiceProvider.class.getName();

    private static final String TAG = CallServiceProviderWrapper.class.getSimpleName();

    /** The actual service implementation. */
    private ICallServiceProvider mServiceInterface;

    /**
     * The class to notify when the binding succeeds or fails.
     */
    private final CallServiceFinder mFinder;

    /**
     * Creates a call-service provider for the specified component.
     */
    public CallServiceProviderWrapper(ComponentName componentName, CallServiceFinder finder) {
        super(CALL_SERVICE_PROVIDER_ACTION, componentName);
        mFinder = finder;
    }

    /** {@inheritDoc} */
    @Override public void handleSuccessfulConnection(IBinder binder) {
        mServiceInterface = ICallServiceProvider.Stub.asInterface(binder);
        mFinder.registerProvider(getComponentName(), this);
    }

    /** {@inheritDoc} */
    @Override public void handleFailedConnection() {
        mFinder.unregisterProvider(getComponentName());
        // TODO(santoscordon): Notify CallServiceFinder.
    }

    /** {@inheritDoc} */
    @Override public void handleServiceDisconnected() {
        mServiceInterface = null;
        // TODO(santoscordon): fill in.
    }

    /** See {@link ICallServiceProvider#lookupCallServices}. */
    public void lookupCallServices(ICallServiceLookupResponse response) {
        try {
            if (mServiceInterface == null) {
                Log.wtf(TAG, "lookupCallServices() invoked while the service is unbound.");
            } else {
                mServiceInterface.lookupCallServices(response);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to lookupCallServices.", e);
        }
    }
}
