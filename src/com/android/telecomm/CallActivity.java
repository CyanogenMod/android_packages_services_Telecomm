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
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.telecomm.PhoneAccount;
import android.telecomm.TelecommConstants;

/**
 * Activity that handles system CALL actions and forwards them to {@link CallsManager}.
 * Handles all three CALL action types: CALL, CALL_PRIVILEGED, and CALL_EMERGENCY.
 */
public class CallActivity extends Activity {

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

        Log.d(this, "onCreate: this = %s, bundle = %s", this, bundle);
        Log.d(this, " - intent = %s", intent);
        Log.d(this, " - configuration = %s", configuration);

        // TODO(santoscordon): Figure out if there is something to restore from bundle.
        // See OutgoingCallBroadcaster in services/Telephony for more.

        processIntent(intent);

        // This activity does not have associated UI, so close.
        finish();

        Log.d(this, "onCreate: end");
    }

    /**
     * Processes intents sent to the activity.
     *
     * @param intent The intent.
     */
    private void processIntent(Intent intent) {
        String action = intent.getAction();

        // TODO: Check for non-voice capable devices before reading any intents.

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
        NewOutgoingCallIntentBroadcaster broadcaster =
                new NewOutgoingCallIntentBroadcaster(mCallsManager, intent);
        broadcaster.processIntent();
    }

    /**
     * Processes INCOMING_CALL intents. Grabs the connection service informations from the intent
     * extra and forwards that to the CallsManager to start the incoming call flow.
     *
     * @param intent The incoming call intent.
     */
    private void processIncomingCallIntent(Intent intent) {
        PhoneAccount phoneAccount = intent.getParcelableExtra(
                TelecommConstants.EXTRA_PHONE_ACCOUNT);
        if (phoneAccount == null) {
            Log.w(this, "Rejecting incoming call due to null phone account");
            return;
        }
        if (phoneAccount.getComponentName() == null) {
            Log.w(this, "Rejecting incoming call due to null component name");
            return;
        }

        Bundle clientExtras = Bundle.EMPTY;
        if (intent.hasExtra(TelecommConstants.EXTRA_INCOMING_CALL_EXTRAS)) {
            clientExtras = intent.getBundleExtra(TelecommConstants.EXTRA_INCOMING_CALL_EXTRAS);
        }

        Log.d(this, "Processing incoming call from connection service [%s]",
                phoneAccount.getComponentName());
        mCallsManager.processIncomingCallIntent(phoneAccount, clientExtras);
    }
}
