/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.telecom.tests;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.os.UserHandle;
import android.telephony.TelephonyManager;

import com.android.server.telecom.Call;
import com.android.server.telecom.Constants;
import com.android.server.telecom.MissedCallNotifier;
import com.android.server.telecom.TelecomBroadcastIntentProcessor;
import com.android.server.telecom.components.TelecomBroadcastReceiver;
import com.android.server.telecom.ui.MissedCallNotifierImpl;

import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.HashSet;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MissedCallNotifierImplTest extends TelecomTestCase {

    private static final Uri TEL_CALL_HANDLE = Uri.parse("tel:+11915552620");
    private static final Uri SIP_CALL_HANDLE = Uri.parse("sip:testaddress@testdomain.com");
    private static final String CALLER_NAME = "Fake Name";
    private static final String MISSED_CALL_TITLE = "Missed Call";
    private static final String MISSED_CALLS_TITLE = "Missed Calls";
    private static final String MISSED_CALLS_MSG = "%s missed calls";
    private static final String USER_CALL_ACTIVITY_LABEL = "Phone";

    private static final int REQUEST_ID = 0;
    private static final long CALL_TIMESTAMP;
    static {
         CALL_TIMESTAMP = System.currentTimeMillis() - 60 * 1000 * 5;
    }

    @Mock
    private NotificationManager mNotificationManager;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        MockitoAnnotations.initMocks(this);

        mContext = mComponentContextFixture.getTestDouble().getApplicationContext();
        mNotificationManager = (NotificationManager) mContext.getSystemService(
                Context.NOTIFICATION_SERVICE);
        TelephonyManager fakeTelephonyManager = (TelephonyManager) mContext.getSystemService(
                Context.TELEPHONY_SERVICE);
        when(fakeTelephonyManager.getNetworkCountryIso()).thenReturn("US");
        doReturn(new ApplicationInfo()).when(mContext).getApplicationInfo();
        doReturn("com.android.server.telecom.tests").when(mContext).getPackageName();

        mComponentContextFixture.putResource(R.string.notification_missedCallTitle,
                MISSED_CALL_TITLE);
        mComponentContextFixture.putResource(R.string.notification_missedCallsTitle,
                MISSED_CALLS_TITLE);
        mComponentContextFixture.putResource(R.string.notification_missedCallsMsg,
                MISSED_CALLS_MSG);
        mComponentContextFixture.putResource(R.string.userCallActivityLabel,
                USER_CALL_ACTIVITY_LABEL);
    }

    public void testCancelNotification() {
        Notification.Builder builder1 = makeNotificationBuilder("builder1");
        Notification.Builder builder2 = makeNotificationBuilder("builder2");
        MissedCallNotifierImpl.NotificationBuilderFactory fakeBuilderFactory =
                makeNotificationBuilderFactory(builder1, builder1, builder2, builder2);

        MissedCallNotifier missedCallNotifier = new MissedCallNotifierImpl(mContext,
                fakeBuilderFactory);

        Call fakeCall = makeFakeCall(TEL_CALL_HANDLE, CALLER_NAME, CALL_TIMESTAMP);

        missedCallNotifier.showMissedCallNotification(fakeCall);
        missedCallNotifier.clearMissedCalls();
        missedCallNotifier.showMissedCallNotification(fakeCall);

        ArgumentCaptor<Integer> requestIdCaptor = ArgumentCaptor.forClass(
                Integer.class);
        verify(mNotificationManager, times(2)).notifyAsUser(isNull(String.class),
                requestIdCaptor.capture(), any(Notification.class), eq(UserHandle.CURRENT));
        verify(mNotificationManager).cancelAsUser(any(String.class), eq(requestIdCaptor.getValue()),
                eq(UserHandle.CURRENT));

        // Verify that the second call to showMissedCallNotification behaves like it were the first.
        verify(builder2).setContentText(CALLER_NAME);
    }

    public void testNotifyMultipleMissedCalls() {
        Notification.Builder[] builders = new Notification.Builder[4];

        for (int i = 0; i < 4; i++) {
            builders[i] = makeNotificationBuilder("builder" + Integer.toString(i));
        }

        Call fakeCall = makeFakeCall(TEL_CALL_HANDLE, CALLER_NAME, CALL_TIMESTAMP);

        MissedCallNotifierImpl.NotificationBuilderFactory fakeBuilderFactory =
                makeNotificationBuilderFactory(builders);

        MissedCallNotifier missedCallNotifier = new MissedCallNotifierImpl(mContext,
                fakeBuilderFactory);

        missedCallNotifier.showMissedCallNotification(fakeCall);
        missedCallNotifier.showMissedCallNotification(fakeCall);

        // The following captor is to capture the two notifications that got passed into
        // notifyAsUser. This distinguishes between the builders used for the full notification
        // (i.e. the one potentially containing sensitive information, such as phone numbers),
        // and the builders used for the notifications shown on the lockscreen, which have been
        // scrubbed of such sensitive info. The notifications which are used as arguments
        // to notifyAsUser are the versions which contain sensitive information.
        ArgumentCaptor<Notification> notificationArgumentCaptor = ArgumentCaptor.forClass(
                Notification.class);
        verify(mNotificationManager, times(2)).notifyAsUser(isNull(String.class), eq(1),
                notificationArgumentCaptor.capture(), eq(UserHandle.CURRENT));
        HashSet<String> privateNotifications = new HashSet<>();
        for (Notification n : notificationArgumentCaptor.getAllValues()) {
            privateNotifications.add(n.toString());
        }

        for (int i = 0; i < 4; i++) {
            Notification.Builder builder = builders[i];
            verify(builder).setWhen(CALL_TIMESTAMP);
            if (i >= 2) {
                // The builders after the first two are for multiple missed calls. The notification
                // for subsequent missed calls is expected to be different in terms of the text
                // contents of the notification, and that is verified here.
                if (privateNotifications.contains(builder.toString())) {
                    verify(builder).setContentText(String.format(MISSED_CALLS_MSG, 2));
                    verify(builder).setContentTitle(MISSED_CALLS_TITLE);
                } else {
                    verify(builder).setContentText(MISSED_CALLS_TITLE);
                    verify(builder).setContentTitle(USER_CALL_ACTIVITY_LABEL);
                }
                verify(builder, never()).addAction(any(Notification.Action.class));
            } else {
                if (privateNotifications.contains(builder.toString())) {
                    verify(builder).setContentText(CALLER_NAME);
                    verify(builder).setContentTitle(MISSED_CALL_TITLE);
                } else {
                    verify(builder).setContentText(MISSED_CALL_TITLE);
                    verify(builder).setContentTitle(USER_CALL_ACTIVITY_LABEL);
                }
            }
        }
    }

    public void testNotifySingleCall() {
        Notification.Builder builder1 = makeNotificationBuilder("builder1");
        Notification.Builder builder2 = makeNotificationBuilder("builder2");
        MissedCallNotifierImpl.NotificationBuilderFactory fakeBuilderFactory =
                makeNotificationBuilderFactory(builder1, builder2);

        MissedCallNotifier missedCallNotifier = new MissedCallNotifierImpl(mContext,
                fakeBuilderFactory);

        Call fakeCall = makeFakeCall(TEL_CALL_HANDLE, CALLER_NAME, CALL_TIMESTAMP);
        missedCallNotifier.showMissedCallNotification(fakeCall);

        ArgumentCaptor<Notification> notificationArgumentCaptor = ArgumentCaptor.forClass(
                Notification.class);
        verify(mNotificationManager).notifyAsUser(isNull(String.class), eq(1),
                notificationArgumentCaptor.capture(), eq(UserHandle.CURRENT));

        Notification.Builder builder;
        Notification.Builder publicBuilder;

        if (notificationArgumentCaptor.getValue().toString().equals("builder1")) {
            builder = builder1;
            publicBuilder = builder2;
        } else {
            builder = builder2;
            publicBuilder = builder1;
        }

        verify(builder).setWhen(CALL_TIMESTAMP);
        verify(publicBuilder).setWhen(CALL_TIMESTAMP);

        verify(builder).setContentText(CALLER_NAME);
        verify(publicBuilder).setContentText(MISSED_CALL_TITLE);

        verify(builder).setContentTitle(MISSED_CALL_TITLE);
        verify(publicBuilder).setContentTitle(USER_CALL_ACTIVITY_LABEL);

        // Create two intents that correspond to call-back and respond back with SMS, and assert
        // that these pending intents have in fact been registered.
        Intent callBackIntent = new Intent(
                TelecomBroadcastIntentProcessor.ACTION_CALL_BACK_FROM_NOTIFICATION,
                TEL_CALL_HANDLE,
                mContext,
                TelecomBroadcastReceiver.class);
        Intent smsIntent = new Intent(
                TelecomBroadcastIntentProcessor.ACTION_SEND_SMS_FROM_NOTIFICATION,
                Uri.fromParts(Constants.SCHEME_SMSTO, TEL_CALL_HANDLE.getSchemeSpecificPart(), null),
                mContext,
                TelecomBroadcastReceiver.class);

        assertNotNull(PendingIntent.getBroadcast(mContext, REQUEST_ID,
                callBackIntent, PendingIntent.FLAG_NO_CREATE));
        assertNotNull(PendingIntent.getBroadcast(mContext, REQUEST_ID,
                smsIntent, PendingIntent.FLAG_NO_CREATE));
    }

    public void testNoSmsBackAfterMissedSipCall() {
        Notification.Builder builder1 = makeNotificationBuilder("builder1");
        MissedCallNotifierImpl.NotificationBuilderFactory fakeBuilderFactory =
                makeNotificationBuilderFactory(builder1);

        MissedCallNotifier missedCallNotifier = new MissedCallNotifierImpl(mContext,
                fakeBuilderFactory);

        Call fakeCall = makeFakeCall(SIP_CALL_HANDLE, CALLER_NAME, CALL_TIMESTAMP);
        missedCallNotifier.showMissedCallNotification(fakeCall);

        // Create two intents that correspond to call-back and respond back with SMS, and assert
        // that in the case of a SIP call, no SMS intent is generated.
        Intent callBackIntent = new Intent(
                TelecomBroadcastIntentProcessor.ACTION_CALL_BACK_FROM_NOTIFICATION,
                SIP_CALL_HANDLE,
                mContext,
                TelecomBroadcastReceiver.class);
        Intent smsIntent = new Intent(
                TelecomBroadcastIntentProcessor.ACTION_SEND_SMS_FROM_NOTIFICATION,
                Uri.fromParts(Constants.SCHEME_SMSTO, SIP_CALL_HANDLE.getSchemeSpecificPart(),
                        null),
                mContext,
                TelecomBroadcastReceiver.class);

        assertNotNull(PendingIntent.getBroadcast(mContext, REQUEST_ID,
                callBackIntent, PendingIntent.FLAG_NO_CREATE));
        assertNull(PendingIntent.getBroadcast(mContext, REQUEST_ID,
                smsIntent, PendingIntent.FLAG_NO_CREATE));
    }

    private Notification.Builder makeNotificationBuilder(String label) {
        Notification.Builder builder = spy(new Notification.Builder(mContext));
        Notification notification = mock(Notification.class);
        when(notification.toString()).thenReturn(label);
        when(builder.toString()).thenReturn(label);
        doReturn(notification).when(builder).build();
        return builder;
    }

    private Call makeFakeCall(Uri handle, String name, long timestamp) {
        Call fakeCall = mock(Call.class);
        when(fakeCall.getHandle()).thenReturn(handle);
        when(fakeCall.getName()).thenReturn(name);
        when(fakeCall.getCreationTimeMillis()).thenReturn(timestamp);
        return fakeCall;
    }

    private MissedCallNotifierImpl.NotificationBuilderFactory makeNotificationBuilderFactory(
            Notification.Builder... builders) {
        MissedCallNotifierImpl.NotificationBuilderFactory builderFactory =
                mock(MissedCallNotifierImpl.NotificationBuilderFactory.class);
        when(builderFactory.getBuilder(mContext)).thenReturn(builders[0],
                Arrays.copyOfRange(builders, 1, builders.length));
        return builderFactory;
    }
}
