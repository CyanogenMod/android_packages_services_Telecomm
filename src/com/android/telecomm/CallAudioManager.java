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

import android.content.Context;
import android.media.AudioManager;
import android.telecomm.CallState;

/**
 * This class manages audio modes, streams and other properties.
 */
final class CallAudioManager extends CallsManagerListenerBase {
    @Override
    public void onCallStateChanged(Call call, CallState oldState, CallState newState) {
        switch (newState) {
            case ACTIVE:
                updateAudioFocusForActiveCall(call);
                break;
            case DISCONNECTED:
                updateAudioFocusForNoCall();
                break;
            default:
                break;
        }
    }

    private void updateAudioFocusForActiveCall(Call call) {
        Log.v(this, "onForegroundCallChanged, requesting audio focus");
        Context context = TelecommApp.getInstance();
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        audioManager.requestAudioFocusForCall(AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        audioManager.setMode(AudioManager.MODE_IN_CALL);
        audioManager.setMicrophoneMute(false);
        audioManager.setSpeakerphoneOn(false);
    }

    private void updateAudioFocusForNoCall() {
        Log.v(this, "updateAudioFocusForNoCall, abandoning audio focus");
        Context context = TelecommApp.getInstance().getApplicationContext();
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        audioManager.setMode(AudioManager.MODE_NORMAL);
        audioManager.abandonAudioFocusForCall();
    }
}
