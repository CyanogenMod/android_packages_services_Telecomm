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
 * This class manages audio modes, streams and other properties.
 */
final class CallAudioManager extends CallsManagerListenerBase {
    private AsyncRingtonePlayer mRinger = new AsyncRingtonePlayer();

    private boolean mHasAudioFocus = false;

    /**
     * Used to keep ordering of unanswered incoming calls. The existence of multiple call services
     * means that there can easily exist multiple incoming calls and explicit ordering is useful for
     * maintaining the proper state of the ringer.
     */
    private final List<String> mUnansweredCallIds = Lists.newLinkedList();

    /**
     * Denotes when the ringer is disabled. This is useful in temporarily disabling the ringer when
     * the a call is answered/rejected by the user, but the call hasn't actually moved out of the
     * ringing state.
     */
    private boolean mIsRingingDisabled = false;

    @Override
    public void onCallAdded(Call call) {
        if (call.getState() == CallState.RINGING) {
            mUnansweredCallIds.add(call.getId());
        }
        updateAudio();
    }

    @Override
    public void onCallRemoved(Call call) {
        removeFromUnansweredCallIds(call.getId());
        updateAudio();
    }

    @Override
    public void onCallStateChanged(Call call, CallState oldState, CallState newState) {
        if (oldState == CallState.RINGING) {
            removeFromUnansweredCallIds(call.getId());
        }

        updateAudio();
    }

    @Override
    public void onIncomingCallAnswered(Call call) {
        mIsRingingDisabled = true;
        updateAudio();
    }

    @Override
    public void onIncomingCallRejected(Call call) {
        mIsRingingDisabled = true;
        updateAudio();
    }

    /**
     * Reads the current state of all calls from CallsManager and sets the appropriate audio modes
     * as well as triggers the start/stop of the ringer.
     */
    private void updateAudio() {
        CallsManager callsManager = CallsManager.getInstance();

        boolean hasRingingCall = !mIsRingingDisabled && !mUnansweredCallIds.isEmpty();
        boolean hasLiveCall = callsManager.hasCallWithState(CallState.ACTIVE, CallState.DIALING);

        int mode = hasRingingCall ? AudioManager.MODE_RINGTONE :
               hasLiveCall ? AudioManager.MODE_IN_CALL :
               AudioManager.MODE_NORMAL;

        boolean needsFocus = (mode != AudioManager.MODE_NORMAL);

        // Acquiring focus needs to be first, unlike releasing focus, which happens at the end.
        if (needsFocus) {
            acquireFocus(hasRingingCall);
            setMode(mode);
        }

        if (hasRingingCall) {
            mRinger.play();
        } else {
            mRinger.stop();
        }

        if (!needsFocus && mHasAudioFocus) {
            setMode(AudioManager.MODE_NORMAL);
            releaseFocus();
        }
    }

    /**
     * Acquires audio focus.
     *
     * @param isForRinging True if this focus is for playing the ringer.
     */
    private void acquireFocus(boolean isForRinging) {
        if (!mHasAudioFocus) {
            int stream = isForRinging ? AudioManager.STREAM_RING : AudioManager.STREAM_VOICE_CALL;

            AudioManager audioManager = getAudioManager();
            audioManager.requestAudioFocusForCall(stream, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            audioManager.setMicrophoneMute(false);
            audioManager.setSpeakerphoneOn(false);
            mHasAudioFocus = true;
        }
    }

    /**
     * Releases focus.
     */
    void releaseFocus() {
        if (mHasAudioFocus) {
            AudioManager audioManager = getAudioManager();

            // Reset speakerphone and mute in case they were changed by telecomm.
            audioManager.setMicrophoneMute(false);
            audioManager.setSpeakerphoneOn(false);
            audioManager.abandonAudioFocusForCall();

            mHasAudioFocus = false;
            Log.v(this, "Focus released");
        }

    }

    /**
     * Sets the audio mode.
     *
     * @param mode Mode constant from AudioManager.MODE_*.
     */
    void setMode(int mode) {
        if (mHasAudioFocus) {
            AudioManager audioManager = getAudioManager();
            if (mode != audioManager.getMode()) {
                Log.v(this, "Audio mode set to %d.", mode);
                audioManager.setMode(mode);
                Log.v(this, "Audio mode actually set to %d.", audioManager.getMode());
            }
        } else {
            Log.wtf(this, "Trying to set audio mode to %d without focus.", mode);
        }
    }

    /**
     * Removes the specified call from the list of unanswered incoming calls.
     *
     * @param callId The ID of the call.
     */
    private void removeFromUnansweredCallIds(String callId) {
        if (!mUnansweredCallIds.isEmpty()) {
            // If the call is the top-most call, then no longer disable the ringer.
            if (callId.equals(mUnansweredCallIds.get(0))) {
                mIsRingingDisabled = false;
            }

            mUnansweredCallIds.remove(callId);
        }
    }

    /**
     * Returns the system audio manager.
     */
    private AudioManager getAudioManager() {
        return (AudioManager) TelecommApp.getInstance().getSystemService(Context.AUDIO_SERVICE);
    }
}
