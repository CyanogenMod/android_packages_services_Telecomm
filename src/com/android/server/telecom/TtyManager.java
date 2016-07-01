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

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.telecom.TelecomManager;

import com.android.internal.util.IndentingPrintWriter;

// TODO: Needed for move to system service: import com.android.internal.R;

final class TtyManager implements WiredHeadsetManager.Listener {
    private final TtyBroadcastReceiver mReceiver = new TtyBroadcastReceiver();
    private final Context mContext;
    private final WiredHeadsetManager mWiredHeadsetManager;
    private int mPreferredTtyMode = TelecomManager.TTY_MODE_OFF;
    private int mCurrentTtyMode = TelecomManager.TTY_MODE_OFF;
    protected NotificationManager mNotificationManager;

    static final int HEADSET_PLUGIN_NOTIFICATION = 1000;

    TtyManager(Context context, WiredHeadsetManager wiredHeadsetManager) {
        mContext = context;
        mWiredHeadsetManager = wiredHeadsetManager;
        mWiredHeadsetManager.addListener(this);

        mNotificationManager =
                (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        mPreferredTtyMode = Settings.Secure.getInt(
                mContext.getContentResolver(),
                Settings.Secure.PREFERRED_TTY_MODE,
                TelecomManager.TTY_MODE_OFF);

        IntentFilter intentFilter = new IntentFilter(
                TelecomManager.ACTION_TTY_PREFERRED_MODE_CHANGED);
        mContext.registerReceiver(mReceiver, intentFilter);

        updateCurrentTtyMode();
    }

    boolean isTtySupported() {
        boolean isEnabled = mContext.getResources().getBoolean(R.bool.tty_enabled);
        Log.v(this, "isTtySupported: %b", isEnabled);
        return isEnabled;
    }

    int getCurrentTtyMode() {
        return mCurrentTtyMode;
    }

    @Override
    public void onWiredHeadsetPluggedInChanged(boolean oldIsPluggedIn, boolean newIsPluggedIn) {
        Log.v(this, "onWiredHeadsetPluggedInChanged");
        updateCurrentTtyMode();

        if (newIsPluggedIn) {
            showHeadSetPlugin();
        } else {
            cancelHeadSetPlugin();
        }
    }

    private void updateCurrentTtyMode() {
        int newTtyMode = TelecomManager.TTY_MODE_OFF;
        if (isTtySupported() && mWiredHeadsetManager.isPluggedIn()) {
            newTtyMode = mPreferredTtyMode;
        }
        Log.v(this, "updateCurrentTtyMode, %d -> %d", mCurrentTtyMode, newTtyMode);

        if (mCurrentTtyMode != newTtyMode) {
            mCurrentTtyMode = newTtyMode;
            Intent ttyModeChanged = new Intent(TelecomManager.ACTION_CURRENT_TTY_MODE_CHANGED);
            ttyModeChanged.putExtra(TelecomManager.EXTRA_CURRENT_TTY_MODE, mCurrentTtyMode);
            mContext.sendBroadcastAsUser(ttyModeChanged, UserHandle.ALL);

            updateAudioTtyMode();
        }
    }

    private void updateAudioTtyMode() {
        String audioTtyMode;
        switch (mCurrentTtyMode) {
            case TelecomManager.TTY_MODE_FULL:
                audioTtyMode = "tty_full";
                break;
            case TelecomManager.TTY_MODE_VCO:
                audioTtyMode = "tty_vco";
                break;
            case TelecomManager.TTY_MODE_HCO:
                audioTtyMode = "tty_hco";
                break;
            case TelecomManager.TTY_MODE_OFF:
            default:
                audioTtyMode = "tty_off";
                break;
        }
        Log.v(this, "updateAudioTtyMode, %s", audioTtyMode);

        AudioManager audioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        audioManager.setParameters("tty_mode=" + audioTtyMode);
    }

    void showHeadSetPlugin() {
        Log.v(TtyManager.this, "showHeadSetPlugin()...");

        String titleText = mContext.getString(
                R.string.headset_plugin_view_title);
        String expandedText = mContext.getString(
                R.string.headset_plugin_view_text);

        Notification notification = new Notification();
        notification.icon = android.R.drawable.stat_sys_headset;
        notification.flags |= Notification.FLAG_NO_CLEAR;
        notification.tickerText = titleText;

        // create the target network operators settings intent
        Intent intent = new Intent("android.intent.action.NO_ACTION");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(mContext, 0, intent, 0);

        notification.setLatestEventInfo(mContext, titleText, expandedText, pi);
        mNotificationManager.notify(HEADSET_PLUGIN_NOTIFICATION, notification);
    }

    void cancelHeadSetPlugin() {
        Log.v(TtyManager.this, "cancelHeadSetPlugin()...");
        mNotificationManager.cancel(HEADSET_PLUGIN_NOTIFICATION);
    }

    private final class TtyBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.startSession("TBR.oR");
            try {
                String action = intent.getAction();
                Log.v(TtyManager.this, "onReceive, action: %s", action);
                if (action.equals(TelecomManager.ACTION_TTY_PREFERRED_MODE_CHANGED)) {
                    int newPreferredTtyMode = intent.getIntExtra(
                            TelecomManager.EXTRA_TTY_PREFERRED_MODE, TelecomManager.TTY_MODE_OFF);
                    if (mPreferredTtyMode != newPreferredTtyMode) {
                        mPreferredTtyMode = newPreferredTtyMode;
                        updateCurrentTtyMode();
                    }
                }
            } finally {
                Log.endSession();
            }
        }
    }

    /**
     * Dumps the state of the {@link TtyManager}.
     *
     * @param pw The {@code IndentingPrintWriter} to write the state to.
     */
    public void dump(IndentingPrintWriter pw) {
        pw.println("mCurrentTtyMode: " + mCurrentTtyMode);
    }
}
