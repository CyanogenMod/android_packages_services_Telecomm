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

import com.android.telecomm.exceptions.CallServiceUnavailableException;
import com.android.telecomm.exceptions.RestrictedCallException;
import com.google.common.collect.Lists;

import java.util.ArrayList;
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

    /**
     * May be unnecessary per off-line discussions (between santoscordon and gilad) since the set
     * of CallsManager APIs that need to be exposed to the dialer (or any application firing call
     * intents) may be empty.
     */
    private DialerAdapter mDialerAdapter;

    private InCallAdapter mInCallAdapter;

    private Switchboard mSwitchboard;

    private CallLogManager mCallLogManager;

    private VoicemailManager mVoicemailManager;

    private List<OutgoingCallFilter> mOutgoingCallFilters = Lists.newArrayList();

    private List<IncomingCallFilter> mIncomingCallFilters = Lists.newArrayList();

    static CallsManager getInstance() {
        return INSTANCE;
    }

    /**
     * Private constructor initializes main components of telecomm.
     */
    private CallsManager() {
        mSwitchboard = new Switchboard();
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
            throws RestrictedCallException, CallServiceUnavailableException {

        for (OutgoingCallFilter policy : mOutgoingCallFilters) {
            policy.validate(handle, contactInfo);
        }

        // No objection to issue the call, proceed with trying to put it through.
        mSwitchboard.placeOutgoingCall(handle, contactInfo, context);
    }
}
