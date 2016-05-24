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

package com.android.server.telecom;

import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;
import com.android.internal.telephony.util.BlacklistUtils;
import android.telecom.TelecomManager;
import com.android.internal.telephony.TelephonyProperties;
import com.android.server.telecom.ui.ViceNotificationImpl;

public final class TelecomBroadcastIntentProcessor {
    /** The action used to send SMS response for the missed call notification. */
    public static final String ACTION_SEND_SMS_FROM_NOTIFICATION =
            "com.android.server.telecom.ACTION_SEND_SMS_FROM_NOTIFICATION";

    /** The action used to call a handle back for the missed call notification. */
    public static final String ACTION_CALL_BACK_FROM_NOTIFICATION =
            "com.android.server.telecom.ACTION_CALL_BACK_FROM_NOTIFICATION";

    /** The action used to clear missed calls. */
    public static final String ACTION_CLEAR_MISSED_CALLS =
            "com.android.server.telecom.ACTION_CLEAR_MISSED_CALLS";

    public static final String ACTION_CALL_PULL =
            "org.codeaurora.ims.ACTION_CALL_PULL";

    private final Context mContext;
    private final CallsManager mCallsManager;

    public TelecomBroadcastIntentProcessor(Context context, CallsManager callsManager) {
        mContext = context;
        mCallsManager = callsManager;
    }

    public static final String ACTION_CLEAR_BLACKLISTED_CALLS =
            "com.android.phone.intent.CLEAR_BLACKLISTED_CALLS";
    /** This action is used to clear blacklisted messages. */
    public static final String ACTION_CLEAR_BLACKLISTED_MESSAGES =
            "com.android.phone.intent.CLEAR_BLACKLISTED_MESSAGES";

    public static final String ACTION_REJECTED_SMS =
            "android.provider.Telephony.SMS_REJECTED";

    // For adding to Blacklist from call log
    static final String REMOVE_BLACKLIST = "com.android.phone.REMOVE_BLACKLIST";
    static final String EXTRA_NUMBER = "number";
    static final String EXTRA_TYPE = "type";
    static final String EXTRA_FROM_NOTIFICATION = "fromNotification";


    public void processIntent(Intent intent) {
        String action = intent.getAction();

        Log.v(this, "Action received: %s.", action);

        MissedCallNotifier missedCallNotifier = mCallsManager.getMissedCallNotifier();

        // Send an SMS from the missed call notification.
        if (ACTION_SEND_SMS_FROM_NOTIFICATION.equals(action)) {
            // Close the notification shade and the notification itself.
            closeSystemDialogs(mContext);
            missedCallNotifier.clearMissedCalls();

            Intent callIntent = new Intent(Intent.ACTION_SENDTO, intent.getData());
            callIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mContext.startActivityAsUser(callIntent, UserHandle.CURRENT);

        // Call back recent caller from the missed call notification.
        } else if (ACTION_CALL_BACK_FROM_NOTIFICATION.equals(action)) {
            // Close the notification shade and the notification itself.
            closeSystemDialogs(mContext);
            missedCallNotifier.clearMissedCalls();

            Intent callIntent = new Intent(Intent.ACTION_CALL_PRIVILEGED, intent.getData());
            callIntent.setFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            mContext.startActivityAsUser(callIntent, UserHandle.CURRENT);

        // Clear the missed call notification and call log entries.
        } else if (ACTION_CLEAR_MISSED_CALLS.equals(action)) {
            missedCallNotifier.clearMissedCalls();
        }  else if (ACTION_CLEAR_BLACKLISTED_CALLS.equals(action)) {
            BlacklistCallNotifier bcn = mCallsManager.getBlacklistCallNotifier();
            bcn.cancelBlacklistedNotification(BlacklistUtils.BLOCK_CALLS);
        } else if (ACTION_CLEAR_BLACKLISTED_MESSAGES.equals(action)) {
            BlacklistCallNotifier bcn = mCallsManager.getBlacklistCallNotifier();
            bcn.cancelBlacklistedNotification(BlacklistUtils.BLOCK_MESSAGES);
        } else if (intent.getAction().equals(REMOVE_BLACKLIST)) {
            if (intent.getBooleanExtra(EXTRA_FROM_NOTIFICATION, false)) {
                // Dismiss the notification that brought us here
                int blacklistType = intent.getIntExtra(EXTRA_TYPE, 0);
                BlacklistCallNotifier bcn = mCallsManager.getBlacklistCallNotifier();
                bcn.cancelBlacklistedNotification(blacklistType);
                BlacklistUtils.addOrUpdate(mContext, intent.getStringExtra(EXTRA_NUMBER),
                        0, blacklistType);
            }
        } else if (ACTION_REJECTED_SMS.equals(action)) {
            if (!intent.getBooleanExtra("blacklisted", false)) {
                return;
            }

            String sender = intent.getStringExtra("sender");
            long timestamp = intent.getLongExtra("timestamp", 0);
            int matchType = intent.getIntExtra("blacklistMatchType", -1);

            BlacklistCallNotifier bcn = mCallsManager.getBlacklistCallNotifier();
            bcn.notifyBlacklistedMessage(sender, timestamp, matchType);
        } else if (ACTION_CALL_PULL.equals(action)) {
            // Close the notification shade and the notification itself.
            closeSystemDialogs(mContext);

            String dialogId =  intent.getStringExtra("org.codeaurora.ims.VICE_CLEAR");
            int callType =  intent.getIntExtra(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, 0);
            Log.i(this,"ACTION_CALL_PULL: calltype = " + callType + ", dialogId = " + dialogId);

            Intent callIntent = new Intent(Intent.ACTION_CALL_PRIVILEGED, intent.getData());
            callIntent.putExtra(TelephonyProperties.EXTRA_IS_CALL_PULL, true);
            callIntent.putExtra(TelephonyProperties.EXTRA_SKIP_SCHEMA_PARSING, true);
            callIntent.putExtra(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, callType);
            callIntent.setFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            mContext.startActivityAsUser(callIntent, UserHandle.CURRENT);
        }
    }

    /**
     * Closes open system dialogs and the notification shade.
     */
    private void closeSystemDialogs(Context context) {
        Intent intent = new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        context.sendBroadcastAsUser(intent, UserHandle.ALL);
    }
}
