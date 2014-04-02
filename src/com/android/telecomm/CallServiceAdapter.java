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
 * Used by call services in order to update state and control calls while the call service is bound
 * to Telecomm. Each call service is given its own instance for the lifetime of the binding between
 * Telecomm and the call service.
 * TODO(santoscordon): Whenever we get any method invocations from the call service, we need to
 * check that the invocation is expected from that call service.
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
            String callId;

            switch (msg.what) {
                case MSG_SET_IS_COMPATIBLE_WITH:
                    callId = (String) msg.obj;
                    if (mPendingOutgoingCallIds.contains(callId)) {
                        mOutgoingCallsManager.setIsCompatibleWith(callId,
                                msg.arg1 == 1 ? true : false);
                    } else {
                        Log.wtf(this, "Unknown outgoing call: %s", callId);
                    }
                    break;
                case MSG_NOTIFY_INCOMING_CALL:
                    CallInfo callInfo = (CallInfo) msg.obj;
                    if (mPendingIncomingCallIds.remove(callInfo.getId())) {
                        mIncomingCallsManager.handleSuccessfulIncomingCall(callInfo);
                    } else {
                        Log.wtf(this, "Unknown incoming call: %s", callInfo);
                    }
                    break;
                case MSG_HANDLE_SUCCESSFUL_OUTGOING_CALL:
                    callId = (String) msg.obj;
                    if (mPendingOutgoingCallIds.remove(callId)) {
                        mOutgoingCallsManager.handleSuccessfulCallAttempt(callId);
                    } else {
                        // TODO(gilad): Figure out how to wire up the callService.abort() call.
                        Log.wtf(this, "Unknown outgoing call: %s", callId);
                    }
                    break;
                case MSG_HANDLE_FAILED_OUTGOING_CALL: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        callId = (String) args.arg1;
                        String reason = (String) args.arg2;
                        if (mPendingOutgoingCallIds.remove(callId)) {
                            mOutgoingCallsManager.handleFailedCallAttempt(callId, reason);
                        } else {
                            Log.wtf(this, "Unknown outgoing call: %s", callId);
                        }
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_SET_ACTIVE:
                    callId = (String) msg.obj;
                    mCallsManager.markCallAsActive(callId);
                    break;
                case MSG_SET_RINGING:
                    callId = (String) msg.obj;
                    mCallsManager.markCallAsRinging(callId);
                    break;
                case MSG_SET_DIALING:
                    callId = (String) msg.obj;
                    mCallsManager.markCallAsDialing(callId);
                    break;
                case MSG_SET_DISCONNECTED: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        callId = (String) args.arg1;
                        String disconnectMessage = (String) args.arg2;
                        int disconnectCause = args.argi1;
                        mCallsManager.markCallAsDisconnected(callId, disconnectCause,
                                disconnectMessage);
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_SET_ON_HOLD:
                    callId = (String) msg.obj;
                    mCallsManager.markCallAsOnHold(callId);
                    break;
            }
        }
    }

    private final CallsManager mCallsManager;
    private final OutgoingCallsManager mOutgoingCallsManager;
    private final IncomingCallsManager mIncomingCallsManager;
    private final Handler mHandler = new CallServiceAdapterHandler();

    /**
     * The set of pending outgoing call IDs.  Any {@link #handleSuccessfulOutgoingCall} and
     * {@link #handleFailedOutgoingCall} invocations with a call ID that is not in this set
     * are ignored.
     */
    private final Set<String> mPendingOutgoingCallIds = Sets.newHashSet();

    /**
     * The set of pending incoming call IDs.  Any {@link #handleIncomingCall} invocations with
     * a call ID not in this set are ignored.
     */
    private final Set<String> mPendingIncomingCallIds = Sets.newHashSet();

    /**
     * Persists the specified parameters.
     *
     * @param outgoingCallsManager Manages the placing of outgoing calls.
     * @param incomingCallsManager Manages the incoming call initialization flow.
     */
    CallServiceAdapter(
            OutgoingCallsManager outgoingCallsManager, IncomingCallsManager incomingCallsManager) {
        ThreadUtil.checkOnMainThread();
        mCallsManager = CallsManager.getInstance();
        mOutgoingCallsManager = outgoingCallsManager;
        mIncomingCallsManager = incomingCallsManager;
    }

    /** {@inheritDoc} */
    @Override
    public void setIsCompatibleWith(String callId, boolean isCompatible) {
        checkValidCallId(callId);
        mHandler.obtainMessage(MSG_SET_IS_COMPATIBLE_WITH, isCompatible ? 1 : 0, 0, callId).
                sendToTarget();
    }

    /** {@inheritDoc} */
    @Override
    public void notifyIncomingCall(CallInfo callInfo) {
        checkValidCallId(callInfo.getId());
        mHandler.obtainMessage(MSG_NOTIFY_INCOMING_CALL, callInfo).sendToTarget();
    }

    /** {@inheritDoc} */
    @Override
    public void handleSuccessfulOutgoingCall(String callId) {
        checkValidCallId(callId);
        mHandler.obtainMessage(MSG_HANDLE_SUCCESSFUL_OUTGOING_CALL, callId).sendToTarget();
    }

    /** {@inheritDoc} */
    @Override
    public void handleFailedOutgoingCall(String callId, String reason) {
        checkValidCallId(callId);
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = callId;
        args.arg2 = reason;
        mHandler.obtainMessage(MSG_HANDLE_FAILED_OUTGOING_CALL, args).sendToTarget();
    }

    /** {@inheritDoc} */
    @Override
    public void setActive(String callId) {
        checkValidCallId(callId);
        mHandler.obtainMessage(MSG_SET_ACTIVE, callId).sendToTarget();
    }

    /** {@inheritDoc} */
    @Override
    public void setRinging(String callId) {
        checkValidCallId(callId);
        mHandler.obtainMessage(MSG_SET_RINGING, callId).sendToTarget();
    }

    /** {@inheritDoc} */
    @Override
    public void setDialing(String callId) {
        checkValidCallId(callId);
        mHandler.obtainMessage(MSG_SET_DIALING, callId).sendToTarget();
    }

    /** {@inheritDoc} */
    // TODO(gilad): Ensure that any communication from the underlying ICallService
    // implementation is expected (or otherwise suppressed at the adapter level).
    @Override
    public void setDisconnected(
            String callId, int disconnectCause, String disconnectMessage) {
        checkValidCallId(callId);
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = callId;
        args.arg2 = disconnectMessage;
        args.argi1 = disconnectCause;
        mHandler.obtainMessage(MSG_SET_DISCONNECTED, args).sendToTarget();
    }

    /** {@inheritDoc} */
    @Override
    public void setOnHold(String callId) {
        checkValidCallId(callId);
        mHandler.obtainMessage(MSG_SET_ON_HOLD, callId).sendToTarget();
    }

    /**
     * Adds the specified call ID to the list of pending outgoing call IDs.
     * TODO(gilad): Consider passing the call processor (instead of the ID) both here and in the
     * remove case (same for incoming) such that the detour via the *CallsManager can be avoided.
     *
     * @param callId The ID of the call.
     */
    void addPendingOutgoingCallId(String callId) {
        mPendingOutgoingCallIds.add(callId);
    }

    /**
     * Removes the specified call ID from the list of pending outgoing call IDs.
     *
     * @param callId The ID of the call.
     */
    void removePendingOutgoingCallId(String callId) {
        mPendingOutgoingCallIds.remove(callId);
    }

    /**
     * Adds a call ID to the list of pending incoming call IDs. Only calls with call IDs in the
     * list will be handled by {@link #handleIncomingCall}.
     *
     * @param callId The ID of the call.
     */
    void addPendingIncomingCallId(String callId) {
        mPendingIncomingCallIds.add(callId);
    }

    /**
     * Removes the specified call ID from the list of pending incoming call IDs.
     *
     * @param callId The ID of the call.
     */
    void removePendingIncomingCallId(String callId) {
        mPendingIncomingCallIds.remove(callId);
    }

    /**
     * Called when the associated call service dies.
     */
    void handleCallServiceDeath() {
        if (!mPendingIncomingCallIds.isEmpty()) {
            // Here and in the for loop below, we need to iterate through a copy because the code
            // inside the loop will modify the original list.
            for (String callId : ImmutableList.copyOf(mPendingIncomingCallIds)) {
                mIncomingCallsManager.handleFailedIncomingCall(callId);
            }

            if (!mPendingIncomingCallIds.isEmpty()) {
                Log.wtf(this, "Pending incoming calls did not get cleared.");
                mPendingIncomingCallIds.clear();
            }
        }

        if (!mPendingOutgoingCallIds.isEmpty()) {
            for (String callId : ImmutableList.copyOf(mPendingOutgoingCallIds)) {
                mOutgoingCallsManager.handleFailedCallAttempt(callId, "Call service disconnected.");
            }

            if (!mPendingOutgoingCallIds.isEmpty()) {
                Log.wtf(this, "Pending outgoing calls did not get cleared.");
                mPendingOutgoingCallIds.clear();
            }
        }
    }

    /**
     * Throws an IllegalArgumentException if the specified call ID is invalid.
     *
     * @param callId The call ID to check.
     */
    private void checkValidCallId(String callId) {
        if (Strings.isNullOrEmpty(callId)) {
            throw new IllegalArgumentException("Invalid call ID.");
        }
    }
}
