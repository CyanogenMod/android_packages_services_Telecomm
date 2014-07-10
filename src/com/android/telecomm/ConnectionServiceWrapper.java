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
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.telecomm.CallAudioState;
import android.telecomm.ConnectionService;
import android.telecomm.CallServiceDescriptor;
import android.telecomm.ConnectionRequest;
import android.telecomm.GatewayInfo;
import android.telecomm.StatusHints;
import android.telecomm.TelecommConstants;
import android.telephony.DisconnectCause;

import com.android.internal.os.SomeArgs;
import com.android.internal.telecomm.IConnectionService;
import com.android.internal.telecomm.IConnectionServiceAdapter;
import com.android.internal.telecomm.ICallServiceProvider;
import com.android.internal.telecomm.ICallVideoProvider;
import com.android.internal.telecomm.RemoteServiceCallback;
import com.android.telecomm.BaseRepository.LookupCallback;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import org.apache.http.conn.ClientConnectionRequest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Wrapper for {@link IConnectionService}s, handles binding to {@link IConnectionService} and keeps
 * track of when the object can safely be unbound. Other classes should not use
 * {@link IConnectionService} directly and instead should use this class to invoke methods of
 * {@link IConnectionService}.
 */
final class ConnectionServiceWrapper extends ServiceBinder<IConnectionService> {
    private static final int MSG_NOTIFY_INCOMING_CALL = 1;
    private static final int MSG_HANDLE_SUCCESSFUL_OUTGOING_CALL = 2;
    private static final int MSG_HANDLE_FAILED_OUTGOING_CALL = 3;
    private static final int MSG_CANCEL_OUTGOING_CALL = 4;
    private static final int MSG_SET_ACTIVE = 5;
    private static final int MSG_SET_RINGING = 6;
    private static final int MSG_SET_DIALING = 7;
    private static final int MSG_SET_DISCONNECTED = 8;
    private static final int MSG_SET_ON_HOLD = 9;
    private static final int MSG_SET_REQUESTING_RINGBACK = 10;
    private static final int MSG_CAN_CONFERENCE = 11;
    private static final int MSG_SET_IS_CONFERENCED = 12;
    private static final int MSG_ADD_CONFERENCE_CALL = 13;
    private static final int MSG_REMOVE_CALL = 14;
    private static final int MSG_ON_POST_DIAL_WAIT = 15;
    private static final int MSG_QUERY_REMOTE_CALL_SERVICES = 16;
    private static final int MSG_SET_CALL_VIDEO_PROVIDER = 17;
    private static final int MSG_SET_FEATURES = 18;
    private static final int MSG_SET_AUDIO_MODE_IS_VOIP = 19;
    private static final int MSG_SET_STATUS_HINTS = 20;

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            Call call;
            switch (msg.what) {
                case MSG_NOTIFY_INCOMING_CALL: {
                    ConnectionRequest request = (ConnectionRequest) msg.obj;
                    call = mCallIdMapper.getCall(request.getCallId());
                    if (call != null && mPendingIncomingCalls.remove(call) &&
                            call.isIncoming()) {
                        mIncomingCallsManager.handleSuccessfulIncomingCall(call, request);
                    } else {
                        // TODO(santoscordon): For this an the other commented logging, we need
                        // to reenable it.  At the moment all ConnectionServiceAdapters receive
                        // notification of changes to all calls, even calls which it may not own
                        // (ala remote connections). We need to fix that and then uncomment the
                        // logging calls here.
                        //Log.w(this, "notifyIncomingCall, unknown incoming call: %s, id: %s",
                        //        call, request.getId());
                    }
                    break;
                }
                case MSG_HANDLE_SUCCESSFUL_OUTGOING_CALL: {
                    ConnectionRequest request = (ConnectionRequest) msg.obj;
                    if (mPendingOutgoingCalls.containsKey(request.getCallId())) {
                        mPendingOutgoingCalls.remove(
                                request.getCallId()).onOutgoingCallSuccess();
                    } else {
                        //Log.w(this, "handleSuccessfulOutgoingCall, unknown call: %s", callId);
                    }
                    break;
                }
                case MSG_HANDLE_FAILED_OUTGOING_CALL: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        ConnectionRequest request = (ConnectionRequest) args.arg1;
                        int statusCode = args.argi1;
                        String statusMsg = (String) args.arg2;
                        // TODO(santoscordon): Do something with 'reason' or get rid of it.

                        if (mPendingOutgoingCalls.containsKey(request.getCallId())) {
                            mPendingOutgoingCalls.remove(request.getCallId())
                                    .onOutgoingCallFailure(statusCode, statusMsg);
                            mCallIdMapper.removeCall(request.getCallId());
                        } else {
                            //Log.w(this, "handleFailedOutgoingCall, unknown call: %s", callId);
                        }
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_CANCEL_OUTGOING_CALL: {
                    ConnectionRequest request = (ConnectionRequest) msg.obj;
                    if (mPendingOutgoingCalls.containsKey(request.getCallId())) {
                        mPendingOutgoingCalls.remove(
                                request.getCallId()).onOutgoingCallCancel();
                    } else {
                        //Log.w(this, "cancelOutgoingCall, unknown call: %s", callId);
                    }
                    break;
                }
                case MSG_SET_ACTIVE:
                    call = mCallIdMapper.getCall(msg.obj);
                    if (call != null) {
                        mCallsManager.markCallAsActive(call);
                    } else {
                        //Log.w(this, "setActive, unknown call id: %s", msg.obj);
                    }
                    break;
                case MSG_SET_RINGING:
                    call = mCallIdMapper.getCall(msg.obj);
                    if (call != null) {
                        mCallsManager.markCallAsRinging(call);
                    } else {
                        //Log.w(this, "setRinging, unknown call id: %s", msg.obj);
                    }
                    break;
                case MSG_SET_DIALING:
                    call = mCallIdMapper.getCall(msg.obj);
                    if (call != null) {
                        mCallsManager.markCallAsDialing(call);
                    } else {
                        //Log.w(this, "setDialing, unknown call id: %s", msg.obj);
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
                            //Log.w(this, "setDisconnected, unknown call id: %s", args.arg1);
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
                        //Log.w(this, "setOnHold, unknown call id: %s", msg.obj);
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
                            //Log.w(this, "setRingback, unknown call id: %s", args.arg1);
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
                        //Log.w(ConnectionServiceWrapper.this,
                        //      "canConference, unknown call id: %s", msg.obj);
                    }
                    break;
                }
                case MSG_SET_IS_CONFERENCED: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        Call childCall = mCallIdMapper.getCall(args.arg1);
                        if (childCall != null) {
                            String conferenceCallId = (String) args.arg2;
                            if (conferenceCallId == null) {
                                childCall.setParentCall(null);
                            } else {
                                Call conferenceCall = mCallIdMapper.getCall(conferenceCallId);
                                if (conferenceCall != null &&
                                        !mPendingConferenceCalls.contains(conferenceCall)) {
                                    childCall.setParentCall(conferenceCall);
                                } else {
                                    //Log.w(this, "setIsConferenced, unknown conference id %s",
                                    //        conferenceCallId);
                                }
                            }
                        } else {
                            //Log.w(this, "setIsConferenced, unknown call id: %s", args.arg1);
                        }
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_ADD_CONFERENCE_CALL: {
                    Call conferenceCall = mCallIdMapper.getCall(msg.obj);
                    if (mPendingConferenceCalls.remove(conferenceCall)) {
                        Log.v(this, "confirming conf call %s", conferenceCall);
                        conferenceCall.confirmConference();
                    } else {
                        //Log.w(this, "addConference, unknown call id: %s", callId);
                    }
                    break;
                }
                case MSG_REMOVE_CALL:
                    break;
                case MSG_ON_POST_DIAL_WAIT: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        call = mCallIdMapper.getCall(args.arg1);
                        if (call != null) {
                            String remaining = (String) args.arg2;
                            call.onPostDialWait(remaining);
                        } else {
                            //Log.w(this, "onPostDialWait, unknown call id: %s", args.arg1);
                        }
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_QUERY_REMOTE_CALL_SERVICES: {
                    ConnectionServiceWrapper.this.queryRemoteConnectionServices(
                            (RemoteServiceCallback) msg.obj);
                    break;
                }
                case MSG_SET_CALL_VIDEO_PROVIDER: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        call = mCallIdMapper.getCall(args.arg1);
                        ICallVideoProvider callVideoProvider = (ICallVideoProvider) args.arg2;
                        if (call != null) {
                            call.setCallVideoProvider(callVideoProvider);
                        }
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_SET_FEATURES: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        call = mCallIdMapper.getCall(args.arg1);
                        int features = (int) args.argi1;
                        if (call != null) {
                            call.setFeatures(features);
                        }
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_SET_AUDIO_MODE_IS_VOIP: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        call = mCallIdMapper.getCall(args.arg1);
                        boolean isVoip = args.argi1 == 1;
                        if (call != null) {
                            call.setAudioModeIsVoip(isVoip);
                        }
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_SET_STATUS_HINTS: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        call = mCallIdMapper.getCall(args.arg1);
                        StatusHints statusHints = (StatusHints) args.arg2;
                        if (call != null) {
                            call.setStatusHints(statusHints);
                        }
                    } finally {
                        args.recycle();
                    }
                    break;
                }
            }
        }
    };

    private final class Adapter extends IConnectionServiceAdapter.Stub {
        /** {@inheritDoc} */
        @Override
        public void notifyIncomingCall(ConnectionRequest request) {
            logIncoming("notifyIncomingCall %s", request);
            mCallIdMapper.checkValidCallId(request.getCallId());
            mHandler.obtainMessage(MSG_NOTIFY_INCOMING_CALL, request).sendToTarget();
        }

        /** {@inheritDoc} */
        @Override
        public void handleSuccessfulOutgoingCall(ConnectionRequest request) {
            logIncoming("handleSuccessfulOutgoingCall %s", request);
            mCallIdMapper.checkValidCallId(request.getCallId());
            mHandler.obtainMessage(MSG_HANDLE_SUCCESSFUL_OUTGOING_CALL, request).sendToTarget();
        }

        /** {@inheritDoc} */
        @Override
        public void handleFailedOutgoingCall(
                ConnectionRequest request,
                int errorCode,
                String errorMsg) {
            logIncoming("handleFailedOutgoingCall %s %d %s", request, errorCode, errorMsg);
            mCallIdMapper.checkValidCallId(request.getCallId());
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = request;
            args.argi1 = errorCode;
            args.arg2 = errorMsg;
            mHandler.obtainMessage(MSG_HANDLE_FAILED_OUTGOING_CALL, args).sendToTarget();
        }

        /** {@inheritDoc} */
        @Override
        public void cancelOutgoingCall(ConnectionRequest request) {
            logIncoming("cancelOutgoingCall %s", request);
            mCallIdMapper.checkValidCallId(request.getCallId());
            mHandler.obtainMessage(MSG_CANCEL_OUTGOING_CALL, request).sendToTarget();
        }

        /** {@inheritDoc} */
        @Override
        public void setActive(String callId) {
            logIncoming("setActive %s", callId);
            mCallIdMapper.checkValidCallId(callId);
            mHandler.obtainMessage(MSG_SET_ACTIVE, callId).sendToTarget();
        }

        /** {@inheritDoc} */
        @Override
        public void setRinging(String callId) {
            logIncoming("setRinging %s", callId);
            mCallIdMapper.checkValidCallId(callId);
            mHandler.obtainMessage(MSG_SET_RINGING, callId).sendToTarget();
        }

        /** {@inheritDoc} */
        @Override
        public void setCallVideoProvider(String callId, ICallVideoProvider callVideoProvider) {
            logIncoming("setCallVideoProvider %s", callId);
            mCallIdMapper.checkValidCallId(callId);
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = callId;
            args.arg2 = callVideoProvider;
            mHandler.obtainMessage(MSG_SET_CALL_VIDEO_PROVIDER, args).sendToTarget();
        }

        /** {@inheritDoc} */
        @Override
        public void setDialing(String callId) {
            logIncoming("setDialing %s", callId);
            mCallIdMapper.checkValidCallId(callId);
            mHandler.obtainMessage(MSG_SET_DIALING, callId).sendToTarget();
        }

        /** {@inheritDoc} */
        @Override
        public void setDisconnected(
                String callId, int disconnectCause, String disconnectMessage) {
            logIncoming("setDisconnected %s %d %s", callId, disconnectCause, disconnectMessage);
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
            logIncoming("setOnHold %s", callId);
            mCallIdMapper.checkValidCallId(callId);
            mHandler.obtainMessage(MSG_SET_ON_HOLD, callId).sendToTarget();
        }

        /** {@inheritDoc} */
        @Override
        public void setRequestingRingback(String callId, boolean ringback) {
            logIncoming("setRequestingRingback %s %b", callId, ringback);
            mCallIdMapper.checkValidCallId(callId);
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = callId;
            args.arg2 = ringback;
            mHandler.obtainMessage(MSG_SET_REQUESTING_RINGBACK, args).sendToTarget();
        }

        /** ${inheritDoc} */
        @Override
        public void removeCall(String callId) {
            logIncoming("removeCall %s", callId);
        }

        /** ${inheritDoc} */
        @Override
        public void setCanConference(String callId, boolean canConference) {
            logIncoming("setCanConference %s %b", callId, canConference);
            mHandler.obtainMessage(MSG_CAN_CONFERENCE, canConference ? 1 : 0, 0, callId)
                    .sendToTarget();
        }

        /** ${inheritDoc} */
        @Override
        public void setIsConferenced(String callId, String conferenceCallId) {
            logIncoming("setIsConferenced %s %s", callId, conferenceCallId);
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = callId;
            args.arg2 = conferenceCallId;
            mHandler.obtainMessage(MSG_SET_IS_CONFERENCED, args).sendToTarget();
        }

        /** ${InheritDoc} */
        @Override
        public void addConferenceCall(String callId) {
            logIncoming("addConferenceCall %s", callId);
            mCallIdMapper.checkValidCallId(callId);
            mHandler.obtainMessage(MSG_ADD_CONFERENCE_CALL, callId).sendToTarget();
        }

        @Override
        public void onPostDialWait(String callId, String remaining) throws RemoteException {
            logIncoming("onPostDialWait %s %s", callId, remaining);
            mCallIdMapper.checkValidCallId(callId);
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = callId;
            args.arg2 = remaining;
            mHandler.obtainMessage(MSG_ON_POST_DIAL_WAIT, args).sendToTarget();
        }

        /** ${inheritDoc} */
        @Override
        public void queryRemoteConnectionServices(RemoteServiceCallback callback) {
            logIncoming("queryRemoteCSs");
            mHandler.obtainMessage(MSG_QUERY_REMOTE_CALL_SERVICES, callback).sendToTarget();
        }

        @Override
        public void setFeatures(String callId, int features) {
            logIncoming("setFeatures %s %d", callId, features);
            mCallIdMapper.checkValidCallId(callId);
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = callId;
            args.argi1 = features;
            mHandler.obtainMessage(MSG_SET_FEATURES, args).sendToTarget();
        }

        @Override
        public void setAudioModeIsVoip(String callId, boolean isVoip) {
            logIncoming("setAudioModeIsVoip %s %b", callId, isVoip);
            mCallIdMapper.checkValidCallId(callId);
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = callId;
            args.argi1 = isVoip ? 1 : 0;
            mHandler.obtainMessage(MSG_SET_AUDIO_MODE_IS_VOIP, args).sendToTarget();
        }

        @Override
        public void setStatusHints(String callId, StatusHints statusHints) {
            logIncoming("setStatusHints %s %s", callId, statusHints);
            mCallIdMapper.checkValidCallId(callId);
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = callId;
            args.arg2 = statusHints;
            mHandler.obtainMessage(MSG_SET_STATUS_HINTS, args).sendToTarget();
        }
    }

    private final Adapter mAdapter = new Adapter();
    private final CallsManager mCallsManager = CallsManager.getInstance();
    private final Set<Call> mPendingIncomingCalls = new HashSet<>();
    private final Set<Call> mPendingConferenceCalls = new HashSet<>();
    private final CallServiceDescriptor mDescriptor;
    private final CallIdMapper mCallIdMapper = new CallIdMapper("ConnectionService");
    private final IncomingCallsManager mIncomingCallsManager;
    private final Map<String, OutgoingCallResponse> mPendingOutgoingCalls = new HashMap<>();

    private Binder mBinder = new Binder();
    private IConnectionService mServiceInterface;
    private final CallServiceRepository mCallServiceRepository;

    /**
     * Creates a call-service for the specified descriptor.
     *
     * @param descriptor The call-service descriptor from
     *            {@link ICallServiceProvider#lookupCallServices}.
     * @param incomingCallsManager Manages the incoming call initialization flow.
     * @param callServiceRepository Connection service repository.
     */
    ConnectionServiceWrapper(
            CallServiceDescriptor descriptor,
            IncomingCallsManager incomingCallsManager,
            CallServiceRepository callServiceRepository) {
        super(TelecommConstants.ACTION_CONNECTION_SERVICE, descriptor.getServiceComponent());
        mDescriptor = descriptor;
        mIncomingCallsManager = incomingCallsManager;
        mCallServiceRepository = callServiceRepository;
    }

    CallServiceDescriptor getDescriptor() {
        return mDescriptor;
    }

    /** See {@link IConnectionService#addConnectionServiceAdapter}. */
    private void addConnectionServiceAdapter(IConnectionServiceAdapter adapter) {
        if (isServiceValid("addConnectionServiceAdapter")) {
            try {
                logOutgoing("addConnectionServiceAdapter %s", adapter);
                mServiceInterface.addConnectionServiceAdapter(adapter);
            } catch (RemoteException e) {
            }
        }
    }

    /**
     * Attempts to place the specified call, see {@link IConnectionService#call}. Returns the result
     * asynchronously through the specified callback.
     */
    void call(final Call call, final OutgoingCallResponse callResponse) {
        Log.d(this, "call(%s) via %s.", call, getComponentName());
        BindCallback callback = new BindCallback() {
            @Override
            public void onSuccess() {
                String callId = mCallIdMapper.getCallId(call);
                mPendingOutgoingCalls.put(callId, callResponse);

                GatewayInfo gatewayInfo = call.getGatewayInfo();
                Bundle extras = call.getExtras();
                if (gatewayInfo != null && gatewayInfo.getGatewayProviderPackageName() != null &&
                        gatewayInfo.getOriginalHandle() != null) {
                    extras = (Bundle) extras.clone();
                    extras.putString(
                            NewOutgoingCallIntentBroadcaster.EXTRA_GATEWAY_PROVIDER_PACKAGE,
                            gatewayInfo.getGatewayProviderPackageName());
                    extras.putParcelable(
                            NewOutgoingCallIntentBroadcaster.EXTRA_GATEWAY_ORIGINAL_URI,
                            gatewayInfo.getOriginalHandle());
                }
                ConnectionRequest request = new ConnectionRequest(callId, call.getHandle(), extras,
                        call.getVideoState());

                try {
                    mServiceInterface.call(request);
                } catch (RemoteException e) {
                    Log.e(this, e, "Failure to call -- %s", getDescriptor());
                    mPendingOutgoingCalls.remove(callId).onOutgoingCallFailure(
                            DisconnectCause.ERROR_UNSPECIFIED, e.toString());
                }
            }

            @Override
            public void onFailure() {
                Log.e(this, new Exception(), "Failure to call %s", getDescriptor());
                callResponse.onOutgoingCallFailure(DisconnectCause.ERROR_UNSPECIFIED, null);
            }
        };

        mBinder.bind(callback);
    }

    /** @see ConnectionService#abort(String) */
    void abort(Call call) {
        // Clear out any pending outgoing call data
        String callId = mCallIdMapper.getCallId(call);

        // If still bound, tell the connection service to abort.
        if (isServiceValid("abort")) {
            try {
                logOutgoing("abort %s", callId);
                mServiceInterface.abort(callId);
            } catch (RemoteException e) {
            }
        }

        removeCall(call);
    }

    /** @see ConnectionService#hold(String) */
    void hold(Call call) {
        if (isServiceValid("hold")) {
            try {
                logOutgoing("hold %s", mCallIdMapper.getCallId(call));
                mServiceInterface.hold(mCallIdMapper.getCallId(call));
            } catch (RemoteException e) {
            }
        }
    }

    /** @see ConnectionService#unhold(String) */
    void unhold(Call call) {
        if (isServiceValid("unhold")) {
            try {
                logOutgoing("unhold %s", mCallIdMapper.getCallId(call));
                mServiceInterface.unhold(mCallIdMapper.getCallId(call));
            } catch (RemoteException e) {
            }
        }
    }

    /** @see ConnectionService#onAudioStateChanged(String,CallAudioState) */
    void onAudioStateChanged(Call activeCall, CallAudioState audioState) {
        if (isServiceValid("onAudioStateChanged")) {
            try {
                logOutgoing("onAudioStateChanged %s %s",
                        mCallIdMapper.getCallId(activeCall), audioState);
                mServiceInterface.onAudioStateChanged(mCallIdMapper.getCallId(activeCall),
                        audioState);
            } catch (RemoteException e) {
            }
        }
    }

    /**
     * Starts retrieval of details for an incoming call. Details are returned through the
     * call-service adapter using the specified call ID. Upon failure, the specified error callback
     * is invoked. Can be invoked even when the connection service is unbound. See
     * {@link IConnectionService#createIncomingCall}.
     *
     * @param call The call used for the incoming call.
     * @param extras The {@link ConnectionService}-provided extras which need to be sent back.
     * @param errorCallback The callback to invoke upon failure.
     */
    void createIncomingCall(final Call call, final Bundle extras, final Runnable errorCallback) {
        Log.d(this, "createIncomingCall(%s) via %s.", call, getComponentName());
        BindCallback callback = new BindCallback() {
            @Override
            public void onSuccess() {
                if (isServiceValid("createIncomingCall")) {
                    mPendingIncomingCalls.add(call);
                    String callId = mCallIdMapper.getCallId(call);
                    logOutgoing("createIncomingCall %s %s", callId, extras);
                    ConnectionRequest request = new ConnectionRequest(
                            callId, call.getHandle(), extras, call.getVideoState());
                    try {
                        mServiceInterface.createIncomingCall(request);
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

    /** @see ConnectionService#disconnect(String) */
    void disconnect(Call call) {
        if (isServiceValid("disconnect")) {
            try {
                logOutgoing("disconnect %s", mCallIdMapper.getCallId(call));
                mServiceInterface.disconnect(mCallIdMapper.getCallId(call));
            } catch (RemoteException e) {
            }
        }
    }

    /** @see ConnectionService#answer(String) */
    void answer(Call call) {
        if (isServiceValid("answer")) {
            try {
                logOutgoing("answer %s", mCallIdMapper.getCallId(call));
                mServiceInterface.answer(mCallIdMapper.getCallId(call));
            } catch (RemoteException e) {
            }
        }
    }

    /** @see ConnectionService#reject(String) */
    void reject(Call call) {
        if (isServiceValid("reject")) {
            try {
                logOutgoing("reject %s", mCallIdMapper.getCallId(call));
                mServiceInterface.reject(mCallIdMapper.getCallId(call));
            } catch (RemoteException e) {
            }
        }
    }

    /** @see ConnectionService#playDtmfTone(String,char) */
    void playDtmfTone(Call call, char digit) {
        if (isServiceValid("playDtmfTone")) {
            try {
                logOutgoing("playDtmfTone %s %c", mCallIdMapper.getCallId(call), digit);
                mServiceInterface.playDtmfTone(mCallIdMapper.getCallId(call), digit);
            } catch (RemoteException e) {
            }
        }
    }

    /** @see ConnectionService#stopDtmfTone(String) */
    void stopDtmfTone(Call call) {
        if (isServiceValid("stopDtmfTone")) {
            try {
                logOutgoing("stopDtmfTone %s", mCallIdMapper.getCallId(call));
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
     * Associates newCall with this connection service by replacing callToReplace.
     */
    void replaceCall(Call newCall, Call callToReplace) {
        Preconditions.checkState(callToReplace.getConnectionService() == this);
        mCallIdMapper.replaceCall(newCall, callToReplace);
    }

    void removeCall(Call call) {
        mPendingIncomingCalls.remove(call);

        OutgoingCallResponse outgoingResultCallback =
                mPendingOutgoingCalls.remove(mCallIdMapper.getCallId(call));
        if (outgoingResultCallback != null) {
            outgoingResultCallback.onOutgoingCallFailure(DisconnectCause.ERROR_UNSPECIFIED, null);
        }

        mCallIdMapper.removeCall(call);
    }

    void onPostDialContinue(Call call, boolean proceed) {
        if (isServiceValid("onPostDialContinue")) {
            try {
                logOutgoing("onPostDialContinue %s %b", mCallIdMapper.getCallId(call), proceed);
                mServiceInterface.onPostDialContinue(mCallIdMapper.getCallId(call), proceed);
            } catch (RemoteException ignored) {
            }
        }
    }

    void onPhoneAccountClicked(Call call) {
        if (isServiceValid("onPhoneAccountClicked")) {
            try {
                logOutgoing("onPhoneAccountClicked %s", mCallIdMapper.getCallId(call));
                mServiceInterface.onPhoneAccountClicked(mCallIdMapper.getCallId(call));
            } catch (RemoteException ignored) {
            }
        }
    }

    void conference(final Call conferenceCall, Call call) {
        if (isServiceValid("conference")) {
            try {
                conferenceCall.setConnectionService(this);
                mPendingConferenceCalls.add(conferenceCall);
                mHandler.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (mPendingConferenceCalls.remove(conferenceCall)) {
                            conferenceCall.expireConference();
                            Log.i(this, "Conference call expired: %s", conferenceCall);
                        }
                    }
                }, Timeouts.getConferenceCallExpireMillis());

                logOutgoing("conference %s %s",
                        mCallIdMapper.getCallId(conferenceCall),
                        mCallIdMapper.getCallId(call));
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
                logOutgoing("splitFromConference %s", mCallIdMapper.getCallId(call));
                mServiceInterface.splitFromConference(mCallIdMapper.getCallId(call));
            } catch (RemoteException ignored) {
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    protected void setServiceInterface(IBinder binder) {
        if (binder == null) {
            // We have lost our service connection. Notify the world that this service is done.
            // We must notify the adapter before CallsManager. The adapter will force any pending
            // outgoing calls to try the next service. This needs to happen before CallsManager
            // tries to clean up any calls still associated with this service.
            handleConnectionServiceDeath();
            CallsManager.getInstance().handleConnectionServiceDeath(this);
            mServiceInterface = null;
        } else {
            mServiceInterface = IConnectionService.Stub.asInterface(binder);
            addConnectionServiceAdapter(mAdapter);
        }
    }

    /**
     * Called when the associated connection service dies.
     */
    private void handleConnectionServiceDeath() {
        if (!mPendingOutgoingCalls.isEmpty()) {
            for (OutgoingCallResponse callback : mPendingOutgoingCalls.values()) {
                callback.onOutgoingCallFailure(DisconnectCause.ERROR_UNSPECIFIED, null);
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

    private void logIncoming(String msg, Object... params) {
        Log.d(this, "ConnectionService -> Telecomm: " + msg, params);
    }

    private void logOutgoing(String msg, Object... params) {
        Log.d(this, "Telecomm -> ConnectionService: " + msg, params);
    }

    private void queryRemoteConnectionServices(final RemoteServiceCallback callback) {
        final List<IBinder> connectionServices = new ArrayList<>();
        final List<ComponentName> components = new ArrayList<>();

        mCallServiceRepository.lookupServices(new LookupCallback<ConnectionServiceWrapper>() {
            private int mRemainingResponses;

            /** ${inheritDoc} */
            @Override
            public void onComplete(Collection<ConnectionServiceWrapper> services) {
                mRemainingResponses = services.size() - 1;
                for (ConnectionServiceWrapper cs : services) {
                    if (cs != ConnectionServiceWrapper.this) {
                        final ConnectionServiceWrapper currentConnectionService = cs;
                        cs.mBinder.bind(new BindCallback() {
                            @Override
                            public void onSuccess() {
                                Log.d(this, "Adding ***** %s",
                                        currentConnectionService.getDescriptor());
                                connectionServices.add(
                                        currentConnectionService.mServiceInterface.asBinder());
                                components.add(currentConnectionService.getComponentName());
                                maybeComplete();
                            }

                            @Override
                            public void onFailure() {
                                // add null so that we always add up to totalExpected even if
                                // some of the connection services fail to bind.
                                maybeComplete();
                            }

                            private void maybeComplete() {
                                if (--mRemainingResponses == 0) {
                                    try {
                                        callback.onResult(components, connectionServices);
                                    } catch (RemoteException ignored) {
                                    }
                                }
                            }
                        });
                    }
                }
            }
        });
    }
}
