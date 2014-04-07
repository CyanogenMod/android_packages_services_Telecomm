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
import android.os.SystemVibrator;
import android.os.Vibrator;
import android.provider.Settings;
import android.telecomm.CallState;

import com.google.common.collect.Lists;

import java.util.List;

/**
 * Controls the ringtone player.
 */
final class Ringer extends CallsManagerListenerBase {
    private static final long[] VIBRATION_PATTERN = new long[] {
        0, // No delay before starting
        1000, // How long to vibrate
        1000, // How long to wait before vibrating again
    };

    /** Indicate that we want the pattern to repeat at the step which turns on vibration. */
    private static final int VIBRATION_PATTERN_REPEAT = 1;

    private final AsyncRingtonePlayer mRingtonePlayer = new AsyncRingtonePlayer();

    /**
     * Used to keep ordering of unanswered incoming calls. There can easily exist multiple incoming
     * calls and explicit ordering is useful for maintaining the proper state of the ringer.
     */
    private final List<Call> mUnansweredCalls = Lists.newLinkedList();

    private final CallAudioManager mCallAudioManager;

    private final Vibrator mVibrator;

    /**
     * Used to track the status of {@link #mVibrator} in the case of simultaneous incoming calls.
     */
    private boolean mIsVibrating = false;

    Ringer(CallAudioManager callAudioManager) {
        mCallAudioManager = callAudioManager;

        // We don't rely on getSystemService(Context.VIBRATOR_SERVICE) to make sure this
        // vibrator object will be isolated from others.
        mVibrator = new SystemVibrator(TelecommApp.getInstance());
    }

    @Override
    public void onCallAdded(Call call) {
        if (call.isIncoming() && call.getState() == CallState.RINGING) {
            if (mUnansweredCalls.contains(call)) {
                Log.wtf(this, "New ringing call is already in list of unanswered calls");
            }
            mUnansweredCalls.add(call);
            if (mUnansweredCalls.size() == 1) {
                // Start the ringer if we are the top-most incoming call (the only one in this
                // case).
                startRinging();
            }
        }
    }

    @Override
    public void onCallRemoved(Call call) {
        removeFromUnansweredCall(call);
    }

    @Override
    public void onCallStateChanged(Call call, CallState oldState, CallState newState) {
        if (newState != CallState.RINGING) {
            removeFromUnansweredCall(call);
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
        if (!mUnansweredCalls.isEmpty() && mUnansweredCalls.get(0) == call) {
            stopRinging();
        }
    }

    /**
     * Removes the specified call from the list of unanswered incoming calls and updates the ringer
     * based on the new state of {@link #mUnansweredCalls}. Safe to call with a call that is not
     * present in the list of incoming calls.
     */
    private void removeFromUnansweredCall(Call call) {
        if (mUnansweredCalls.remove(call)) {
            if (mUnansweredCalls.isEmpty()) {
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

        if (shouldVibrate(TelecommApp.getInstance()) && !mIsVibrating) {
            mVibrator.vibrate(VIBRATION_PATTERN, VIBRATION_PATTERN_REPEAT,
                    AudioManager.STREAM_RING);
            mIsVibrating = true;
        }
    }

    private void stopRinging() {
        Log.v(this, "stopRinging");
        mRingtonePlayer.stop();
        // Even though stop is asynchronous it's ok to update the audio manager. Things like audio
        // focus are voluntary so releasing focus too early is not detrimental.
        mCallAudioManager.setIsRinging(false);

        if (mIsVibrating) {
            mVibrator.cancel();
            mIsVibrating = false;
        }
    }

    private boolean shouldVibrate(Context context) {
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        int ringerMode = audioManager.getRingerMode();
        if (getVibrateWhenRinging(context)) {
            return ringerMode != AudioManager.RINGER_MODE_SILENT;
        } else {
            return ringerMode == AudioManager.RINGER_MODE_VIBRATE;
        }
    }

    private boolean getVibrateWhenRinging(Context context) {
        if (!mVibrator.hasVibrator()) {
            return false;
        }
        return Settings.System.getInt(context.getContentResolver(),
                Settings.System.VIBRATE_WHEN_RINGING, 0) != 0;
    }
}
