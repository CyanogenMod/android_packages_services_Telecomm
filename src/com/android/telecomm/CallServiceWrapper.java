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

import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.telecomm.CallInfo;
import android.telecomm.CallService;
import android.telecomm.CallServiceDescriptor;
import android.telecomm.ICallService;
import android.telecomm.ICallServiceAdapter;
import android.telecomm.ICallServiceProvider;

/**
 * Wrapper for {@link ICallService}s, handles binding to {@link ICallService} and keeps track of
 * when the object can safely be unbound. Other classes should not use {@link ICallService} directly
 * and instead should use this class to invoke methods of {@link ICallService}.
 * TODO(santoscordon): Keep track of when the service can be safely unbound.
 * TODO(santoscordon): Look into combining with android.telecomm.CallService.
 */
public class CallServiceWrapper extends ServiceBinder<ICallService> {

    /**
     * The service action used to bind to ICallService implementations.
     * TODO(santoscordon): Move this to TelecommConstants.
     */
    static final String CALL_SERVICE_ACTION = ICallService.class.getName();

    /** The descriptor of this call service as supplied by the call-service provider. */
    private final CallServiceDescriptor mDescriptor;

    /**
     * The adapter used by the underlying call-service implementation to communicate with Telecomm.
     */
    private final CallServiceAdapter mAdapter;

    /** The actual service implementation. */
    private ICallService mServiceInterface;

    /**
     * Creates a call-service provider for the specified component.
     *
     * @param descriptor The call-service descriptor from
     *         {@link ICallServiceProvider#lookupCallServices}.
     * @param adapter The call-service adapter.
     */
    public CallServiceWrapper(CallServiceDescriptor descriptor, CallServiceAdapter adapter) {
        super(CALL_SERVICE_ACTION, descriptor.getServiceComponent());
        mDescriptor = descriptor;
        mAdapter = adapter;
    }

    public CallServiceDescriptor getDescriptor() {
        return mDescriptor;
    }

    /** See {@link ICallService#setCallServiceAdapter}. */
    public void setCallServiceAdapter(ICallServiceAdapter callServiceAdapter) {
        if (isServiceValid("setCallServiceAdapter")) {
            try {
                mServiceInterface.setCallServiceAdapter(callServiceAdapter);
            } catch (RemoteException e) {
                Log.e(this, e, "Failed to setCallServiceAdapter.");
            }
        }
    }

    /** See {@link ICallService#isCompatibleWith}. */
    public void isCompatibleWith(CallInfo callInfo) {
        if (isServiceValid("isCompatibleWith")) {
            try {
                mServiceInterface.isCompatibleWith(callInfo);
            } catch (RemoteException e) {
                Log.e(this, e, "Failed checking isCompatibleWith.");
            }
        }
    }

    /** See {@link ICallService#call}. */
    public void call(CallInfo callInfo) {
        String callId = callInfo.getId();
        if (isServiceValid("call")) {
            try {
                mServiceInterface.call(callInfo);
                mAdapter.addPendingOutgoingCallId(callId);
            } catch (RemoteException e) {
                Log.e(this, e, "Failed to place call " + callId + ".");
            }
        }
    }

    /** See {@link ICallService#abort}. */
    public void abort(String callId) {
        mAdapter.removePendingOutgoingCallId(callId);
        if (isServiceValid("abort")) {
            try {
                mServiceInterface.abort(callId);
            } catch (RemoteException e) {
                Log.e(this, e, "Failed to abort call %s", callId);
            }
        }
    }

    /** See {@link ICallService#setIncomingCallId}. */
    public void setIncomingCallId(String callId, Bundle extras) {
        if (isServiceValid("setIncomingCallId")) {
            mAdapter.addPendingIncomingCallId(callId);
            try {
                mServiceInterface.setIncomingCallId(callId, extras);
            } catch (RemoteException e) {
                Log.e(this, e, "Failed to setIncomingCallId for call %s", callId);
                mAdapter.removePendingIncomingCallId(callId);
            }
        }
    }

    /** See {@link ICallService#disconnect}. */
    public void disconnect(String callId) {
        if (isServiceValid("disconnect")) {
            try {
                mServiceInterface.disconnect(callId);
            } catch (RemoteException e) {
                Log.e(this, e, "Failed to disconnect call %s", callId);
            }
        }
    }

    /** See {@link ICallService#answer}. */
    public void answer(String callId) {
        if (isServiceValid("answer")) {
            try {
                mServiceInterface.answer(callId);
            } catch (RemoteException e) {
                Log.e(this, e, "Failed to answer call %s", callId);
            }
        }
    }

    /** See {@link ICallService#reject}. */
    public void reject(String callId) {
        if (isServiceValid("reject")) {
            try {
                mServiceInterface.reject(callId);
            } catch (RemoteException e) {
                Log.e(this, e, "Failed to reject call %s");
            }
        }
    }

    /**
     * Starts retrieval of details for an incoming call. Details are returned through the
     * call-service adapter using the specified call ID. Upon failure, the specified error callback
     * is invoked. Can be invoked even when the call service is unbound.
     *
     * @param callId The call ID used for the incoming call.
     * @param extras The {@link CallService}-provided extras which need to be sent back.
     * @param errorCallback The callback invoked upon failure.
     */
    void retrieveIncomingCall(final String callId, final Bundle extras,
            final Runnable errorCallback) {

        BindCallback callback = new BindCallback() {
            @Override public void onSuccess() {
                setIncomingCallId(callId, extras);
            }
            @Override public void onFailure() {
                errorCallback.run();
            }
        };

        bind(callback);
    }

    /**
     * Cancels the incoming call for the specified call ID.
     * TODO(santoscordon): This method should be called by IncomingCallsManager when the incoming
     * call has failed.
     *
     * @param callId The ID of the call.
     */
    void cancelIncomingCall(String callId) {
        mAdapter.removePendingIncomingCallId(callId);
    }

    /** {@inheritDoc} */
    @Override protected void setServiceInterface(IBinder binder) {
        mServiceInterface = ICallService.Stub.asInterface(binder);
        setCallServiceAdapter(mAdapter);
    }

    private boolean isServiceValid(String actionName) {
        if (mServiceInterface != null) {
            return true;
        }

        Log.wtf(this, "%s invoked while service is unbound", actionName);
        return false;
    }
}
