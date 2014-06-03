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

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.view.KeyEvent;

/**
 * Static class to handle listening to the headset media buttons.
 */
final class HeadsetMediaButton extends CallsManagerListenerBase {

    /**
     * Broadcast receiver for the ACTION_MEDIA_BUTTON broadcast intent.
     *
     * This functionality isn't lumped in with the other intents in TelecommBroadcastReceiver
     * because we instantiate this as a totally separate BroadcastReceiver instance, since we need
     * to manually adjust its IntentFilter's priority (to make sure we get these intents *before*
     * the media player.)
     */
    private final class MediaButtonBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            KeyEvent event = (KeyEvent) intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
            Log.v(this, "MediaButtonBroadcastReceiver.onReceive()...  event = %s.", event);
            if ((event != null) && (event.getKeyCode() == KeyEvent.KEYCODE_HEADSETHOOK)) {
                boolean consumed = handleHeadsetHook(event);
                Log.v(this, "==> handleHeadsetHook(): consumed = %b.", consumed);
                if (consumed) {
                    abortBroadcast();
                }
            } else {
                if (CallsManager.getInstance().hasAnyCalls()) {
                    // If the phone is anything other than completely idle, then we consume and
                    // ignore any media key events, otherwise it is too easy to accidentally start
                    // playing music while a phone call is in progress.
                    Log.v(this, "MediaButtonBroadcastReceiver: consumed");
                    abortBroadcast();
                }
            }
        }
    }

    // Types of media button presses
    static final int SHORT_PRESS = 1;
    static final int LONG_PRESS = 2;

    private final MediaSession.Callback mSessionCallback = new MediaSession.Callback() {
        @Override
        public void onMediaButtonEvent(Intent intent) {
            KeyEvent event = (KeyEvent) intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
            Log.v(this, "SessionCallback.onMediaButton()...  event = %s.", event);
            if ((event != null) && (event.getKeyCode() == KeyEvent.KEYCODE_HEADSETHOOK)) {
                Log.v(this, "SessionCallback: HEADSETHOOK");
                boolean consumed = handleHeadsetHook(event);
                Log.v(this, "==> handleHeadsetHook(): consumed = %b.", consumed);
            }
        }
    };

    private final MediaButtonBroadcastReceiver mMediaButtonReceiver =
            new MediaButtonBroadcastReceiver();

    private final CallsManager mCallsManager;

    private final MediaSession mSession;

    HeadsetMediaButton(Context context, CallsManager callsManager) {
        mCallsManager = callsManager;

        // Use a separate receiver (from TelecommBroadcastReceiver) for ACTION_MEDIA_BUTTON
        // broadcasts, since we need to manually adjust its priority (to make sure we get these
        // intents *before* the media player.)
        IntentFilter mediaButtonIntentFilter =
                new IntentFilter(Intent.ACTION_MEDIA_BUTTON);

        // Make sure we're higher priority than the media player's MediaButtonIntentReceiver (which
        // currently has the default priority of zero; see apps/Music/AndroidManifest.xml.)
        mediaButtonIntentFilter.setPriority(1);

        context.registerReceiver(mMediaButtonReceiver, mediaButtonIntentFilter);

        // register the component so it gets priority for calls
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        am.registerMediaButtonEventReceiverForCalls(new ComponentName(context.getPackageName(),
                MediaButtonBroadcastReceiver.class.getName()));

        // Register a MediaSession but don't enable it yet. This is a
        // replacement for MediaButtonReceiver
        MediaSessionManager msm =
                (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        mSession = msm.createSession(HeadsetMediaButton.class.getSimpleName());
        mSession.addCallback(mSessionCallback);
        mSession.setFlags(MediaSession.FLAG_EXCLUSIVE_GLOBAL_PRIORITY
                | MediaSession.FLAG_HANDLES_MEDIA_BUTTONS);
        mSession.setPlaybackToLocal(AudioManager.STREAM_VOICE_CALL);
    }

    /**
     * Handles the wired headset button while in-call.
     *
     * @return true if we consumed the event.
     */
    private boolean handleHeadsetHook(KeyEvent event) {
        Log.d(this, "handleHeadsetHook()...%s %s", event.getAction(), event.getRepeatCount());

        if (event.isLongPress()) {
            return mCallsManager.onMediaButton(LONG_PRESS);
        } else if (event.getAction() == KeyEvent.ACTION_UP && event.getRepeatCount() == 0) {
            return mCallsManager.onMediaButton(SHORT_PRESS);
        }

        return true;
    }

    /** ${inheritDoc} */
    @Override
    public void onCallAdded(Call call) {
        if (!mSession.isActive()) {
            mSession.setActive(true);
        }
    }

    /** ${inheritDoc} */
    @Override
    public void onCallRemoved(Call call) {
        if (!mCallsManager.hasAnyCalls()) {
            if (mSession.isActive()) {
                mSession.setActive(false);
            }
        }
    }
}
