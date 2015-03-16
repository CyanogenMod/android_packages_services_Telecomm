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

package com.android.server.telecom.components;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.os.IBinder;

import com.android.server.telecom.Log;
import com.android.server.telecom.TelecomSystem;

/**
 * Implementation of the ITelecom interface.
 */
public class TelecomService extends Service implements TelecomSystem.Component {

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(this, "onBind");
        // We are guaranteed that the TelecomService will be started before any other
        // components in this package because it is started and kept running by the system.
        TelecomSystem.setInstance(new TelecomSystem(this));
        // Start the BluetoothPhoneService
        if (BluetoothAdapter.getDefaultAdapter() != null) {
            startService(new Intent(this, BluetoothPhoneService.class));
        }
        return getTelecomSystem().getTelecomServiceImpl().getBinder();
    }

    @Override
    public TelecomSystem getTelecomSystem() {
        return TelecomSystem.getInstance();
    }
}
