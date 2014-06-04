/*
 * Copyright (C) 2013 The Android Open Source Project
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
 Ca* See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.telecomm.testapps;

import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.telecomm.CallAudioState;
import android.telecomm.CallInfo;
import android.telecomm.CallService;
import android.telecomm.CallServiceAdapter;
import android.telecomm.CallState;
import android.telephony.DisconnectCause;
import android.util.Log;

import com.android.telecomm.tests.R;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.Maps;

import java.util.List;
import java.util.Map;

/**
 * Service which provides fake calls to test the ICallService interface.
 * TODO(santoscordon): Rename all classes in the directory to Dummy* (e.g., DummyCallService).
 */
public class TestCallService extends CallService {
    private static final String SCHEME_TEL = "tel";

    private final Map<String, CallInfo> mCalls = Maps.newHashMap();
    private final Handler mHandler = new Handler();

    /** Used to play an audio tone during a call. */
    private MediaPlayer mMediaPlayer;

    /** {@inheritDoc} */
    @Override
    public void onAdapterAttached(CallServiceAdapter callServiceAdapter) {
        log("onAdapterAttached");
        mMediaPlayer = createMediaPlayer();
    }

    /**
     * Responds as compatible for all calls except those starting with the number 7 (arbitrarily
     * chosen for testing purposes).
     *
     * {@inheritDoc}
     */
    @Override
    public void isCompatibleWith(CallInfo callInfo) {
        log("isCompatibleWith, callInfo: " + callInfo);
        Preconditions.checkNotNull(callInfo.getHandle());

        // Is compatible if the handle doesn't start with 7.
        boolean isCompatible = !callInfo.getHandle().getSchemeSpecificPart().startsWith("7");

        // Tell CallsManager whether this call service can place the call.
        getAdapter().setIsCompatibleWith(callInfo.getId(), isCompatible);
    }

    /**
     * Starts a call by calling into the adapter.
     *
     * {@inheritDoc}
     */
    @Override
    public void call(final CallInfo callInfo) {
        String number = callInfo.getHandle().getSchemeSpecificPart();
        log("call, number: " + number);

        // Crash on 555-DEAD to test call service crashing.
        if ("5550340".equals(number)) {
            throw new RuntimeException("Goodbye, cruel world.");
        }

        mCalls.put(callInfo.getId(), callInfo);
        getAdapter().handleSuccessfulOutgoingCall(callInfo.getId());
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                activateCall(callInfo.getId());
            }
        }, 4000);
    }

    /** {@inheritDoc} */
    @Override
    public void abort(String callId) {
        log("abort, callId: " + callId);
        destroyCall(callId);
    }

    /** {@inheritDoc} */
    @Override
    public void setIncomingCallId(String callId, Bundle extras) {
        log("setIncomingCallId, callId: " + callId + " extras: " + extras);

        // Use dummy number for testing incoming calls.
        Uri handle = Uri.fromParts(SCHEME_TEL, "5551234", null);

        CallInfo callInfo = new CallInfo(callId, CallState.RINGING, handle);
        mCalls.put(callInfo.getId(), callInfo);
        getAdapter().notifyIncomingCall(callInfo);
    }

    /** {@inheritDoc} */
    @Override
    public void answer(String callId) {
        log("answer, callId: " + callId);
        activateCall(callId);
    }

    /** {@inheritDoc} */
    @Override
    public void reject(String callId) {
        log("reject, callId: " + callId);
        getAdapter().setDisconnected(callId, DisconnectCause.INCOMING_REJECTED, null);
    }

    /** {@inheritDoc} */
    @Override
    public void disconnect(String callId) {
        log("disconnect, callId: " + callId);

        destroyCall(callId);
        getAdapter().setDisconnected(callId, DisconnectCause.LOCAL, null);
    }

    /** {@inheritDoc} */
    @Override
    public void hold(String callId) {
        log("hold, callId: " + callId);
        getAdapter().setOnHold(callId);
    }

    /** {@inheritDoc} */
    @Override
    public void unhold(String callId) {
        log("unhold, callId: " + callId);
        getAdapter().setActive(callId);
    }

    /** {@inheritDoc} */
    @Override
    public void playDtmfTone(String callId, char digit) {
        log("playDtmfTone, callId: " + callId + " digit: " + digit);
    }

    /** {@inheritDoc} */
    @Override
    public void stopDtmfTone(String callId) {
        log("stopDtmfTone, callId: " + callId);
    }

    /** {@inheritDoc} */
    @Override
    public void onAudioStateChanged(String callId, CallAudioState audioState) {
        log("onAudioStateChanged, callId: " + callId + " audioState: " + audioState);
    }

    /** {@inheritDoc} */
    @Override
    public boolean onUnbind(Intent intent) {
        log("onUnbind");
        mMediaPlayer = null;
        return super.onUnbind(intent);
    }

    /** ${inheritDoc} */
    @Override
    public void addToConference(String conferenceCallId, List<String> callIds) {
    }

    /** ${inheritDoc} */
    @Override
    public void splitFromConference(String conferenceCallId, String callId) {
    }

    private void activateCall(String callId) {
        getAdapter().setActive(callId);
        if (mMediaPlayer != null && !mMediaPlayer.isPlaying()) {
            mMediaPlayer.start();
        }
    }

    /**
     * Removes the specified call ID from the set of live call IDs and stops playing audio if
     * there exist no more live calls.
     *
     * @param callId The identifier of the call to destroy.
     */
    private void destroyCall(String callId) {
        Preconditions.checkState(!Strings.isNullOrEmpty(callId));
        mCalls.remove(callId);

        // Stops audio if there are no more calls.
        if (mCalls.isEmpty() && mMediaPlayer.isPlaying()) {
            mMediaPlayer.stop();
            mMediaPlayer.release();
            mMediaPlayer = createMediaPlayer();
        }
    }

    private MediaPlayer createMediaPlayer() {
        // Prepare the media player to play a tone when there is a call.
        MediaPlayer mediaPlayer = MediaPlayer.create(getApplicationContext(), R.raw.beep_boop);
        mediaPlayer.setLooping(true);
        return mediaPlayer;
    }

    private static void log(String msg) {
        Log.w("testcallservice", "[TestCallService] " + msg);
    }
}
