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

package com.android.telecomm;

import com.google.android.collect.Lists;

import com.android.internal.telecomm.ITelecommService;

import android.content.ComponentName;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.os.ServiceManager;
import android.telecomm.Subscription;
import android.text.TextUtils;

import java.util.List;

/**
 * Implementation of the ITelecomm interface.
 */
public class TelecommServiceImpl extends ITelecommService.Stub {
    private static final String TAG = TelecommServiceImpl.class.getSimpleName();

    private static final String SERVICE_NAME = "telecomm";

    private static final int MSG_SILENCE_RINGER = 1;
    private static final int MSG_SHOW_CALL_SCREEN = 2;

    /** The singleton instance. */
    private static TelecommServiceImpl sInstance;

    /**
     * A handler that processes messages on the main thread in the phone process. Since many
     * of the Phone calls are not thread safe this is needed to shuttle the requests from the
     * inbound binder threads to the main thread in the phone process.
     */
    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_SILENCE_RINGER:
                    silenceRingerInternal();
                    break;
                case MSG_SHOW_CALL_SCREEN:
                    showCallScreenInternal(msg.arg1 == 1);
                    break;
            }
        }
    };

    /**
     * Initialize the singleton TelecommServiceImpl instance.
     * This is only done once, at startup, from TelecommApp.onCreate().
     */
    static TelecommServiceImpl init() {
        synchronized (TelecommServiceImpl.class) {
            if (sInstance == null) {
                sInstance = new TelecommServiceImpl();
            } else {
                Log.wtf(TAG, "init() called multiple times!  sInstance %s", sInstance);
            }
            return sInstance;
        }
    }

    /** Private constructor; @see init() */
    private TelecommServiceImpl() {
        publish();
    }

    private void publish() {
        Log.d(this, "publish: %s", this);
        ServiceManager.addService(SERVICE_NAME, this);
    }

    //
    // Implementation of the ITelephony interface.
    //

    @Override
    public void silenceRinger() {
        Log.d(this, "silenceRinger");
        // TODO: find a more appropriate permission to check here.
        enforceModifyPermission();
        mHandler.sendEmptyMessage(MSG_SILENCE_RINGER);
    }

    /**
     * Internal implemenation of silenceRinger().
     * This should only be called from the main thread of the Phone app.
     * @see #silenceRinger
     */
    private void silenceRingerInternal() {
        CallsManager.getInstance().getRinger().silence();
    }

    /**
     * Make sure the caller has the MODIFY_PHONE_STATE permission.
     *
     * @throws SecurityException if the caller does not have the required permission
     */
    private void enforceModifyPermission() {
        TelecommApp.getInstance().enforceCallingOrSelfPermission(
                android.Manifest.permission.MODIFY_PHONE_STATE, null);
    }

    @Override
    public void showCallScreen(boolean showDialpad) {
        mHandler.obtainMessage(MSG_SHOW_CALL_SCREEN, showDialpad ? 1 : 0, 0).sendToTarget();
    }

    private void showCallScreenInternal(boolean showDialpad) {
        CallsManager.getInstance().getInCallController().bringToForeground(showDialpad);
    }

    @Override
    public ComponentName getSystemPhoneApplication() {
        final Resources resources = TelecommApp.getInstance().getResources();
        final String packageName = resources.getString(R.string.ui_default_package);
        final String className = resources.getString(R.string.dialer_default_class);

        if (TextUtils.isEmpty(packageName) || TextUtils.isEmpty(className)) {
            return null;
        }

        return new ComponentName(packageName, className);
    }

    // TODO (STOPSHIP): Static list of Subscriptions for testing and UX work only.

    private  static final ComponentName sComponentName = new ComponentName(
            "com.android.telecomm",
            TelecommServiceImpl.class.getName());  // This field is a no-op

    private static final List<Subscription> sSubscriptions = Lists.newArrayList(
            new Subscription(
                    sComponentName,
                    "subscription0",
                    Uri.parse("tel:999-555-1212"),
                    R.string.test_subscription_0_label,
                    R.string.test_subscription_0_short_description,
                    R.drawable.q_mobile,
                    true,
                    true),
            new Subscription(
                    sComponentName,
                    "subscription1",
                    Uri.parse("tel:333-111-2222"),
                    R.string.test_subscription_1_label,
                    R.string.test_subscription_1_short_description,
                    R.drawable.market_wireless,
                    true,
                    false),
            new Subscription(
                    sComponentName,
                    "subscription2",
                    Uri.parse("mailto:two@example.com"),
                    R.string.test_subscription_2_label,
                    R.string.test_subscription_2_short_description,
                    R.drawable.talk_to_your_circles,
                    true,
                    false),
            new Subscription(
                    sComponentName,
                    "subscription3",
                    Uri.parse("mailto:three@example.com"),
                    R.string.test_subscription_3_label,
                    R.string.test_subscription_3_short_description,
                    R.drawable.chat_with_others,
                    true,
                    false)
    );



    @Override
    public List<Subscription> getSubscriptions() {
        return sSubscriptions;
    }

    @Override
    public void setEnabled(Subscription subscription, boolean enabled) {
        // Enforce MODIFY_PHONE_STATE ?
        // TODO
    }

    @Override
    public void setSystemDefault(Subscription subscription) {
        // Enforce MODIFY_PHONE_STATE ?
        // TODO
    }
}
