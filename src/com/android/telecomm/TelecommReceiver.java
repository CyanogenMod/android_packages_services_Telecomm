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

package com.android.telecomm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.util.Log;

/**
 * Receiver for public intents relating to Telecomm.
 */
public class TelecommReceiver extends BroadcastReceiver {

    private static final String TAG = TelecommReceiver.class.getSimpleName();

    /**
     * Action used as a request for CallsManager to connect with the CallService described in the
     * extras of this intent.
     * Extras used: {@link #EXTRA_PACKAGE_NAME}, {@link #EXTRA_CALL_SERVICE_ID}
     * TODO(santoscordon): As this gets finalized, it should eventually move to a public location.
     * This would normally go into TelephonyManager.java but we are avoiding more additions to that
     * file so this may require a new container or it could go into framework's Intent.java.
     */
    public static final String ACTION_CONNECT_CALL_SERVICE =
            "com.android.telecomm.CONNECT_CALL_SERVICE";

    /**
     * The package name of the {@link ICallServiceProvider} used to get the {@link CallService}.
     */
    static final String EXTRA_PACKAGE_NAME = "com.android.telecomm.PACKAGE_NAME";

    /**
     * The CallService ID used to identify the {@link CallService} via {@link ICallServiceProvider}.
     * IDs are only required to be unique within the scope of an {@link ICallServiceProvider}.
     */
    static final String EXTRA_CALL_SERVICE_ID =
            "com.android.telecomm.CALL_SERVICE_ID";

    private CallsManager mCallsManager = CallsManager.getInstance();

    /** {@inheritDoc} */
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (ACTION_CONNECT_CALL_SERVICE.equals(action)) {
            connectToCallService(intent);
        }
    }

    /**
     * Tells CallsManager to connect to the {@link #CallService} identified by the package name
     * and call-service ID in the extras of the intent parameter.
     *
     * @param intent The intent containing the package name and call-service ID as extras.
     */
    private void connectToCallService(Intent intent) {
        String packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME);
        String callServiceId = intent.getStringExtra(EXTRA_CALL_SERVICE_ID);
        if (TextUtils.isEmpty(packageName) || TextUtils.isEmpty(callServiceId)) {
            Log.w(TAG, "Rejecting request to connect to call service due to lack of data."
                    + " packageName: [" + packageName + "]"
                    + ", callServiceId: [" + callServiceId + "]");
            return;
        }

        // TODO(santoscordon): Use packageName and callServiceId to connect with the CallService.
    }
}
