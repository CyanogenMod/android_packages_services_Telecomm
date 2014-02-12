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

import com.google.common.base.Strings;

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

    /** Used to run code (e.g. messages, Runnables) on the main (UI) thread. */
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    /**
     * Persists the specified parameters.
     */
    CallServiceAdapter(OutgoingCallsManager outgoingCallsManager) {
        mCallsManager = CallsManager.getInstance();
        mOutgoingCallsManager = outgoingCallsManager;
    }

    /** {@inheritDoc} */
    @Override public void getNextCallId() {
        // TODO(santoscordon): needs response object.
    }

    /** {@inheritDoc} */
    @Override public void setCompatibleWith(String callId, boolean isCompatible) {
        // TODO(santoscordon): fill in.
    }

    /**
     * {@inheritDoc}
     */
    @Override public void handleIncomingCall(CallInfo callInfo) {
        // TODO(santoscordon): fill in.
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
     * Throws an IllegalArgumentException if the specified call ID is invalid.
     *
     * @param callId The call ID to check.
     */
    private void checkValidCallId(String callId) {
        if (Strings.isNullOrEmpty(callId)) {
            throw new IllegalArgumentException();
        }
    }
}
