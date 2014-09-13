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

import android.app.Application;
import android.os.UserHandle;

/**
 * Top-level Application class for Telecom.
 */
public final class TelecomApp extends Application {

    /** Singleton instance of TelecomApp. */
    private static TelecomApp sInstance;

    /**
     * Missed call notifier. Exists here so that the instance can be shared with
     * {@link TelecomBroadcastReceiver}.
     */
    private MissedCallNotifier mMissedCallNotifier;

    /**
     * Maintains the list of registered {@link android.telecom.PhoneAccountHandle}s.
     */
    private PhoneAccountRegistrar mPhoneAccountRegistrar;

    /** {@inheritDoc} */
    @Override public void onCreate() {
        super.onCreate();
        sInstance = this;

        mMissedCallNotifier = new MissedCallNotifier(this);
        mPhoneAccountRegistrar = new PhoneAccountRegistrar(this);

        if (UserHandle.myUserId() == UserHandle.USER_OWNER) {
            TelecomServiceImpl.init(mMissedCallNotifier, mPhoneAccountRegistrar);
        }
    }

    public static TelecomApp getInstance() {
        if (null == sInstance) {
            throw new IllegalStateException("No TelecomApp running.");
        }
        return sInstance;
    }

    MissedCallNotifier getMissedCallNotifier() {
        return mMissedCallNotifier;
    }

    PhoneAccountRegistrar getPhoneAccountRegistrar() {
        return mPhoneAccountRegistrar;
    }
}
