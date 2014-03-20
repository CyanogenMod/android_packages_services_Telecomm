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

import android.Manifest;
import android.content.Intent;
import android.telecomm.CallService;
import android.telecomm.CallState;
import android.telecomm.TelecommConstants;
import android.telephony.TelephonyManager;

/**
 * Send a {@link TelephonyManager#ACTION_PHONE_STATE_CHANGED} broadcast when the call state
 * changes.
 */
final class PhoneStateBroadcaster extends CallsManagerListenerBase {
    @Override
    public void onCallStateChanged(Call call, CallState oldState, CallState newState) {
        final String phoneState;
        switch (newState) {
            case DIALING:
            case ACTIVE:
            case ON_HOLD:
                phoneState = TelephonyManager.EXTRA_STATE_OFFHOOK;
                break;
            case RINGING:
                phoneState = TelephonyManager.EXTRA_STATE_RINGING;
                break;
            case ABORTED:
            case DISCONNECTED:
                phoneState = TelephonyManager.EXTRA_STATE_IDLE;
                break;
            default:
                Log.w(this, "Call is in an unknown state (%s), not broadcasting: %s",
                        newState, call.getId());
                return;
        }
        sendPhoneStateChangedBroadcast(call, phoneState);
    }

    private void sendPhoneStateChangedBroadcast(Call call, String phoneState) {
        Log.v(this, "sendPhoneStateChangedBroadcast, call %s, phoneState: %s", call, phoneState);

        Intent intent = new Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
        intent.putExtra(TelephonyManager.EXTRA_STATE, phoneState);
        // TODO: See if we can add this (the current API doesn't have a callId).
        intent.putExtra(TelecommConstants.EXTRA_CALL_ID, call.getId());

        // Populate both, since the original API was needlessly complicated.
        String callHandle = call.getHandle().getSchemeSpecificPart();
        intent.putExtra(TelephonyManager.EXTRA_INCOMING_NUMBER, callHandle);
        // TODO: See if we can add this (the current API only sets this on NEW_OUTGOING_CALL).
        intent.putExtra(Intent.EXTRA_PHONE_NUMBER, callHandle);

        // TODO: Replace these with real constants once this API has been vetted.
        CallServiceWrapper callService = call.getCallService();
        if (callService != null) {
            intent.putExtra(CallService.class.getName(), callService.getComponentName());
        }
        TelecommApp.getInstance().sendBroadcast(intent, Manifest.permission.READ_PHONE_STATE);
        Log.i(this, "Broadcasted state change: %s", phoneState);
    }
}
