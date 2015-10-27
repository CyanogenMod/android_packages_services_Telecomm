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

package com.android.server.telecom;

import android.content.Context;
import android.media.AudioManager;
import android.media.IAudioService;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.telecom.CallAudioState;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;

/**
 * This class manages audio modes, streams and other properties.
 */
@VisibleForTesting
public class CallAudioManager extends CallsManagerListenerBase {
    private static final int STREAM_NONE = -1;

    private static final String STREAM_DESCRIPTION_NONE = "STEAM_NONE";
    private static final String STREAM_DESCRIPTION_ALARM = "STEAM_ALARM";
    private static final String STREAM_DESCRIPTION_BLUETOOTH_SCO = "STREAM_BLUETOOTH_SCO";
    private static final String STREAM_DESCRIPTION_DTMF = "STREAM_DTMF";
    private static final String STREAM_DESCRIPTION_MUSIC = "STREAM_MUSIC";
    private static final String STREAM_DESCRIPTION_NOTIFICATION = "STREAM_NOTIFICATION";
    private static final String STREAM_DESCRIPTION_RING = "STREAM_RING";
    private static final String STREAM_DESCRIPTION_SYSTEM = "STREAM_SYSTEM";
    private static final String STREAM_DESCRIPTION_VOICE_CALL = "STREAM_VOICE_CALL";

    private static final String MODE_DESCRIPTION_INVALID = "MODE_INVALID";
    private static final String MODE_DESCRIPTION_CURRENT = "MODE_CURRENT";
    private static final String MODE_DESCRIPTION_NORMAL = "MODE_NORMAL";
    private static final String MODE_DESCRIPTION_RINGTONE = "MODE_RINGTONE";
    private static final String MODE_DESCRIPTION_IN_CALL = "MODE_IN_CALL";
    private static final String MODE_DESCRIPTION_IN_COMMUNICATION = "MODE_IN_COMMUNICATION";

    private static final int MSG_AUDIO_MANAGER_INITIALIZE = 0;
    private static final int MSG_AUDIO_MANAGER_ABANDON_AUDIO_FOCUS_FOR_CALL = 2;
    private static final int MSG_AUDIO_MANAGER_REQUEST_AUDIO_FOCUS_FOR_CALL = 4;
    private static final int MSG_AUDIO_MANAGER_SET_MODE = 5;

    private final Handler mAudioManagerHandler = new Handler(Looper.getMainLooper()) {

        private AudioManager mAudioManager;

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_AUDIO_MANAGER_INITIALIZE: {
                    mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
                    break;
                }
                case MSG_AUDIO_MANAGER_ABANDON_AUDIO_FOCUS_FOR_CALL: {
                    mAudioManager.abandonAudioFocusForCall();
                    break;
                }
                case MSG_AUDIO_MANAGER_REQUEST_AUDIO_FOCUS_FOR_CALL: {
                    int stream = msg.arg1;
                    mAudioManager.requestAudioFocusForCall(
                            stream,
                            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
                    break;
                }
                case MSG_AUDIO_MANAGER_SET_MODE: {
                    int newMode = msg.arg1;
                    int oldMode = mAudioManager.getMode();
                    Log.v(this, "Request to change audio mode from %s to %s", modeToString(oldMode),
                            modeToString(newMode));

                    if (oldMode != newMode) {
                        if (oldMode == AudioManager.MODE_IN_CALL &&
                                newMode == AudioManager.MODE_RINGTONE) {
                            Log.i(this, "Transition from IN_CALL -> RINGTONE."
                                    + "  Resetting to NORMAL first.");
                            mAudioManager.setMode(AudioManager.MODE_NORMAL);
                        }
                        mAudioManager.setMode(newMode);
                        synchronized (mLock) {
                            mMostRecentlyUsedMode = newMode;
                        }
                    }
                    break;
                }
                default:
                    break;
            }
        }
    };

    public interface AudioServiceFactory {
        IAudioService getAudioService();
    }

    private final Context mContext;
    private final TelecomSystem.SyncRoot mLock;
    private final CallsManager mCallsManager;
    private final CallAudioRouteStateMachine mCallAudioRouteStateMachine;

    private int mAudioFocusStreamType;
    private boolean mIsRinging;
    private boolean mIsTonePlaying;
    private int mMostRecentlyUsedMode = AudioManager.MODE_IN_CALL;
    private Call mCallToSpeedUpMTAudio = null;

    public CallAudioManager(
            Context context,
            TelecomSystem.SyncRoot lock,
            CallsManager callsManager,
            CallAudioRouteStateMachine callAudioRouteStateMachine) {
        mContext = context;
        mLock = lock;
        mAudioManagerHandler.obtainMessage(MSG_AUDIO_MANAGER_INITIALIZE, 0, 0).sendToTarget();
        mCallsManager = callsManager;
        mAudioFocusStreamType = STREAM_NONE;

        mCallAudioRouteStateMachine = callAudioRouteStateMachine;
    }

    @VisibleForTesting
    public CallAudioState getCallAudioState() {
        return mCallAudioRouteStateMachine.getCurrentCallAudioState();
    }

    @Override
    public void onCallAdded(Call call) {
        Log.v(this, "onCallAdded");
        onCallUpdated(call);

        if (hasFocus() && getForegroundCall() == call) {
            if (!call.isIncoming()) {
                // Unmute new outgoing call.
                mCallAudioRouteStateMachine.sendMessage(CallAudioRouteStateMachine.MUTE_OFF);
            }
        }
    }

    @Override
    public void onCallRemoved(Call call) {
        Log.v(this, "onCallRemoved");
        if (mCallsManager.getCalls().isEmpty()) {
            Log.v(this, "all calls removed, resetting system audio to default state");
            mCallAudioRouteStateMachine.sendMessage(CallAudioRouteStateMachine.REINITIALIZE);
        }

        // If we didn't already have focus, there's nothing to do.
        if (hasFocus()) {
            updateAudioStreamAndMode(call);
        }
    }

    @Override
    public void onCallStateChanged(Call call, int oldState, int newState) {
        Log.v(this, "onCallStateChanged : oldState = %d, newState = %d", oldState, newState);
        onCallUpdated(call);
    }

    @Override
    public void onIncomingCallAnswered(Call call) {
        Log.v(this, "onIncomingCallAnswered");

        if (mCallsManager.getCalls().size() == 1) {
            mCallAudioRouteStateMachine.sendMessage(CallAudioRouteStateMachine.SWITCH_FOCUS,
                    CallAudioRouteStateMachine.HAS_FOCUS);
        }

        if (call.can(android.telecom.Call.Details.CAPABILITY_SPEED_UP_MT_AUDIO)) {
            Log.v(this, "Speed up audio setup for IMS MT call.");
            mCallToSpeedUpMTAudio = call;
            updateAudioStreamAndMode(call);
        }
    }

    @Override
    public void onForegroundCallChanged(Call oldForegroundCall, Call newForegroundCall) {
        onCallUpdated(newForegroundCall);
        // Ensure that the foreground call knows about the latest audio state.
        updateAudioForForegroundCall();
    }

    @Override
    public void onIsVoipAudioModeChanged(Call call) {
        updateAudioStreamAndMode(call);
    }

    void toggleMute() {
        mCallAudioRouteStateMachine.sendMessage(CallAudioRouteStateMachine.TOGGLE_MUTE);
    }

    void mute(boolean shouldMute) {
        if (!hasFocus()) {
            return;
        }

        Log.v(this, "mute, shouldMute: %b", shouldMute);

        // Don't mute if there are any emergency calls.
        if (mCallsManager.hasEmergencyCall()) {
            shouldMute = false;
            Log.v(this, "ignoring mute for emergency call");
        }

        mCallAudioRouteStateMachine.sendMessage(shouldMute
                ? CallAudioRouteStateMachine.MUTE_ON : CallAudioRouteStateMachine.MUTE_OFF);
    }

    /**
     * Changed the audio route, for example from earpiece to speaker phone.
     *
     * @param route The new audio route to use. See {@link CallAudioState}.
     */
    void setAudioRoute(int route) {
        Log.v(this, "setAudioRoute, route: %s", CallAudioState.audioRouteToString(route));
        switch (route) {
            case CallAudioState.ROUTE_BLUETOOTH:
                mCallAudioRouteStateMachine.sendMessage(
                        CallAudioRouteStateMachine.SWITCH_BLUETOOTH);
                return;
            case CallAudioState.ROUTE_SPEAKER:
                mCallAudioRouteStateMachine.sendMessage(
                        CallAudioRouteStateMachine.SWITCH_SPEAKER);
                return;
            case CallAudioState.ROUTE_WIRED_HEADSET:
                mCallAudioRouteStateMachine.sendMessage(
                        CallAudioRouteStateMachine.SWITCH_HEADSET);
                return;
            case CallAudioState.ROUTE_EARPIECE:
                mCallAudioRouteStateMachine.sendMessage(
                        CallAudioRouteStateMachine.SWITCH_EARPIECE);
                return;
            case CallAudioState.ROUTE_WIRED_OR_EARPIECE:
                mCallAudioRouteStateMachine.sendMessage(
                        CallAudioRouteStateMachine.SWITCH_WIRED_OR_EARPIECE);
                return;
            default:
                Log.wtf(this, "Invalid route specified: %d", route);
        }
    }

    /**
     * Sets the audio stream and mode based on whether a call is ringing.
     *
     * @param call The call which changed ringing state.
     * @param isRinging {@code true} if the call is ringing, {@code false} otherwise.
     */
    @VisibleForTesting
    public void setIsRinging(Call call, boolean isRinging) {
        if (mIsRinging != isRinging) {
            Log.i(this, "setIsRinging %b -> %b (call = %s)", mIsRinging, isRinging, call);
            mIsRinging = isRinging;
            updateAudioStreamAndMode(call);
        }
    }

    /**
     * Sets the tone playing status. Some tones can play even when there are no live calls and this
     * status indicates that we should keep audio focus even for tones that play beyond the life of
     * calls.
     *
     * @param isPlayingNew The status to set.
     */
    void setIsTonePlaying(boolean isPlayingNew) {
        if (mIsTonePlaying != isPlayingNew) {
            Log.v(this, "mIsTonePlaying %b -> %b.", mIsTonePlaying, isPlayingNew);
            mIsTonePlaying = isPlayingNew;
            updateAudioStreamAndMode();
        }
    }

    private void onCallUpdated(Call call) {
        updateAudioStreamAndMode(call);
        if (call != null && call.getState() == CallState.ACTIVE &&
                            call == mCallToSpeedUpMTAudio) {
            mCallToSpeedUpMTAudio = null;
        }
    }

    private void updateAudioStreamAndMode() {
        updateAudioStreamAndMode(null /* call */);
    }

    private void updateAudioStreamAndMode(Call callToUpdate) {
        Log.i(this, "updateAudioStreamAndMode :  mIsRinging: %b, mIsTonePlaying: %b, call: %s",
                mIsRinging, mIsTonePlaying, callToUpdate);

        if (mIsRinging) {
            Log.i(this, "updateAudioStreamAndMode : ringing");
            requestAudioFocusAndSetMode(AudioManager.STREAM_RING, AudioManager.MODE_RINGTONE);
        } else {
            Call foregroundCall = getForegroundCall();
            Call waitingForAccountSelectionCall = mCallsManager
                    .getFirstCallWithState(CallState.SELECT_PHONE_ACCOUNT);
            Call call = mCallsManager.getForegroundCall();
            if (foregroundCall == null && call != null && call == mCallToSpeedUpMTAudio) {
                Log.v(this, "updateAudioStreamAndMode : no foreground, speeding up MT audio.");
                requestAudioFocusAndSetMode(AudioManager.STREAM_VOICE_CALL,
                                                         AudioManager.MODE_IN_CALL);
            } else if (foregroundCall != null && !foregroundCall.isDisconnected() &&
                    waitingForAccountSelectionCall == null) {
                // In the case where there is a call that is waiting for account selection,
                // this will fall back to abandonAudioFocus() below, which temporarily exits
                // the in-call audio mode. This is to allow TalkBack to speak the "Call with"
                // dialog information at media volume as opposed to through the earpiece.
                // Once exiting the "Call with" dialog, the audio focus will return to an in-call
                // audio mode when this method (updateAudioStreamAndMode) is called again.
                int mode = foregroundCall.getIsVoipAudioMode() ?
                        AudioManager.MODE_IN_COMMUNICATION : AudioManager.MODE_IN_CALL;
                Log.v(this, "updateAudioStreamAndMode : foreground");
                requestAudioFocusAndSetMode(AudioManager.STREAM_VOICE_CALL, mode);
            } else if (mIsTonePlaying) {
                // There is no call, however, we are still playing a tone, so keep focus.
                // Since there is no call from which to determine the mode, use the most
                // recently used mode instead.
                Log.v(this, "updateAudioStreamAndMode : tone playing");
                requestAudioFocusAndSetMode(
                        AudioManager.STREAM_VOICE_CALL, mMostRecentlyUsedMode);
            } else if (!hasRingingForegroundCall() && mCallsManager.hasOnlyDisconnectedCalls()) {
                Log.v(this, "updateAudioStreamAndMode : no ringing call");
                abandonAudioFocus();
            } else {
                // mIsRinging is false, but there is a foreground ringing call present. Don't
                // abandon audio focus immediately to prevent audio focus from getting lost between
                // the time it takes for the foreground call to transition from RINGING to ACTIVE/
                // DISCONNECTED. When the call eventually transitions to the next state, audio
                // focus will be correctly abandoned by the if clause above.
            }
        }
    }

    private void requestAudioFocusAndSetMode(int stream, int mode) {
        Log.v(this, "requestAudioFocusAndSetMode : stream: %s -> %s, mode: %s",
                streamTypeToString(mAudioFocusStreamType), streamTypeToString(stream),
                modeToString(mode));
        Preconditions.checkState(stream != STREAM_NONE);

        // Even if we already have focus, if the stream is different we update audio manager to give
        // it a hint about the purpose of our focus.
        if (mAudioFocusStreamType != stream) {
            Log.i(this, "requestAudioFocusAndSetMode : requesting stream: %s -> %s",
                    streamTypeToString(mAudioFocusStreamType), streamTypeToString(stream));
            mAudioManagerHandler.obtainMessage(
                    MSG_AUDIO_MANAGER_REQUEST_AUDIO_FOCUS_FOR_CALL,
                    stream,
                    0)
                    .sendToTarget();
        }
        mAudioFocusStreamType = stream;
        mCallAudioRouteStateMachine.sendMessage(CallAudioRouteStateMachine.SWITCH_FOCUS,
                CallAudioRouteStateMachine.HAS_FOCUS);

        setMode(mode);
    }

    private void abandonAudioFocus() {
        if (hasFocus()) {
            setMode(AudioManager.MODE_NORMAL);
            Log.v(this, "abandoning audio focus");
            mAudioManagerHandler.obtainMessage(MSG_AUDIO_MANAGER_ABANDON_AUDIO_FOCUS_FOR_CALL, 0, 0)
                    .sendToTarget();
            mAudioFocusStreamType = STREAM_NONE;
            mCallToSpeedUpMTAudio = null;
        }
        mCallAudioRouteStateMachine.sendMessage(CallAudioRouteStateMachine.SWITCH_FOCUS,
                CallAudioRouteStateMachine.NO_FOCUS);
    }

    /**
     * Sets the audio mode.
     *
     * @param newMode Mode constant from AudioManager.MODE_*.
     */
    private void setMode(int newMode) {
        Preconditions.checkState(hasFocus());
        mAudioManagerHandler.obtainMessage(MSG_AUDIO_MANAGER_SET_MODE, newMode, 0).sendToTarget();
    }

    private void updateAudioForForegroundCall() {
        Call call = mCallsManager.getForegroundCall();
        if (call != null && call.getConnectionService() != null) {
            call.getConnectionService().onCallAudioStateChanged(call,
                    mCallAudioRouteStateMachine.getCurrentCallAudioState());
        }
    }

    /**
     * Returns the current foreground call in order to properly set the audio mode.
     */
    private Call getForegroundCall() {
        Call call = mCallsManager.getForegroundCall();

        // We ignore any foreground call that is in the ringing state because we deal with ringing
        // calls exclusively through the mIsRinging variable set by {@link Ringer}.
        if (call != null && call.getState() == CallState.RINGING) {
            return null;
        }

        return call;
    }

    private boolean hasRingingForegroundCall() {
        Call call = mCallsManager.getForegroundCall();
        return call != null && call.getState() == CallState.RINGING;
    }

    private boolean hasFocus() {
        return mAudioFocusStreamType != STREAM_NONE;
    }

    /**
     * Translates an {@link AudioManager} stream type to a human-readable string description.
     *
     * @param streamType The stream type.
     * @return Human readable description.
     */
    private String streamTypeToString(int streamType) {
        switch (streamType) {
            case STREAM_NONE:
                return STREAM_DESCRIPTION_NONE;
            case AudioManager.STREAM_ALARM:
                return STREAM_DESCRIPTION_ALARM;
            case AudioManager.STREAM_BLUETOOTH_SCO:
                return STREAM_DESCRIPTION_BLUETOOTH_SCO;
            case AudioManager.STREAM_DTMF:
                return STREAM_DESCRIPTION_DTMF;
            case AudioManager.STREAM_MUSIC:
                return STREAM_DESCRIPTION_MUSIC;
            case AudioManager.STREAM_NOTIFICATION:
                return STREAM_DESCRIPTION_NOTIFICATION;
            case AudioManager.STREAM_RING:
                return STREAM_DESCRIPTION_RING;
            case AudioManager.STREAM_SYSTEM:
                return STREAM_DESCRIPTION_SYSTEM;
            case AudioManager.STREAM_VOICE_CALL:
                return STREAM_DESCRIPTION_VOICE_CALL;
            default:
                return "STEAM_OTHER_" + streamType;
        }
    }

    /**
     * Translates an {@link AudioManager} mode into a human readable string.
     *
     * @param mode The mode.
     * @return The string.
     */
    private String modeToString(int mode) {
        switch (mode) {
            case AudioManager.MODE_INVALID:
                return MODE_DESCRIPTION_INVALID;
            case AudioManager.MODE_CURRENT:
                return MODE_DESCRIPTION_CURRENT;
            case AudioManager.MODE_NORMAL:
                return MODE_DESCRIPTION_NORMAL;
            case AudioManager.MODE_RINGTONE:
                return MODE_DESCRIPTION_RINGTONE;
            case AudioManager.MODE_IN_CALL:
                return MODE_DESCRIPTION_IN_CALL;
            case AudioManager.MODE_IN_COMMUNICATION:
                return MODE_DESCRIPTION_IN_COMMUNICATION;
            default:
                return "MODE_OTHER_" + mode;
        }
    }

    /**
     * Dumps the state of the {@link CallAudioManager}.
     *
     * @param pw The {@code IndentingPrintWriter} to write the state to.
     */
    public void dump(IndentingPrintWriter pw) {
        pw.println("mAudioState: " + mCallAudioRouteStateMachine.getCurrentCallAudioState());
        pw.println("mAudioFocusStreamType: " + streamTypeToString(mAudioFocusStreamType));
        pw.println("mIsRinging: " + mIsRinging);
        pw.println("mIsTonePlaying: " + mIsTonePlaying);
        pw.println("mMostRecentlyUsedMode: " + modeToString(mMostRecentlyUsedMode));
    }
}