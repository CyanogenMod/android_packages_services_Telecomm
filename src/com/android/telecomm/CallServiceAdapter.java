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
import android.util.Log;

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
    private static final String TAG = CallServiceAdapter.class.getSimpleName();

    private final CallsManager mCallsManager;

    private final OutgoingCallsManager mOutgoingCallsManager;

    /** Used to run code (e.g. messages, Runnables) on the main (UI) thread. */
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    /** The list of unconfirmed incoming call IDs. Contains only IDs for incoming calls which are
     * pending confirmation from the call service. Entries are added by the call service when a
     * confirmation request is sent and removed when the confirmation is received or it times out.
     * See {@link IncomingCallsManager} for more information about the incoming sequence and its
     * timeouts.
     */
    private final Set<String> mUnconfirmedIncomingCallIds = Sets.newHashSet();

    /**
     * Persists the specified parameters.
     */
    CallServiceAdapter(OutgoingCallsManager outgoingCallsManager) {
        mCallsManager = CallsManager.getInstance();
        mOutgoingCallsManager = outgoingCallsManager;
    }

    /** {@inheritDoc} */
    @Override public void setCompatibleWith(String callId, boolean isCompatible) {
        // TODO(santoscordon): fill in.
    }

    /** {@inheritDoc} */
    @Override public void handleConfirmedIncomingCall(final CallInfo callInfo) {
        checkValidCallId(callInfo.getId());
        mHandler.post(new Runnable() {
            @Override public void run() {
                if (mUnconfirmedIncomingCallIds.remove(callInfo.getId())) {
                    // TODO(santoscordon): Uncomment when ready.
                    // mIncomingCallsManager.handleSuccessfulIncomingCall(callInfo);
                } else {
                    Log.wtf(TAG, "Call service confirming unknown incoming call " + callInfo);
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
     * Adds a call ID to the list of unconfirmed incoming call IDs. Only calls with call IDs in the
     * list will be handled by {@link #handleConfirmedIncomingCall}.
     *
     * @param callId The ID of the call.
     */
    void addUnconfirmedIncomingCallId(String callId) {
        mUnconfirmedIncomingCallIds.add(callId);
    }

    /**
     * Removed a call ID from the list of unconfirmed incoming call IDs.
     *
     * @param callId The ID of the call.
     */
    void removeUnconfirmedIncomingCallId(String callId) {
        mUnconfirmedIncomingCallIds.remove(callId);
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
