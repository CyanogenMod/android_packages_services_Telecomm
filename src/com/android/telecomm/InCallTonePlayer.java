/*
 * Copyright 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.telecomm;

import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;

/**
 * Play a call-related tone (ringback, busy signal, etc.) through ToneGenerator. To use, create an
 * instance using InCallTonePlayer.Factory (passing in the TONE_* constant for the tone you want)
 * and start() it. Implemented on top of {@link Thread} so that the tone plays in its own thread.
 */
public final class InCallTonePlayer extends Thread {

    /**
     * Factory used to create InCallTonePlayers. Exists to aid with testing mocks.
     */
    public static class Factory {
        private final CallAudioManager mCallAudioManager;

        Factory(CallAudioManager callAudioManager) {
            mCallAudioManager = callAudioManager;
        }

        InCallTonePlayer createPlayer(int tone) {
            return new InCallTonePlayer(tone, mCallAudioManager);
        }
    }

    // The possible tones that we can play.
    public static final int TONE_NONE = 0;
    public static final int TONE_RING_BACK = 1;

    // The tone volume relative to other sounds in the stream.
    private static final int RELATIVE_VOLUME_EMERGENCY = 100;
    private static final int RELATIVE_VOLUME_HIPRI = 80;
    private static final int RELATIVE_VOLUME_LOPRI = 50;

    // Buffer time (in msec) to add on to the tone timeout value. Needed mainly when the timeout
    // value for a tone is exact duration of the tone itself.
    private static final int TIMEOUT_BUFFER_MS = 20;

    // The tone state.
    private static final int STATE_OFF = 0;
    private static final int STATE_ON = 1;
    private static final int STATE_STOPPED = 2;

    /**
     * Keeps count of the number of actively playing tones so that we can notify CallAudioManager
     * when we need focus and when it can be release. This should only be manipulated from the main
     * thread.
     */
    private static int sTonesPlaying = 0;

    private final CallAudioManager mCallAudioManager;

    private final Handler mMainThreadHandler = new Handler(Looper.getMainLooper());

    /** The ID of the tone to play. */
    private final int mToneId;

    /** Current state of the tone player. */
    private int mState;

    /**
     * Initializes the tone player. Private; use the {@link Factory} to create tone players.
     *
     * @param toneId ID of the tone to play, see TONE_* constants.
     */
    private InCallTonePlayer(int toneId, CallAudioManager callAudioManager) {
        mState = STATE_OFF;
        mToneId = toneId;
        mCallAudioManager = callAudioManager;
    }

    /** {@inheritDoc} */
    @Override
    public void run() {
        ToneGenerator toneGenerator = null;
        try {
            Log.d(this, "run(toneId = %s)", mToneId);

            final int toneType;  // Passed to ToneGenerator.startTone.
            final int toneVolume;  // Passed to the ToneGenerator constructor.
            final int toneLengthMs;

            switch (mToneId) {
                case TONE_RING_BACK:
                    toneType = ToneGenerator.TONE_SUP_RINGTONE;
                    toneVolume = RELATIVE_VOLUME_HIPRI;
                    toneLengthMs = Integer.MAX_VALUE - TIMEOUT_BUFFER_MS;
                    break;
                default:
                    throw new IllegalStateException("Bad toneId: " + mToneId);
            }

            // TODO(santoscordon): Bluetooth should be set manually (STREAM_BLUETOOTH_SCO) for tone
            // generator.
            int stream = AudioManager.STREAM_VOICE_CALL;

            // If the ToneGenerator creation fails, just continue without it. It is a local audio
            // signal, and is not as important.
            try {
                Log.v(this, "Creating generator");
                toneGenerator = new ToneGenerator(stream, toneVolume);
            } catch (RuntimeException e) {
                Log.w(this, "Failed to create ToneGenerator.", e);
                return;
            }

            // TODO(santoscordon): Certain CDMA tones need to check the ringer-volume state before
            // playing. See CallNotifier.InCallTonePlayer.

            // TODO(santoscordon): Some tones play through the end of a call so we need to inform
            // CallAudioManager that we want focus the same way that Ringer does.

            synchronized (this) {
                if (mState != STATE_STOPPED) {
                    mState = STATE_ON;
                    toneGenerator.startTone(toneType);
                    try {
                        Log.v(this, "Starting tone %d...waiting for %d ms.", mToneId,
                                toneLengthMs + TIMEOUT_BUFFER_MS);
                        wait(toneLengthMs + TIMEOUT_BUFFER_MS);
                    } catch (InterruptedException e) {
                        Log.w(this, "wait interrupted", e);
                    }
                }
            }
            mState = STATE_OFF;
        } finally {
            if (toneGenerator != null) {
                toneGenerator.release();
            }
            cleanUpTonePlayer();
        }
    }

    void startTone() {
        ThreadUtil.checkOnMainThread();

        sTonesPlaying++;
        if (sTonesPlaying == 1) {
            mCallAudioManager.setIsTonePlaying(true);
        }

        start();
    }

    /**
     * Stops the tone.
     */
    void stopTone() {
        synchronized (this) {
            if (mState == STATE_ON) {
                Log.d(this, "Stopping the tone %d.", mToneId);
                notify();
            }
            mState = STATE_STOPPED;
        }
    }

    private void cleanUpTonePlayer() {
        // Release focus on the main thread.
        mMainThreadHandler.post(new Runnable() {
            @Override public void run() {
                if (sTonesPlaying == 0) {
                    Log.wtf(this, "Over-releasing focus for tone player.");
                } else if (--sTonesPlaying == 0) {
                    mCallAudioManager.setIsTonePlaying(false);
                }
            }
        });
    }
}
