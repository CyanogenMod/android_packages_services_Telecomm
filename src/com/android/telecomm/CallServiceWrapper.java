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
import android.telecomm.CallAudioState;
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
     * Creates a call-service provider for the specified descriptor.
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
        Log.d(this, "isCompatibleWith(%s) via %s.", callInfo, getComponentName());
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
        Log.d(this, "call(%s) via %s.", callInfo, getComponentName());
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

    /** @see CallService#abort(String) */
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

    /** @see CallService#hold(String) */
    void hold(String callId) {
        if (isServiceValid("hold")) {
            try {
                mServiceInterface.hold(callId);
            } catch (RemoteException e) {
                Log.e(this, e, "Failed to put on hold for call %s", callId);
            }
        }
    }

    /** @see CallService#unhold(String) */
    void unhold(String callId) {
        if (isServiceValid("unhold")) {
            try {
                mServiceInterface.unhold(callId);
            } catch (RemoteException e) {
                Log.e(this, e, "Failed to remove from hold for call %s", callId);
            }
        }
    }

    /** @see CallService#onAudioStateChanged(String,CallAudioState) */
    void onAudioStateChanged(String activeCallId, CallAudioState audioState) {
        if (isServiceValid("onAudioStateChanged")) {
            try {
                mServiceInterface.onAudioStateChanged(activeCallId, audioState);
            } catch (RemoteException e) {
                Log.e(this, e, "Failed to update audio state for call %s", activeCallId);
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

        Log.d(this, "setIncomingCall(%s) via %s.", callId, getComponentName());
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

    /** @see CallService#disconnect(String) */
    void disconnect(String callId) {
        if (isServiceValid("disconnect")) {
            try {
                mServiceInterface.disconnect(callId);
            } catch (RemoteException e) {
                Log.e(this, e, "Failed to disconnect call %s", callId);
            }
        }
    }

    /** @see CallService#answer(String) */
    void answer(String callId) {
        if (isServiceValid("answer")) {
            try {
                mServiceInterface.answer(callId);
            } catch (RemoteException e) {
                Log.e(this, e, "Failed to answer call %s", callId);
            }
        }
    }

    /** @see CallService#reject(String) */
    void reject(String callId) {
        if (isServiceValid("reject")) {
            try {
                mServiceInterface.reject(callId);
            } catch (RemoteException e) {
                Log.e(this, e, "Failed to reject call %s", callId);
            }
        }
    }

    /** @see CallService#playDtmfTone(String,char) */
    void playDtmfTone(String callId, char digit) {
        if (isServiceValid("playDtmfTone")) {
            try {
                mServiceInterface.playDtmfTone(callId, digit);
            } catch (RemoteException e) {
                Log.e(this, e, "Failed to play DTMF tone for call %s", callId);
            }
        }
    }

    /** @see CallService#stopDtmfTone(String) */
    void stopDtmfTone(String callId) {
        if (isServiceValid("stopDtmfTone")) {
            try {
                mServiceInterface.stopDtmfTone(callId);
            } catch (RemoteException e) {
                Log.e(this, e, "Failed to stop DTMF tone for call %s", callId);
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
        if (binder == null) {
            // We have lost our service connection. Notify the world that this call service is done.
            // We must notify the adapter before CallsManager. The adapter will force any pending
            // outgoing calls to try the next call service. This needs to happen before CallsManager
            // tries to clean up any calls still associated with this call service.
            mAdapter.handleCallServiceDeath();
            CallsManager.getInstance().handleCallServiceDeath(this);
            mServiceInterface = null;
        } else {
            mServiceInterface = ICallService.Stub.asInterface(binder);
            setCallServiceAdapter(mAdapter);
        }
    }
}
