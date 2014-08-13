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

import android.telecomm.CallState;
import android.telephony.DisconnectCause;

import java.util.Collection;

/**
 * Monitors events from CallsManager and plays in-call tones for events which require them, such as
 * different type of call disconnections (busy tone, congestion tone, etc).
 */
public final class InCallToneMonitor extends CallsManagerListenerBase {
    private final InCallTonePlayer.Factory mPlayerFactory;

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

        if (newState == CallState.DISCONNECTED) {
            int toneToPlay = InCallTonePlayer.TONE_INVALID;

            Log.v(this, "Disconnect cause: %d.", call.getDisconnectCause());

            switch(call.getDisconnectCause()) {
                case DisconnectCause.BUSY:
                    toneToPlay = InCallTonePlayer.TONE_BUSY;
                    break;
                case DisconnectCause.CONGESTION:
                    toneToPlay = InCallTonePlayer.TONE_CONGESTION;
                    break;
                case DisconnectCause.CDMA_REORDER:
                    toneToPlay = InCallTonePlayer.TONE_REORDER;
                    break;
                case DisconnectCause.CDMA_INTERCEPT:
                    toneToPlay = InCallTonePlayer.TONE_INTERCEPT;
                    break;
                case DisconnectCause.CDMA_DROP:
                    toneToPlay = InCallTonePlayer.TONE_CDMA_DROP;
                    break;
                case DisconnectCause.OUT_OF_SERVICE:
                    toneToPlay = InCallTonePlayer.TONE_OUT_OF_SERVICE;
                    break;
                case DisconnectCause.UNOBTAINABLE_NUMBER:
                    toneToPlay = InCallTonePlayer.TONE_UNOBTAINABLE_NUMBER;
                    break;
                case DisconnectCause.ERROR_UNSPECIFIED:
                    toneToPlay = InCallTonePlayer.TONE_CALL_ENDED;
                    break;
                case DisconnectCause.NORMAL:
                case DisconnectCause.LOCAL:
                    // Only play the disconnect sound on normal disconnects if there are no other
                    // calls present beyond the one that is currently disconnected.
                    Collection<Call> allCalls = mCallsManager.getCalls();
                    if (allCalls.size() == 1) {
                        if (!allCalls.contains(call)) {
                            Log.wtf(this, "Disconnecting call not found %s.", call);
                        }
                        toneToPlay = InCallTonePlayer.TONE_CALL_ENDED;
                    }
                    break;
            }

            Log.d(this, "Found a disconnected call with tone to play %d.", toneToPlay);

            if (toneToPlay != InCallTonePlayer.TONE_INVALID) {
                mPlayerFactory.createPlayer(toneToPlay).startTone();
            }
        }
    }
}
