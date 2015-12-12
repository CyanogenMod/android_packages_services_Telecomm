/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.telecom;


import android.app.ActivityManagerNative;
import android.content.Context;
import android.content.pm.UserInfo;
import android.media.AudioManager;
import android.media.IAudioService;
import android.os.Binder;
import android.os.Message;
import android.os.RemoteException;
import android.os.UserHandle;
import android.telecom.CallAudioState;

import com.android.internal.util.IState;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import java.util.HashMap;

/**
 * This class describes the available routes of a call as a state machine.
 * Transitions are caused solely by the commands sent as messages. Possible values for msg.what
 * are defined as event constants in this file.
 *
 * The eight states are all instances of the abstract base class, {@link AudioState}. Each state
 * is a combination of one of the four audio routes (earpiece, wired headset, bluetooth, and
 * speakerphone) and audio focus status (active or quiescent).
 *
 * Messages are processed first by the processMessage method in the base class, AudioState.
 * Any messages not completely handled by AudioState are further processed by the same method in
 * the route-specific abstract classes: {@link EarpieceRoute}, {@link HeadsetRoute},
 * {@link BluetoothRoute}, and {@link SpeakerRoute}. Finally, messages that are not handled at
 * this level are then processed by the classes corresponding to the state instances themselves.
 *
 * There are several variables carrying additional state. These include:
 * mAvailableRoutes: A bitmask describing which audio routes are available
 * mWasOnSpeaker: A boolean indicating whether we should switch to speakerphone after disconnecting
 *     from a wired headset
 * mIsMuted: a boolean indicating whether the audio is muted
 */
public class CallAudioRouteStateMachine extends StateMachine {
    /** Direct the audio stream through the device's earpiece. */
    public static final int ROUTE_EARPIECE      = CallAudioState.ROUTE_EARPIECE;

    /** Direct the audio stream through Bluetooth. */
    public static final int ROUTE_BLUETOOTH     = CallAudioState.ROUTE_BLUETOOTH;

    /** Direct the audio stream through a wired headset. */
    public static final int ROUTE_WIRED_HEADSET = CallAudioState.ROUTE_WIRED_HEADSET;

    /** Direct the audio stream through the device's speakerphone. */
    public static final int ROUTE_SPEAKER       = CallAudioState.ROUTE_SPEAKER;

    /** Valid values for msg.what */
    public static final int CONNECT_WIRED_HEADSET = 1;
    public static final int DISCONNECT_WIRED_HEADSET = 2;
    public static final int CONNECT_BLUETOOTH = 3;
    public static final int DISCONNECT_BLUETOOTH = 4;
    public static final int CONNECT_DOCK = 5;
    public static final int DISCONNECT_DOCK = 6;

    public static final int SWITCH_EARPIECE = 1001;
    public static final int SWITCH_BLUETOOTH = 1002;
    public static final int SWITCH_HEADSET = 1003;
    public static final int SWITCH_SPEAKER = 1004;
    public static final int SWITCH_WIRED_OR_EARPIECE = 1005;

    public static final int REINITIALIZE = 2001;

    public static final int MUTE_ON = 3001;
    public static final int MUTE_OFF = 3002;
    public static final int TOGGLE_MUTE = 3003;

    public static final int SWITCH_FOCUS = 4001;

    // Used in testing to execute verifications. Not compatible with subsessions.
    public static final int RUN_RUNNABLE = 9001;

    /** Valid values for mAudioFocusType */
    public static final int NO_FOCUS = 1;
    public static final int HAS_FOCUS = 2;

    private static final String ACTIVE_EARPIECE_ROUTE_NAME = "ActiveEarpieceRoute";
    private static final String ACTIVE_BLUETOOTH_ROUTE_NAME = "ActiveBluetoothRoute";
    private static final String ACTIVE_SPEAKER_ROUTE_NAME = "ActiveSpeakerRoute";
    private static final String ACTIVE_HEADSET_ROUTE_NAME = "ActiveHeadsetRoute";
    private static final String QUIESCENT_EARPIECE_ROUTE_NAME = "QuiescentEarpieceRoute";
    private static final String QUIESCENT_BLUETOOTH_ROUTE_NAME = "QuiescentBluetoothRoute";
    private static final String QUIESCENT_SPEAKER_ROUTE_NAME = "QuiescentSpeakerRoute";
    private static final String QUIESCENT_HEADSET_ROUTE_NAME = "QuiescentHeadsetRoute";

    public static final String NAME = CallAudioRouteStateMachine.class.getName();

    @Override
    protected void onPreHandleMessage(Message msg) {
        if (msg.obj != null && msg.obj instanceof Session) {
            Log.continueSession((Session) msg.obj, "CARSM.pM_" + msg.what);
        }
    }

    @Override
    protected void onPostHandleMessage(Message msg) {
        Log.endSession();
    }

    abstract class AudioState extends State {
        @Override
        public void enter() {
            super.enter();
            Log.event(mCallsManager.getForegroundCall(), Log.Events.AUDIO_ROUTE,
                    "Entering state " + getName());
        }

        @Override
        public void exit() {
            Log.event(mCallsManager.getForegroundCall(), Log.Events.AUDIO_ROUTE,
                    "Leaving state " + getName());
            super.exit();
        }

        @Override
        public boolean processMessage(Message msg) {
            Log.d(this, "Message received: %s", msg);
            switch (msg.what) {
                case CONNECT_WIRED_HEADSET:
                    Log.event(mCallsManager.getForegroundCall(), Log.Events.AUDIO_ROUTE,
                            "Wired headset connected");
                    mAvailableRoutes &= ~ROUTE_EARPIECE;
                    mAvailableRoutes |= ROUTE_WIRED_HEADSET;
                    return NOT_HANDLED;
                case CONNECT_BLUETOOTH:
                    // This case is here because the bluetooth manager sends out a lot of spurious
                    // state changes, and no layers above this one can tell which are actual changes
                    // in connection/disconnection status. This filters it out.
                    if ((mAvailableRoutes & ROUTE_BLUETOOTH) != 0) {
                        return HANDLED; // Do nothing if we already have bluetooth as enabled.
                    } else {
                        Log.event(mCallsManager.getForegroundCall(), Log.Events.AUDIO_ROUTE,
                                "Bluetooth connected");
                        mAvailableRoutes |= ROUTE_BLUETOOTH;
                        return NOT_HANDLED;
                    }
                case DISCONNECT_WIRED_HEADSET:
                    Log.event(mCallsManager.getForegroundCall(), Log.Events.AUDIO_ROUTE,
                            "Wired headset disconnected");
                    mAvailableRoutes &= ~ROUTE_WIRED_HEADSET;
                    mAvailableRoutes |= ROUTE_EARPIECE;
                    return NOT_HANDLED;
                case DISCONNECT_BLUETOOTH:
                    if ((mAvailableRoutes & ROUTE_BLUETOOTH) == 0) {
                        return HANDLED;
                    } else {
                        Log.event(mCallsManager.getForegroundCall(), Log.Events.AUDIO_ROUTE,
                                "Bluetooth disconnected");
                        mAvailableRoutes &= ~ROUTE_BLUETOOTH;
                        return NOT_HANDLED;
                    }
                case SWITCH_WIRED_OR_EARPIECE:
                    if ((mAvailableRoutes & ROUTE_EARPIECE) != 0) {
                        sendInternalMessage(SWITCH_EARPIECE);
                    } else if ((mAvailableRoutes & ROUTE_WIRED_HEADSET) != 0) {
                        sendInternalMessage(SWITCH_HEADSET);
                    } else {
                        Log.e(this, new IllegalStateException(),
                                "Neither headset nor earpiece are available. Defaulting to " +
                                        "earpiece.");
                        sendInternalMessage(SWITCH_EARPIECE);
                    }
                    return HANDLED;
                case REINITIALIZE:
                    CallAudioState initState = getInitialAudioState();
                    mAvailableRoutes = initState.getSupportedRouteMask();
                    mIsMuted = initState.isMuted();
                    mWasOnSpeaker = initState.getRoute() == ROUTE_SPEAKER;
                    transitionTo(mRouteCodeToQuiescentState.get(initState.getRoute()));
                    return HANDLED;
                default:
                    return NOT_HANDLED;
            }
        }

        // Behavior will depend on whether the state is an active one or a quiescent one.
        abstract public void updateSystemAudioState();
        abstract public boolean isActive();
    }

    class ActiveEarpieceRoute extends EarpieceRoute {
        @Override
        public String getName() {
            return ACTIVE_EARPIECE_ROUTE_NAME;
        }

        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public void enter() {
            super.enter();
            setSpeakerphoneOn(false);
            setBluetoothOn(false);
            CallAudioState newState = new CallAudioState(mIsMuted, ROUTE_EARPIECE,
                    mAvailableRoutes);
            setSystemAudioState(mCurrentCallAudioState, newState);
            updateInternalCallAudioState();
        }

        @Override
        public void updateSystemAudioState() {
            CallAudioState oldAudioState = mCurrentCallAudioState;
            updateInternalCallAudioState();
            setSystemAudioState(oldAudioState, mCurrentCallAudioState);
        }

        @Override
        public boolean processMessage(Message msg) {
            if (super.processMessage(msg) == HANDLED) {
                return HANDLED;
            }
            switch (msg.what) {
                case SWITCH_EARPIECE:
                    // Nothing to do here
                    return HANDLED;
                case SWITCH_BLUETOOTH:
                    if ((mAvailableRoutes & ROUTE_BLUETOOTH) != 0) {
                        transitionTo(mActiveBluetoothRoute);
                    } else {
                        Log.w(this, "Ignoring switch to bluetooth command. Not available.");
                    }
                    return HANDLED;
                case SWITCH_HEADSET:
                    if ((mAvailableRoutes & ROUTE_WIRED_HEADSET) != 0) {
                        transitionTo(mActiveHeadsetRoute);
                    } else {
                        Log.w(this, "Ignoring switch to headset command. Not available.");
                    }
                    return HANDLED;
                case SWITCH_SPEAKER:
                    transitionTo(mActiveSpeakerRoute);
                    return HANDLED;
                case SWITCH_FOCUS:
                    if (msg.arg1 == NO_FOCUS) {
                        transitionTo(mQuiescentEarpieceRoute);
                    }
                    return HANDLED;
                default:
                    return NOT_HANDLED;
            }
        }
    }

    class QuiescentEarpieceRoute extends EarpieceRoute {
        @Override
        public String getName() {
            return QUIESCENT_EARPIECE_ROUTE_NAME;
        }

        @Override
        public boolean isActive() {
            return false;
        }

        @Override
        public void enter() {
            super.enter();
            updateInternalCallAudioState();
        }

        @Override
        public void updateSystemAudioState() {
            updateInternalCallAudioState();
        }

        @Override
        public boolean processMessage(Message msg) {
            if (super.processMessage(msg) == HANDLED) {
                return HANDLED;
            }
            switch (msg.what) {
                case SWITCH_EARPIECE:
                    // Nothing to do here
                    return HANDLED;
                case SWITCH_BLUETOOTH:
                    if ((mAvailableRoutes & ROUTE_BLUETOOTH) != 0) {
                        transitionTo(mQuiescentBluetoothRoute);
                    } else {
                        Log.w(this, "Ignoring switch to bluetooth command. Not available.");
                    }
                    return HANDLED;
                case SWITCH_HEADSET:
                    if ((mAvailableRoutes & ROUTE_WIRED_HEADSET) != 0) {
                        transitionTo(mQuiescentHeadsetRoute);
                    } else {
                        Log.w(this, "Ignoring switch to headset command. Not available.");
                    }
                    return HANDLED;
                case SWITCH_SPEAKER:
                    transitionTo(mQuiescentSpeakerRoute);
                    return HANDLED;
                case SWITCH_FOCUS:
                    if (msg.arg1 == HAS_FOCUS) {
                        transitionTo(mActiveEarpieceRoute);
                    }
                    return HANDLED;
                default:
                    return NOT_HANDLED;
            }
        }
    }

    abstract class EarpieceRoute extends AudioState {
        @Override
        public boolean processMessage(Message msg) {
            if (super.processMessage(msg) == HANDLED) {
                return HANDLED;
            }
            switch (msg.what) {
                case CONNECT_WIRED_HEADSET:
                    sendInternalMessage(SWITCH_HEADSET);
                    return HANDLED;
                case CONNECT_BLUETOOTH:
                    sendInternalMessage(SWITCH_BLUETOOTH);
                    return HANDLED;
                case DISCONNECT_BLUETOOTH:
                    updateSystemAudioState();
                    // No change in audio route required
                    return HANDLED;
                case DISCONNECT_WIRED_HEADSET:
                    Log.e(this, new IllegalStateException(),
                            "Wired headset should not go from connected to not when on " +
                            "earpiece");
                    updateSystemAudioState();
                    return HANDLED;
                case CONNECT_DOCK:
                    sendInternalMessage(SWITCH_SPEAKER);
                    return HANDLED;
                case DISCONNECT_DOCK:
                    // Nothing to do here
                    return HANDLED;
                default:
                    return NOT_HANDLED;
            }
        }
    }

    class ActiveHeadsetRoute extends HeadsetRoute {
        @Override
        public String getName() {
            return ACTIVE_HEADSET_ROUTE_NAME;
        }

        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public void enter() {
            super.enter();
            setSpeakerphoneOn(false);
            setBluetoothOn(false);
            CallAudioState newState = new CallAudioState(mIsMuted, ROUTE_WIRED_HEADSET,
                    mAvailableRoutes);
            setSystemAudioState(mCurrentCallAudioState, newState);
            updateInternalCallAudioState();
        }

        @Override
        public void updateSystemAudioState() {
            CallAudioState oldAudioState = mCurrentCallAudioState;
            updateInternalCallAudioState();
            setSystemAudioState(oldAudioState, mCurrentCallAudioState);
        }

        @Override
        public boolean processMessage(Message msg) {
            if (super.processMessage(msg) == HANDLED) {
                return HANDLED;
            }
            switch (msg.what) {
                case SWITCH_EARPIECE:
                    if ((mAvailableRoutes & ROUTE_EARPIECE) != 0) {
                        transitionTo(mActiveEarpieceRoute);
                    } else {
                        Log.w(this, "Ignoring switch to earpiece command. Not available.");
                    }
                    return HANDLED;
                case SWITCH_BLUETOOTH:
                    if ((mAvailableRoutes & ROUTE_BLUETOOTH) != 0) {
                        transitionTo(mActiveBluetoothRoute);
                    } else {
                        Log.w(this, "Ignoring switch to bluetooth command. Not available.");
                    }
                    return HANDLED;
                case SWITCH_HEADSET:
                    // Nothing to do
                    return HANDLED;
                case SWITCH_SPEAKER:
                    transitionTo(mActiveSpeakerRoute);
                    return HANDLED;
                case SWITCH_FOCUS:
                    if (msg.arg1 == NO_FOCUS) {
                        transitionTo(mQuiescentHeadsetRoute);
                    }
                    return HANDLED;
                default:
                    return NOT_HANDLED;
            }
        }
    }

    class QuiescentHeadsetRoute extends HeadsetRoute {
        @Override
        public String getName() {
            return QUIESCENT_HEADSET_ROUTE_NAME;
        }

        @Override
        public boolean isActive() {
            return false;
        }

        @Override
        public void enter() {
            super.enter();
            updateInternalCallAudioState();
        }

        @Override
        public void updateSystemAudioState() {
            updateInternalCallAudioState();
        }

        @Override
        public boolean processMessage(Message msg) {
            if (super.processMessage(msg) == HANDLED) {
                return HANDLED;
            }
            switch (msg.what) {
                case SWITCH_EARPIECE:
                    if ((mAvailableRoutes & ROUTE_EARPIECE) != 0) {
                        transitionTo(mQuiescentEarpieceRoute);
                    } else {
                        Log.w(this, "Ignoring switch to earpiece command. Not available.");
                    }
                    return HANDLED;
                case SWITCH_BLUETOOTH:
                    if ((mAvailableRoutes & ROUTE_BLUETOOTH) != 0) {
                        transitionTo(mQuiescentBluetoothRoute);
                    } else {
                        Log.w(this, "Ignoring switch to bluetooth command. Not available.");
                    }
                    return HANDLED;
                case SWITCH_HEADSET:
                    // Nothing to do
                    return HANDLED;
                case SWITCH_SPEAKER:
                    transitionTo(mQuiescentSpeakerRoute);
                    return HANDLED;
                case SWITCH_FOCUS:
                    if (msg.arg1 == HAS_FOCUS) {
                        transitionTo(mActiveHeadsetRoute);
                    }
                    return HANDLED;
                default:
                    return NOT_HANDLED;
            }
        }
    }

    abstract class HeadsetRoute extends AudioState {
        @Override
        public boolean processMessage(Message msg) {
            if (super.processMessage(msg) == HANDLED) {
                return HANDLED;
            }
            switch (msg.what) {
                case CONNECT_WIRED_HEADSET:
                    Log.e(this, new IllegalStateException(),
                            "Wired headset should already be connected.");
                    mAvailableRoutes |= ROUTE_WIRED_HEADSET;
                    updateSystemAudioState();
                    return HANDLED;
                case CONNECT_BLUETOOTH:
                    sendInternalMessage(SWITCH_BLUETOOTH);
                    return HANDLED;
                case DISCONNECT_BLUETOOTH:
                    updateSystemAudioState();
                    // No change in audio route required
                    return HANDLED;
                case DISCONNECT_WIRED_HEADSET:
                    if (mWasOnSpeaker) {
                        sendInternalMessage(SWITCH_SPEAKER);
                    } else {
                        sendInternalMessage(SWITCH_EARPIECE);
                    }
                    return HANDLED;
                case CONNECT_DOCK:
                    // Nothing to do here
                    return HANDLED;
                case DISCONNECT_DOCK:
                    // Nothing to do here
                    return HANDLED;
                default:
                    return NOT_HANDLED;
            }
        }
    }

    class ActiveBluetoothRoute extends BluetoothRoute {
        @Override
        public String getName() {
            return ACTIVE_BLUETOOTH_ROUTE_NAME;
        }

        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public void enter() {
            super.enter();
            setSpeakerphoneOn(false);
            setBluetoothOn(true);
            CallAudioState newState = new CallAudioState(mIsMuted, ROUTE_BLUETOOTH,
                    mAvailableRoutes);
            setSystemAudioState(mCurrentCallAudioState, newState);
            updateInternalCallAudioState();
        }

        @Override
        public void updateSystemAudioState() {
            CallAudioState oldAudioState = mCurrentCallAudioState;
            updateInternalCallAudioState();
            setSystemAudioState(oldAudioState, mCurrentCallAudioState);
        }

        @Override
        public boolean processMessage(Message msg) {
            if (super.processMessage(msg) == HANDLED) {
                return HANDLED;
            }
            switch (msg.what) {
                case SWITCH_EARPIECE:
                    if ((mAvailableRoutes & ROUTE_EARPIECE) != 0) {
                        transitionTo(mActiveEarpieceRoute);
                    } else {
                        Log.w(this, "Ignoring switch to earpiece command. Not available.");
                    }
                    return HANDLED;
                case SWITCH_BLUETOOTH:
                    // Nothing to do
                    return HANDLED;
                case SWITCH_HEADSET:
                    if ((mAvailableRoutes & ROUTE_WIRED_HEADSET) != 0) {
                        transitionTo(mActiveHeadsetRoute);
                    } else {
                        Log.w(this, "Ignoring switch to headset command. Not available.");
                    }
                    return HANDLED;
                case SWITCH_SPEAKER:
                    transitionTo(mActiveSpeakerRoute);
                    return HANDLED;
                case SWITCH_FOCUS:
                    if (msg.arg1 == NO_FOCUS) {
                        transitionTo(mQuiescentBluetoothRoute);
                    }
                    return HANDLED;
                default:
                    return NOT_HANDLED;
            }
        }
    }

    class QuiescentBluetoothRoute extends BluetoothRoute {
        @Override
        public String getName() {
            return QUIESCENT_BLUETOOTH_ROUTE_NAME;
        }

        @Override
        public boolean isActive() {
            return false;
        }

        @Override
        public void enter() {
            super.enter();
            updateInternalCallAudioState();
        }

        @Override
        public void updateSystemAudioState() {
            updateInternalCallAudioState();
        }

        @Override
        public boolean processMessage(Message msg) {
            if (super.processMessage(msg) == HANDLED) {
                return HANDLED;
            }
            switch (msg.what) {
                case SWITCH_EARPIECE:
                    if ((mAvailableRoutes & ROUTE_EARPIECE) != 0) {
                        transitionTo(mQuiescentEarpieceRoute);
                    } else {
                        Log.w(this, "Ignoring switch to earpiece command. Not available.");
                    }
                    return HANDLED;
                case SWITCH_BLUETOOTH:
                    // Nothing to do
                    return HANDLED;
                case SWITCH_HEADSET:
                    if ((mAvailableRoutes & ROUTE_WIRED_HEADSET) != 0) {
                        transitionTo(mQuiescentHeadsetRoute);
                    } else {
                        Log.w(this, "Ignoring switch to headset command. Not available.");
                    }
                    return HANDLED;
                case SWITCH_SPEAKER:
                    transitionTo(mQuiescentSpeakerRoute);
                    return HANDLED;
                case SWITCH_FOCUS:
                    if (msg.arg1 == HAS_FOCUS) {
                        transitionTo(mActiveBluetoothRoute);
                    }
                    return HANDLED;
                default:
                    return NOT_HANDLED;
            }
        }
    }

    abstract class BluetoothRoute extends AudioState {
        @Override
        public boolean processMessage(Message msg) {
            if (super.processMessage(msg) == HANDLED) {
                return HANDLED;
            }
            switch (msg.what) {
                case CONNECT_WIRED_HEADSET:
                    sendInternalMessage(SWITCH_HEADSET);
                    return HANDLED;
                case CONNECT_BLUETOOTH:
                    // We can't tell when a change in bluetooth state corresponds to an
                    // actual connection or disconnection, so we'll just ignore it if we're already
                    // in the bluetooth route.
                    return HANDLED;
                case DISCONNECT_BLUETOOTH:
                    sendInternalMessage(SWITCH_WIRED_OR_EARPIECE);
                    mWasOnSpeaker = false;
                    return HANDLED;
                case DISCONNECT_WIRED_HEADSET:
                    updateSystemAudioState();
                    // No change in audio route required
                    return HANDLED;
                case CONNECT_DOCK:
                    // Nothing to do here
                    return HANDLED;
                case DISCONNECT_DOCK:
                    // Nothing to do here
                    return HANDLED;
                default:
                    return NOT_HANDLED;
            }
        }
    }

    class ActiveSpeakerRoute extends SpeakerRoute {
        @Override
        public String getName() {
            return ACTIVE_SPEAKER_ROUTE_NAME;
        }

        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public void enter() {
            super.enter();
            mWasOnSpeaker = true;
            setSpeakerphoneOn(true);
            setBluetoothOn(false);
            CallAudioState newState = new CallAudioState(mIsMuted, ROUTE_SPEAKER,
                    mAvailableRoutes);
            setSystemAudioState(mCurrentCallAudioState, newState);
            updateInternalCallAudioState();
        }

        @Override
        public void updateSystemAudioState() {
            CallAudioState oldAudioState = mCurrentCallAudioState;
            updateInternalCallAudioState();
            setSystemAudioState(oldAudioState, mCurrentCallAudioState);
        }

        @Override
        public boolean processMessage(Message msg) {
            if (super.processMessage(msg) == HANDLED) {
                return HANDLED;
            }
            switch(msg.what) {
                case SWITCH_EARPIECE:
                    if ((mAvailableRoutes & ROUTE_EARPIECE) != 0) {
                        transitionTo(mActiveEarpieceRoute);
                    } else {
                        Log.w(this, "Ignoring switch to earpiece command. Not available.");
                    }
                    return HANDLED;
                case SWITCH_BLUETOOTH:
                    if ((mAvailableRoutes & ROUTE_BLUETOOTH) != 0) {
                        transitionTo(mActiveBluetoothRoute);
                    } else {
                        Log.w(this, "Ignoring switch to bluetooth command. Not available.");
                    }
                    return HANDLED;
                case SWITCH_HEADSET:
                    if ((mAvailableRoutes & ROUTE_WIRED_HEADSET) != 0) {
                        transitionTo(mActiveHeadsetRoute);
                    } else {
                        Log.w(this, "Ignoring switch to headset command. Not available.");
                    }
                    return HANDLED;
                case SWITCH_SPEAKER:
                    // Nothing to do
                    return HANDLED;
                case SWITCH_FOCUS:
                    if (msg.arg1 == NO_FOCUS) {
                        transitionTo(mQuiescentSpeakerRoute);
                    }
                    return HANDLED;
                default:
                    return NOT_HANDLED;
            }
        }
    }

    class QuiescentSpeakerRoute extends SpeakerRoute {
        @Override
        public String getName() {
            return QUIESCENT_SPEAKER_ROUTE_NAME;
        }

        @Override
        public boolean isActive() {
            return false;
        }

        @Override
        public void enter() {
            super.enter();
            // Omit setting mWasOnSpeaker to true here, since this does not reflect a call
            // actually being on speakerphone.
            updateInternalCallAudioState();
        }

        @Override
        public void updateSystemAudioState() {
            updateInternalCallAudioState();
        }

        @Override
        public boolean processMessage(Message msg) {
            if (super.processMessage(msg) == HANDLED) {
                return HANDLED;
            }
            switch(msg.what) {
                case SWITCH_EARPIECE:
                    if ((mAvailableRoutes & ROUTE_EARPIECE) != 0) {
                        transitionTo(mQuiescentEarpieceRoute);
                    } else {
                        Log.w(this, "Ignoring switch to earpiece command. Not available.");
                    }
                    return HANDLED;
                case SWITCH_BLUETOOTH:
                    if ((mAvailableRoutes & ROUTE_BLUETOOTH) != 0) {
                        transitionTo(mQuiescentBluetoothRoute);
                    } else {
                        Log.w(this, "Ignoring switch to bluetooth command. Not available.");
                    }
                    return HANDLED;
                case SWITCH_HEADSET:
                    if ((mAvailableRoutes & ROUTE_WIRED_HEADSET) != 0) {
                        transitionTo(mQuiescentHeadsetRoute);
                    } else {
                        Log.w(this, "Ignoring switch to headset command. Not available.");
                    }
                    return HANDLED;
                case SWITCH_SPEAKER:
                    // Nothing to do
                    return HANDLED;
                case SWITCH_FOCUS:
                    if (msg.arg1 == HAS_FOCUS) {
                        transitionTo(mActiveSpeakerRoute);
                    }
                    return HANDLED;
                default:
                    return NOT_HANDLED;
            }
        }
    }

    abstract class SpeakerRoute extends AudioState {
        @Override
        public boolean processMessage(Message msg) {
            if (super.processMessage(msg) == HANDLED) {
                return HANDLED;
            }
            switch (msg.what) {
                case CONNECT_WIRED_HEADSET:
                    sendInternalMessage(SWITCH_HEADSET);
                    return HANDLED;
                case CONNECT_BLUETOOTH:
                    sendInternalMessage(SWITCH_BLUETOOTH);
                    return HANDLED;
                case DISCONNECT_BLUETOOTH:
                    updateSystemAudioState();
                    // No change in audio route required
                    return HANDLED;
                case DISCONNECT_WIRED_HEADSET:
                    updateSystemAudioState();
                    // No change in audio route required
                    return HANDLED;
                case CONNECT_DOCK:
                    // Nothing to do here
                    return HANDLED;
                case DISCONNECT_DOCK:
                    sendInternalMessage(SWITCH_WIRED_OR_EARPIECE);
                    return HANDLED;
               default:
                    return NOT_HANDLED;
            }
        }
    }

    private final ActiveEarpieceRoute mActiveEarpieceRoute = new ActiveEarpieceRoute();
    private final ActiveHeadsetRoute mActiveHeadsetRoute = new ActiveHeadsetRoute();
    private final ActiveBluetoothRoute mActiveBluetoothRoute = new ActiveBluetoothRoute();
    private final ActiveSpeakerRoute mActiveSpeakerRoute = new ActiveSpeakerRoute();
    private final QuiescentEarpieceRoute mQuiescentEarpieceRoute = new QuiescentEarpieceRoute();
    private final QuiescentHeadsetRoute mQuiescentHeadsetRoute = new QuiescentHeadsetRoute();
    private final QuiescentBluetoothRoute mQuiescentBluetoothRoute = new QuiescentBluetoothRoute();
    private final QuiescentSpeakerRoute mQuiescentSpeakerRoute = new QuiescentSpeakerRoute();

    /**
     * A few pieces of hidden state. Used to avoid exponential explosion of number of explicit
     * states
     */
    private int mAvailableRoutes;
    private boolean mWasOnSpeaker;
    private boolean mIsMuted;

    private final Context mContext;
    private final CallsManager mCallsManager;
    private final AudioManager mAudioManager;
    private final BluetoothManager mBluetoothManager;
    private final WiredHeadsetManager mWiredHeadsetManager;
    private final StatusBarNotifier mStatusBarNotifier;
    private final CallAudioManager.AudioServiceFactory mAudioServiceFactory;

    private HashMap<String, Integer> mStateNameToRouteCode;
    private HashMap<Integer, AudioState> mRouteCodeToQuiescentState;

    // CallAudioState is used as an interface to communicate with many other system components.
    // No internal state transitions should depend on this variable.
    private CallAudioState mCurrentCallAudioState;

    public CallAudioRouteStateMachine(
            Context context,
            CallsManager callsManager,
            BluetoothManager bluetoothManager,
            WiredHeadsetManager wiredHeadsetManager,
            StatusBarNotifier statusBarNotifier,
            CallAudioManager.AudioServiceFactory audioServiceFactory) {
        super(NAME);
        addState(mActiveEarpieceRoute);
        addState(mActiveHeadsetRoute);
        addState(mActiveBluetoothRoute);
        addState(mActiveSpeakerRoute);
        addState(mQuiescentEarpieceRoute);
        addState(mQuiescentHeadsetRoute);
        addState(mQuiescentBluetoothRoute);
        addState(mQuiescentSpeakerRoute);

        mContext = context;
        mCallsManager = callsManager;
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        mBluetoothManager = bluetoothManager;
        mWiredHeadsetManager = wiredHeadsetManager;
        mStatusBarNotifier = statusBarNotifier;
        mAudioServiceFactory = audioServiceFactory;

        mStateNameToRouteCode = new HashMap<>(8);
        mStateNameToRouteCode.put(mQuiescentEarpieceRoute.getName(), ROUTE_EARPIECE);
        mStateNameToRouteCode.put(mQuiescentBluetoothRoute.getName(), ROUTE_BLUETOOTH);
        mStateNameToRouteCode.put(mQuiescentHeadsetRoute.getName(), ROUTE_WIRED_HEADSET);
        mStateNameToRouteCode.put(mQuiescentSpeakerRoute.getName(), ROUTE_SPEAKER);
        mStateNameToRouteCode.put(mActiveEarpieceRoute.getName(), ROUTE_EARPIECE);
        mStateNameToRouteCode.put(mActiveBluetoothRoute.getName(), ROUTE_BLUETOOTH);
        mStateNameToRouteCode.put(mActiveHeadsetRoute.getName(), ROUTE_WIRED_HEADSET);
        mStateNameToRouteCode.put(mActiveSpeakerRoute.getName(), ROUTE_SPEAKER);

        mRouteCodeToQuiescentState = new HashMap<>(4);
        mRouteCodeToQuiescentState.put(ROUTE_EARPIECE, mQuiescentEarpieceRoute);
        mRouteCodeToQuiescentState.put(ROUTE_BLUETOOTH, mQuiescentBluetoothRoute);
        mRouteCodeToQuiescentState.put(ROUTE_SPEAKER, mQuiescentSpeakerRoute);
        mRouteCodeToQuiescentState.put(ROUTE_WIRED_HEADSET, mQuiescentHeadsetRoute);
    }

    /**
     * Initializes the state machine with info on initial audio route, supported audio routes,
     * and mute status.
     */
    public void initialize() {
        CallAudioState initState = getInitialAudioState();
        initialize(initState);
    }

    public void initialize(CallAudioState initState) {
        mCurrentCallAudioState = initState;
        mAvailableRoutes = initState.getSupportedRouteMask();
        mIsMuted = initState.isMuted();
        mWasOnSpeaker = initState.getRoute() == ROUTE_SPEAKER;

        mStatusBarNotifier.notifyMute(initState.isMuted());
        mStatusBarNotifier.notifySpeakerphone(initState.getRoute() == CallAudioState.ROUTE_SPEAKER);
        setInitialState(mRouteCodeToQuiescentState.get(initState.getRoute()));
        start();
    }

    /**
     * Getter for the current CallAudioState object that the state machine is keeping track of.
     * Used for compatibility purposes.
     */
    public CallAudioState getCurrentCallAudioState() {
        return mCurrentCallAudioState;
    }

    public void sendMessageWithSessionInfo(int message, int arg) {
        sendMessage(message, arg, 0, Log.createSubsession());
    }

    public void sendMessageWithSessionInfo(int message) {
        sendMessage(message, 0, 0, Log.createSubsession());
    }

    /**
     * This is for state-independent changes in audio route (i.e. muting or runnables)
     * @param msg that couldn't be handled.
     */
    @Override
    protected void unhandledMessage(Message msg) {
        CallAudioState newCallAudioState;
        switch (msg.what) {
            case MUTE_ON:
                setMuteOn(true);
                newCallAudioState = new CallAudioState(mIsMuted,
                        mCurrentCallAudioState.getRoute(),
                        mAvailableRoutes);
                setSystemAudioState(mCurrentCallAudioState, newCallAudioState);
                updateInternalCallAudioState();
                return;
            case MUTE_OFF:
                setMuteOn(false);
                newCallAudioState = new CallAudioState(mIsMuted,
                        mCurrentCallAudioState.getRoute(),
                        mAvailableRoutes);
                setSystemAudioState(mCurrentCallAudioState, newCallAudioState);
                updateInternalCallAudioState();
                return;
            case TOGGLE_MUTE:
                if (mIsMuted) {
                    sendInternalMessage(MUTE_OFF);
                } else {
                    sendInternalMessage(MUTE_ON);
                }
                return;
            case RUN_RUNNABLE:
                Runnable r = (Runnable) msg.obj;
                r.run();
                return;
            default:
                Log.e(this, new IllegalStateException(),
                        "Unexpected message code");
        }
    }

    public void quitStateMachine() {
        quitNow();
    }

    private void setSpeakerphoneOn(boolean on) {
        if (mAudioManager.isSpeakerphoneOn() != on) {
            Log.i(this, "turning speaker phone %s", on);
            mAudioManager.setSpeakerphoneOn(on);
        }
    }

    private void setBluetoothOn(boolean on) {
        if (mBluetoothManager.isBluetoothAvailable()) {
            boolean isAlreadyOn = mBluetoothManager.isBluetoothAudioConnectedOrPending();
            if (on != isAlreadyOn) {
                Log.i(this, "connecting bluetooth %s", on);
                if (on) {
                    mBluetoothManager.connectBluetoothAudio();
                } else {
                    mBluetoothManager.disconnectBluetoothAudio();
                }
            }
        }
    }

    private void setMuteOn(boolean mute) {
        mIsMuted = mute;
        Log.event(mCallsManager.getForegroundCall(), Log.Events.MUTE,
                mute ? "on" : "off");
        if (mute != mAudioManager.isMicrophoneMute() && isInActiveState()) {
            IAudioService audio = mAudioServiceFactory.getAudioService();
            Log.i(this, "changing microphone mute state to: %b [serviceIsNull=%b]",
                    mute, audio == null);
            if (audio != null) {
                try {
                    // We use the audio service directly here so that we can specify
                    // the current user. Telecom runs in the system_server process which
                    // may run as a separate user from the foreground user. If we
                    // used AudioManager directly, we would change mute for the system's
                    // user and not the current foreground, which we want to avoid.
                    audio.setMicrophoneMute(
                            mute, mContext.getOpPackageName(), getCurrentUserId());

                } catch (RemoteException e) {
                    Log.e(this, e, "Remote exception while toggling mute.");
                }
                // TODO: Check microphone state after attempting to set to ensure that
                // our state corroborates AudioManager's state.
            }
        }
    }

    /**
     * Updates the CallAudioState object from current internal state. The result is used for
     * external communication only.
     */
    private void updateInternalCallAudioState() {
        IState currentState = getCurrentState();
        if (currentState == null) {
            Log.e(this, new IllegalStateException(), "Current state should never be null" +
                    " when updateInternalCallAudioState is called.");
            mCurrentCallAudioState = new CallAudioState(
                    mIsMuted, mCurrentCallAudioState.getRoute(), mAvailableRoutes);
            return;
        }
        int currentRoute = mStateNameToRouteCode.get(currentState.getName());
        mCurrentCallAudioState = new CallAudioState(mIsMuted, currentRoute, mAvailableRoutes);
    }

    private void setSystemAudioState(CallAudioState oldCallAudioState,
            CallAudioState newCallAudioState) {
        Log.i(this, "setSystemAudioState: changing from %s to %s", oldCallAudioState,
                newCallAudioState);
        Log.event(mCallsManager.getForegroundCall(), Log.Events.AUDIO_ROUTE,
                CallAudioState.audioRouteToString(newCallAudioState.getRoute()));

        if (!oldCallAudioState.equals(newCallAudioState)) {
            mCallsManager.onCallAudioStateChanged(oldCallAudioState, newCallAudioState);
            updateAudioForForegroundCall(newCallAudioState);
        }
    }

    private void updateAudioForForegroundCall(CallAudioState newCallAudioState) {
        Call call = mCallsManager.getForegroundCall();
        if (call != null && call.getConnectionService() != null) {
            call.getConnectionService().onCallAudioStateChanged(call, newCallAudioState);
        }
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

    private void sendInternalMessage(int messageCode) {
        // Internal messages are messages which the state machine sends to itself in the
        // course of processing externally-sourced messages. We want to send these messages at
        // the front of the queue in order to make actions appear atomic to the user and to
        // prevent scenarios such as these:
        // 1. State machine handler thread is suspended for some reason.
        // 2. Headset gets connected (sends CONNECT_HEADSET).
        // 3. User switches to speakerphone in the UI (sends SWITCH_SPEAKER).
        // 4. State machine handler is un-suspended.
        // 5. State machine handler processes the CONNECT_HEADSET message and sends
        //    SWITCH_HEADSET at end of queue.
        // 6. State machine handler processes SWITCH_SPEAKER.
        // 7. State machine handler processes SWITCH_HEADSET.
        Session subsession = Log.createSubsession();
        if(subsession != null) {
            sendMessageAtFrontOfQueue(messageCode, subsession);
        } else {
            sendMessageAtFrontOfQueue(messageCode);
        }
    }

    private CallAudioState getInitialAudioState() {
        int supportedRouteMask = calculateSupportedRoutes();
        int route = (supportedRouteMask & ROUTE_WIRED_HEADSET) != 0
                ? ROUTE_WIRED_HEADSET : ROUTE_EARPIECE;
        if ((supportedRouteMask & ROUTE_BLUETOOTH) != 0) {
            route = ROUTE_BLUETOOTH;
        }

        return new CallAudioState(false, route, supportedRouteMask);
    }

    private int getCurrentUserId() {
        final long ident = Binder.clearCallingIdentity();
        try {
            UserInfo currentUser = ActivityManagerNative.getDefault().getCurrentUser();
            return currentUser.id;
        } catch (RemoteException e) {
            // Activity manager not running, nothing we can do assume user 0.
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        return UserHandle.USER_OWNER;
    }

    private boolean isInActiveState() {
        AudioState currentState = (AudioState) getCurrentState();
        if (currentState == null) {
            Log.w(this, "Current state is null, assuming inactive state");
            return false;
        }
        return currentState.isActive();
    }
}