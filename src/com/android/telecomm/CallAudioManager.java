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
import android.telecomm.CallAudioState;
import android.telecomm.CallState;

import com.google.common.base.Preconditions;

/**
 * This class manages audio modes, streams and other properties.
 */
final class CallAudioManager extends CallsManagerListenerBase
        implements WiredHeadsetManager.Listener {
    private static final int STREAM_NONE = -1;

    private final StatusBarNotifier mStatusBarNotifier;
    private final AudioManager mAudioManager;
    private final BluetoothManager mBluetoothManager;
    private final WiredHeadsetManager mWiredHeadsetManager;

    private CallAudioState mAudioState;
    private int mAudioFocusStreamType;
    private boolean mIsRinging;
    private boolean mIsTonePlaying;
    private boolean mWasSpeakerOn;

    CallAudioManager(Context context, StatusBarNotifier statusBarNotifier,
            WiredHeadsetManager wiredHeadsetManager) {
        mStatusBarNotifier = statusBarNotifier;
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mBluetoothManager = new BluetoothManager(context, this);
        mWiredHeadsetManager = wiredHeadsetManager;
        mWiredHeadsetManager.addListener(this);

        saveAudioState(getInitialAudioState(null));
        mAudioFocusStreamType = STREAM_NONE;
    }

    CallAudioState getAudioState() {
        return mAudioState;
    }

    @Override
    public void onCallAdded(Call call) {
        updateAudioStreamAndMode();
        if (CallsManager.getInstance().getCalls().size() == 1) {
            Log.v(this, "first call added, reseting system audio to default state");
            setInitialAudioState(call);
        } else if (!call.isIncoming()) {
            // Unmute new outgoing call.
            setSystemAudioState(false, mAudioState.route, mAudioState.supportedRouteMask);
        }
    }

    @Override
    public void onCallRemoved(Call call) {
        if (CallsManager.getInstance().getCalls().isEmpty()) {
            Log.v(this, "all calls removed, reseting system audio to default state");
            setInitialAudioState(null);
            mWasSpeakerOn = false;
        }
        updateAudioStreamAndMode();
    }

    @Override
    public void onCallStateChanged(Call call, CallState oldState, CallState newState) {
        updateAudioStreamAndMode();
    }

    @Override
    public void onIncomingCallAnswered(Call call) {
        int route = mAudioState.route;

        // We do two things:
        // (1) If this is the first call, then we can to turn on bluetooth if available.
        // (2) Unmute the audio for the new incoming call.
        boolean isOnlyCall = CallsManager.getInstance().getCalls().size() == 1;
        if (isOnlyCall && mBluetoothManager.isBluetoothAvailable()) {
            mBluetoothManager.connectBluetoothAudio();
            route = CallAudioState.ROUTE_BLUETOOTH;
        }

        setSystemAudioState(false /* isMute */, route, mAudioState.supportedRouteMask);
    }

    @Override
    public void onForegroundCallChanged(Call oldForegroundCall, Call newForegroundCall) {
        updateAudioStreamAndMode();
        // Ensure that the foreground call knows about the latest audio state.
        updateAudioForForegroundCall();
    }

    @Override
    public void onAudioModeIsVoipChanged(Call call) {
        updateAudioStreamAndMode();
    }

    /**
      * Updates the audio route when the headset plugged in state changes. For example, if audio is
      * being routed over speakerphone and a headset is plugged in then switch to wired headset.
      */
    @Override
    public void onWiredHeadsetPluggedInChanged(boolean oldIsPluggedIn, boolean newIsPluggedIn) {
        int newRoute = CallAudioState.ROUTE_EARPIECE;
        if (newIsPluggedIn) {
            newRoute = CallAudioState.ROUTE_WIRED_HEADSET;
        } else if (mWasSpeakerOn) {
            Call call = getForegroundCall();
            if (call != null && call.isAlive()) {
                // Restore the speaker state.
                newRoute = CallAudioState.ROUTE_SPEAKER;
            }
        }
        setSystemAudioState(mAudioState.isMuted, newRoute, calculateSupportedRoutes());
    }

    void toggleMute() {
        mute(!mAudioState.isMuted);
    }

    void mute(boolean shouldMute) {
        Log.v(this, "mute, shouldMute: %b", shouldMute);

        // Don't mute if there are any emergency calls.
        if (CallsManager.getInstance().hasEmergencyCall()) {
            shouldMute = false;
            Log.v(this, "ignoring mute for emergency call");
        }

        if (mAudioState.isMuted != shouldMute) {
            setSystemAudioState(shouldMute, mAudioState.route, mAudioState.supportedRouteMask);
        }
    }

    /**
     * Changed the audio route, for example from earpiece to speaker phone.
     *
     * @param route The new audio route to use. See {@link CallAudioState}.
     */
    void setAudioRoute(int route) {
        Log.v(this, "setAudioRoute, route: %s", CallAudioState.audioRouteToString(route));

        // Change ROUTE_WIRED_OR_EARPIECE to a single entry.
        int newRoute = selectWiredOrEarpiece(route, mAudioState.supportedRouteMask);

        // If route is unsupported, do nothing.
        if ((mAudioState.supportedRouteMask | newRoute) == 0) {
            Log.wtf(this, "Asking to set to a route that is unsupported: %d", newRoute);
            return;
        }

        if (mAudioState.route != newRoute) {
            // Remember the new speaker state so it can be restored when the user plugs and unplugs
            // a headset.
            mWasSpeakerOn = newRoute == CallAudioState.ROUTE_SPEAKER;
            setSystemAudioState(mAudioState.isMuted, newRoute, mAudioState.supportedRouteMask);
        }
    }

    void setIsRinging(boolean isRinging) {
        if (mIsRinging != isRinging) {
            Log.v(this, "setIsRinging %b -> %b", mIsRinging, isRinging);
            mIsRinging = isRinging;
            updateAudioStreamAndMode();
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
        ThreadUtil.checkOnMainThread();

        if (mIsTonePlaying != isPlayingNew) {
            Log.v(this, "mIsTonePlaying %b -> %b.", mIsTonePlaying, isPlayingNew);
            mIsTonePlaying = isPlayingNew;
            updateAudioStreamAndMode();
        }
    }

    /**
     * Updates the audio routing according to the bluetooth state.
     */
    void onBluetoothStateChange(BluetoothManager bluetoothManager) {
        int newRoute = mAudioState.route;
        if (bluetoothManager.isBluetoothAudioConnectedOrPending()) {
            newRoute = CallAudioState.ROUTE_BLUETOOTH;
        } else if (mAudioState.route == CallAudioState.ROUTE_BLUETOOTH) {
            newRoute = CallAudioState.ROUTE_WIRED_OR_EARPIECE;
            // Do not switch to speaker when bluetooth disconnects.
            mWasSpeakerOn = false;
        }

        setSystemAudioState(mAudioState.isMuted, newRoute, calculateSupportedRoutes());
    }

    boolean isBluetoothAudioOn() {
        return mBluetoothManager.isBluetoothAudioConnected();
    }

    boolean isBluetoothDeviceAvailable() {
        return mBluetoothManager.isBluetoothAvailable();
    }

    private void saveAudioState(CallAudioState audioState) {
        mAudioState = audioState;
        mStatusBarNotifier.notifyMute(mAudioState.isMuted);
        mStatusBarNotifier.notifySpeakerphone(mAudioState.route == CallAudioState.ROUTE_SPEAKER);
    }

    private void setSystemAudioState(boolean isMuted, int route, int supportedRouteMask) {
        CallAudioState oldAudioState = mAudioState;
        saveAudioState(new CallAudioState(isMuted, route, supportedRouteMask));
        Log.i(this, "changing audio state from %s to %s", oldAudioState, mAudioState);

        // Mute.
        if (mAudioState.isMuted != mAudioManager.isMicrophoneMute()) {
            Log.i(this, "changing microphone mute state to: %b", mAudioState.isMuted);
            mAudioManager.setMicrophoneMute(mAudioState.isMuted);
        }

        // Audio route.
        if (mAudioState.route == CallAudioState.ROUTE_BLUETOOTH) {
            turnOnSpeaker(false);
            turnOnBluetooth(true);
        } else if (mAudioState.route == CallAudioState.ROUTE_SPEAKER) {
            turnOnBluetooth(false);
            turnOnSpeaker(true);
        } else if (mAudioState.route == CallAudioState.ROUTE_EARPIECE ||
                mAudioState.route == CallAudioState.ROUTE_WIRED_HEADSET) {
            turnOnBluetooth(false);
            turnOnSpeaker(false);
        }

        if (!oldAudioState.equals(mAudioState)) {
            CallsManager.getInstance().onAudioStateChanged(oldAudioState, mAudioState);
            updateAudioForForegroundCall();
        }
    }

    private void turnOnSpeaker(boolean on) {
        // Wired headset and earpiece work the same way
        if (mAudioManager.isSpeakerphoneOn() != on) {
            Log.i(this, "turning speaker phone off");
            mAudioManager.setSpeakerphoneOn(on);
        }
    }

    private void turnOnBluetooth(boolean on) {
        if (mBluetoothManager.isBluetoothAvailable()) {
            boolean isAlreadyOn = mBluetoothManager.isBluetoothAudioConnected();
            if (on != isAlreadyOn) {
                if (on) {
                    mBluetoothManager.connectBluetoothAudio();
                } else {
                    mBluetoothManager.disconnectBluetoothAudio();
                }
            }
        }
    }

    private void updateAudioStreamAndMode() {
        Log.v(this, "updateAudioStreamAndMode, mIsRinging: %b, mIsTonePlaying: %b", mIsRinging,
                mIsTonePlaying);
        if (mIsRinging) {
            requestAudioFocusAndSetMode(AudioManager.STREAM_RING, AudioManager.MODE_RINGTONE);
        } else {
            Call call = getForegroundCall();
            if (call != null) {
                int mode = call.getAudioModeIsVoip() ?
                        AudioManager.MODE_IN_COMMUNICATION : AudioManager.MODE_IN_CALL;
                requestAudioFocusAndSetMode(AudioManager.STREAM_VOICE_CALL, mode);
            } else if (mIsTonePlaying) {
                // There is no call, however, we are still playing a tone, so keep focus.
                requestAudioFocusAndSetMode(
                        AudioManager.STREAM_VOICE_CALL, AudioManager.MODE_IN_COMMUNICATION);
            } else {
                abandonAudioFocus();
            }
        }
    }

    private void requestAudioFocusAndSetMode(int stream, int mode) {
        Log.v(this, "setSystemAudioStreamAndMode, stream: %d -> %d", mAudioFocusStreamType, stream);
        Preconditions.checkState(stream != STREAM_NONE);

        // Even if we already have focus, if the stream is different we update audio manager to give
        // it a hint about the purpose of our focus.
        if (mAudioFocusStreamType != stream) {
            Log.v(this, "requesting audio focus for stream: %d", stream);
            mAudioManager.requestAudioFocusForCall(stream,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        }
        mAudioFocusStreamType = stream;
        setMode(mode);
    }

    private void abandonAudioFocus() {
        if (mAudioFocusStreamType != STREAM_NONE) {
            setMode(AudioManager.MODE_NORMAL);
            Log.v(this, "abandoning audio focus");
            mAudioManager.abandonAudioFocusForCall();
            mAudioFocusStreamType = STREAM_NONE;
        }
    }

    /**
     * Sets the audio mode.
     *
     * @param newMode Mode constant from AudioManager.MODE_*.
     */
    private void setMode(int newMode) {
        Preconditions.checkState(mAudioFocusStreamType != STREAM_NONE);
        int oldMode = mAudioManager.getMode();
        Log.v(this, "Request to change audio mode from %d to %d", oldMode, newMode);
        if (oldMode != newMode) {
            mAudioManager.setMode(newMode);
        }
    }

    private int selectWiredOrEarpiece(int route, int supportedRouteMask) {
        // Since they are mutually exclusive and one is ALWAYS valid, we allow a special input of
        // ROUTE_WIRED_OR_EARPIECE so that callers dont have to make a call to check which is
        // supported before calling setAudioRoute.
        if (route == CallAudioState.ROUTE_WIRED_OR_EARPIECE) {
            route = CallAudioState.ROUTE_WIRED_OR_EARPIECE & supportedRouteMask;
            if (route == 0) {
                Log.wtf(this, "One of wired headset or earpiece should always be valid.");
                // assume earpiece in this case.
                route = CallAudioState.ROUTE_EARPIECE;
            }
        }
        return route;
    }

    private int calculateSupportedRoutes() {
        int routeMask = CallAudioState.ROUTE_SPEAKER;

        if (mWiredHeadsetManager.isPluggedIn()) {
            routeMask |= CallAudioState.ROUTE_WIRED_HEADSET;
        } else {
            routeMask |= CallAudioState.ROUTE_EARPIECE;
        }

        if (mBluetoothManager.isBluetoothAvailable()) {
            routeMask |=  CallAudioState.ROUTE_BLUETOOTH;
        }

        return routeMask;
    }

    private CallAudioState getInitialAudioState(Call call) {
        int supportedRouteMask = calculateSupportedRoutes();
        int route = selectWiredOrEarpiece(
                CallAudioState.ROUTE_WIRED_OR_EARPIECE, supportedRouteMask);

        // We want the UI to indicate that "bluetooth is in use" in two slightly different cases:
        // (a) The obvious case: if a bluetooth headset is currently in use for an ongoing call.
        // (b) The not-so-obvious case: if an incoming call is ringing, and we expect that audio
        //     *will* be routed to a bluetooth headset once the call is answered. In this case, just
        //     check if the headset is available. Note this only applies when we are dealing with
        //     the first call.
        if (call != null && mBluetoothManager.isBluetoothAvailable()) {
            switch(call.getState()) {
                case ACTIVE:
                case ON_HOLD:
                    if (mBluetoothManager.isBluetoothAudioConnectedOrPending()) {
                        route = CallAudioState.ROUTE_BLUETOOTH;
                    }
                    break;
                case RINGING:
                    route = CallAudioState.ROUTE_BLUETOOTH;
                    break;
                default:
                    break;
            }
        }

        return new CallAudioState(false, route, supportedRouteMask);
    }

    private void setInitialAudioState(Call call) {
        CallAudioState audioState = getInitialAudioState(call);
        setSystemAudioState(audioState.isMuted, audioState.route, audioState.supportedRouteMask);
    }

    private void updateAudioForForegroundCall() {
        Call call = CallsManager.getInstance().getForegroundCall();
        if (call != null && call.getConnectionService() != null) {
            call.getConnectionService().onAudioStateChanged(call, mAudioState);
        }
    }

    /**
     * Returns the current foreground call in order to properly set the audio mode.
     */
    private Call getForegroundCall() {
        Call call = CallsManager.getInstance().getForegroundCall();

        // We ignore any foreground call that is in the ringing state because we deal with ringing
        // calls exclusively through the mIsRinging variable set by {@link Ringer}.
        if (call != null && call.getState() == CallState.RINGING) {
            call = null;
        }
        return call;
    }
}
