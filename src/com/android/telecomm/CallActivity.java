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

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.telecomm.CallServiceDescriptor;
import android.telecomm.TelecommConstants;
import android.util.Log;
import android.widget.Toast;

import com.android.telecomm.exceptions.RestrictedCallException;

/**
 * Activity that handles system CALL actions and forwards them to {@link CallsManager}.
 * Handles all three CALL action types: CALL, CALL_PRIVILEGED, and CALL_EMERGENCY.
 */
public class CallActivity extends Activity {

    private static final String TAG = CallActivity.class.getSimpleName();

    /** Indicates whether or not debug-level entries should be logged. */
    private static boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private CallsManager mCallsManager = CallsManager.getInstance();

    /**
     * {@inheritDoc}
     *
     * This method is the single point of entry for the CALL intent, which is used by built-in apps
     * like Contacts & Dialer, as well as 3rd party apps to initiate outgoing calls.
     */
    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);

        // TODO(santoscordon): This activity will be displayed until the next screen which could be
        // the in-call UI and error dialog or potentially a call-type selection dialog.
        // Traditionally, this has been a black screen with a spinner. We need to reevaluate if this
        // is still desired and add back if necessary. Currently, the activity is set to NoDisplay
        // theme which means it shows no UI.

        Intent intent = getIntent();
        Configuration configuration = getResources().getConfiguration();

        if (DEBUG) {
            Log.d(TAG, "onCreate: this = " + this + ", bundle= " + bundle);
            Log.d(TAG, " - intent = " + intent);
            Log.d(TAG, " - configuration = " + configuration);
        }

        // TODO(santoscordon): Figure out if there is something to restore from bundle.
        // See OutgoingCallBroadcaster in services/Telephony for more.

        processIntent(intent);

        // This activity does not have associated UI, so close.
        finish();

        if (DEBUG) {
            Log.d(TAG, "onCreate: end");
        }
    }

    /**
     * Processes intents sent to the activity.
     *
     * @param intent The intent.
     */
    private void processIntent(Intent intent) {
        String action = intent.getAction();

        if (Intent.ACTION_CALL.equals(action) ||
                Intent.ACTION_CALL_PRIVILEGED.equals(action) ||
                Intent.ACTION_CALL_EMERGENCY.equals(action)) {
            processOutgoingCallIntent(intent);
        } else if (TelecommConstants.ACTION_INCOMING_CALL.equals(action)) {
            processIncomingCallIntent(intent);
        }
    }

    /**
     * Processes CALL, CALL_PRIVILEGED, and CALL_EMERGENCY intents.
     *
     * @param intent Call intent containing data about the handle to call.
     */
    private void processOutgoingCallIntent(Intent intent) {
        // TODO(santoscordon): Remove the toast.
        String toastContent = "[" + intent.getAction() + "] " + intent.getDataString();
        Toast.makeText(this, toastContent, Toast.LENGTH_LONG).show();

        // TODO(gilad): Pull the scheme etc. from the data string as well as any relevant extras
        // from the intent into structured data and invoke the corresponding CallsManager APIs
        // based on that.  May want to add a static utility to perform that in case the logic is
        // non-trivial/voluminous.
        String handle = intent.getDataString();
        ContactInfo contactInfo = null;
        try {
            // we use the application context because the lifetime of the call services bound on
            // this context extends beyond the life of this activity.
            Context context = getApplicationContext();

            mCallsManager.processOutgoingCallIntent(handle, contactInfo, context);
        } catch (RestrictedCallException e) {
            // TODO(gilad): Handle or explicitly state to be ignored.
        }
    }

    /**
     * Processes INCOMING_CALL intents. Grabs the call service informations from the intent extra
     * and forwards that to the CallsManager to start the incoming call flow.
     *
     * @param intent The incoming call intent.
     */
    private void processIncomingCallIntent(Intent intent) {
        CallServiceDescriptor descriptor =
                intent.getParcelableExtra(TelecommConstants.EXTRA_CALL_SERVICE_DESCRIPTOR);
        if (descriptor == null) {
            Log.w(TAG, "Rejecting incoming call due to null descriptor");
            return;
        }

        // Notify CallsManager.
    }
}
