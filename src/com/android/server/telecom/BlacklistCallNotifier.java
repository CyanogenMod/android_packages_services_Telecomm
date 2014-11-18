/*
 * Copyright 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.telecom;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.TaskStackBuilder;
import android.content.*;
import android.net.Uri;
import android.provider.CallLog.Calls;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.style.RelativeSizeSpan;
import com.android.internal.telephony.util.BlacklistUtils;

import java.util.ArrayList;

// TODO: Needed for move to system service: import com.android.internal.R;

/**
 * Handles notifying the user of any blacklisted calls or messages.
 */
class BlacklistCallNotifier extends CallsManagerListenerBase {

    private static final boolean DEBUG = false;

    private static final RelativeSizeSpan TIME_SPAN = new RelativeSizeSpan(0.7f);

    private final Context mContext;
    private final NotificationManager mNotificationManager;

    static final int BLACKLISTED_CALL_NOTIFICATION = 7;
    static final int BLACKLISTED_MESSAGE_NOTIFICATION = 8;

    // used to track blacklisted calls and messages
    private static class BlacklistedItemInfo {
        String number;
        long date;
        int matchType;

        BlacklistedItemInfo(String number, long date, int matchType) {
            this.number = number;
            this.date = date;
            this.matchType = matchType;
        }
    };
    private ArrayList<BlacklistedItemInfo> mBlacklistedCalls =
            new ArrayList<BlacklistedItemInfo>();
    private ArrayList<BlacklistedItemInfo> mBlacklistedMessages =
            new ArrayList<BlacklistedItemInfo>();

    BlacklistCallNotifier(Context context) {
        mContext = context;
        mNotificationManager =
                (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    /** {@inheritDoc} */
    @Override
    public void onCallStateChanged(Call call, int oldState, int newState) {

    }

    /* package */ void notifyBlacklistedCall(String number, long date, int matchType) {
        notifyBlacklistedItem(number, date, matchType, BLACKLISTED_CALL_NOTIFICATION);
    }

    /* package */ void notifyBlacklistedMessage(String number, long date, int matchType) {
        notifyBlacklistedItem(number, date, matchType, BLACKLISTED_MESSAGE_NOTIFICATION);
    }

    private void notifyBlacklistedItem(String number, long date,
                                       int matchType, int notificationId) {
        if (!BlacklistUtils.isBlacklistNotifyEnabled(mContext)) {
            return;
        }

        if (DEBUG) Log.d(this, "notifyBlacklistedItem(). number: " + number + ", match type: "
                + matchType + ", date: " + date + ", type: " + notificationId);

        ArrayList<BlacklistedItemInfo> items = notificationId == BLACKLISTED_CALL_NOTIFICATION
                ? mBlacklistedCalls : mBlacklistedMessages;
        PendingIntent clearIntent = notificationId == BLACKLISTED_CALL_NOTIFICATION
                ? createClearBlacklistedCallsIntent() : createClearBlacklistedMessagesIntent();
        int iconDrawableResId = notificationId == BLACKLISTED_CALL_NOTIFICATION
                ? R.drawable.ic_block_contact_holo_dark : R.drawable.ic_block_message_holo_dark;

        // Keep track of the call/message, keeping list sorted from newest to oldest
        items.add(0, new BlacklistedItemInfo(number, date, matchType));

        // Get the intent to open Blacklist settings if user taps on content ready
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setClassName("com.android.settings",
                "com.android.settings.Settings$BlacklistSettingsActivity");
        PendingIntent blSettingsIntent = PendingIntent.getActivity(mContext, 0, intent, 0);

        // Start building the notification
        Notification.Builder builder = new Notification.Builder(mContext);
        builder.setSmallIcon(iconDrawableResId)
                .setContentIntent(blSettingsIntent)
                .setAutoCancel(true)
                .setContentTitle(mContext.getString(R.string.blacklist_title))
                .setWhen(date)
                .setDeleteIntent(clearIntent);

        // Add the 'Remove block' notification action only for MATCH_LIST items since
        // MATCH_REGEX and MATCH_PRIVATE items does not have an associated specific number
        // to unblock, and MATCH_UNKNOWN unblock for a single number does not make sense.
        boolean addUnblockAction = true;

        if (items.size() == 1) {
            int messageResId;

            switch (matchType) {
                case BlacklistUtils.MATCH_PRIVATE:
                    messageResId = notificationId == BLACKLISTED_CALL_NOTIFICATION
                            ? R.string.blacklist_call_notification_private_number
                            : R.string.blacklist_message_notification_private_number;
                    break;
                case BlacklistUtils.MATCH_UNKNOWN:
                    messageResId = notificationId == BLACKLISTED_CALL_NOTIFICATION
                            ? R.string.blacklist_call_notification_unknown_number
                            : R.string.blacklist_message_notification_unknown_number;
                    break;
                default:
                    messageResId = notificationId == BLACKLISTED_CALL_NOTIFICATION
                            ? R.string.blacklist_call_notification
                            : R.string.blacklist_message_notification;
                    break;
            }
            builder.setContentText(mContext.getString(messageResId, number));

            if (matchType != BlacklistUtils.MATCH_LIST) {
                addUnblockAction = false;
            }
        } else {
            int messageResId = notificationId == BLACKLISTED_CALL_NOTIFICATION
                    ? R.string.blacklist_call_notification_multiple
                    : R.string.blacklist_message_notification_multiple;
            String message = mContext.getString(messageResId, items.size());

            builder.setContentText(message);
            builder.setNumber(items.size());

            Notification.InboxStyle style = new Notification.InboxStyle(builder);

            for (BlacklistedItemInfo info : items) {
                // Takes care of displaying "Private" instead of an empty string
                String numberString = TextUtils.isEmpty(info.number)
                        ? mContext.getString(R.string.blacklist_notification_list_private)
                        : info.number;
                style.addLine(formatSingleCallLine(numberString, info.date));

                if (!TextUtils.equals(number, info.number)) {
                    addUnblockAction = false;
                } else if (info.matchType != BlacklistUtils.MATCH_LIST) {
                    addUnblockAction = false;
                }
            }
            style.setBigContentTitle(message);
            style.setSummaryText(" ");
            builder.setStyle(style);
        }

        if (addUnblockAction) {
            int actionDrawableResId = notificationId == BLACKLISTED_CALL_NOTIFICATION
                    ? R.drawable.ic_unblock_contact_holo_dark
                    : R.drawable.ic_unblock_message_holo_dark;
            int unblockType = notificationId == BLACKLISTED_CALL_NOTIFICATION
                    ? BlacklistUtils.BLOCK_CALLS : BlacklistUtils.BLOCK_MESSAGES;
            PendingIntent action = getUnblockNumberFromNotificationPendingIntent(
                    mContext, number, unblockType);

            builder.addAction(actionDrawableResId,
                    mContext.getString(R.string.unblock_number), action);
        }

        mNotificationManager.notify(notificationId, builder.getNotification());
    }

    private PendingIntent createClearBlacklistedCallsIntent() {
        Intent intent = new Intent(mContext, TelecomBroadcastReceiver.class);
        intent.setAction(TelecomBroadcastReceiver.ACTION_CLEAR_BLACKLISTED_CALLS);
        return PendingIntent.getService(mContext, 0, intent, 0);
    }

    private PendingIntent createClearBlacklistedMessagesIntent() {
        Intent intent = new Intent(mContext, TelecomBroadcastReceiver.class);
        intent.setAction(TelecomBroadcastReceiver.ACTION_CLEAR_BLACKLISTED_MESSAGES);
        return PendingIntent.getService(mContext, 0, intent, 0);
    }

    void cancelBlacklistedNotification(int type) {
        if ((type & BlacklistUtils.BLOCK_CALLS) != 0) {
            mBlacklistedCalls.clear();
            mNotificationManager.cancel(BLACKLISTED_CALL_NOTIFICATION);
        }
        if ((type & BlacklistUtils.BLOCK_MESSAGES) != 0) {
            mBlacklistedMessages.clear();
            mNotificationManager.cancel(BLACKLISTED_MESSAGE_NOTIFICATION);
        }
    }

    private CharSequence formatSingleCallLine(String caller, long date) {
        int flags = DateUtils.FORMAT_SHOW_TIME;
        if (!DateUtils.isToday(date)) {
            flags |= DateUtils.FORMAT_SHOW_WEEKDAY;
        }

        SpannableStringBuilder lineBuilder = new SpannableStringBuilder();
        lineBuilder.append(caller);
        lineBuilder.append("  ");

        int timeIndex = lineBuilder.length();
        lineBuilder.append(DateUtils.formatDateTime(mContext, date, flags));
        lineBuilder.setSpan(TIME_SPAN, timeIndex, lineBuilder.length(), 0);

        return lineBuilder;
    }

    /* package */ static PendingIntent getUnblockNumberFromNotificationPendingIntent(
            Context context, String number, int type) {
        Intent intent = new Intent(TelecomBroadcastReceiver.REMOVE_BLACKLIST);
        intent.putExtra(TelecomBroadcastReceiver.EXTRA_NUMBER, number);
        intent.putExtra(TelecomBroadcastReceiver.EXTRA_FROM_NOTIFICATION, true);
        intent.putExtra(TelecomBroadcastReceiver.EXTRA_TYPE, type);
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

}
