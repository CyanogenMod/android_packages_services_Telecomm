/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.telecomm.testcallservice;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * This class receives the notification callback intents used to update call states for
 * {@link TestCallService}.
 */
public class CallNotificationReceiver extends BroadcastReceiver {
    /**
     * Exit intent action is sent when the user clicks the "exit" action of the TestCallService
     * notification. Used to cancel (remove) the notification.
     */
    static final String ACTION_CALL_SERVICE_EXIT =
            "com.android.telecomm.testcallservice.ACTION_CALL_SERVICE_EXIT";

    /** {@inheritDoc} */
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (ACTION_CALL_SERVICE_EXIT.equals(action)) {
            CallServiceNotifier.getInstance().cancelNotification(context);
        }
    }
}
