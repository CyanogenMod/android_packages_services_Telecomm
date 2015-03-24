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

package com.android.server.telecom.components;

import com.android.server.telecom.CallIntentProcessor;
import com.android.server.telecom.Log;
import com.android.server.telecom.R;
import com.android.server.telecom.TelephonyUtil;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.UserHandle;
import android.os.UserManager;
import android.telecom.PhoneAccount;
import android.telecom.TelecomManager;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.widget.Toast;

// TODO: Needed for move to system service: import com.android.internal.R;

/**
 * Handles system CALL actions and forwards them to {@link CallIntentProcessor}.
 * Handles all three CALL action types: CALL, CALL_PRIVILEGED, and CALL_EMERGENCY.
 *
 * Pre-L, the only way apps were were allowed to make outgoing emergency calls was the
 * ACTION_CALL_PRIVILEGED action (which requires the system only CALL_PRIVILEGED permission).
 *
 * In L, any app that has the CALL_PRIVILEGED permission can continue to make outgoing emergency
 * calls via ACTION_CALL_PRIVILEGED.
 *
 * In addition, the default dialer (identified via
 * {@link android.telecom.TelecomManager#getDefaultPhoneApp()} will also be granted the ability to
 * make emergency outgoing calls using the CALL action. In order to do this, it must call
 * startActivityForResult on the CALL intent to allow its package name to be passed to
 * {@link UserCallIntentProcessor}. Calling startActivity will continue to work on all
 * non-emergency numbers just like it did pre-L.
 */
public class UserCallIntentProcessor {

    private final Context mContext;

    public UserCallIntentProcessor(Context context) {
        mContext = context;
    }

    /**
     * Processes intents sent to the activity.
     *
     * @param intent The intent.
     */
    public void processIntent(Intent intent, String callingPackageName) {
        // Ensure call intents are not processed on devices that are not capable of calling.
        if (!isVoiceCapable()) {
            return;
        }

        String action = intent.getAction();

        if (Intent.ACTION_CALL.equals(action) ||
                Intent.ACTION_CALL_PRIVILEGED.equals(action) ||
                Intent.ACTION_CALL_EMERGENCY.equals(action)) {
            processOutgoingCallIntent(intent, callingPackageName);
        }
    }

    private void processOutgoingCallIntent(Intent intent, String callingPackageName) {
        Uri handle = intent.getData();
        String scheme = handle.getScheme();
        String uriString = handle.getSchemeSpecificPart();

        if (!PhoneAccount.SCHEME_VOICEMAIL.equals(scheme)) {
            handle = Uri.fromParts(PhoneNumberUtils.isUriNumber(uriString) ?
                    PhoneAccount.SCHEME_SIP : PhoneAccount.SCHEME_TEL, uriString, null);
        }

        UserManager userManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        if (userManager.hasUserRestriction(UserManager.DISALLOW_OUTGOING_CALLS)
                && !TelephonyUtil.shouldProcessAsEmergency(mContext, handle)) {
            // Only emergency calls are allowed for users with the DISALLOW_OUTGOING_CALLS
            // restriction.
            Toast.makeText(
                    mContext,
                    mContext.getResources().getString(R.string.outgoing_call_not_allowed),
                    Toast.LENGTH_SHORT).show();
            Log.d(this, "Rejecting non-emergency phone call due to DISALLOW_OUTGOING_CALLS "
                    + "restriction");
            return;
        }

        intent.putExtra(CallIntentProcessor.KEY_IS_DEFAULT_DIALER, isDefaultDialer(callingPackageName));
        sendBroadcastToReceiver(intent);
    }

    private boolean isDefaultDialer(String callingPackageName) {
        if (TextUtils.isEmpty(callingPackageName)) {
            return false;
        }

        final TelecomManager telecomManager =
                (TelecomManager) mContext.getSystemService(Context.TELECOM_SERVICE);
        final ComponentName defaultPhoneApp = telecomManager.getDefaultPhoneApp();
        return (defaultPhoneApp != null
                && TextUtils.equals(defaultPhoneApp.getPackageName(), callingPackageName));
    }

    /**
     * Returns whether the device is voice-capable (e.g. a phone vs a tablet).
     *
     * @return {@code True} if the device is voice-capable.
     */
    private boolean isVoiceCapable() {
        return mContext.getApplicationContext().getResources().getBoolean(
                com.android.internal.R.bool.config_voice_capable);
    }

    /**
     * Trampolines the intent to the broadcast receiver that runs only as the primary user.
     */
    private boolean sendBroadcastToReceiver(Intent intent) {
        intent.putExtra(CallIntentProcessor.KEY_IS_INCOMING_CALL, false);
        intent.setFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        intent.setClass(mContext, PrimaryCallReceiver.class);
        Log.d(this, "Sending broadcast as user to CallReceiver");
        mContext.sendBroadcastAsUser(intent, UserHandle.OWNER);
        return true;
    }
}
