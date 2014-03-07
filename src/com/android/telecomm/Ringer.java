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

import android.media.Ringtone;
import android.media.RingtoneManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.provider.Settings;
import android.util.Log;

/**
 * Controls ringing and vibration for incoming calls.
 *
 * TODO(santoscordon): Consider moving all ringing responsibility to InCall app as an implementation
 * within InCallServiceBase.
 */
final class Ringer {

    private static final String TAG = Ringer.class.getSimpleName();

    // Message codes used with {@link #mRingtoneHandler}.
    private static final int EVENT_PLAY_RING = 1;
    private static final int EVENT_STOP_RING = 2;

    /**
     * Handler used to send messages to the ringtone-playing thread.
     */
    private Handler mRingtoneHandler;

    /**
     * The active ringtone. Accessed only from the thread looping {@link #mRingtoneHandler}.
     */
    private Ringtone mRingtone;

    /**
     * Starts the vibration, ringer, and/or call-waiting tone.
     */
    void startRinging() {
        // TODO(santoscordon): Double-check that we want to play the ringtone. e.g., don't play if
        // the volume is currently set to 0.

        ThreadUtil.checkOnMainThread();
        Handler handler = getRingtoneHandler();

        Log.d(TAG, "Posting play");
        handler.obtainMessage(EVENT_PLAY_RING, getCurrentRingtone()).sendToTarget();
    }

    /**
     * Stops the vibration, ringer, and/or call-waiting tone.
     */
    void stopRinging() {
        ThreadUtil.checkOnMainThread();
        if (mRingtoneHandler != null) {
            Log.d(TAG, "Posting stop");
            mRingtoneHandler.sendEmptyMessage(EVENT_STOP_RING);
            mRingtoneHandler = null;
        }
    }

    /**
     * Returns the handler to use for playing ringtones.
     */
    private Handler getRingtoneHandler() {
        if (mRingtoneHandler == null) {
            // TODO(santoscordon): Clean this up. Needs more investigation for multi-incoming calls
            // and this multiple thread approach.
            HandlerThread thread = new HandlerThread("ringer");
            thread.start();

            mRingtoneHandler = new Handler(thread.getLooper()) {
                @Override
                public void handleMessage(Message msg) {
                    switch(msg.what) {
                        case EVENT_PLAY_RING:
                            handlePlayRingtone(this, (Ringtone) msg.obj);
                            break;
                        case EVENT_STOP_RING:
                            handleStopRingtone(this);
                            break;
                    }
                }
            };
        }
        return mRingtoneHandler;
    }

    /**
     * @return The user's currently-selected ringtone.
     */
    private Ringtone getCurrentRingtone() {
        // TODO(santoscordon): Needs support for custom ringtones.
        return RingtoneManager.getRingtone(
                TelecommApp.getInstance(), Settings.System.DEFAULT_RINGTONE_URI);
    }

    /**
     * Plays the ringtone. Processed by {@link #mRingtoneHandler}.
     *
     * @param handler The handler that invoked this method.
     */
    private void handlePlayRingtone(Handler handler, Ringtone ringtone) {
        ThreadUtil.checkNotOnMainThread();
        // Verify that we haven't been asked to stop the ringtone before we start playing it.
        if (!handler.hasMessages(EVENT_STOP_RING)) {

            // Check to see if a ringtone already exists and is playing.
            if (mRingtone != null && mRingtone.isPlaying()) {
                mRingtone.stop();
            }
            mRingtone = ringtone;
            mRingtone.play();
        }

        // TODO(santoscordon): Requires reposting EVENT_PLAY_RINGTONE in the case where the ringtone
        // ends. This method only plays one loop of the ringtone.
    }

    /**
     * Stops the ringtone and cleans up references.
     *
     * @param handler The handler that invoked this method.
     */
    private void handleStopRingtone(Handler handler) {
        ThreadUtil.checkNotOnMainThread();
        if (mRingtone != null) {
            mRingtone.stop();
            mRingtone = null;
        }

        handler.getLooper().quitSafely();
    }
}
