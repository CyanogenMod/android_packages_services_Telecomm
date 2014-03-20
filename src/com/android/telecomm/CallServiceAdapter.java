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

import com.android.internal.telecomm.ICallServiceAdapter;
import com.google.android.collect.Sets;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;

import java.util.Collections;
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

        mCallsManager = CallsManager.getInstance();
        mOutgoingCallsManager = outgoingCallsManager;
        mIncomingCallsManager = incomingCallsManager;
    }

    /** {@inheritDoc} */
    @Override public void setIsCompatibleWith(final String callId, final boolean isCompatible) {
        checkValidCallId(callId);
        mHandler.post(new Runnable() {
            @Override public void run() {
                if (mPendingOutgoingCallIds.contains(callId)) {
                    mOutgoingCallsManager.setIsCompatibleWith(callId, isCompatible);
                } else {
                    Log.wtf(CallServiceAdapter.this, "Unknown outgoing call: %s", callId);
                }
            }
        });
    }

    /** {@inheritDoc} */
    @Override public void notifyIncomingCall(final CallInfo callInfo) {
        checkValidCallId(callInfo.getId());
        mHandler.post(new Runnable() {
            @Override public void run() {
                if (mPendingIncomingCallIds.remove(callInfo.getId())) {
                    mIncomingCallsManager.handleSuccessfulIncomingCall(callInfo);
                } else {
                    Log.wtf(CallServiceAdapter.this, "Unknown incoming call: %s", callInfo);
                }
            }
        });
    }

    /** {@inheritDoc} */
    @Override public void handleSuccessfulOutgoingCall(final String callId) {
        checkValidCallId(callId);
        mHandler.post(new Runnable() {
            @Override public void run() {
                if (mPendingOutgoingCallIds.remove(callId)) {
                    mOutgoingCallsManager.handleSuccessfulCallAttempt(callId);
                } else {
                    // TODO(gilad): Figure out how to wire up the callService.abort() call.
                    Log.wtf(CallServiceAdapter.this, "Unknown outgoing call: %s", callId);
                }
            }
        });
    }

    /** {@inheritDoc} */
    @Override public void handleFailedOutgoingCall(final String callId, final String reason) {
        checkValidCallId(callId);
        mHandler.post(new Runnable() {
            @Override public void run() {
                if (mPendingOutgoingCallIds.remove(callId)) {
                    mOutgoingCallsManager.handleFailedCallAttempt(callId, reason);
                } else {
                    Log.wtf(CallServiceAdapter.this, "Unknown outgoing call: %s", callId);
                }
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
    // TODO(gilad): Ensure that any communication from the underlying ICallService
    // implementation is expected (or otherwise suppressed at the adapter level).
    @Override public void setDisconnected(final String callId) {
        checkValidCallId(callId);
        mHandler.post(new Runnable() {
            @Override public void run() {
                mCallsManager.markCallAsDisconnected(callId);
            }
        });
    }

    /** {@inheritDoc} */
    @Override public void setOnHold(final String callId) {
        checkValidCallId(callId);
        mHandler.post(new Runnable() {
            @Override public void run() {
                mCallsManager.markCallAsOnHold(callId);
            }
        });
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
