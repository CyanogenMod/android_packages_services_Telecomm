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

package com.android.telecomm.testapps;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.telecomm.PhoneAccount;
import android.telecomm.PhoneAccountMetadata;
import android.telecomm.TelecommConstants;

/**
 * This class receives the notification callback intents used to update call states for
 * {@link TestConnectionService}.
 */
public class CallNotificationReceiver extends BroadcastReceiver {
    /**
     * Exit intent action is sent when the user clicks the "exit" action of the
     * TestConnectionService notification. Used to cancel (remove) the notification.
     */
    static final String ACTION_CALL_SERVICE_EXIT =
            "com.android.telecomm.testapps.ACTION_CALL_SERVICE_EXIT";
    static final String ACTION_REGISTER_PHONE_ACCOUNT =
            "com.android.telecomm.testapps.ACTION_REGISTER_PHONE_ACCOUNT";
    static final String ACTION_SHOW_ALL_PHONE_ACCOUNTS =
            "com.android.telecomm.testapps.ACTION_SHOW_ALL_PHONE_ACCOUNTS";
    static final String ACTION_VIDEO_CALL =
            "com.android.telecomm.testapps.ACTION_VIDEO_CALL";
    static final String ACTION_AUDIO_CALL =
            "com.android.telecomm.testapps.ACTION_AUDIO_CALL";

    /** {@inheritDoc} */
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (ACTION_CALL_SERVICE_EXIT.equals(action)) {
            CallServiceNotifier.getInstance().cancelNotifications(context);
        } else if (ACTION_REGISTER_PHONE_ACCOUNT.equals(action)) {
            CallServiceNotifier.getInstance().registerPhoneAccount(context);
        } else if (ACTION_SHOW_ALL_PHONE_ACCOUNTS.equals(action)) {
            CallServiceNotifier.getInstance().showAllPhoneAccounts(context);
        } else if (ACTION_VIDEO_CALL.equals(action)) {
            sendIncomingCallIntent(context, true);
        } else if (ACTION_AUDIO_CALL.equals(action)) {
            sendIncomingCallIntent(context, false);
        }
    }

    /**
     * Creates the intent to add an incoming call through Telecomm.
     *
     * @param context The current context.
     * @param isVideoCall {@code True} if this is a video call.
     */
    private void sendIncomingCallIntent(Context context, boolean isVideoCall) {
        // Create intent for adding an incoming call.
        Intent intent = new Intent(TelecommConstants.ACTION_INCOMING_CALL);
        // TODO(santoscordon): Use a private @hide permission to make sure this only goes to
        // Telecomm instead of setting the package explicitly.
        intent.setPackage("com.android.telecomm");

        PhoneAccount phoneAccount = new PhoneAccount(
                new ComponentName(context, TestConnectionService.class),
                null /* id */);
        intent.putExtra(TelecommConstants.EXTRA_PHONE_ACCOUNT, phoneAccount);

        // For the purposes of testing, indicate whether the incoming call is a video call by
        // stashing an indicator in the EXTRA_INCOMING_CALL_EXTRAS.
        Bundle extras = new Bundle();
        extras.putBoolean(TestConnectionService.IS_VIDEO_CALL, isVideoCall);

        intent.putExtra(TelecommConstants.EXTRA_INCOMING_CALL_EXTRAS, extras);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
}
