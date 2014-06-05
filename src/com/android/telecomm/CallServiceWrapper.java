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
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.telecomm.CallAudioState;
import android.telecomm.CallInfo;
import android.telecomm.CallService;
import android.telecomm.CallServiceDescriptor;
import android.telecomm.ConnectionRequest;
import android.telecomm.TelecommConstants;
import android.telephony.DisconnectCause;

import com.android.internal.os.SomeArgs;
import com.android.internal.telecomm.ICallService;
import com.android.internal.telecomm.ICallServiceAdapter;
import com.android.internal.telecomm.ICallServiceProvider;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import org.apache.http.conn.ClientConnectionRequest;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Wrapper for {@link ICallService}s, handles binding to {@link ICallService} and keeps track of
 * when the object can safely be unbound. Other classes should not use {@link ICallService} directly
 * and instead should use this class to invoke methods of {@link ICallService}.
 */
final class CallServiceWrapper extends ServiceBinder<ICallService> {
    private static final String TAG = CallServiceWrapper.class.getSimpleName();

    private final class Adapter extends ICallServiceAdapter.Stub {
        private static final int MSG_NOTIFY_INCOMING_CALL = 1;
        private static final int MSG_HANDLE_SUCCESSFUL_OUTGOING_CALL = 2;
        private static final int MSG_HANDLE_FAILED_OUTGOING_CALL = 3;
        private static final int MSG_SET_ACTIVE = 4;
        private static final int MSG_SET_RINGING = 5;
        private static final int MSG_SET_DIALING = 6;
        private static final int MSG_SET_DISCONNECTED = 7;
        private static final int MSG_SET_ON_HOLD = 8;
        private static final int MSG_SET_REQUESTING_RINGBACK = 9;
        private static final int MSG_ON_POST_DIAL_WAIT = 10;
        private static final int MSG_CAN_CONFERENCE = 11;
        private static final int MSG_SET_IS_CONFERENCED = 12;
        private static final int MSG_ADD_CONFERENCE_CALL = 13;

        private final Handler mHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                Call call;
                switch (msg.what) {
                    case MSG_NOTIFY_INCOMING_CALL:
                        CallInfo clientCallInfo = (CallInfo) msg.obj;
                        call = mCallIdMapper.getCall(clientCallInfo.getId());
                        if (call != null && mPendingIncomingCalls.remove(call) &&
                                call.isIncoming()) {
                            CallInfo callInfo = new CallInfo(null, clientCallInfo.getState(),
                                    clientCallInfo.getHandle());
                            mIncomingCallsManager.handleSuccessfulIncomingCall(call, callInfo);
                        } else {
                            Log.w(this, "notifyIncomingCall, unknown incoming call: %s, id: %s",
                                    call, clientCallInfo.getId());
                        }
                        break;
                    case MSG_HANDLE_SUCCESSFUL_OUTGOING_CALL: {
                        String callId = (String) msg.obj;
                        if (mPendingOutgoingCalls.containsKey(callId)) {
                            mPendingOutgoingCalls.remove(callId).onResult(true, 0, null);
                        } else {
                            Log.w(this, "handleSuccessfulOutgoingCall, unknown call: %s", callId);
                        }
                        break;
                    }
                    case MSG_HANDLE_FAILED_OUTGOING_CALL: {
                        SomeArgs args = (SomeArgs) msg.obj;
                        try {
                            String callId = (String) args.arg1;
                            int statusCode = args.argi1;
                            String statusMsg = (String) args.arg2;
                            // TODO(santoscordon): Do something with 'reason' or get rid of it.

                            if (mPendingOutgoingCalls.containsKey(callId)) {
                                mPendingOutgoingCalls.remove(callId).onResult(
                                        false, statusCode, statusMsg);
                                mCallIdMapper.removeCall(callId);
                            } else {
                                Log.w(this, "handleFailedOutgoingCall, unknown call: %s", callId);
                            }
                        } finally {
                            args.recycle();
                        }
                        break;
                    }
                    case MSG_SET_ACTIVE:
                        call = mCallIdMapper.getCall(msg.obj);
                        if (call != null) {
                            mCallsManager.markCallAsActive(call);
                        } else {
                            Log.w(this, "setActive, unknown call id: %s", msg.obj);
                        }
                        break;
                    case MSG_SET_RINGING:
                        call = mCallIdMapper.getCall(msg.obj);
                        if (call != null) {
                            mCallsManager.markCallAsRinging(call);
                        } else {
                            Log.w(this, "setRinging, unknown call id: %s", msg.obj);
                        }
                        break;
                    case MSG_SET_DIALING:
                        call = mCallIdMapper.getCall(msg.obj);
                        if (call != null) {
                            mCallsManager.markCallAsDialing(call);
                        } else {
                            Log.w(this, "setDialing, unknown call id: %s", msg.obj);
                        }
                        break;
                    case MSG_SET_DISCONNECTED: {
                        SomeArgs args = (SomeArgs) msg.obj;
                        try {
                            call = mCallIdMapper.getCall(args.arg1);
                            String disconnectMessage = (String) args.arg2;
                            int disconnectCause = args.argi1;
                            if (call != null) {
                                mCallsManager.markCallAsDisconnected(call, disconnectCause,
                                        disconnectMessage);
                            } else {
                                Log.w(this, "setDisconnected, unknown call id: %s", args.arg1);
                            }
                        } finally {
                            args.recycle();
                        }
                        break;
                    }
                    case MSG_SET_ON_HOLD:
                        call = mCallIdMapper.getCall(msg.obj);
                        if (call != null) {
                            mCallsManager.markCallAsOnHold(call);
                        } else {
                            Log.w(this, "setOnHold, unknown call id: %s", msg.obj);
                        }
                        break;
                    case MSG_SET_REQUESTING_RINGBACK: {
                        SomeArgs args = (SomeArgs) msg.obj;
                        try {
                            call = mCallIdMapper.getCall(args.arg1);
                            boolean ringback = (boolean) args.arg2;
                            if (call != null) {
                                call.setRequestingRingback(ringback);
                            } else {
                                Log.w(this, "setRingback, unknown call id: %s", args.arg1);
                            }
                        } finally {
                            args.recycle();
                        }
                        break;
                    }
                    case MSG_ON_POST_DIAL_WAIT: {
                        SomeArgs args = (SomeArgs) msg.obj;
                        try {
                            call = mCallIdMapper.getCall(args.arg1);
                            if (call != null) {
                                String remaining = (String) args.arg2;
                                call.onPostDialWait(remaining);
                            } else {
                                Log.w(this, "onPostDialWait, unknown call id: %s", args.arg1);
                            }
                        } finally {
                            args.recycle();
                        }
                        break;
                    }
                    case MSG_CAN_CONFERENCE: {
                        call = mCallIdMapper.getCall(msg.obj);
                        if (call != null) {
                            call.setIsConferenceCapable(msg.arg1 == 1);
                        } else {
                            Log.w(CallServiceWrapper.this, "canConference, unknown call id: %s",
                                    msg.obj);
                        }
                        break;
                    }
                    case MSG_SET_IS_CONFERENCED: {
                        SomeArgs args = (SomeArgs) msg.obj;
                        try {
                            Call childCall = mCallIdMapper.getCall(args.arg1);
                            if (childCall != null) {
                                String conferenceCallId = (String) args.arg2;
                                Log.d(this, "setIsConferenced %s, %s", childCall, conferenceCallId);

                                if (conferenceCallId == null) {
                                    childCall.setParentCall(null);
                                } else {
                                    Call conferenceCall = mCallIdMapper.getCall(conferenceCallId);
                                    if (conferenceCall != null &&
                                            !mPendingConferenceCalls.contains(conferenceCall)) {
                                        childCall.setParentCall(conferenceCall);
                                    } else {
                                        Log.w(this, "setIsConferenced, unknown conference id %s",
                                                conferenceCallId);
                                    }
                                }
                            } else {
                                Log.w(this, "setIsConferenced, unknown call id: %s", args.arg1);
                            }
                        } finally {
                            args.recycle();
                        }
                        break;
                    }
                    case MSG_ADD_CONFERENCE_CALL: {
                        SomeArgs args = (SomeArgs) msg.obj;
                        try {
                            String callId = (String) args.arg1;
                            Log.d(this, "addConferenceCall attempt %s, %s",
                                    callId, mPendingConferenceCalls);

                            Call conferenceCall = mCallIdMapper.getCall(callId);
                            if (mPendingConferenceCalls.remove(conferenceCall)) {
                                Log.v(this, "confirming conf call %s", conferenceCall);
                                conferenceCall.confirmConference();
                            } else {
                                Log.w(this, "addConference, unknown call id: %s", callId);
                            }
                        } finally {
                            args.recycle();
                        }
                        break;
                    }
                }
            }
        };

        /** {@inheritDoc} */
        @Override
        public void setIsCompatibleWith(String callId, boolean isCompatible) {
            Log.wtf(this, "Not expected.");
        }

        /** {@inheritDoc} */
        @Override
        public void notifyIncomingCall(CallInfo callInfo) {
            mCallIdMapper.checkValidCallId(callInfo.getId());
            mHandler.obtainMessage(MSG_NOTIFY_INCOMING_CALL, callInfo).sendToTarget();
        }

        /** {@inheritDoc} */
        @Override
        public void handleSuccessfulOutgoingCall(String callId) {
            Log.d(this, "handleSuccessfulOutgoingCall %s", callId);
            mCallIdMapper.checkValidCallId(callId);
            mHandler.obtainMessage(MSG_HANDLE_SUCCESSFUL_OUTGOING_CALL, callId).sendToTarget();
        }

        /** {@inheritDoc} */
        @Override
        public void handleFailedOutgoingCall(
                ConnectionRequest request,
                int errorCode,
                String errorMsg) {
            mCallIdMapper.checkValidCallId(request.getCallId());
            Log.d(this, "handleFailedOutgoingCall %s", request.getCallId());
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = request.getCallId();
            args.argi1 = errorCode;
            args.arg2 = errorMsg;
            mHandler.obtainMessage(MSG_HANDLE_FAILED_OUTGOING_CALL, args).sendToTarget();
        }

        /** {@inheritDoc} */
        @Override
        public void setActive(String callId) {
            mCallIdMapper.checkValidCallId(callId);
            mHandler.obtainMessage(MSG_SET_ACTIVE, callId).sendToTarget();
        }

        /** {@inheritDoc} */
        @Override
        public void setRinging(String callId) {
            mCallIdMapper.checkValidCallId(callId);
            mHandler.obtainMessage(MSG_SET_RINGING, callId).sendToTarget();
        }

        /** {@inheritDoc} */
        @Override
        public void setDialing(String callId) {
            mCallIdMapper.checkValidCallId(callId);
            mHandler.obtainMessage(MSG_SET_DIALING, callId).sendToTarget();
        }

        /** {@inheritDoc} */
        @Override
        public void setDisconnected(
                String callId, int disconnectCause, String disconnectMessage) {
            mCallIdMapper.checkValidCallId(callId);
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = callId;
            args.arg2 = disconnectMessage;
            args.argi1 = disconnectCause;
            mHandler.obtainMessage(MSG_SET_DISCONNECTED, args).sendToTarget();
        }

        /** {@inheritDoc} */
        @Override
        public void setOnHold(String callId) {
            mCallIdMapper.checkValidCallId(callId);
            mHandler.obtainMessage(MSG_SET_ON_HOLD, callId).sendToTarget();
        }

        /** {@inheritDoc} */
        @Override
        public void setRequestingRingback(String callId, boolean ringback) {
            mCallIdMapper.checkValidCallId(callId);
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = callId;
            args.arg2 = ringback;
            mHandler.obtainMessage(MSG_SET_REQUESTING_RINGBACK, args).sendToTarget();
        }

        /** ${inheritDoc} */
        @Override
        public void removeCall(String callId) {
        }

        /** ${inheritDoc} */
        @Override
        public void setCanConference(String callId, boolean canConference) {
            Log.d(this, "setCanConference(%s, %b)", callId, canConference);
            mHandler.obtainMessage(MSG_CAN_CONFERENCE, canConference ? 1 : 0, 0, callId)
                    .sendToTarget();
        }

        /** ${inheritDoc} */
        @Override
        public void setIsConferenced(String callId, String conferenceCallId) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = callId;
            args.arg2 = conferenceCallId;
            mHandler.obtainMessage(MSG_SET_IS_CONFERENCED, args).sendToTarget();
        }

        /** ${InheritDoc} */
        @Override
        public void addConferenceCall(String callId, CallInfo callInfo) {
            mCallIdMapper.checkValidCallId(callId);
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = callId;
            args.arg2 = callInfo;
            mHandler.obtainMessage(MSG_ADD_CONFERENCE_CALL, args).sendToTarget();
        }

        @Override
        public void onPostDialWait(String callId, String remaining) throws RemoteException {
            mCallIdMapper.checkValidCallId(callId);
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = callId;
            args.arg2 = remaining;
            mHandler.obtainMessage(MSG_ON_POST_DIAL_WAIT, args).sendToTarget();
        }
    }

    private final Adapter mAdapter = new Adapter();
    private final CallsManager mCallsManager = CallsManager.getInstance();
    private final Set<Call> mPendingIncomingCalls = new HashSet<>();
    private final Set<Call> mPendingConferenceCalls = new HashSet<>();
    private final CallServiceDescriptor mDescriptor;
    private final CallIdMapper mCallIdMapper = new CallIdMapper("CallService");
    private final IncomingCallsManager mIncomingCallsManager;
    private final Map<String, AsyncResultCallback<Boolean>> mPendingOutgoingCalls = new HashMap<>();
    private final Handler mHandler = new Handler();

    private Binder mBinder = new Binder();
    private ICallService mServiceInterface;

    /**
     * Creates a call-service for the specified descriptor.
     *
     * @param descriptor The call-service descriptor from
     *            {@link ICallServiceProvider#lookupCallServices}.
     * @param incomingCallsManager Manages the incoming call initialization flow.
     */
    CallServiceWrapper(
            CallServiceDescriptor descriptor,
            IncomingCallsManager incomingCallsManager) {
        super(TelecommConstants.ACTION_CALL_SERVICE, descriptor.getServiceComponent());
        mDescriptor = descriptor;
        mIncomingCallsManager = incomingCallsManager;
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
     * Attempts to place the specified call, see {@link ICallService#call}. Returns the result
     * asynchronously through the specified callback.
     */
    void call(final Call call, final AsyncResultCallback<Boolean> resultCallback) {
        Log.d(this, "call(%s) via %s.", call, getComponentName());
        BindCallback callback = new BindCallback() {
            @Override
            public void onSuccess() {
                String callId = mCallIdMapper.getCallId(call);
                mPendingOutgoingCalls.put(callId, resultCallback);

                try {
                    CallInfo callInfo = call.toCallInfo(callId);
                    mServiceInterface.call(callInfo);
                } catch (RemoteException e) {
                    mPendingOutgoingCalls.remove(callId).onResult(
                            false, DisconnectCause.ERROR_UNSPECIFIED, e.toString());
                }
            }

            @Override
            public void onFailure() {
                resultCallback.onResult(false, DisconnectCause.ERROR_UNSPECIFIED, null);
            }
        };

        mBinder.bind(callback);
    }

    /** @see CallService#abort(String) */
    void abort(Call call) {
        // Clear out any pending outgoing call data
        String callId = mCallIdMapper.getCallId(call);

        // If still bound, tell the call service to abort.
        if (isServiceValid("abort")) {
            try {
                mServiceInterface.abort(callId);
            } catch (RemoteException e) {
            }
        }

        removeCall(call);
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
     * is invoked. Can be invoked even when the call service is unbound. See
     * {@link ICallService#setIncomingCallId}.
     *
     * @param call The call used for the incoming call.
     * @param extras The {@link CallService}-provided extras which need to be sent back.
     * @param errorCallback The callback to invoke upon failure.
     */
    void setIncomingCallId(final Call call, final Bundle extras, final Runnable errorCallback) {
        Log.d(this, "setIncomingCall(%s) via %s.", call, getComponentName());
        BindCallback callback = new BindCallback() {
            @Override
            public void onSuccess() {
                if (isServiceValid("setIncomingCallId")) {
                    mPendingIncomingCalls.add(call);
                    try {
                        mServiceInterface.setIncomingCallId(mCallIdMapper.getCallId(call),
                                extras);
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
        if (mCallIdMapper.getCallId(call) == null) {
            mCallIdMapper.addCall(call);
        }
    }

    /**
     * Associates newCall with this call service by replacing callToReplace.
     */
    void replaceCall(Call newCall, Call callToReplace) {
        Preconditions.checkState(callToReplace.getCallService() == this);
        mCallIdMapper.replaceCall(newCall, callToReplace);
    }

    void removeCall(Call call) {
        mPendingIncomingCalls.remove(call);

        AsyncResultCallback<Boolean> outgoingResultCallback =
            mPendingOutgoingCalls.remove(mCallIdMapper.getCallId(call));
        if (outgoingResultCallback != null) {
            outgoingResultCallback.onResult(false, DisconnectCause.ERROR_UNSPECIFIED, null);
        }

        mCallIdMapper.removeCall(call);
    }

    void onPostDialContinue(Call call, boolean proceed) {
        if (isServiceValid("onPostDialContinue")) {
            try {
                mServiceInterface.onPostDialContinue(mCallIdMapper.getCallId(call), proceed);
            } catch (RemoteException ignored) {
            }
        }
    }

    void conference(final Call conferenceCall, Call call) {
        if (isServiceValid("conference")) {
            try {
                conferenceCall.setCallService(this);
                mPendingConferenceCalls.add(conferenceCall);
                mHandler.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (mPendingConferenceCalls.remove(conferenceCall)) {
                            conferenceCall.expireConference();
                            Log.i(this, "Conference call expired: %s", conferenceCall);
                        }
                    }
                }, Timeouts.getConferenceCallExpireMillis());

                mServiceInterface.conference(
                        mCallIdMapper.getCallId(conferenceCall),
                        mCallIdMapper.getCallId(call));
            } catch (RemoteException ignored) {
            }
        }
    }

    void splitFromConference(Call call) {
        if (isServiceValid("splitFromConference")) {
            try {
                mServiceInterface.splitFromConference(mCallIdMapper.getCallId(call));
            } catch (RemoteException ignored) {
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    protected void setServiceInterface(IBinder binder) {
        if (binder == null) {
            // We have lost our service connection. Notify the world that this call service is done.
            // We must notify the adapter before CallsManager. The adapter will force any pending
            // outgoing calls to try the next call service. This needs to happen before CallsManager
            // tries to clean up any calls still associated with this call service.
            handleCallServiceDeath();
            CallsManager.getInstance().handleCallServiceDeath(this);
            mServiceInterface = null;
        } else {
            mServiceInterface = ICallService.Stub.asInterface(binder);
            setCallServiceAdapter(mAdapter);
        }
    }

    /**
     * Called when the associated call service dies.
     */
    private void handleCallServiceDeath() {
        if (!mPendingOutgoingCalls.isEmpty()) {
            for (AsyncResultCallback<Boolean> callback : mPendingOutgoingCalls.values()) {
                callback.onResult(false, DisconnectCause.ERROR_UNSPECIFIED, null);
            }
            mPendingOutgoingCalls.clear();
        }

        if (!mPendingIncomingCalls.isEmpty()) {
            // Iterate through a copy because the code inside the loop will modify the original
            // list.
            for (Call call : ImmutableList.copyOf(mPendingIncomingCalls)) {
                Preconditions.checkState(call.isIncoming());
                mIncomingCallsManager.handleFailedIncomingCall(call);
            }

            if (!mPendingIncomingCalls.isEmpty()) {
                Log.wtf(this, "Pending calls did not get cleared.");
                mPendingIncomingCalls.clear();
            }
        }

        mCallIdMapper.clear();
    }
}
