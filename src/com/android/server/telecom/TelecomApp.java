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
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.UserHandle;
import android.os.ServiceManager;

/**
 * Top-level Application class for Telecom.
 */
public final class TelecomApp extends Application {

    /**
     * The Telecom service implementation.
     */
    private TelecomServiceImpl mTelecomService;

    /**
     * Missed call notifier. Exists here so that the instance can be shared with
     * {@link TelecomBroadcastReceiver}.
     */
    private MissedCallNotifier mMissedCallNotifier;

    /**
     * Maintains the list of registered {@link android.telecom.PhoneAccountHandle}s.
     */
    private PhoneAccountRegistrar mPhoneAccountRegistrar;

    /**
     * The calls manager for the Telecom service.
     */
    private CallsManager mCallsManager;

    /**
     * The Telecom broadcast receiver.
     */
    private TelecomBroadcastReceiver mTelecomBroadcastReceiver;

    /**
     * The {@link android.telecom.PhoneAccount} broadcast receiver.
     */
    private PhoneAccountBroadcastReceiver mPhoneAccountBroadcastReceiver;

    /** {@inheritDoc} */
    @Override
    public void onCreate() {
        super.onCreate();

        if (UserHandle.myUserId() == UserHandle.USER_OWNER) {
            // Note: This style of initialization mimics what will be performed once Telecom is
            // moved
            // to run in the system service. The emphasis is on ensuring that initialization of all
            // telecom classes happens in one place without relying on Singleton initialization.
            mMissedCallNotifier = new MissedCallNotifier(this);
            mPhoneAccountRegistrar = new PhoneAccountRegistrar(this);

            mCallsManager = new CallsManager(this, mMissedCallNotifier, mPhoneAccountRegistrar);
            CallsManager.initialize(mCallsManager);

            mTelecomService = new TelecomServiceImpl(mMissedCallNotifier, mPhoneAccountRegistrar,
                    mCallsManager, this);
            ServiceManager.addService(Context.TELECOM_SERVICE, mTelecomService);
            mPhoneAccountBroadcastReceiver = new PhoneAccountBroadcastReceiver(
                    mPhoneAccountRegistrar);
            mTelecomBroadcastReceiver = new TelecomBroadcastReceiver(mMissedCallNotifier);

            // Setup broadcast listener for telecom intents.
            IntentFilter telecomFilter = new IntentFilter();
            telecomFilter.addAction(TelecomBroadcastReceiver.ACTION_CALL_BACK_FROM_NOTIFICATION);
            telecomFilter.addAction(TelecomBroadcastReceiver.ACTION_CALL_BACK_FROM_NOTIFICATION);
            telecomFilter.addAction(TelecomBroadcastReceiver.ACTION_SEND_SMS_FROM_NOTIFICATION);
            registerReceiver(mTelecomBroadcastReceiver, telecomFilter);

            IntentFilter phoneAccountFilter = new IntentFilter();
            phoneAccountFilter.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
            phoneAccountFilter.addDataScheme("package");
            registerReceiver(mPhoneAccountBroadcastReceiver, phoneAccountFilter);
        }
    }

    MissedCallNotifier getMissedCallNotifier() {
        return mMissedCallNotifier;
    }

    PhoneAccountRegistrar getPhoneAccountRegistrar() {
        return mPhoneAccountRegistrar;
    }
}
