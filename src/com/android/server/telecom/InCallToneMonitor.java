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

package com.android.server.telecom;

import android.media.ToneGenerator;
import android.telecom.Connection;
import android.telecom.VideoProfile;

/**
 * Monitors events from CallsManager and plays in-call tones for events which require them, such as
 * different type of call disconnections (busy tone, congestion tone, etc).
 */
public final class InCallToneMonitor extends CallsManagerListenerBase {
    private final InCallTonePlayer.Factory mPlayerFactory;
    private InCallTonePlayer mHoldTonePlayer = null;

    private final CallsManager mCallsManager;

    InCallToneMonitor(InCallTonePlayer.Factory playerFactory, CallsManager callsManager) {
        mPlayerFactory = playerFactory;
        mCallsManager = callsManager;
    }

    @Override
    public void onCallStateChanged(Call call, int oldState, int newState) {
        if (mCallsManager.getForegroundCall() != call) {
            // We only play tones for foreground calls.
            return;
        }

        if (newState == CallState.DISCONNECTED && call.getDisconnectCause() != null) {
            int toneToPlay = InCallTonePlayer.TONE_INVALID;

            Log.v(this, "Disconnect cause: %s.", call.getDisconnectCause());

            switch(call.getDisconnectCause().getTone()) {
                case ToneGenerator.TONE_SUP_BUSY:
                    toneToPlay = InCallTonePlayer.TONE_BUSY;
                    break;
                case ToneGenerator.TONE_SUP_CONGESTION:
                    toneToPlay = InCallTonePlayer.TONE_CONGESTION;
                    break;
                case ToneGenerator.TONE_CDMA_REORDER:
                    toneToPlay = InCallTonePlayer.TONE_REORDER;
                    break;
                case ToneGenerator.TONE_CDMA_ABBR_INTERCEPT:
                    toneToPlay = InCallTonePlayer.TONE_INTERCEPT;
                    break;
                case ToneGenerator.TONE_CDMA_CALLDROP_LITE:
                    toneToPlay = InCallTonePlayer.TONE_CDMA_DROP;
                    break;
                case ToneGenerator.TONE_SUP_ERROR:
                    toneToPlay = InCallTonePlayer.TONE_UNOBTAINABLE_NUMBER;
                    break;
                case ToneGenerator.TONE_PROP_PROMPT:
                    toneToPlay = InCallTonePlayer.TONE_CALL_ENDED;
                    break;
            }

            Log.d(this, "Found a disconnected call with tone to play %d.", toneToPlay);

            if (toneToPlay != InCallTonePlayer.TONE_INVALID) {
                mPlayerFactory.createPlayer(toneToPlay).startTone();
            }
        } else {
            // If foreground call changes state, we may need to start or stop the hold tone.
            maybePlayHoldTone();
        }
    }

    /**
     * Handles requests received via the {@link VideoProviderProxy} requesting a change in the video
     * state of the call by the peer.  If the request involves the peer turning their camera on,
     * the call waiting tone is played to inform the user of the incoming request.
     *
     * @param call The call.
     * @param videoProfile The requested video profile.
     */
    @Override
    public void onSessionModifyRequestReceived(Call call, VideoProfile videoProfile) {
        if (videoProfile == null) {
            return;
        }

        if (mCallsManager.getForegroundCall() != call) {
            // We only play tones for foreground calls.
            return;
        }

        int previousVideoState = call.getVideoState();
        int newVideoState = videoProfile.getVideoState();
        Log.v(this, "onSessionModifyRequestReceived : videoProfile = " + VideoProfile
                .videoStateToString(newVideoState));

        boolean isUpgradeRequest = !VideoProfile.isReceptionEnabled(previousVideoState) &&
                VideoProfile.isReceptionEnabled(newVideoState);

        if (isUpgradeRequest) {
            mPlayerFactory.createPlayer(InCallTonePlayer.TONE_VIDEO_UPGRADE).startTone();
        }
    }

    /**
     * Play or stop a call hold tone for a call.  Triggered via
     * {@link Connection#sendConnectionEvent(String)} when the
     * {@link Connection#EVENT_ON_HOLD_TONE_START} event or
     * {@link Connection#EVENT_ON_HOLD_TONE_STOP} event is passed through to the
     *
     * @param call The call which requested the hold tone.
     */
    @Override
    public void onHoldToneRequested(Call call) {
        maybePlayHoldTone();
    }

    @Override
    public void onCallAdded(Call call) {
        maybePlayHoldTone();
    }

    @Override
    public void onCallRemoved(Call call) {
        maybePlayHoldTone();
    }

    @Override
    public void onForegroundCallChanged(Call oldForegroundCall, Call newForegroundCall) {
        maybePlayHoldTone();
    }

    /**
     * Determines if a hold tone should be played and then starts or stops it accordingly.
     */
    private void maybePlayHoldTone() {
        if (shouldPlayHoldTone()) {
            if (mHoldTonePlayer == null) {
                mHoldTonePlayer = mPlayerFactory.createPlayer(InCallTonePlayer.TONE_CALL_WAITING);
                mHoldTonePlayer.start();
            }
        } else {
            if (mHoldTonePlayer != null) {
                mHoldTonePlayer.stopTone();
                mHoldTonePlayer = null;
            }
        }
    }

    /**
     * Determines if a hold tone should be played.
     * A hold tone should be played only if foreground call is equals with call which is
     * remotely held.
     *
     * @return {@code true} if the the hold tone should be played, {@code false} otherwise.
     */
    private boolean shouldPlayHoldTone() {
        Call foregroundCall = mCallsManager.getForegroundCall();
        // If there is no foreground call, no hold tone should play.
        if (foregroundCall == null) {
            // Clean up mPlayHoldToneId
            return false;
        }

        // If another call is ringing, no hold tone should play.
        if (mCallsManager.hasRingingCall()) {
            return false;
        }

        // If the foreground call isn't active, no hold tone should play. This might happen, for
        // example, if the user puts a remotely held call on hold itself.
        if (!foregroundCall.isActive()) {
            return false;
        }

        return foregroundCall.isRemotelyHeld();
    }
}
