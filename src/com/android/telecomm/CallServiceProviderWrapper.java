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
import android.os.IBinder;
import android.os.RemoteException;
import android.telecomm.TelecommConstants;

import com.android.internal.telecomm.ICallServiceLookupResponse;
import com.android.internal.telecomm.ICallServiceProvider;

/**
 * Wrapper for {@link ICallServiceProvider}s, handles binding to {@link ICallServiceProvider} and
 * keeps track of when the object can safely be unbound. Other classes should not use
 * {@link ICallServiceProvider} directly and instead should use this class to invoke methods of
 * {@link ICallServiceProvider}.
 * TODO(santoscordon): Keep track of when the service can be safely unbound.
 */
final class CallServiceProviderWrapper extends ServiceBinder<ICallServiceProvider> {
    /**
     * The service action used to bind to ICallServiceProvider implementations.
     * TODO(santoscordon): Move this to TelecommConstants.
     */
    static final String CALL_SERVICE_PROVIDER_ACTION = ICallServiceProvider.class.getName();

    /** The actual service implementation. */
    private ICallServiceProvider mServiceInterface;

    private Binder mBinder = new Binder();

    /**
     * Creates a call-service provider for the specified component.
     *
     * @param componentName The component name of the service to bind to.
     */
    CallServiceProviderWrapper(ComponentName componentName) {
        super(TelecommConstants.ACTION_CALL_SERVICE_PROVIDER, componentName);
    }

    /**
     * initiates a call-service lookup cycle, see {@link ICallServiceProvider#lookupCallServices}.
     * Upon failure, the specified error callback is invoked.  Can be invoked even when the call
     * service is unbound.
     *
     * @param response The response object via which to return the relevant call-service
     *     implementations, if any.
     * @param errorCallback The callback to invoke upon failure.
     */
    void lookupCallServices(
            final ICallServiceLookupResponse response,
            final Runnable errorCallback) {

        BindCallback callback = new BindCallback() {
            @Override public void onSuccess() {
                if (isServiceValid("lookupCallServices")) {
                    try {
                        mServiceInterface.lookupCallServices(response);
                    } catch (RemoteException e) {
                        Log.e(CallServiceProviderWrapper.this, e, "Failed to lookupCallServices.");
                    }
                }
            }
            @Override public void onFailure() {
                errorCallback.run();
            }
        };

        mBinder.bind(callback);
    }

    /** {@inheritDoc} */
    @Override protected void setServiceInterface(IBinder binder) {
        mServiceInterface = ICallServiceProvider.Stub.asInterface(binder);
    }
}
