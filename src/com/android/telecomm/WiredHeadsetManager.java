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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;

import java.util.HashSet;

/** Listens for and caches headset state. */
class WiredHeadsetManager {
    interface Listener {
        void onWiredHeadsetPluggedInChanged(boolean oldIsPluggedIn, boolean newIsPluggedIn);
    }

    /** Receiver for wired headset plugged and unplugged events. */
    private class WiredHeadsetBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_HEADSET_PLUG)) {
                boolean isPluggedIn = intent.getIntExtra("state", 0) == 1;
                Log.v(WiredHeadsetManager.this, "ACTION_HEADSET_PLUG event, plugged in: %b",
                        isPluggedIn);
                onHeadsetPluggedInChanged(isPluggedIn);
            }
        }
    }

    private final WiredHeadsetBroadcastReceiver mReceiver;
    private boolean mIsPluggedIn;
    private final HashSet<Listener> mListeners = new HashSet<>();

    WiredHeadsetManager(Context context) {
        mReceiver = new WiredHeadsetBroadcastReceiver();

        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mIsPluggedIn = audioManager.isWiredHeadsetOn();

        // Register for misc other intent broadcasts.
        IntentFilter intentFilter = new IntentFilter(Intent.ACTION_HEADSET_PLUG);
        context.registerReceiver(mReceiver, intentFilter);
    }

    void addListener(Listener listener) {
        mListeners.add(listener);
    }

    void removeListener(Listener listener) {
        mListeners.remove(listener);
    }

    boolean isPluggedIn() {
        return mIsPluggedIn;
    }

    private void onHeadsetPluggedInChanged(boolean isPluggedIn) {
        if (mIsPluggedIn != isPluggedIn) {
            Log.v(this, "onHeadsetPluggedInChanged, mIsPluggedIn: %b -> %b", mIsPluggedIn,
                    isPluggedIn);
            boolean oldIsPluggedIn = mIsPluggedIn;
            mIsPluggedIn = isPluggedIn;
            for (Listener listener : mListeners) {
                listener.onWiredHeadsetPluggedInChanged(oldIsPluggedIn, mIsPluggedIn);
            }
        }
    }
}
