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
import android.telecomm.CallInfo;
import android.telecomm.CallServiceDescriptor;
import android.telecomm.TelecommConstants;
import android.telecomm.CallServiceSelector.CallServiceSelectionResponse;

import com.google.common.base.Preconditions;
import com.android.internal.telecomm.ICallServiceSelectionResponse;
import com.android.internal.telecomm.ICallServiceSelector;

import java.util.List;

/**
 * Wrapper for {@link ICallServiceSelector}s, handles binding and keeps track of when the object can
 * safely be unbound.
 */
final class CallServiceSelectorWrapper extends ServiceBinder<ICallServiceSelector> {
    class SelectionResponseImpl extends ICallServiceSelectionResponse.Stub {
        private final CallServiceSelectionResponse mResponse;

        SelectionResponseImpl(CallServiceSelectionResponse response) {
            mResponse = response;
        }

        @Override
        public void setSelectedCallServiceDescriptors(
                final List<CallServiceDescriptor> descriptors) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mResponse.setSelectedCallServices(descriptors);
                }
            });
        }
    }

    private ICallServiceSelector mSelectorInterface;

    private Binder mBinder = new Binder();

    private Handler mHandler = new Handler();

    /**
     * Creates a call-service selector for the specified component.
     *
     * @param componentName The component name of the service.
     */
    CallServiceSelectorWrapper(ComponentName componentName) {
        super(TelecommConstants.ACTION_CALL_SERVICE_SELECTOR, componentName);
    }

    /**
     * Retrieves the sorted set of call services that are preferred by this selector. Upon failure,
     * the error callback is invoked. Can be invoked even when the call service is unbound.
     *
     * @param callInfo The details of the call.
     * @param selectionResponse The selection response callback to invoke upon success.
     * @param errorCallback The callback to invoke upon failure.
     */
    void select(final CallInfo callInfo, final List<CallServiceDescriptor> callServiceDescriptors,
            final CallServiceSelectionResponse selectionResponse, final Runnable errorCallback) {
        BindCallback callback = new BindCallback() {
            @Override
            public void onSuccess() {
                if (isServiceValid("select")) {
                    try {
                        mSelectorInterface.select(callInfo, callServiceDescriptors,
                                new SelectionResponseImpl(selectionResponse));
                    } catch (RemoteException e) {
                        Log.e(CallServiceSelectorWrapper.this, e, "Failed calling select for selector: %s.",
                                getComponentName());
                    }
                }
            }

            @Override
            public void onFailure() {
                errorCallback.run();
            }
        };

        mBinder.bind(callback);
    }

    /** {@inheritDoc} */
    @Override
    protected void setServiceInterface(IBinder binder) {
        mSelectorInterface = ICallServiceSelector.Stub.asInterface(binder);
    }
}
