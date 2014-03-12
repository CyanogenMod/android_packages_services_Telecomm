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
public class CallServiceProviderWrapper extends ServiceBinder<ICallServiceProvider> {
    /** The actual service implementation. */
    private ICallServiceProvider mServiceInterface;

    /**
     * Creates a call-service provider for the specified component.
     *
     * @param componentName The component name of the service to bind to.
     * @param repository The call-service repository.
     */
    public CallServiceProviderWrapper(
            ComponentName componentName, CallServiceRepository repository) {

        super(TelecommConstants.ACTION_CALL_SERVICE_PROVIDER, componentName);
    }

    /**
     * See {@link ICallServiceProvider#lookupCallServices}.
     */
    public void lookupCallServices(ICallServiceLookupResponse response) {
        try {
            if (mServiceInterface == null) {
                Log.wtf(this, "lookupCallServices() invoked while the service is unbound.");
            } else {
                mServiceInterface.lookupCallServices(response);
            }
        } catch (RemoteException e) {
            Log.e(this, e, "Failed to lookupCallServices.");
        }
    }

    /** {@inheritDoc} */
    @Override protected void setServiceInterface(IBinder binder) {
        mServiceInterface = ICallServiceProvider.Stub.asInterface(binder);
    }
}
