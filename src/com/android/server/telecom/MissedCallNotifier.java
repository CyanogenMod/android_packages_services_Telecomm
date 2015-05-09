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
import android.content.AsyncQueryHandler;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.UserHandle;
import android.provider.CallLog;
import android.provider.CallLog.Calls;
import android.provider.Settings;
import android.telecom.CallState;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccount;
import android.telephony.PhoneNumberUtils;
import android.text.BidiFormatter;
import android.text.SpannableStringBuilder;
import android.text.TextDirectionHeuristics;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.style.RelativeSizeSpan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// TODO: Needed for move to system service: import com.android.internal.R;

/**
 * Creates a notification for calls that the user missed (neither answered nor rejected).
 * TODO: Make TelephonyManager.clearMissedCalls call into this class.
 */
class MissedCallNotifier extends CallsManagerListenerBase {

    private static final String[] CALL_LOG_PROJECTION = new String[] {
        Calls._ID,
        Calls.NUMBER,
        Calls.NUMBER_PRESENTATION,
        Calls.DATE,
        Calls.DURATION,
        Calls.TYPE,
    };

    private static final int CALL_LOG_COLUMN_ID = 0;
    private static final int CALL_LOG_COLUMN_NUMBER = 1;
    private static final int CALL_LOG_COLUMN_NUMBER_PRESENTATION = 2;
    private static final int CALL_LOG_COLUMN_DATE = 3;
    private static final int CALL_LOG_COLUMN_DURATION = 4;
    private static final int CALL_LOG_COLUMN_TYPE = 5;

    private static final int MISSED_CALL_NOTIFICATION_ID = 1;

    // notification light default constants
    public static final int DEFAULT_COLOR = 0xFFFFFF; //White
    public static final int DEFAULT_TIME = 1000; // 1 second

    private final Context mContext;
    private final NotificationManager mNotificationManager;
    private ExecutorService mCallInfoExecutor;
    private CallInfoProvider mCallInfoProvider;
    private boolean mNetworkConnected;
    private final List<MissedCallInfo> mMissedCalls =
            Collections.synchronizedList(new ArrayList<MissedCallInfo>());

    MissedCallNotifier(Context context, CallInfoProvider callInfoProvider) {
        mContext = context;
        mNotificationManager =
                (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        mCallInfoProvider = callInfoProvider;
        updateOnStartup();
        registerForNetwork();
    }

    private BroadcastReceiver mConnectivityListener = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            ConnectivityManager connectivityManager = (ConnectivityManager) context
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo info = connectivityManager.getActiveNetworkInfo();
            if (info == null || !info.isConnected()) {
                mNetworkConnected = false;
                return;
            }
            mNetworkConnected = true;
            if (!mMissedCalls.isEmpty()) {
                synchronized (mMissedCalls) {
                    for (MissedCallInfo callInfo : mMissedCalls) {
                        if (!callInfo.isFetchRequested()) {
                            fetchCallInfoAsync(callInfo);
                        }
                    }
                }
            }
        }
    };

    private void registerForNetwork() {
        if (mCallInfoProvider.providesCallInfo() && mCallInfoProvider.requiresNetwork()) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
            mContext.registerReceiver(mConnectivityListener, filter);
        }
    }

    void fetchCallInfoAsync(final MissedCallInfo call) {
        // If there is no network and the provider needs it for
        // lookups, delay the fetching as the connectivity manager
        // receiver will submit tasks upon connectivity
        if (!mNetworkConnected && mCallInfoProvider.requiresNetwork()) {
            return;
        }
        // Don't fetch information for contacts for whom information
        // is already available via contacts provider
        if (call.getCallerInfo() != null && call.getCallerInfo().contactExists) {
            return;
        }
        if (mCallInfoExecutor == null) {
            mCallInfoExecutor = Executors.newSingleThreadExecutor();
        }
        // Marker used to ensure that the network receiver doesn't
        // retrigger requests for those already submitted
        call.setFetchRequested(true);
        mCallInfoExecutor.submit(new Runnable() {
            @Override
            public void run() {
                boolean hasInfo = mCallInfoProvider.updateInfoForCall(call);
                call.setProviderHasInformation(hasInfo);
                // Check if the provider has additional info, and that
                // the call object is still valid
                if (hasInfo && mMissedCalls.contains(call)) {
                    showMissedCallNotificationInternal(call);
                }
            }
        });
    }

    void showMissedCallNotification(Call call) {
        // keep track of the call, keeping list sorted from newest to oldest
        MissedCallInfo missedCallInfo = new MissedCallInfo(call);
        missedCallInfo.setName(getNameForCall(call));
        missedCallInfo.setPhotoVisible(mMissedCalls.size() == 0);
        mMissedCalls.add(0, missedCallInfo);

        if (mCallInfoProvider.providesCallInfo()) {
            fetchCallInfoAsync(missedCallInfo);
        }
        showMissedCallNotificationInternal(missedCallInfo);
    }

    /** {@inheritDoc} */
    @Override
    public void onCallStateChanged(Call call, int oldState, int newState) {
        if (oldState == CallState.RINGING && newState == CallState.DISCONNECTED &&
                call.getDisconnectCause().getCode() == DisconnectCause.MISSED) {
            showMissedCallNotification(call);
        }
    }

    /** Clears missed call notification and marks the call log's missed calls as read. */
    void clearMissedCalls() {
        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                // Clear the list of new missed calls from the call log.
                ContentValues values = new ContentValues();
                values.put(Calls.NEW, 0);
                values.put(Calls.IS_READ, 1);
                StringBuilder where = new StringBuilder();
                where.append(Calls.NEW);
                where.append(" = 1 AND ");
                where.append(Calls.TYPE);
                where.append(" = ?");
                mContext.getContentResolver().update(Calls.CONTENT_URI, values, where.toString(),
                        new String[]{ Integer.toString(Calls.MISSED_TYPE) });
            }
        });
        cancelMissedCallNotification();
    }

    /**
     * Create a system notification for the missed call.
     *
     * @param call The missed call.
     */
    void showMissedCallNotificationInternal(MissedCallInfo call) {
        // Create the notification.
        Notification.Builder builder = new Notification.Builder(mContext);
        builder.setSmallIcon(android.R.drawable.stat_notify_missed_call)
                .setColor(mContext.getResources().getColor(R.color.theme_color))
                .setWhen(call.getCreationTimeMillis())
                .setContentIntent(createCallLogPendingIntent())
                .setAutoCancel(true)
                .setDeleteIntent(createClearMissedCallsPendingIntent());

        // display the first line of the notification:
        // 1 missed call: call name
        // more than 1 missed call: <number of calls> + "missed calls" (+ list of calls)
        if (mMissedCalls.size() == 1) {
            builder.setContentTitle(mContext.getText(R.string.notification_missedCallTitle));
            builder.setContentText(call.getName());
            builder.setSubText(call.getSummaryText());
        } else {
            String message = mContext.getString(R.string.notification_missedCallsMsg,
                    mMissedCalls.size());

            builder.setContentTitle(mContext.getText(R.string.notification_missedCallsTitle));
            builder.setContentText(message);

            Notification.InboxStyle style = new Notification.InboxStyle(builder);
            String number = call.getNumber();

            for (MissedCallInfo info : mMissedCalls) {
                style.addLine(formatSingleCallLine(info.getName(), info.getCreationTimeMillis()));

                // only keep number if equal for all calls in order to hide actions
                // if the calls came from different numbers
                if (!TextUtils.equals(number, info.getNumber())) {
                    number = null;
                }
            }
            style.setBigContentTitle(message);
            style.setSummaryText(" ");
            builder.setStyle(style);
        }

        Uri handleUri = call.getHandle();
        String handle = handleUri == null ? null : handleUri.getSchemeSpecificPart();

        // Add additional actions when there is only 1 missed call, like call-back and SMS.
        if (mMissedCalls.size() == 1) {
            Log.d(this, "Add actions with number %s.", Log.piiHandle(handle));

            if (!TextUtils.isEmpty(handle)
                    && !TextUtils.equals(handle, mContext.getString(R.string.handle_restricted))) {
                builder.addAction(R.drawable.stat_sys_phone_call,
                        mContext.getString(R.string.notification_missedCall_call_back),
                        createCallBackPendingIntent(handleUri));

                builder.addAction(R.drawable.ic_text_holo_dark,
                        mContext.getString(R.string.notification_missedCall_message),
                        createSendSmsFromNotificationPendingIntent(handleUri));
            }

            Bitmap photoIcon = call.getPhotoIcon();
            if (photoIcon != null) {
                builder.setLargeIcon(photoIcon);
            } else {
                Drawable photo = call.getPhoto();
                if (photo != null && photo instanceof BitmapDrawable) {
                    builder.setLargeIcon(((BitmapDrawable) photo).getBitmap());
                }
            }
        } else {
            Log.d(this, "Suppress actions. handle: %s, missedCalls: %d.", Log.piiHandle(handle),
                    mMissedCalls.size());
        }

        Notification notification = builder.build();
        configureLedOnNotification(mContext, notification);

        // Allow providers to update notification before displaying it,
        // for things like badging...etc
        if (call.providerHasInformation()) {
            mCallInfoProvider.updateMissedCallNotification(mMissedCalls, notification);
        }

        Log.i(this, "Adding missed call notification for %s.", call);
        mNotificationManager.notifyAsUser(
                null /* tag */ , MISSED_CALL_NOTIFICATION_ID, notification, UserHandle.CURRENT);
    }

    /** Cancels the "missed call" notification. */
    private void cancelMissedCallNotification() {
        // Reset the list of missed calls
        mMissedCalls.clear();
        mNotificationManager.cancel(MISSED_CALL_NOTIFICATION_ID);
    }

    private static final RelativeSizeSpan TIME_SPAN = new RelativeSizeSpan(0.7f);

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

    /**
     * Returns the name to use in the missed call notification.
     */
    private String getNameForCall(Call call) {
        String handle = call.getHandle() == null ? null : call.getHandle().getSchemeSpecificPart();
        String name = call.getName();

        if (!TextUtils.isEmpty(name) && TextUtils.isGraphic(name)) {
            return name;
        } else if (!TextUtils.isEmpty(handle)) {
            // A handle should always be displayed LTR using {@link BidiFormatter} regardless of the
            // content of the rest of the notification.
            // TODO: Does this apply to SIP addresses?
            BidiFormatter bidiFormatter = BidiFormatter.getInstance();
            return bidiFormatter.unicodeWrap(handle, TextDirectionHeuristics.LTR);
        } else {
            // Use "unknown" if the call is unidentifiable.
            return mContext.getString(R.string.unknown);
        }
    }

    /**
     * Creates a new pending intent that sends the user to the call log.
     *
     * @return The pending intent.
     */
    private PendingIntent createCallLogPendingIntent() {
        Intent intent = new Intent(Intent.ACTION_VIEW, null);
        intent.setType(CallLog.Calls.CONTENT_TYPE);

        TaskStackBuilder taskStackBuilder = TaskStackBuilder.create(mContext);
        taskStackBuilder.addNextIntent(intent);

        return taskStackBuilder.getPendingIntent(0, 0);
    }

    /**
     * Creates an intent to be invoked when the missed call notification is cleared.
     */
    private PendingIntent createClearMissedCallsPendingIntent() {
        return createTelecomPendingIntent(
                TelecomBroadcastReceiver.ACTION_CLEAR_MISSED_CALLS, null);
    }

    /**
     * Creates an intent to be invoked when the user opts to "call back" from the missed call
     * notification.
     *
     * @param handle The handle to call back.
     */
    private PendingIntent createCallBackPendingIntent(Uri handle) {
        return createTelecomPendingIntent(
                TelecomBroadcastReceiver.ACTION_CALL_BACK_FROM_NOTIFICATION, handle);
    }

    /**
     * Creates an intent to be invoked when the user opts to "send sms" from the missed call
     * notification.
     */
    private PendingIntent createSendSmsFromNotificationPendingIntent(Uri handle) {
        return createTelecomPendingIntent(
                TelecomBroadcastReceiver.ACTION_SEND_SMS_FROM_NOTIFICATION,
                Uri.fromParts(Constants.SCHEME_SMSTO, handle.getSchemeSpecificPart(), null));
    }

    /**
     * Creates generic pending intent from the specified parameters to be received by
     * {@link TelecomBroadcastReceiver}.
     *
     * @param action The intent action.
     * @param data The intent data.
     */
    private PendingIntent createTelecomPendingIntent(String action, Uri data) {
        Intent intent = new Intent(action, data, mContext, TelecomBroadcastReceiver.class);
        return PendingIntent.getBroadcast(mContext, 0, intent, 0);
    }

    /**
     * Configures a notification to emit the blinky notification light.
     *
     */
    private static void configureLedOnNotification(Context context, Notification notification) {
        ContentResolver resolver = context.getContentResolver();

        boolean lightEnabled = Settings.System.getInt(resolver,
                Settings.System.NOTIFICATION_LIGHT_PULSE, 0) == 1;
        if (!lightEnabled) {
            return;
        }

        notification.flags |= Notification.FLAG_SHOW_LIGHTS;

        // Get Missed call values if they are to be used
        boolean customEnabled = Settings.System.getInt(resolver,
                Settings.System.NOTIFICATION_LIGHT_PULSE_CUSTOM_ENABLE, 0) == 1;
        if (!customEnabled) {
            notification.defaults |= Notification.DEFAULT_LIGHTS;
            return;
        }

        notification.ledARGB = Settings.System.getInt(resolver,
            Settings.System.NOTIFICATION_LIGHT_PULSE_CALL_COLOR, DEFAULT_COLOR);
        notification.ledOnMS = Settings.System.getInt(resolver,
            Settings.System.NOTIFICATION_LIGHT_PULSE_CALL_LED_ON, DEFAULT_TIME);
        notification.ledOffMS = Settings.System.getInt(resolver,
            Settings.System.NOTIFICATION_LIGHT_PULSE_CALL_LED_OFF, DEFAULT_TIME);
    }

    /**
     * Adds the missed call notification on startup if there are unread missed calls.
     */
    private void updateOnStartup() {
        Log.d(this, "updateOnStartup()...");

        // instantiate query handler
        AsyncQueryHandler queryHandler = new AsyncQueryHandler(mContext.getContentResolver()) {
            @Override
            protected void onQueryComplete(int token, Object cookie, Cursor cursor) {
                Log.d(MissedCallNotifier.this, "onQueryComplete()...");
                if (cursor != null) {
                    try {
                        while (cursor.moveToNext()) {
                            // Get data about the missed call from the cursor
                            final String handleString = cursor.getString(CALL_LOG_COLUMN_NUMBER);
                            final int presentation =
                                    cursor.getInt(CALL_LOG_COLUMN_NUMBER_PRESENTATION);
                            final long date = cursor.getLong(CALL_LOG_COLUMN_DATE);

                            final Uri handle;
                            if (presentation != Calls.PRESENTATION_ALLOWED
                                    || TextUtils.isEmpty(handleString)) {
                                handle = null;
                            } else {
                                handle = Uri.fromParts(PhoneNumberUtils.isUriNumber(handleString) ?
                                        PhoneAccount.SCHEME_SIP : PhoneAccount.SCHEME_TEL,
                                                handleString, null);
                            }

                            // Convert the data to a call object
                            Call call = new Call(mContext, null, null, null, null, null, true,
                                    false);
                            call.setDisconnectCause(new DisconnectCause(DisconnectCause.MISSED));
                            call.setState(CallState.DISCONNECTED);
                            call.setCreationTimeMillis(date);

                            // Listen for the update to the caller information before posting the
                            // notification so that we have the contact info and photo.
                            call.addListener(new Call.ListenerBase() {
                                @Override
                                public void onCallerInfoChanged(Call call) {
                                    call.removeListener(this);  // No longer need to listen to call
                                                                // changes after the contact info
                                                                // is retrieved.
                                    showMissedCallNotification(call);
                                }
                            });
                            // Set the handle here because that is what triggers the contact info
                            // query.
                            call.setHandle(handle, presentation);
                        }
                    } finally {
                        cursor.close();
                    }
                }
            }
        };

        // setup query spec, look for all Missed calls that are new.
        StringBuilder where = new StringBuilder("type=");
        where.append(Calls.MISSED_TYPE);
        where.append(" AND new=1");

        // start the query
        queryHandler.startQuery(0, null, Calls.CONTENT_URI, CALL_LOG_PROJECTION,
                where.toString(), null, Calls.DEFAULT_SORT_ORDER);
    }
}
