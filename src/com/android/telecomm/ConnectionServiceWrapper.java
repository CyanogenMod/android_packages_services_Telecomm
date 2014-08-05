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

import android.app.PendingIntent;
import android.content.ComponentName;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.telecomm.CallAudioState;
import android.telecomm.ConnectionRequest;
import android.telecomm.ConnectionService;
import android.telecomm.GatewayInfo;
import android.telecomm.ParcelableConnection;
import android.telecomm.PhoneAccount;
import android.telecomm.PhoneAccountHandle;
import android.telecomm.StatusHints;
import android.telephony.DisconnectCause;

import com.android.internal.os.SomeArgs;
import com.android.internal.telecomm.IConnectionService;
import com.android.internal.telecomm.IConnectionServiceAdapter;
import com.android.internal.telecomm.IVideoCallProvider;
import com.android.internal.telecomm.RemoteServiceCallback;
import com.google.common.base.Preconditions;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
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
    private static final int MSG_HANDLE_CREATE_CONNECTION_SUCCESSFUL = 1;
    private static final int MSG_HANDLE_CREATE_CONNECTION_FAILED = 2;
    private static final int MSG_HANDLE_CREATE_CONNECTION_CANCELLED = 3;
    private static final int MSG_SET_ACTIVE = 4;
    private static final int MSG_SET_RINGING = 5;
    private static final int MSG_SET_DIALING = 6;
    private static final int MSG_SET_DISCONNECTED = 7;
    private static final int MSG_SET_ON_HOLD = 8;
    private static final int MSG_SET_REQUESTING_RINGBACK = 9;
    private static final int MSG_SET_CALL_CAPABILITIES = 10;
    private static final int MSG_SET_IS_CONFERENCED = 11;
    private static final int MSG_ADD_CONFERENCE_CALL = 12;
    private static final int MSG_REMOVE_CALL = 13;
    private static final int MSG_ON_POST_DIAL_WAIT = 14;
    private static final int MSG_QUERY_REMOTE_CALL_SERVICES = 15;
    private static final int MSG_SET_CALL_VIDEO_PROVIDER = 16;
    private static final int MSG_SET_AUDIO_MODE_IS_VOIP = 17;
    private static final int MSG_SET_STATUS_HINTS = 18;
    private static final int MSG_SET_HANDLE = 19;
    private static final int MSG_SET_CALLER_DISPLAY_NAME = 20;
    private static final int MSG_SET_VIDEO_STATE = 21;
    private static final int MSG_START_ACTIVITY_FROM_IN_CALL = 22;

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            Call call;
            switch (msg.what) {
                case MSG_HANDLE_CREATE_CONNECTION_SUCCESSFUL: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        ConnectionRequest request = (ConnectionRequest) args.arg1;
                        if (mPendingResponses.containsKey(request.getCallId())) {
                            ParcelableConnection connection = (ParcelableConnection) args.arg2;
                            mPendingResponses.remove(request.getCallId()).
                                    handleCreateConnectionSuccessful(request, connection);
                        }
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_HANDLE_CREATE_CONNECTION_FAILED: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        ConnectionRequest request = (ConnectionRequest) args.arg1;
                        int statusCode = args.argi1;
                        String statusMsg = (String) args.arg2;
                        removeCall(
                                mCallIdMapper.getCall(request.getCallId()),
                                statusCode,
                                statusMsg);
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_HANDLE_CREATE_CONNECTION_CANCELLED: {
                    ConnectionRequest request = (ConnectionRequest) msg.obj;
                    if (mPendingResponses.containsKey(request.getCallId())) {
                        mPendingResponses.remove(
                                request.getCallId()).handleCreateConnectionCancelled();
                    } else {
                        //Log.w(this, "handleCreateConnectionCancelled, unknown call: %s", callId);
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
                    call = mCallIdMapper.getCall(msg.obj);
                    if (call != null) {
                        call.setRequestingRingback(msg.arg1 == 1);
                    } else {
                        //Log.w(this, "setRingback, unknown call id: %s", args.arg1);
                    }
                    break;
                }
                case MSG_SET_CALL_CAPABILITIES: {
                    call = mCallIdMapper.getCall(msg.obj);
                    if (call != null) {
                        call.setCallCapabilities(msg.arg1);
                    } else {
                        //Log.w(ConnectionServiceWrapper.this,
                        //      "setCallCapabilities, unknown call id: %s", msg.obj);
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
                    queryRemoteConnectionServices((RemoteServiceCallback) msg.obj);
                    break;
                }
                case MSG_SET_CALL_VIDEO_PROVIDER: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        call = mCallIdMapper.getCall(args.arg1);
                        IVideoCallProvider videoCallProvider = (IVideoCallProvider) args.arg2;
                        if (call != null) {
                            call.setVideoCallProvider(videoCallProvider);
                        }
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_SET_AUDIO_MODE_IS_VOIP: {
                    call = mCallIdMapper.getCall(msg.obj);
                    if (call != null) {
                        call.setAudioModeIsVoip(msg.arg1 == 1);
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
                case MSG_SET_HANDLE: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        call = mCallIdMapper.getCall(args.arg1);
                        if (call != null) {
                            call.setHandle((Uri) args.arg2, args.argi1);
                        }
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_SET_CALLER_DISPLAY_NAME: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        call = mCallIdMapper.getCall(args.arg1);
                        if (call != null) {
                            call.setCallerDisplayName((String) args.arg2, args.argi1);
                        }
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_SET_VIDEO_STATE: {
                    call = mCallIdMapper.getCall(msg.obj);
                    if (call != null) {
                        call.setVideoState(msg.arg1);
                    }
                    break;
                }
                case MSG_START_ACTIVITY_FROM_IN_CALL: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        call = mCallIdMapper.getCall(args.arg1);
                        if (call != null) {
                            call.startActivityFromInCall((PendingIntent) args.arg2);
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
        @Override
        public void handleCreateConnectionSuccessful(
                ConnectionRequest request, ParcelableConnection connection) {

            logIncoming("handleCreateConnectionSuccessful %s", request);
            mCallIdMapper.checkValidCallId(request.getCallId());
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = request;
            args.arg2 = connection;
            mHandler.obtainMessage(MSG_HANDLE_CREATE_CONNECTION_SUCCESSFUL, args).sendToTarget();
        }

        @Override
        public void handleCreateConnectionFailed(
                ConnectionRequest request, int errorCode, String errorMsg) {
            logIncoming("handleCreateConnectionFailed %s %d %s", request, errorCode, errorMsg);
            mCallIdMapper.checkValidCallId(request.getCallId());
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = request;
            args.argi1 = errorCode;
            args.arg2 = errorMsg;
            mHandler.obtainMessage(MSG_HANDLE_CREATE_CONNECTION_FAILED, args).sendToTarget();
        }

        @Override
        public void handleCreateConnectionCancelled(ConnectionRequest request) {
            logIncoming("handleCreateConnectionCancelled %s", request);
            mCallIdMapper.checkValidCallId(request.getCallId());
            mHandler.obtainMessage(MSG_HANDLE_CREATE_CONNECTION_CANCELLED, request).sendToTarget();
        }

        @Override
        public void setActive(String callId) {
            logIncoming("setActive %s", callId);
            mCallIdMapper.checkValidCallId(callId);
            mHandler.obtainMessage(MSG_SET_ACTIVE, callId).sendToTarget();
        }

        @Override
        public void setRinging(String callId) {
            logIncoming("setRinging %s", callId);
            mCallIdMapper.checkValidCallId(callId);
            mHandler.obtainMessage(MSG_SET_RINGING, callId).sendToTarget();
        }

        @Override
        public void setVideoCallProvider(String callId, IVideoCallProvider videoCallProvider) {
            logIncoming("setVideoCallProvider %s", callId);
            mCallIdMapper.checkValidCallId(callId);
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = callId;
            args.arg2 = videoCallProvider;
            mHandler.obtainMessage(MSG_SET_CALL_VIDEO_PROVIDER, args).sendToTarget();
        }

        @Override
        public void setDialing(String callId) {
            logIncoming("setDialing %s", callId);
            mCallIdMapper.checkValidCallId(callId);
            mHandler.obtainMessage(MSG_SET_DIALING, callId).sendToTarget();
        }

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

        @Override
        public void setOnHold(String callId) {
            logIncoming("setOnHold %s", callId);
            mCallIdMapper.checkValidCallId(callId);
            mHandler.obtainMessage(MSG_SET_ON_HOLD, callId).sendToTarget();
        }

        @Override
        public void setRequestingRingback(String callId, boolean ringback) {
            logIncoming("setRequestingRingback %s %b", callId, ringback);
            mCallIdMapper.checkValidCallId(callId);
            mHandler.obtainMessage(MSG_SET_REQUESTING_RINGBACK, ringback ? 1 : 0, 0, callId)
                    .sendToTarget();
        }

        @Override
        public void removeCall(String callId) {
            logIncoming("removeCall %s", callId);
        }

        @Override
        public void setCallCapabilities(String callId, int callCapabilities) {
            logIncoming("setCallCapabilities %s %d", callId, callCapabilities);
            mHandler.obtainMessage(MSG_SET_CALL_CAPABILITIES, callCapabilities, 0, callId)
                    .sendToTarget();
        }

        @Override
        public void setIsConferenced(String callId, String conferenceCallId) {
            logIncoming("setIsConferenced %s %s", callId, conferenceCallId);
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = callId;
            args.arg2 = conferenceCallId;
            mHandler.obtainMessage(MSG_SET_IS_CONFERENCED, args).sendToTarget();
        }

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

        @Override
        public void queryRemoteConnectionServices(RemoteServiceCallback callback) {
            logIncoming("queryRemoteCSs");
            mHandler.obtainMessage(MSG_QUERY_REMOTE_CALL_SERVICES, callback).sendToTarget();
        }

        @Override
        public void setVideoState(String callId, int videoState) {
            logIncoming("setVideoState %s %d", callId, videoState);
            mCallIdMapper.checkValidCallId(callId);
            mHandler.obtainMessage(MSG_SET_VIDEO_STATE, videoState, 0, callId).sendToTarget();
        }

        @Override
        public void setAudioModeIsVoip(String callId, boolean isVoip) {
            logIncoming("setAudioModeIsVoip %s %b", callId, isVoip);
            mCallIdMapper.checkValidCallId(callId);
            mHandler.obtainMessage(MSG_SET_AUDIO_MODE_IS_VOIP, isVoip ? 1 : 0, 0,
                    callId).sendToTarget();
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

        @Override
        public void setHandle(String callId, Uri handle, int presentation) {
            logIncoming("setHandle %s %s %d", callId, handle, presentation);
            mCallIdMapper.checkValidCallId(callId);
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = callId;
            args.arg2 = handle;
            args.argi1 = presentation;
            mHandler.obtainMessage(MSG_SET_HANDLE, args).sendToTarget();
        }

        @Override
        public void setCallerDisplayName(
                String callId, String callerDisplayName, int presentation) {
            logIncoming("setCallerDisplayName %s %s %d", callId, callerDisplayName, presentation);
            mCallIdMapper.checkValidCallId(callId);
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = callId;
            args.arg2 = callerDisplayName;
            args.argi1 = presentation;
            mHandler.obtainMessage(MSG_SET_CALLER_DISPLAY_NAME, args).sendToTarget();
        }

        @Override
        public void startActivityFromInCall(String callId, PendingIntent intent) {
            logIncoming("startActivityFromInCall %s %s", callId, intent);
            mCallIdMapper.checkValidCallId(callId);
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = callId;
            args.arg2 = intent;
            mHandler.obtainMessage(MSG_START_ACTIVITY_FROM_IN_CALL, args).sendToTarget();
        }
    }

    private final Adapter mAdapter = new Adapter();
    private final CallsManager mCallsManager = CallsManager.getInstance();
    private final Set<Call> mPendingConferenceCalls = new HashSet<>();
    private final CallIdMapper mCallIdMapper = new CallIdMapper("ConnectionService");
    private final Map<String, CreateConnectionResponse> mPendingResponses = new HashMap<>();

    private Binder mBinder = new Binder();
    private IConnectionService mServiceInterface;
    private final ConnectionServiceRepository mConnectionServiceRepository;

    /**
     * Creates a connection service.
     *
     * @param componentName The component name of the service with which to bind.
     * @param connectionServiceRepository Connection service repository.
     * @param phoneAccountRegistrar Phone account registrar
     */
    ConnectionServiceWrapper(
            ComponentName componentName,
            ConnectionServiceRepository connectionServiceRepository,
            PhoneAccountRegistrar phoneAccountRegistrar) {
        super(ConnectionService.SERVICE_INTERFACE, componentName);
        mConnectionServiceRepository = connectionServiceRepository;
        phoneAccountRegistrar.addListener(new PhoneAccountRegistrar.Listener() {
            // TODO -- Upon changes to PhoneAccountRegistrar, need to re-wire connections
            // To do this, we must proxy remote ConnectionService objects
        });
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
     * Creates a new connection for a new outgoing call or to attach to an existing incoming call.
     */
    void createConnection(final Call call, final CreateConnectionResponse response) {
        Log.d(this, "createConnection(%s) via %s.", call, getComponentName());
        BindCallback callback = new BindCallback() {
            @Override
            public void onSuccess() {
                String callId = mCallIdMapper.getCallId(call);
                mPendingResponses.put(callId, response);

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

                try {
                    mServiceInterface.createConnection(
                            call.getConnectionManagerPhoneAccount(),
                            new ConnectionRequest(
                                    call.getTargetPhoneAccount(),
                                    callId,
                                    call.getHandle(),
                                    call.getHandlePresentation(),
                                    extras,
                                    call.getVideoState()),
                            call.isIncoming());
                } catch (RemoteException e) {
                    Log.e(this, e, "Failure to createConnection -- %s", getComponentName());
                    mPendingResponses.remove(callId).handleCreateConnectionFailed(
                            DisconnectCause.OUTGOING_FAILURE, e.toString());
                }
            }

            @Override
            public void onFailure() {
                Log.e(this, new Exception(), "Failure to call %s", getComponentName());
                response.handleCreateConnectionFailed(DisconnectCause.OUTGOING_FAILURE, null);
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

        removeCall(call, DisconnectCause.LOCAL, null);
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

    /** @see ConnectionService#answer(String,int) */
    void answer(Call call, int videoState) {
        if (isServiceValid("answer")) {
            try {
                logOutgoing("answer %s %d", mCallIdMapper.getCallId(call), videoState);
                mServiceInterface.answer(mCallIdMapper.getCallId(call), videoState);
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
        removeCall(call, DisconnectCause.ERROR_UNSPECIFIED, null);
    }

    void removeCall(Call call, int disconnectCause, String disconnectMessage) {
        CreateConnectionResponse response = mPendingResponses.remove(mCallIdMapper.getCallId(call));
        if (response != null) {
            response.handleCreateConnectionFailed(disconnectCause, disconnectMessage);
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

    void swapWithBackgroundCall(Call call) {
        if (isServiceValid("swapWithBackgroundCall")) {
            try {
                logOutgoing("swapWithBackgroundCall %s", mCallIdMapper.getCallId(call));
                mServiceInterface.swapWithBackgroundCall(mCallIdMapper.getCallId(call));
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
        if (!mPendingResponses.isEmpty()) {
            CreateConnectionResponse[] responses = mPendingResponses.values().toArray(
                    new CreateConnectionResponse[mPendingResponses.values().size()]);
            mPendingResponses.clear();
            for (int i = 0; i < responses.length; i++) {
                responses[i].handleCreateConnectionFailed(DisconnectCause.ERROR_UNSPECIFIED, null);
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
        PhoneAccountRegistrar registrar = TelecommApp.getInstance().getPhoneAccountRegistrar();

        // Only give remote connection services to this connection service if it is listed as
        // the connection manager.
        PhoneAccountHandle simCallManager = registrar.getSimCallManager();
        Log.d(this, "queryRemoteConnectionServices finds simCallManager = %s", simCallManager);
        if (simCallManager == null ||
                !simCallManager.getComponentName().equals(getComponentName())) {
            noRemoteServices(callback);
            return;
        }

        // Make a list of ConnectionServices that are listed as being associated with SIM accounts
        final Set<ConnectionServiceWrapper> simServices = new HashSet<>();
        for (PhoneAccountHandle handle : registrar.getEnabledPhoneAccounts()) {
            PhoneAccount account = registrar.getPhoneAccount(handle);
            if ((account.getCapabilities() & PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION) != 0) {
                ConnectionServiceWrapper service =
                        mConnectionServiceRepository.getService(handle.getComponentName());
                if (service != null) {
                    simServices.add(service);
                }
            }
        }

        final List<ComponentName> simServiceComponentNames = new ArrayList<>();
        final List<IBinder> simServiceBinders = new ArrayList<>();

        Log.v(this, "queryRemoteConnectionServices, simServices = %s", simServices);

        for (ConnectionServiceWrapper simService : simServices) {
            if (simService == this) {
                // Only happens in the unlikely case that a SIM service is also a SIM call manager
                continue;
            }

            final ConnectionServiceWrapper currentSimService = simService;

            currentSimService.mBinder.bind(new BindCallback() {
                @Override
                public void onSuccess() {
                    Log.d(this, "Adding simService %s", currentSimService.getComponentName());
                    simServiceComponentNames.add(currentSimService.getComponentName());
                    simServiceBinders.add(currentSimService.mServiceInterface.asBinder());
                    maybeComplete();
                }

                @Override
                public void onFailure() {
                    Log.d(this, "Failed simService %s", currentSimService.getComponentName());
                    // We know maybeComplete() will always be a no-op from now on, so go ahead and
                    // signal failure of the entire request
                    noRemoteServices(callback);
                }

                private void maybeComplete() {
                    if (simServiceComponentNames.size() == simServices.size()) {
                        setRemoteServices(callback, simServiceComponentNames, simServiceBinders);
                    }
                }
            });
        }
    }

    private void setRemoteServices(
            RemoteServiceCallback callback,
            List<ComponentName> componentNames,
            List<IBinder> binders) {
        try {
            callback.onResult(componentNames, binders);
        } catch (RemoteException e) {
            Log.e(this, e, "Contacting ConnectionService %s",
                    ConnectionServiceWrapper.this.getComponentName());
        }
    }

    private void noRemoteServices(RemoteServiceCallback callback) {
        try {
            callback.onResult(Collections.EMPTY_LIST, Collections.EMPTY_LIST);
        } catch (RemoteException e) {
            Log.e(this, e, "Contacting ConnectionService %s", this.getComponentName());
        }
    }
}
