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
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.telecomm.CallServiceDescriptor;
import android.telecomm.TelecommConstants;

import com.android.internal.telecomm.ICallServiceLookupResponse;
import com.android.internal.telecomm.ICallServiceProvider;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Wrapper for {@link ICallServiceProvider}s, handles binding to {@link ICallServiceProvider} and
 * keeps track of when the object can safely be unbound. Other classes should not use
 * {@link ICallServiceProvider} directly and instead should use this class to invoke methods of
 * {@link ICallServiceProvider}.
 */
final class CallServiceProviderWrapper extends ServiceBinder<ICallServiceProvider> {
    interface LookupResponse {
        void setCallServiceDescriptors(List<CallServiceDescriptor> descriptors);
    }

    private class LookupResponseWrapper extends ICallServiceLookupResponse.Stub {
        private final LookupResponse mResponse;

        LookupResponseWrapper(LookupResponse response) {
            mResponse = response;
        }

        @Override
        public void setCallServiceDescriptors(final List<CallServiceDescriptor> descriptors) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (mLookupResponses.remove(mResponse)) {
                        mResponse.setCallServiceDescriptors(descriptors);
                    }
                }
            });
        }
    };

    /** The actual service implementation. */
    private ICallServiceProvider mServiceInterface;
    private final Binder mBinder = new Binder();
    private Set<LookupResponse> mLookupResponses = new HashSet<LookupResponse>();
    private final Handler mHandler = new Handler();

    /**
     * Creates a call-service provider for the specified component.
     *
     * @param componentName The component name of the service to bind to.
     */
    CallServiceProviderWrapper(ComponentName componentName) {
        super(TelecommConstants.ACTION_CALL_SERVICE_PROVIDER, componentName);
    }

    /**
     * Initiates a call-service lookup cycle, see {@link ICallServiceProvider#lookupCallServices}.
     * Can be invoked even when the call service is unbound.
     *
     * @param response The response object via which to return the relevant call-service
     *     implementations, if any.
     */
    void lookupCallServices(final LookupResponse response) {
        mLookupResponses.add(response);
        BindCallback callback = new BindCallback() {
            @Override
            public void onSuccess() {
                if (isServiceValid("lookupCallServices")) {
                    try {
                        mServiceInterface.lookupCallServices(new LookupResponseWrapper(response));
                    } catch (RemoteException e) {
                    }
                }
            }

            @Override
            public void onFailure() {
                if (mLookupResponses.remove(response)) {
                    response.setCallServiceDescriptors(null);
                }
            }
        };

        mBinder.bind(callback);
    }

    /** {@inheritDoc} */
    @Override protected void setServiceInterface(IBinder binder) {
        if (binder == null) {
            mServiceInterface = null;

            Set<LookupResponse> responses = mLookupResponses;
            mLookupResponses = new HashSet<LookupResponse>();
            for (LookupResponse response : responses) {
                response.setCallServiceDescriptors(null);
            }
        } else {
            mServiceInterface = ICallServiceProvider.Stub.asInterface(binder);
        }
    }
}
