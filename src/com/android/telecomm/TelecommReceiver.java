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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telecomm.CallServiceDescriptor;
import android.util.Log;

import com.google.common.base.Strings;

/**
 * Receiver for public intents relating to Telecomm.
 *
 * TODO(gilad): Unify the incoming/outgoing approach to use startActivity in both cases thereby
 * eliminating the incoming logic below, as well as this class as a whole.
 */
public class TelecommReceiver extends BroadcastReceiver {

    private static final String TAG = TelecommReceiver.class.getSimpleName();

    /**
     * Action used by call services to notify Telecomm that there is an incoming call. This intent
     * starts the incoming call sequence which will ultimately connect to the call service described
     * in the intent extras. A new call object along with the token (also provided in the intent
     * extras) will ultimately be sent to the call service indicating that Telecomm has received its
     * incoming call.
     * Extras used: {@link #EXTRA_CALL_SERVICE_DESCRIPTOR}, {@link #EXTRA_INCOMING_CALL_TOKEN}
     * TODO(santoscordon): As this gets finalized, this should eventually move to TelecommConstants.
     * TODO(santoscordon): Expose a new service like TelephonyManager for Telecomm and expose
     * a method for incoming calls instead of forcing the call service to build and send an Intent.
     */
    public static final String ACTION_INCOMING_CALL = "com.android.telecomm.INCOMING_CALL";

    /**
     * The {@link CallServiceDescriptor} describing the call service for an incoming call.
     */
    static final String EXTRA_CALL_SERVICE_DESCRIPTOR = "com.android.telecomm.CALL_SERVICE_DESCRIPTOR";

    /**
     * A String-based token used to identify the incoming call. Telecomm will use this token when
     * providing a call object to the call service so that the call service can map the call object
     * with the appropriate incoming call. Telecomm does not use or manipulate this token in any
     * way; it simply passes it through to the call service. Cannot be empty or null.
     */
    static final String EXTRA_INCOMING_CALL_TOKEN = "com.android.telecomm.INCOMING_CALL_TOKEN";

    private CallsManager mCallsManager = CallsManager.getInstance();

    /** {@inheritDoc} */
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (ACTION_INCOMING_CALL.equals(action)) {
            handleIncomingCall(intent);
        }
    }

    /**
     * Notifies CallsManager that a call service has an incoming call and it should start the
     * incoming call sequence.
     *
     * @param intent The incoming call intent.
     */
    private void handleIncomingCall(Intent intent) {
        CallServiceDescriptor descriptor = intent.getParcelableExtra(EXTRA_CALL_SERVICE_DESCRIPTOR);
        if (descriptor == null) {
            Log.w(TAG, "Rejecting incoming call due to null descriptor");
            return;
        }

        String token = Strings.emptyToNull(intent.getStringExtra(EXTRA_INCOMING_CALL_TOKEN));
        if (token == null) {
            Log.w(TAG, "Rejecting incoming call due to null token");
        }

        // TODO(santoscordon): Notify CallsManager.
    }
}
