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

import android.app.Application;
import android.content.ComponentName;
import android.net.Uri;
import android.os.UserHandle;
import android.telecomm.PhoneAccount;
import android.telecomm.PhoneAccountMetadata;
import android.telephony.PhoneNumberUtils;

/**
 * Top-level Application class for Telecomm.
 */
public final class TelecommApp extends Application {

    /** Singleton instance of TelecommApp. */
    private static TelecommApp sInstance;

    /**
     * Missed call notifier. Exists here so that the instance can be shared with
     * {@link TelecommBroadcastReceiver}.
     */
    private MissedCallNotifier mMissedCallNotifier;

    /**
     * Maintains the list of registered {@link android.telecomm.PhoneAccount}s.
     */
    private PhoneAccountRegistrar mPhoneAccountRegistrar;

    /** {@inheritDoc} */
    @Override public void onCreate() {
        super.onCreate();
        sInstance = this;

        mMissedCallNotifier = new MissedCallNotifier(this);
        mPhoneAccountRegistrar = new PhoneAccountRegistrar(this);

        addHangoutsAccount();

        if (UserHandle.myUserId() == UserHandle.USER_OWNER) {
            TelecommServiceImpl.init(mMissedCallNotifier, mPhoneAccountRegistrar);
        }
    }

    public static TelecommApp getInstance() {
        if (null == sInstance) {
            throw new IllegalStateException("No TelecommApp running.");
        }
        return sInstance;
    }

    MissedCallNotifier getMissedCallNotifier() {
        return mMissedCallNotifier;
    }

    PhoneAccountRegistrar getPhoneAccountRegistrar() {
        return mPhoneAccountRegistrar;
    }

    private void addHangoutsAccount() {
        // TODO: STOPSHIP. We are adding a hacked PhoneAccount to ensure that Wi-Fi calling in
        // Hangouts continues to work. This needs to be replaced with proper Wi-Fi calling wiring
        // to the appropriate Connection Services.
        PhoneAccountMetadata hangouts = new PhoneAccountMetadata(
                new PhoneAccount(
                        new ComponentName(
                                "com.google.android.talk",
                                "com.google.android.apps.babel.telephony.TeleConnectionService"),
                        "null_id"),
                Uri.fromParts("tel", "null_uri", null),
                "650-253-0000",
                PhoneAccountMetadata.CAPABILITY_CALL_PROVIDER,
                R.drawable.stat_sys_phone_call,
                "Wi-Fi calling",
                "Wi-Fi calling by Google Hangouts",
                false);
        mPhoneAccountRegistrar.clearAccounts(
                hangouts.getAccount().getComponentName().getPackageName());
        mPhoneAccountRegistrar.registerPhoneAccount(hangouts);
    }
}
