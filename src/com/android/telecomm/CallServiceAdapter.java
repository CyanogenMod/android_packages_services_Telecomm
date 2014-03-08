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
import android.os.Looper;
import android.telecomm.CallInfo;
import android.telecomm.ICallServiceAdapter;

import com.google.android.collect.Sets;
import com.google.common.base.Strings;

import java.util.Set;

/**
 * Used by call services in order to update state and control calls while the call service is bound
 * to Telecomm. Each call service is given its own instance for the lifetime of the binding between
 * Telecomm and the call service.
 * TODO(santoscordon): Whenever we get any method invocations from the call service, we need to
 * check that the invocation is expected from that call service.
 * TODO(santoscordon): Move away from Runnable objects and into messages so that we create fewer
 * objects per IPC method call.
 * TODO(santoscordon): Do we need Binder.clear/restoreCallingIdentity() in the service methods?
 */
public final class CallServiceAdapter extends ICallServiceAdapter.Stub {
    private final CallsManager mCallsManager;

    private final OutgoingCallsManager mOutgoingCallsManager;

    private final IncomingCallsManager mIncomingCallsManager;

    /** Used to run code (e.g. messages, Runnables) on the main (UI) thread. */
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    /** The set of pending incoming call IDs. Contains the call IDs for which we are expecting
     * details via {@link #handleIncomingCall}. If {@link #handleIncomingCall} is invoked for a call
     * ID that is not in this set, it will be ignored.
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

        mCallsManager = CallsManager.getInstance();
        mOutgoingCallsManager = outgoingCallsManager;
        mIncomingCallsManager = incomingCallsManager;
    }

    /** {@inheritDoc} */
    @Override public void setCompatibleWith(String callId, boolean isCompatible) {
        // TODO(santoscordon): fill in.
    }

    /** {@inheritDoc} */
    @Override public void handleIncomingCall(final CallInfo callInfo) {
        checkValidCallId(callInfo.getId());
        mHandler.post(new Runnable() {
            @Override public void run() {
                if (mPendingIncomingCallIds.remove(callInfo.getId())) {
                    mIncomingCallsManager.handleSuccessfulIncomingCall(callInfo);
                } else {
                    Log.wtf(CallServiceAdapter.this,
                            "Received details for an unknown incoming call %s", callInfo);
                }
            }
        });
    }

    /** {@inheritDoc} */
    @Override public void handleSuccessfulOutgoingCall(final String callId) {
        checkValidCallId(callId);
        mHandler.post(new Runnable() {
            @Override public void run() {
                mOutgoingCallsManager.handleSuccessfulCallAttempt(callId);
            }
        });
    }

    /** {@inheritDoc} */
    @Override public void handleFailedOutgoingCall(final String callId, final String reason) {
        checkValidCallId(callId);
        mHandler.post(new Runnable() {
            @Override public void run() {
                mOutgoingCallsManager.handleFailedCallAttempt(callId, reason);
            }
        });
    }

    /** {@inheritDoc} */
    @Override public void setActive(final String callId) {
        checkValidCallId(callId);
        mHandler.post(new Runnable() {
            @Override public void run() {
                mCallsManager.markCallAsActive(callId);
            }
        });
    }

    /** {@inheritDoc} */
    @Override public void setRinging(final String callId) {
        checkValidCallId(callId);
        mHandler.post(new Runnable() {
            @Override public void run() {
                mCallsManager.markCallAsRinging(callId);
            }
        });
    }

    /** {@inheritDoc} */
    @Override public void setDialing(final String callId) {
        checkValidCallId(callId);
        mHandler.post(new Runnable() {
            @Override public void run() {
                mCallsManager.markCallAsDialing(callId);
            }
        });
    }

    /** {@inheritDoc} */
    @Override public void setDisconnected(final String callId) {
        checkValidCallId(callId);
        mHandler.post(new Runnable() {
            @Override public void run() {
                mCallsManager.markCallAsDisconnected(callId);
            }
        });
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
     * Removed a call ID from the list of pending incoming call IDs.
     *
     * @param callId The ID of the call.
     */
    void removePendingIncomingCallId(String callId) {
        mPendingIncomingCallIds.remove(callId);
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
