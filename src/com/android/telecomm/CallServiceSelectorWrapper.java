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
import android.telecomm.CallInfo;
import android.telecomm.CallServiceDescriptor;
import android.telecomm.CallServiceSelector;
import android.telecomm.TelecommConstants;

import com.android.internal.telecomm.ICallServiceSelector;
import com.android.internal.telecomm.ICallServiceSelectorAdapter;

import java.util.List;

/**
 * Wrapper for {@link ICallServiceSelector}s, handles binding and keeps track of when the object can
 * safely be unbound.
 */
final class CallServiceSelectorWrapper extends ServiceBinder<ICallServiceSelector> {
    private ICallServiceSelector mSelectorInterface;
    private final Binder mBinder = new Binder();
    private final CallIdMapper mCallIdMapper = new CallIdMapper("CallServiceSelector");
    private final CallServiceSelectorAdapter mAdapter;

    /**
     * Creates a call-service selector for the specified component using the specified action to
     * bind to it.
     *
     * @param action The action used to bind to the selector.
     * @param componentName The component name of the service.
     * @param callsManager The calls manager.
     * @param outgoingCallsManager The outgoing calls manager.
     */
    CallServiceSelectorWrapper(
            String action,
            ComponentName componentName,
            CallsManager callsManager,
            OutgoingCallsManager outgoingCallsManager) {

        super(action, componentName);
        mAdapter =
                new CallServiceSelectorAdapter(callsManager, outgoingCallsManager, mCallIdMapper);
    }

    /**
     * Creates a call-service selector for specified component and uses
     * {@link TelecommConstants#ACTION_CALL_SERVICE_SELECTOR} as the action to bind.
     *
     * @param componentName The component name of the service.
     * @param callsManager The calls manager.
     * @param outgoingCallsManager The outgoing calls manager.
     */
    CallServiceSelectorWrapper(
            ComponentName componentName,
            CallsManager callsManager,
            OutgoingCallsManager outgoingCallsManager) {

        this(TelecommConstants.ACTION_CALL_SERVICE_SELECTOR,
                componentName,
                callsManager,
                outgoingCallsManager);
    }

    /** See {@link CallServiceSelector#setCallServiceSelectorAdapter}. */
    private void setCallServiceSelectorAdapter(ICallServiceSelectorAdapter adapter) {
        if (isServiceValid("setCallServiceSelectorAdapter")) {
            try {
                mSelectorInterface.setCallServiceSelectorAdapter(adapter);
            } catch (RemoteException e) {
            }
        }
    }

    /**
     * Retrieves the sorted set of call services that are preferred by this selector. Upon failure,
     * the error callback is invoked. Can be invoked even when the call service is unbound.
     *
     * @param call The call being placed using the {@link CallService}s.
     * @param descriptors The descriptors of the available {@link CallService}s with which to place
     *            the call.
     * @param errorCallback The callback to invoke upon failure.
     */
    void select(final Call call, final List<CallServiceDescriptor> descriptors,
            final Runnable errorCallback) {
        BindCallback callback = new BindCallback() {
            @Override
            public void onSuccess() {
                if (isServiceValid("select")) {
                    try {
                        CallInfo callInfo = call.toCallInfo(mCallIdMapper.getCallId(call));
                        mSelectorInterface.select(callInfo, descriptors);
                    } catch (RemoteException e) {
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

    private void onCallUpdated(final CallInfo callInfo) {
        BindCallback callback = new BindCallback() {
            @Override
            public void onSuccess() {
                if (isServiceValid("onCallUpdated")) {
                    try {
                        mSelectorInterface.onCallUpdated(callInfo);
                    } catch (RemoteException e) {
                    }
                }
            }
            @Override
            public void onFailure() {
            }
        };
        mBinder.bind(callback);
    }

    private void onCallRemoved(final String callId) {
        BindCallback callback = new BindCallback() {
            @Override
            public void onSuccess() {
                if (isServiceValid("onCallRemoved")) {
                    try {
                        mSelectorInterface.onCallRemoved(callId);
                    } catch (RemoteException e) {
                    }
                }
            }
            @Override
            public void onFailure() {
            }
        };
        mBinder.bind(callback);
    }

    void addCall(Call call) {
        mCallIdMapper.addCall(call);
        onCallUpdated(call.toCallInfo(mCallIdMapper.getCallId(call)));
    }

    void removeCall(Call call) {
        String callId = mCallIdMapper.getCallId(call);
        mCallIdMapper.removeCall(call);
        onCallRemoved(callId);
    }

    /** {@inheritDoc} */
    @Override
    protected void setServiceInterface(IBinder binder) {
        if (binder == null) {
            mSelectorInterface = null;
        } else {
            mSelectorInterface = ICallServiceSelector.Stub.asInterface(binder);
            setCallServiceSelectorAdapter(mAdapter);
        }
    }
}
