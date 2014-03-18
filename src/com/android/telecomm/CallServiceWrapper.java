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
import android.telecomm.TelecommConstants;

import com.android.internal.telecomm.ICallService;
import com.android.internal.telecomm.ICallServiceAdapter;
import com.android.internal.telecomm.ICallServiceProvider;

/**
 * Wrapper for {@link ICallService}s, handles binding to {@link ICallService} and keeps track of
 * when the object can safely be unbound. Other classes should not use {@link ICallService} directly
 * and instead should use this class to invoke methods of {@link ICallService}.
 */
final class CallServiceWrapper extends ServiceBinder<ICallService> {

    /** The descriptor of this call service as supplied by the call-service provider. */
    private final CallServiceDescriptor mDescriptor;

    /**
     * The adapter used by the underlying call-service implementation to communicate with Telecomm.
     */
    private final CallServiceAdapter mAdapter;

    /** The actual service implementation. */
    private ICallService mServiceInterface;

    private Binder mBinder = new Binder();

    /**
     * Creates a call-service provider for the specified component.
     *
     * @param descriptor The call-service descriptor from
     *         {@link ICallServiceProvider#lookupCallServices}.
     * @param adapter The call-service adapter.
     */
    CallServiceWrapper(CallServiceDescriptor descriptor, CallServiceAdapter adapter) {
        super(TelecommConstants.ACTION_CALL_SERVICE, descriptor.getServiceComponent());
        mDescriptor = descriptor;
        mAdapter = adapter;
    }

    CallServiceDescriptor getDescriptor() {
        return mDescriptor;
    }

    /** See {@link ICallService#setCallServiceAdapter}. */
    void setCallServiceAdapter(ICallServiceAdapter callServiceAdapter) {
        if (isServiceValid("setCallServiceAdapter")) {
            try {
                mServiceInterface.setCallServiceAdapter(callServiceAdapter);
            } catch (RemoteException e) {
                Log.e(this, e, "Failed to setCallServiceAdapter.");
            }
        }
    }

    /**
     * Checks whether or not the specified call is compatible with this call-service implementation,
     * see {@link ICallService#isCompatibleWith}.  Upon failure, the specified error callback is
     * invoked. Can be invoked even when the call service is unbound.
     *
     * @param callInfo The details of the call.
     * @param errorCallback The callback to invoke upon failure.
     */
    void isCompatibleWith(final CallInfo callInfo, final Runnable errorCallback) {
        BindCallback callback = new BindCallback() {
            @Override public void onSuccess() {
                if (isServiceValid("isCompatibleWith")) {
                    try {
                        mAdapter.addPendingOutgoingCallId(callInfo.getId());
                        mServiceInterface.isCompatibleWith(callInfo);
                    } catch (RemoteException e) {
                        mAdapter.removePendingOutgoingCallId(callInfo.getId());
                        Log.e(CallServiceWrapper.this, e, "Failed checking isCompatibleWith.");
                    }
                }
            }
            @Override public void onFailure() {
                errorCallback.run();
            }
        };

        mBinder.bind(callback);
    }

    /**
     * Attempts to place the specified call, see {@link ICallService#call}.  Upon failure, the
     * specified error callback is invoked. Can be invoked even when the call service is unbound.
     *
     * @param callInfo The details of the call.
     * @param errorCallback The callback to invoke upon failure.
     */
    void call(final CallInfo callInfo, final Runnable errorCallback) {
        BindCallback callback = new BindCallback() {
            @Override public void onSuccess() {
                String callId = callInfo.getId();
                if (isServiceValid("call")) {
                    try {
                        mServiceInterface.call(callInfo);
                    } catch (RemoteException e) {
                        Log.e(CallServiceWrapper.this, e, "Failed to place call %s", callId);
                    }
                }
            }
            @Override public void onFailure() {
                errorCallback.run();
            }
        };

         mBinder.bind(callback);
    }

    /** See {@link ICallService#abort}. */
    void abort(String callId) {
        mAdapter.removePendingOutgoingCallId(callId);
        if (isServiceValid("abort")) {
            try {
                mServiceInterface.abort(callId);
            } catch (RemoteException e) {
                Log.e(this, e, "Failed to abort call %s", callId);
            }
        }
    }

    /** See {@link ICallService#hold}. */
    public void hold(String callId) {
        if (isServiceValid("hold")) {
            try {
                mServiceInterface.hold(callId);
            } catch (RemoteException e) {
                Log.e(this, e, "Failed to put on hold for call %s", callId);
            }
        }
    }

    /** See {@link ICallService#unhold}. */
    public void unhold(String callId) {
        if (isServiceValid("unhold")) {
            try {
                mServiceInterface.unhold(callId);
            } catch (RemoteException e) {
                Log.e(this, e, "Failed to remove from hold for call %s", callId);
            }
        }
    }

    /**
     * Starts retrieval of details for an incoming call. Details are returned through the
     * call-service adapter using the specified call ID. Upon failure, the specified error callback
     * is invoked. Can be invoked even when the call service is unbound.
     * See {@link ICallService#setIncomingCallId}.
     *
     * @param callId The call ID used for the incoming call.
     * @param extras The {@link CallService}-provided extras which need to be sent back.
     * @param errorCallback The callback to invoke upon failure.
     */
    void setIncomingCallId(
            final String callId,
            final Bundle extras,
            final Runnable errorCallback) {

        BindCallback callback = new BindCallback() {
            @Override public void onSuccess() {
                if (isServiceValid("setIncomingCallId")) {
                    mAdapter.addPendingIncomingCallId(callId);
                    try {
                        mServiceInterface.setIncomingCallId(callId, extras);
                    } catch (RemoteException e) {
                        Log.e(CallServiceWrapper.this, e,
                                "Failed to setIncomingCallId for call %s", callId);
                        mAdapter.removePendingIncomingCallId(callId);
                    }
                }
            }
            @Override public void onFailure() {
                errorCallback.run();
            }
        };

        mBinder.bind(callback);
    }

    /** See {@link ICallService#disconnect}. */
    void disconnect(String callId) {
        if (isServiceValid("disconnect")) {
            try {
                mServiceInterface.disconnect(callId);
            } catch (RemoteException e) {
                Log.e(this, e, "Failed to disconnect call %s", callId);
            }
        }
    }

    /** See {@link ICallService#answer}. */
    void answer(String callId) {
        if (isServiceValid("answer")) {
            try {
                mServiceInterface.answer(callId);
            } catch (RemoteException e) {
                Log.e(this, e, "Failed to answer call %s", callId);
            }
        }
    }

    /** See {@link ICallService#reject}. */
    void reject(String callId) {
        if (isServiceValid("reject")) {
            try {
                mServiceInterface.reject(callId);
            } catch (RemoteException e) {
                Log.e(this, e, "Failed to reject call %s");
            }
        }
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

    /**
     * Cancels the outgoing call for the specified call ID.
     *
     * @param callId The ID of the call.
     */
    void cancelOutgoingCall(String callId) {
        mAdapter.removePendingOutgoingCallId(callId);
    }

    /** {@inheritDoc} */
    @Override protected void setServiceInterface(IBinder binder) {
        mServiceInterface = ICallService.Stub.asInterface(binder);
        setCallServiceAdapter(mAdapter);
    }
}
