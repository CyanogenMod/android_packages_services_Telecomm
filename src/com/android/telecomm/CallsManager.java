/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.content.Context;

import com.android.telecomm.exceptions.RestrictedCallException;
import com.google.common.collect.Lists;

import java.util.List;

/**
 * Singleton.
 *
 * NOTE(gilad): by design most APIs are package private, use the relevant adapter/s to allow
 * access from other packages specifically refraining from passing the CallsManager instance
 * beyond the com.android.telecomm package boundary.
 */
public final class CallsManager {

    private static final CallsManager INSTANCE = new CallsManager();

    private final Switchboard mSwitchboard;

    /** Used to control the in-call app. */
    private final InCallController mInCallController;

    /**
     * May be unnecessary per off-line discussions (between santoscordon and gilad) since the set
     * of CallsManager APIs that need to be exposed to the dialer (or any application firing call
     * intents) may be empty.
     */
    private DialerAdapter mDialerAdapter;

    private InCallAdapter mInCallAdapter;

    private CallLogManager mCallLogManager;

    private VoicemailManager mVoicemailManager;

    private List<OutgoingCallValidator> mOutgoingCallValidators = Lists.newArrayList();

    private List<IncomingCallValidator> mIncomingCallValidators = Lists.newArrayList();

    static CallsManager getInstance() {
        return INSTANCE;
    }

    /**
     * Initializes the required Telecomm components.
     */
    private CallsManager() {
        mSwitchboard = new Switchboard();
        mInCallController = new InCallController(this);
    }

    /**
     * Attempts to issue/connect the specified call.  From an (arbitrary) application standpoint,
     * all that is required to initiate this flow is to fire either of the CALL, CALL_PRIVILEGED,
     * and CALL_EMERGENCY intents. These are listened to by CallActivity.java which then invokes
     * this method.
     *
     * @param handle The handle to dial.
     * @param contactInfo Information about the entity being called.
     * @param context The application context.
     */
    void processOutgoingCallIntent(String handle, ContactInfo contactInfo, Context context)
            throws RestrictedCallException {

        for (OutgoingCallValidator validator : mOutgoingCallValidators) {
            validator.validate(handle, contactInfo);
        }

        // No objection to issue the call, proceed with trying to put it through.
        mSwitchboard.placeOutgoingCall(handle, contactInfo, context);
    }

    /**
     * Instructs Telecomm to answer the specified call. Intended to be invoked by the in-call
     * app through {@link InCallAdapter} after Telecomm notifies it of an incoming call followed by
     * the user opting to answer said call.
     *
     * @param callId The ID of the call.
     */
    void answerCall(String callId) {
        // TODO(santoscordon): fill in and check that it is in the ringing state.
    }

    /**
     * Instructs Telecomm to reject the specified call. Intended to be invoked by the in-call
     * app through {@link InCallAdapter} after Telecomm notifies it of an incoming call followed by
     * the user opting to reject said call.
     *
     * @param callId The ID of the call.
     */
    void rejectCall(String callId) {
        // TODO(santoscordon): fill in and check that it is in the ringing state.
    }

    /**
     * Instructs Telecomm to disconnect the specified call. Intended to be invoked by the
     * in-call app through {@link InCallAdapter} for an ongoing call. This is usually triggered by
     * the user hitting the end-call button.
     *
     * @param callId The ID of the call.
     */
    void disconnectCall(String callId) {
        // TODO(santoscordon): fill in and check that the call is in the active state.
    }
}
