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
import com.google.common.base.Preconditions;

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
    private final CallIdMapper mCallIdMapper;

    /**
     * Creates a call-service for the specified descriptor.
     *
     * @param descriptor The call-service descriptor from
     *         {@link ICallServiceProvider#lookupCallServices}.
     * @param outgoingCallsManager Manages the placing of outgoing calls.
     * @param incomingCallsManager Manages the incoming call initialization flow.
     */
    CallServiceWrapper(
            CallServiceDescriptor descriptor,
            OutgoingCallsManager outgoingCallsManager,
            IncomingCallsManager incomingCallsManager) {
        super(TelecommConstants.ACTION_CALL_SERVICE, descriptor.getServiceComponent());
        mDescriptor = descriptor;
        mCallIdMapper = new CallIdMapper("CallService");
        mAdapter = new CallServiceAdapter(outgoingCallsManager, incomingCallsManager,
                mCallIdMapper);
    }

    CallServiceDescriptor getDescriptor() {
        return mDescriptor;
    }

    /** See {@link ICallService#setCallServiceAdapter}. */
    private void setCallServiceAdapter(ICallServiceAdapter callServiceAdapter) {
        if (isServiceValid("setCallServiceAdapter")) {
            try {
                mServiceInterface.setCallServiceAdapter(callServiceAdapter);
            } catch (RemoteException e) {
            }
        }
    }

    /**
     * Checks whether or not the specified call is compatible with this call-service implementation,
     * see {@link ICallService#isCompatibleWith}.  Upon failure, the specified error callback is
     * invoked. Can be invoked even when the call service is unbound.
     *
     * @param errorCallback The callback to invoke upon failure.
     */
    void isCompatibleWith(final Call call, final Runnable errorCallback) {
        Log.d(this, "isCompatibleWith(%s) via %s.", call, getComponentName());
        BindCallback callback = new BindCallback() {
            @Override public void onSuccess() {
                if (isServiceValid("isCompatibleWith")) {
                    try {
                        mAdapter.addPendingCall(call);
                        CallInfo callInfo = call.toCallInfo(mCallIdMapper.getCallId(call));
                        mServiceInterface.isCompatibleWith(callInfo);
                    } catch (RemoteException e) {
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
     */
    void call(Call call) {
        Log.d(this, "call(%s) via %s.", call, getComponentName());
        if (isServiceValid("call")) {
            try {
                CallInfo callInfo = call.toCallInfo(mCallIdMapper.getCallId(call));
                mServiceInterface.call(callInfo);
            } catch (RemoteException e) {
            }
        }
    }

    /** @see CallService#abort(String) */
    void abort(Call call) {
        mAdapter.removePendingCall(call);
        if (isServiceValid("abort")) {
            try {
                mServiceInterface.abort(mCallIdMapper.getCallId(call));
            } catch (RemoteException e) {
            }
        }
    }

    /** @see CallService#hold(String) */
    void hold(Call call) {
        if (isServiceValid("hold")) {
            try {
                mServiceInterface.hold(mCallIdMapper.getCallId(call));
            } catch (RemoteException e) {
            }
        }
    }

    /** @see CallService#unhold(String) */
    void unhold(Call call) {
        if (isServiceValid("unhold")) {
            try {
                mServiceInterface.unhold(mCallIdMapper.getCallId(call));
            } catch (RemoteException e) {
            }
        }
    }

    /** @see CallService#onAudioStateChanged(String,CallAudioState) */
    void onAudioStateChanged(Call activeCall, CallAudioState audioState) {
        if (isServiceValid("onAudioStateChanged")) {
            try {
                mServiceInterface.onAudioStateChanged(mCallIdMapper.getCallId(activeCall),
                        audioState);
            } catch (RemoteException e) {
            }
        }
    }

    /**
     * Starts retrieval of details for an incoming call. Details are returned through the
     * call-service adapter using the specified call ID. Upon failure, the specified error callback
     * is invoked. Can be invoked even when the call service is unbound.
     * See {@link ICallService#setIncomingCallId}.
     *
     * @param call The call used for the incoming call.
     * @param extras The {@link CallService}-provided extras which need to be sent back.
     * @param errorCallback The callback to invoke upon failure.
     */
    void setIncomingCallId(final Call call, final Bundle extras, final Runnable errorCallback) {
        Log.d(this, "setIncomingCall(%s) via %s.", call, getComponentName());
        BindCallback callback = new BindCallback() {
            @Override public void onSuccess() {
                if (isServiceValid("setIncomingCallId")) {
                    mAdapter.addPendingCall(call);
                    try {
                        mServiceInterface.setIncomingCallId(mCallIdMapper.getCallId(call),
                                extras);
                    } catch (RemoteException e) {
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
    void disconnect(Call call) {
        if (isServiceValid("disconnect")) {
            try {
                mServiceInterface.disconnect(mCallIdMapper.getCallId(call));
            } catch (RemoteException e) {
            }
        }
    }

    /** @see CallService#answer(String) */
    void answer(Call call) {
        if (isServiceValid("answer")) {
            try {
                mServiceInterface.answer(mCallIdMapper.getCallId(call));
            } catch (RemoteException e) {
            }
        }
    }

    /** @see CallService#reject(String) */
    void reject(Call call) {
        if (isServiceValid("reject")) {
            try {
                mServiceInterface.reject(mCallIdMapper.getCallId(call));
            } catch (RemoteException e) {
            }
        }
    }

    /** @see CallService#playDtmfTone(String,char) */
    void playDtmfTone(Call call, char digit) {
        if (isServiceValid("playDtmfTone")) {
            try {
                mServiceInterface.playDtmfTone(mCallIdMapper.getCallId(call), digit);
            } catch (RemoteException e) {
            }
        }
    }

    /** @see CallService#stopDtmfTone(String) */
    void stopDtmfTone(Call call) {
        if (isServiceValid("stopDtmfTone")) {
            try {
                mServiceInterface.stopDtmfTone(mCallIdMapper.getCallId(call));
            } catch (RemoteException e) {
            }
        }
    }

    void addCall(Call call) {
        mCallIdMapper.addCall(call);
    }

    /**
     * Associates newCall with this call service by replacing callToReplace.
     */
    void replaceCall(Call newCall, Call callToReplace) {
        Preconditions.checkState(callToReplace.getCallService() == this);
        mCallIdMapper.replaceCall(newCall, callToReplace);
    }

    void removeCall(Call call) {
        mAdapter.removePendingCall(call);
        mCallIdMapper.removeCall(call);
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
