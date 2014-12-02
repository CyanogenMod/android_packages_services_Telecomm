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
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.ServiceManager;
import android.os.UserHandle;

/**
 * Top-level Application class for Telecom.
 */
public final class TelecomApp extends Application {

    /**
     * Used to bind to the telecom service. Once created, the telecom service will start the telecom
     * global state.
     */
    private class TelecomServiceConnection implements ServiceConnection {
        /** {@inheritDoc} */
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            Log.i(this, "onServiceConnected: %s", name);
            ServiceManager.addService(Context.TELECOM_SERVICE, service);
        }

        /** {@inheritDoc} */
        @Override public void onServiceDisconnected(ComponentName name) {
            Log.i(this, "onDisconnected: %s", name);
            bindToService();
        }
    }

    private ServiceConnection mServiceConnection;

    /** {@inheritDoc} */
    @Override
    public void onCreate() {
        super.onCreate();

        if (UserHandle.myUserId() == UserHandle.USER_OWNER) {
            bindToService();
        }
    }

    private void bindToService() {
        if (mServiceConnection != null) {
            unbindService(mServiceConnection);
            mServiceConnection = null;
        }

        ComponentName componentName = new ComponentName(this, TelecomService.class);
        Intent intent = new Intent(TelecomService.SERVICE_INTERFACE);
        intent.setComponent(componentName);
        int bindFlags = Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT;

        Log.i(this, "binding to TelecomService.");
        ServiceConnection serviceConnection = new TelecomServiceConnection();
        if (bindServiceAsUser(intent, serviceConnection, bindFlags, UserHandle.OWNER)) {
            mServiceConnection = serviceConnection;
            Log.i(this, "TelecomService binding successful");
        } else {
            Log.e(this, null, "Failed to bind to TelecomService.");
        }
    }

    MissedCallNotifier getMissedCallNotifier() {
        return mMissedCallNotifier;
    }

    PhoneAccountRegistrar getPhoneAccountRegistrar() {
        return mPhoneAccountRegistrar;
    }
}
