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

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnErrorListener;
import android.media.MediaPlayer.OnPreparedListener;
import android.media.RingtoneManager;
import android.net.Uri;
import android.telecomm.CallState;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

import java.io.IOException;
import java.util.List;

/**
 * Controls ringing and vibration for incoming calls.
 */
final class Ringer extends CallsManagerListenerBase implements OnErrorListener, OnPreparedListener {
    // States for the Ringer.
    /** Actively playing the ringer. */
    private static final int RINGING = 1;

    /** Ringer currently stopped. */
    private static final int STOPPED = 2;

    /** {@link #mMediaPlayer} is preparing, expected to ring once prepared. */
    private static final int PREPARING_WITH_RING = 3;

    /** {@link #mMediaPlayer} is preparing, expected to stop once prepared. */
    private static final int PREPARING_WITH_STOP = 4;

    /**
     * The current state of the ringer.
     */
    private int mState = STOPPED;

    /** The active media player for the ringer. */
    private MediaPlayer mMediaPlayer;

    /*
     * Used to keep ordering of unanswered incoming calls. The existence of multiple call services
     * means that there can easily exist multiple incoming calls and explicit ordering is useful for
     * maintaining the proper state of the ringer.
     */
    private final List<String> mUnansweredCallIds = Lists.newLinkedList();

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

    /**
     * Starts the vibration, ringer, and/or call-waiting tone.
     * TODO(santoscordon): vibration and call-waiting tone.
     */
    private void startRinging() {
        // Check if we are muted before playing the ringer.
        if (getAudioManager().getStreamVolume(AudioManager.STREAM_RING) > 0) {
            moveToState(RINGING);
        } else {
            Log.d(this, "Ringer play skipped due to muted volume.");
        }
    }

    /**
     * Stops the vibration, ringer, and/or call-waiting tone.
     */
    private void stopRinging() {
        moveToState(STOPPED);
    }

    /**
     * Handles asynchronous media player "prepared" response by playing the ringer if we are
     * still expected to or uninitializing it if we've been asked to stop.
     *
     * {@inheritDoc}
     */
    @Override
    public void onPrepared(MediaPlayer mediaPlayer) {
        Preconditions.checkState(mMediaPlayer == null);

        // See {@link #moveToState} for state transitions.
        if (PREPARING_WITH_RING == mState) {
            Log.i(this, "Playing the ringer.");
            setRingerAudioMode();
            mMediaPlayer = mediaPlayer;
            mMediaPlayer.start();
            setState(RINGING);
        } else if (PREPARING_WITH_STOP == mState) {
            mediaPlayer.release();
            setState(STOPPED);
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean onError(MediaPlayer mediaPlayer, int what, int extra) {
        Log.i(this, "Mediaplayer failed to initialize. What: %d, extra: %d.", what, extra);
        resetMediaPlayer();
        setState(STOPPED);
        return true;
    }

    /**
     * Transitions the state of the ringer. State machine below. Any missing arrows imply that the
     * state remains the same (e.g., (r) on RING state keeps it at RING state).
     *
     * +----------------(s)----------------------------+
     * |                                               |
     * +----------------(e)-------+                    |
     * |                          |                    |
     * +-> STOPPED -(r)-> PREPARING_WITH_RING +-(p)-> RING
     *       ^                    ^           |
     *       |                    |           |
     *     (p,e)                 (r)          |
     *       |                    |           +-(s)-> PREPARING_WITH_STOP
     *       |                    |                      | |
     *       |                    +----------------------+ |
     *       +---------------------------------------------+
     *
     * STOPPED - Ringer completely stopped, like its initial state.
     * PREPARING_TO_RING - Media player preparing asynchronously to start ringing.
     * RINGING - The ringtone is currently playing.
     * PREPARING_TO_STOP - Media player is still preparing, but we've already been asked to stop.
     *
     * (r) - {@link #startRinging}
     * (s) - {@link #stopRinging}
     * (p) - {@link #onPrepared}
     * (e) - {@link #onError}
     */
    private void moveToState(int newState) {
        // Only this method sets PREPARING_* states.
        Preconditions.checkState(newState == RINGING || newState == STOPPED);

        if (newState == mState) {
            return;
        }

        if (RINGING == newState) {
            if (STOPPED == mState) {
                // If we are stopped, we need to preparing the media player and wait for it to
                // start the ring. New state set by prepareForRinging.
                if (prepareForRinging()) {
                    setState(PREPARING_WITH_RING);
                }
            } else if (PREPARING_WITH_STOP == mState) {
                // We are currently preparing the media player, but expect it to put the ringer into
                // stop once prepared...change that to ring.
                setState(PREPARING_WITH_RING);
            }
        } else if (STOPPED == newState) {
            if (RINGING == mState) {
                // We are currently ringing, so just stop it.
                stopPlayingRinger();
                setState(STOPPED);
            } else if (PREPARING_WITH_RING == mState) {
                // We are preparing the media player, make sure that when it is finished, it moves
                // to STOPPED instead of ringing.
                setState(PREPARING_WITH_STOP);
            }
        }
    }

    /**
     * Sets the ringer state and checks the current thread.
     *
     * @param newState The new state to set.
     */
    private void setState(int newState) {
        ThreadUtil.checkOnMainThread();
        Log.v(this, "setState, %d -> %d", mState, newState);
        mState = newState;
    }

    /**
     * Starts media player's asynchronous prepare. Response returned in either {@link #onError} or
     * {@link #onPrepared}.
     *
     * @return True if the prepare was successfully started.
     */
    private boolean prepareForRinging() {
        Log.i(this, "Preparing the ringer.");

        Uri ringtoneUri = getCurrentRingtoneUri();
        if (ringtoneUri == null) {
            Log.e(this, null, "Ringtone not set.");
            return false;
        }

        MediaPlayer mediaPlayer = new MediaPlayer();
        mediaPlayer.setOnErrorListener(this);
        mediaPlayer.setOnPreparedListener(this);
        mediaPlayer.setAudioStreamType(AudioManager.STREAM_RING);

        try {
            mediaPlayer.setDataSource(TelecommApp.getInstance(), ringtoneUri);
            mediaPlayer.prepareAsync();
            return true;
        } catch (IOException e) {
            mediaPlayer.reset();
            mediaPlayer.release();

            Log.e(this, e, "Failed to initialize media player for ringer: %s.", ringtoneUri);
            return false;
        }
    }

    /**
     * Stops and uninitializes the media player.
     */
    private void stopPlayingRinger() {
        Preconditions.checkNotNull(mMediaPlayer);
        Log.i(this, "Stopping the ringer.");

        resetMediaPlayer();
        unsetRingerAudioMode();
    }

    /**
     * Stops and uninitializes the media player.
     */
    private void resetMediaPlayer() {
        if (mMediaPlayer != null) {
            // Ringtone.java does not do stop() before release, but it's safer to do so and none of
            // the documentation suggests that stop() should be skipped.
            mMediaPlayer.stop();
            mMediaPlayer.release();
            mMediaPlayer = null;
        }
    }

    /**
     * @return The default ringtone Uri.
     */
    private Uri getCurrentRingtoneUri() {
        return RingtoneManager.getActualDefaultRingtoneUri(
                TelecommApp.getInstance(), RingtoneManager.TYPE_RINGTONE);
    }

    /**
     * Sets the audio mode for playing the ringtone.
     */
    private void setRingerAudioMode() {
        AudioManager audioManager = getAudioManager();
        audioManager.requestAudioFocusForCall(
                AudioManager.STREAM_RING, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        audioManager.setMode(AudioManager.MODE_RINGTONE);
    }

    /**
     * Returns the audio mode to the normal state after ringing.
     */
    private void unsetRingerAudioMode() {
        AudioManager audioManager = getAudioManager();
        audioManager.setMode(AudioManager.MODE_NORMAL);
        audioManager.abandonAudioFocusForCall();
    }

    /**
     * Returns the system audio manager.
     */
    private AudioManager getAudioManager() {
        return (AudioManager) TelecommApp.getInstance().getSystemService(Context.AUDIO_SERVICE);
    }
}
