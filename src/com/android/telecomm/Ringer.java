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

import com.google.common.collect.Lists;

import java.util.List;

/**
 * Controls the ringtone player.
 */
final class Ringer extends CallsManagerListenerBase {
    private final AsyncRingtonePlayer mRingtonePlayer = new AsyncRingtonePlayer();

    /**
     * Used to keep ordering of unanswered incoming calls. There can easily exist multiple incoming
     * calls and explicit ordering is useful for maintaining the proper state of the ringer.
     */
    private final List<String> mUnansweredCallIds = Lists.newLinkedList();

    private final CallAudioManager mCallAudioManager;

    Ringer(CallAudioManager callAudioManager) {
        mCallAudioManager = callAudioManager;
    }

    @Override
    public void onCallAdded(Call call) {
        if (call.isIncoming() && call.getState() == CallState.RINGING) {
            if (mUnansweredCallIds.contains(call.getId())) {
                Log.wtf(this, "New ringing call is already in list of unanswered calls");
            }
            mUnansweredCallIds.add(call.getId());
            if (mUnansweredCallIds.size() == 1) {
                // Start the ringer if we are the top-most incoming call (the only one in this
                // case).
                startRinging();
            }
        }
    }

    @Override
    public void onCallRemoved(Call call) {
        removeFromUnansweredCallIds(call.getId());
    }

    @Override
    public void onCallStateChanged(Call call, CallState oldState, CallState newState) {
        if (newState != CallState.RINGING) {
            removeFromUnansweredCallIds(call.getId());
        }
    }

    @Override
    public void onIncomingCallAnswered(Call call) {
        onRespondedToIncomingCall(call);
    }

    @Override
    public void onIncomingCallRejected(Call call) {
        onRespondedToIncomingCall(call);
    }

    private void onRespondedToIncomingCall(Call call) {
        // Only stop the ringer if this call is the top-most incoming call.
        if (!mUnansweredCallIds.isEmpty() && mUnansweredCallIds.get(0).equals(call.getId())) {
            stopRinging();
        }
    }

    /**
     * Removes the specified call from the list of unanswered incoming calls and updates the ringer
     * based on the new state of {@link #mUnansweredCallIds}. Safe to call with a call ID that
     * is not present in the list of incoming calls.
     *
     * @param callId The ID of the call.
     */
    private void removeFromUnansweredCallIds(String callId) {
        if (mUnansweredCallIds.remove(callId)) {
            if (mUnansweredCallIds.isEmpty()) {
                stopRinging();
            } else {
                startRinging();
            }
        }
    }

    private void startRinging() {
        AudioManager audioManager = (AudioManager) TelecommApp.getInstance().getSystemService(
                Context.AUDIO_SERVICE);
        if (audioManager.getStreamVolume(AudioManager.STREAM_RING) > 0) {
            Log.v(this, "startRinging");
            mCallAudioManager.setIsRinging(true);
            mRingtonePlayer.play();
        } else {
            Log.v(this, "startRinging, skipping because volume is 0");
        }
    }

    private void stopRinging() {
        Log.v(this, "stopRinging");
        mRingtonePlayer.stop();
        // Even though stop is asynchronous it's ok to update the audio manager. Things like audio
        // focus are voluntary so releasing focus too early is not detrimental.
        mCallAudioManager.setIsRinging(false);
    }
}
