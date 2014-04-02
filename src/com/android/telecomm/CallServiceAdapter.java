/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.telecomm;

import android.os.Handler;
import android.os.Message;
import android.telecomm.CallInfo;

import com.android.internal.telecomm.ICallServiceAdapter;
import com.google.android.collect.Sets;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.android.internal.os.SomeArgs;

import java.util.Set;

/**
 * Used by call services to communicate with Telecomm. Each call service is given its own instance
 * of the adapter for the lifetmie of the binding.
 * TODO(santoscordon): Do we need Binder.clear/restoreCallingIdentity() in the service methods?
 */
public final class CallServiceAdapter extends ICallServiceAdapter.Stub {
    private static final int MSG_SET_IS_COMPATIBLE_WITH = 0;
    private static final int MSG_NOTIFY_INCOMING_CALL = 1;
    private static final int MSG_HANDLE_SUCCESSFUL_OUTGOING_CALL = 2;
    private static final int MSG_HANDLE_FAILED_OUTGOING_CALL = 3;
    private static final int MSG_SET_ACTIVE = 4;
    private static final int MSG_SET_RINGING = 5;
    private static final int MSG_SET_DIALING = 6;
    private static final int MSG_SET_DISCONNECTED = 7;
    private static final int MSG_SET_ON_HOLD = 8;

    private final class CallServiceAdapterHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            Call call;
            switch (msg.what) {
                case MSG_SET_IS_COMPATIBLE_WITH:
                    call = mCallIdMapper.getCall(msg.obj);
                    if (call != null && !call.isIncoming()) {
                        mOutgoingCallsManager.setIsCompatibleWith(call,
                                msg.arg1 == 1 ? true : false);
                    } else {
                        Log.w(this, "Unknown call: %s, id: %s", call, msg.obj);
                    }
                    break;
                case MSG_NOTIFY_INCOMING_CALL:
                    CallInfo clientCallInfo = (CallInfo) msg.obj;
                    call = mCallIdMapper.getCall(clientCallInfo.getId());
                    if (call != null && mPendingCalls.remove(call) && call.isIncoming()) {
                        CallInfo callInfo = new CallInfo(null, clientCallInfo.getState(),
                                clientCallInfo.getHandle());
                        mIncomingCallsManager.handleSuccessfulIncomingCall(call, callInfo);
                    } else {
                        Log.w(this, "Unknown incoming call: %s, id: %s", call, msg.obj);
                    }
                    break;
                case MSG_HANDLE_SUCCESSFUL_OUTGOING_CALL:
                    call = mCallIdMapper.getCall(msg.obj);
                    if (call != null && mPendingCalls.remove(call) && !call.isIncoming()) {
                        mOutgoingCallsManager.handleSuccessfulCallAttempt(call);
                    } else {
                        // TODO(gilad): Figure out how to wire up the callService.abort() call.
                        Log.w(this, "Unknown outgoing call: %s, id: %s", call, msg.obj);
                    }
                    break;
                case MSG_HANDLE_FAILED_OUTGOING_CALL: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        call = mCallIdMapper.getCall(args.arg1);
                        String reason = (String) args.arg2;
                        if (call != null && mPendingCalls.remove(call) && !call.isIncoming()) {
                            mOutgoingCallsManager.handleFailedCallAttempt(call, reason);
                        } else {
                            Log.w(this, "Unknown outgoing call: %s, id: %s", call, msg.obj);
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
                        Log.w(this, "Unknown call id: %s", msg.obj);
                    }
                    break;
                case MSG_SET_RINGING:
                    call = mCallIdMapper.getCall(msg.obj);
                    if (call != null) {
                        mCallsManager.markCallAsRinging(call);
                    } else {
                        Log.w(this, "Unknown call id: %s", msg.obj);
                    }
                    break;
                case MSG_SET_DIALING:
                    call = mCallIdMapper.getCall(msg.obj);
                    if (call != null) {
                        mCallsManager.markCallAsDialing(call);
                    } else {
                        Log.w(this, "Unknown call id: %s", msg.obj);
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
                            Log.w(this, "Unknown call id: %s", msg.obj);
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
                        Log.w(this, "Unknown call id: %s", msg.obj);
                    }
                    break;
            }
        }
    }

    private final CallsManager mCallsManager;
    private final OutgoingCallsManager mOutgoingCallsManager;
    private final IncomingCallsManager mIncomingCallsManager;
    private final Handler mHandler = new CallServiceAdapterHandler();
    private final CallIdMapper mCallIdMapper;
    private final Set<Call> mPendingCalls = Sets.newHashSet();

    /**
     * Persists the specified parameters.
     *
     * @param outgoingCallsManager Manages the placing of outgoing calls.
     * @param incomingCallsManager Manages the incoming call initialization flow.
     */
    CallServiceAdapter(
            OutgoingCallsManager outgoingCallsManager,
            IncomingCallsManager incomingCallsManager,
            CallIdMapper callIdMapper) {
        ThreadUtil.checkOnMainThread();
        mCallsManager = CallsManager.getInstance();
        mOutgoingCallsManager = outgoingCallsManager;
        mIncomingCallsManager = incomingCallsManager;
        mCallIdMapper = callIdMapper;
    }

    /** {@inheritDoc} */
    @Override
    public void setIsCompatibleWith(String callId, boolean isCompatible) {
        Log.v(this, "setIsCompatibleWith id: %d, isCompatible: %b", callId, isCompatible);
        mCallIdMapper.checkValidCallId(callId);
        mHandler.obtainMessage(MSG_SET_IS_COMPATIBLE_WITH, isCompatible ? 1 : 0, 0, callId).
                sendToTarget();
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
        mCallIdMapper.checkValidCallId(callId);
        mHandler.obtainMessage(MSG_HANDLE_SUCCESSFUL_OUTGOING_CALL, callId).sendToTarget();
    }

    /** {@inheritDoc} */
    @Override
    public void handleFailedOutgoingCall(String callId, String reason) {
        mCallIdMapper.checkValidCallId(callId);
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = callId;
        args.arg2 = reason;
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

    /**
     * Adds the specified call to the list of pending calls. Only calls in this list which are
     * outgoing will be handled by {@link #isCompatibleWith}, {@link handleSuccessfulOutgoingCall},
     * and {@link handleFailedOutgoingCall}. Similarly, only calls in this list which are incoming
     * will be handled by {@link notifyIncomingCall}.
     */
    void addPendingCall(Call call) {
        mPendingCalls.add(call);
    }

    /**
     * Removes the specified call from the list of pending calls.
     */
    void removePendingCall(Call call) {
        mPendingCalls.remove(call);
    }

    /**
     * Called when the associated call service dies.
     */
    void handleCallServiceDeath() {
        if (!mPendingCalls.isEmpty()) {
            // Iterate through a copy because the code inside the loop will modify the original
            // list.
            for (Call call : ImmutableList.copyOf(mPendingCalls)) {
                if (call.isIncoming()) {
                    mIncomingCallsManager.handleFailedIncomingCall(call);
                } else {
                    mOutgoingCallsManager.handleFailedCallAttempt(call,
                            "Call service disconnected.");
                }
            }

            if (!mPendingCalls.isEmpty()) {
                Log.wtf(this, "Pending calls did not get cleared.");
                mPendingCalls.clear();
            }
        }
    }
}
