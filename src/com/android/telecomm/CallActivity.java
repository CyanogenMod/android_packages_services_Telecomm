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
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.telecomm.PhoneAccountHandle;
import android.telecomm.TelecommManager;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;
import android.text.TextUtils;

/**
 * Activity that handles system CALL actions and forwards them to {@link CallsManager}.
 * Handles all three CALL action types: CALL, CALL_PRIVILEGED, and CALL_EMERGENCY.
 *
 * Pre-L, the only way apps were were allowed to make outgoing emergency calls was the
 * ACTION_CALL_PRIVILEGED action (which requires the system only CALL_PRIVILEGED permission).
 *
 * In L, any app that has the CALL_PRIVILEGED permission can continue to make outgoing emergency
 * calls via ACTION_CALL_PRIVILEGED.
 *
 * In addition, the default dialer (identified via {@link TelecommManager#getDefaultPhoneApp()}
 * will also be granted the ability to make emergency outgoing calls using the CALL action. In
 * order to do this, it must call startActivityForResult on the CALL intent to allow its package
 * name to be passed to {@link CallActivity}. Calling startActivity will continue to work on all
 * non-emergency numbers just like it did pre-L.
 */
public class CallActivity extends Activity {
    private CallsManager mCallsManager = CallsManager.getInstance();
    private boolean mIsVoiceCapable;

    /**
     * {@inheritDoc}
     *
     * This method is the single point of entry for the CALL intent, which is used by built-in apps
     * like Contacts & Dialer, as well as 3rd party apps to initiate outgoing calls.
     */
    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);

        mIsVoiceCapable = isVoiceCapable();

        // TODO: This activity will be displayed until the next screen which could be
        // the in-call UI and error dialog or potentially a call-type selection dialog.
        // Traditionally, this has been a black screen with a spinner. We need to reevaluate if this
        // is still desired and add back if necessary. Currently, the activity is set to NoDisplay
        // theme which means it shows no UI.

        Intent intent = getIntent();
        Configuration configuration = getResources().getConfiguration();

        Log.d(this, "onCreate: this = %s, bundle = %s", this, bundle);
        Log.d(this, " - intent = %s", intent);
        Log.d(this, " - configuration = %s", configuration);

        // TODO: Figure out if there is something to restore from bundle.
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
        // Ensure call intents are not processed on devices that are not capable of calling.
        if (!mIsVoiceCapable) {
            setResult(RESULT_CANCELED);
            return;
        }

        String action = intent.getAction();

        // TODO: Check for non-voice capable devices before reading any intents.

        if (Intent.ACTION_CALL.equals(action) ||
                Intent.ACTION_CALL_PRIVILEGED.equals(action) ||
                Intent.ACTION_CALL_EMERGENCY.equals(action)) {
            processOutgoingCallIntent(intent);
        } else if (TelecommManager.ACTION_INCOMING_CALL.equals(action)) {
            processIncomingCallIntent(intent);
        }
    }

    /**
     * Processes CALL, CALL_PRIVILEGED, and CALL_EMERGENCY intents.
     *
     * @param intent Call intent containing data about the handle to call.
     */
    private void processOutgoingCallIntent(Intent intent) {

        String uriString = intent.getData().getSchemeSpecificPart();
        Uri handle = Uri.fromParts(
                PhoneNumberUtils.isUriNumber(uriString) ? "sip" : "tel", uriString, null);
        PhoneAccountHandle phoneAccountHandle = intent.getParcelableExtra(
                TelecommManager.EXTRA_PHONE_ACCOUNT_HANDLE);

        Bundle clientExtras = null;
        if (intent.hasExtra(TelecommManager.EXTRA_OUTGOING_CALL_EXTRAS)) {
            clientExtras = intent.getBundleExtra(TelecommManager.EXTRA_OUTGOING_CALL_EXTRAS);
        }
        if (clientExtras == null) {
            clientExtras = Bundle.EMPTY;
        }

        // Send to CallsManager to ensure the InCallUI gets kicked off before the broadcast returns
        Call call = mCallsManager.startOutgoingCall(handle, phoneAccountHandle, clientExtras);

        NewOutgoingCallIntentBroadcaster broadcaster = new NewOutgoingCallIntentBroadcaster(
                mCallsManager, call, intent, isDefaultDialer());
        final boolean success = broadcaster.processIntent();
        setResult(success ? RESULT_OK : RESULT_CANCELED);
    }

    /**
     * Processes INCOMING_CALL intents. Grabs the connection service information from the intent
     * extra and forwards that to the CallsManager to start the incoming call flow.
     *
     * @param intent The incoming call intent.
     */
    private void processIncomingCallIntent(Intent intent) {
        PhoneAccountHandle phoneAccountHandle = intent.getParcelableExtra(
                TelecommManager.EXTRA_PHONE_ACCOUNT_HANDLE);
        if (phoneAccountHandle == null) {
            Log.w(this, "Rejecting incoming call due to null phone account");
            return;
        }
        if (phoneAccountHandle.getComponentName() == null) {
            Log.w(this, "Rejecting incoming call due to null component name");
            return;
        }

        Bundle clientExtras = null;
        if (intent.hasExtra(TelecommManager.EXTRA_INCOMING_CALL_EXTRAS)) {
            clientExtras = intent.getBundleExtra(TelecommManager.EXTRA_INCOMING_CALL_EXTRAS);
        }
        if (clientExtras == null) {
            clientExtras = Bundle.EMPTY;
        }

        Log.d(this, "Processing incoming call from connection service [%s]",
                phoneAccountHandle.getComponentName());
        mCallsManager.processIncomingCallIntent(phoneAccountHandle, clientExtras);
    }

    private boolean isDefaultDialer() {
        final String packageName = getCallingPackage();
        if (TextUtils.isEmpty(packageName)) {
            return false;
        }

        final TelecommManager telecommManager =
                (TelecommManager) getSystemService(Context.TELECOMM_SERVICE);
        final ComponentName defaultPhoneApp = telecommManager.getDefaultPhoneApp();
        return (defaultPhoneApp != null
                && TextUtils.equals(defaultPhoneApp.getPackageName(), packageName));
    }

    /**
     * Returns whether the device is voice-capable (e.g. a phone vs a tablet).
     *
     * @return {@code True} if the device is voice-capable.
     */
    private boolean isVoiceCapable() {
        return getApplicationContext().getResources().getBoolean(
                com.android.internal.R.bool.config_voice_capable);
    }
}
