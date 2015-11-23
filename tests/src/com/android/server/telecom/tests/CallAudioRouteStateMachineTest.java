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

package com.android.server.telecom.tests;

import android.content.Context;
import android.media.AudioManager;
import android.media.IAudioService;
import android.telecom.CallAudioState;

import com.android.server.telecom.BluetoothManager;
import com.android.server.telecom.Call;
import com.android.server.telecom.CallAudioManager;
import com.android.server.telecom.CallAudioRouteStateMachine;
import com.android.server.telecom.CallsManager;
import com.android.server.telecom.ConnectionServiceWrapper;
import com.android.server.telecom.StatusBarNotifier;
import com.android.server.telecom.WiredHeadsetManager;

import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


public class CallAudioRouteStateMachineTest extends TelecomTestCase {
    private static final int NONE = 0;
    private static final int ON = 1;
    private static final int OFF = 2;

    private class TestParameters {
        public String name;
        public int initialRoute;
        public int availableRoutes; // may excl. speakerphone, because that's always available
        public int speakerInteraction; // one of NONE, ON, or OFF
        public int bluetoothInteraction; // one of NONE, ON, or OFF
        public int action;
        public int expectedRoute;
        public int expectedAvailableRoutes; // also may exclude the speakerphone.

        public TestParameters(String name, int initialRoute, int availableRoutes, int
                speakerInteraction, int bluetoothInteraction, int action, int expectedRoute, int
                expectedAvailableRoutes) {
            this.name = name;
            this.initialRoute = initialRoute;
            this.availableRoutes = availableRoutes;
            this.speakerInteraction = speakerInteraction;
            this.bluetoothInteraction = bluetoothInteraction;
            this.action = action;
            this.expectedRoute = expectedRoute;
            this.expectedAvailableRoutes = expectedAvailableRoutes;
        }

        @Override
        public String toString() {
            return "TestParameters{" +
                    "name='" + name + '\'' +
                    ", initialRoute=" + initialRoute +
                    ", availableRoutes=" + availableRoutes +
                    ", speakerInteraction=" + speakerInteraction +
                    ", bluetoothInteraction=" + bluetoothInteraction +
                    ", action=" + action +
                    ", expectedRoute=" + expectedRoute +
                    ", expectedAvailableRoutes=" + expectedAvailableRoutes +
                    '}';
        }
    }

    @Mock CallsManager mockCallsManager;
    @Mock BluetoothManager mockBluetoothManager;
    @Mock IAudioService mockAudioService;
    @Mock ConnectionServiceWrapper mockConnectionServiceWrapper;
    @Mock WiredHeadsetManager mockWiredHeadsetManager;
    @Mock StatusBarNotifier mockStatusBarNotifier;
    @Mock Call fakeCall;

    private CallAudioManager.AudioServiceFactory mAudioServiceFactory;
    private static final int TEST_TIMEOUT = 200;
    private AudioManager mockAudioManager;
    @Override
    public void setUp() throws Exception {
        super.setUp();
        MockitoAnnotations.initMocks(this);
        mContext = mComponentContextFixture.getTestDouble().getApplicationContext();
        mockAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);

        mAudioServiceFactory = new CallAudioManager.AudioServiceFactory() {
            @Override
            public IAudioService getAudioService() {
                return mockAudioService;
            }
        };

        when(mockCallsManager.getForegroundCall()).thenReturn(fakeCall);
        when(fakeCall.getConnectionService()).thenReturn(mockConnectionServiceWrapper);
        when(fakeCall.isAlive()).thenReturn(true);
        doNothing().when(mockConnectionServiceWrapper).onCallAudioStateChanged(any(Call.class),
                any(CallAudioState.class));
    }

    public void testStateMachineTransitionsWithFocus() throws Throwable {
        List<TestParameters> paramList = generateTransitionTests();
        for (TestParameters params : paramList) {
            try {
                runParametrizedTestCaseWithFocus(params);
            } catch (Throwable e) {
                String newMessage = "Failed at parameters: \n" + params.toString() + '\n'
                        + e.getMessage();
                throw(new Throwable(newMessage, e));
            }
        }
    }

    public void testStateMachineTransitionsWithoutFocus() throws Throwable {
        List<TestParameters> paramList = generateTransitionTests();
        for (TestParameters params : paramList) {
            try {
                runParametrizedTestCaseWithoutFocus(params);
            } catch (Throwable e) {
                String newMessage = "Failed at parameters: \n" + params.toString() + '\n'
                        + e.getMessage();
                throw(new Throwable(newMessage, e));
            }
        }
    }

    public void testSpeakerPersistence() {
        CallAudioRouteStateMachine stateMachine = new CallAudioRouteStateMachine(
                mContext,
                mockCallsManager,
                mockBluetoothManager,
                mockWiredHeadsetManager,
                mockStatusBarNotifier,
                mAudioServiceFactory);

        when(mockBluetoothManager.isBluetoothAudioConnectedOrPending()).thenReturn(false);
        when(mockBluetoothManager.isBluetoothAvailable()).thenReturn(true);
        when(mockAudioManager.isSpeakerphoneOn()).thenReturn(true);
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                Object[] args = invocation.getArguments();
                when(mockAudioManager.isSpeakerphoneOn()).thenReturn((Boolean) args[0]);
                return null;
            }
        }).when(mockAudioManager).setSpeakerphoneOn(any(Boolean.class));
        CallAudioState initState = new CallAudioState(false, CallAudioState.ROUTE_SPEAKER,
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_SPEAKER);
        stateMachine.initialize(initState);

        stateMachine.sendMessageWithSessionInfo(CallAudioRouteStateMachine.SWITCH_FOCUS,
                CallAudioRouteStateMachine.HAS_FOCUS);
        stateMachine.sendMessageWithSessionInfo(CallAudioRouteStateMachine.CONNECT_WIRED_HEADSET);
        CallAudioState expectedMiddleState = new CallAudioState(false,
                CallAudioState.ROUTE_WIRED_HEADSET,
                CallAudioState.ROUTE_WIRED_HEADSET | CallAudioState.ROUTE_SPEAKER);
        verifyNewSystemCallAudioState(initState, expectedMiddleState);
        resetMocks();

        stateMachine.sendMessageWithSessionInfo(CallAudioRouteStateMachine.DISCONNECT_WIRED_HEADSET);
        verifyNewSystemCallAudioState(expectedMiddleState, initState);
    }

    public void testInitializationWithNoHeadsetNoBluetooth() {
        when(mockWiredHeadsetManager.isPluggedIn()).thenReturn(false);
        when(mockBluetoothManager.isBluetoothAvailable()).thenReturn(false);

        CallAudioRouteStateMachine stateMachine = new CallAudioRouteStateMachine(
                mContext,
                mockCallsManager,
                mockBluetoothManager,
                mockWiredHeadsetManager,
                mockStatusBarNotifier,
                mAudioServiceFactory);
        stateMachine.initialize();
        CallAudioState expectedState = new CallAudioState(false, CallAudioState.ROUTE_EARPIECE,
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_SPEAKER);
        assertEquals(expectedState, stateMachine.getCurrentCallAudioState());
    }

    public void testInitializationWithHeadsetNoBluetooth() {
        when(mockWiredHeadsetManager.isPluggedIn()).thenReturn(true);
        when(mockBluetoothManager.isBluetoothAvailable()).thenReturn(false);

        CallAudioRouteStateMachine stateMachine = new CallAudioRouteStateMachine(
                mContext,
                mockCallsManager,
                mockBluetoothManager,
                mockWiredHeadsetManager,
                mockStatusBarNotifier,
                mAudioServiceFactory);
        stateMachine.initialize();
        CallAudioState expectedState = new CallAudioState(false, CallAudioState.ROUTE_WIRED_HEADSET,
                CallAudioState.ROUTE_WIRED_HEADSET | CallAudioState.ROUTE_SPEAKER);
        assertEquals(expectedState, stateMachine.getCurrentCallAudioState());
    }

    public void testInitializationWithHeadsetAndBluetooth() {
        when(mockWiredHeadsetManager.isPluggedIn()).thenReturn(true);
        when(mockBluetoothManager.isBluetoothAvailable()).thenReturn(true);

        CallAudioRouteStateMachine stateMachine = new CallAudioRouteStateMachine(
                mContext,
                mockCallsManager,
                mockBluetoothManager,
                mockWiredHeadsetManager,
                mockStatusBarNotifier,
                mAudioServiceFactory);
        stateMachine.initialize();
        CallAudioState expectedState = new CallAudioState(false, CallAudioState.ROUTE_BLUETOOTH,
                CallAudioState.ROUTE_WIRED_HEADSET | CallAudioState.ROUTE_SPEAKER
                | CallAudioState.ROUTE_BLUETOOTH);
        assertEquals(expectedState, stateMachine.getCurrentCallAudioState());
    }

    public void testInitializationWithBluetoothNoHeadset() {
        when(mockWiredHeadsetManager.isPluggedIn()).thenReturn(false);
        when(mockBluetoothManager.isBluetoothAvailable()).thenReturn(true);

        CallAudioRouteStateMachine stateMachine = new CallAudioRouteStateMachine(
                mContext,
                mockCallsManager,
                mockBluetoothManager,
                mockWiredHeadsetManager,
                mockStatusBarNotifier,
                mAudioServiceFactory);
        stateMachine.initialize();
        CallAudioState expectedState = new CallAudioState(false, CallAudioState.ROUTE_BLUETOOTH,
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_SPEAKER
                        | CallAudioState.ROUTE_BLUETOOTH);
        assertEquals(expectedState, stateMachine.getCurrentCallAudioState());
    }

    private List<TestParameters> generateTransitionTests() {
        List<TestParameters> params = new ArrayList<>();
        params.add(new TestParameters(
                "Connect headset during earpiece", // name
                CallAudioState.ROUTE_EARPIECE, // initialRoute
                CallAudioState.ROUTE_EARPIECE, // availableRoutes
                NONE, // speakerInteraction
                NONE, // bluetoothInteraction
                CallAudioRouteStateMachine.CONNECT_WIRED_HEADSET, // action
                CallAudioState.ROUTE_WIRED_HEADSET, // expectedRoute
                CallAudioState.ROUTE_WIRED_HEADSET // expectedAvailableRoutes
        ));

        params.add(new TestParameters(
                "Connect headset during bluetooth", // name
                CallAudioState.ROUTE_BLUETOOTH, // initialRoute
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_BLUETOOTH, // availableRoutes
                NONE, // speakerInteraction
                OFF, // bluetoothInteraction
                CallAudioRouteStateMachine.CONNECT_WIRED_HEADSET, // action
                CallAudioState.ROUTE_WIRED_HEADSET, // expectedRoute
                CallAudioState.ROUTE_WIRED_HEADSET | CallAudioState.ROUTE_BLUETOOTH // expectedAvail
        ));

        params.add(new TestParameters(
                "Connect headset during speakerphone", // name
                CallAudioState.ROUTE_SPEAKER, // initialRoute
                CallAudioState.ROUTE_EARPIECE, // availableRoutes
                OFF, // speakerInteraction
                NONE, // bluetoothInteraction
                CallAudioRouteStateMachine.CONNECT_WIRED_HEADSET, // action
                CallAudioState.ROUTE_WIRED_HEADSET, // expectedRoute
                CallAudioState.ROUTE_WIRED_HEADSET // expectedAvailableRoutes
        ));

        params.add(new TestParameters(
                "Disconnect headset during headset", // name
                CallAudioState.ROUTE_WIRED_HEADSET, // initialRoute
                CallAudioState.ROUTE_WIRED_HEADSET, // availableRoutes
                NONE, // speakerInteraction
                NONE, // bluetoothInteraction
                CallAudioRouteStateMachine.DISCONNECT_WIRED_HEADSET, // action
                CallAudioState.ROUTE_EARPIECE, // expectedRoute
                CallAudioState.ROUTE_EARPIECE // expectedAvailableRoutes
        ));

        params.add(new TestParameters(
                "Disconnect headset during headset with bluetooth available", // name
                CallAudioState.ROUTE_WIRED_HEADSET, // initialRoute
                CallAudioState.ROUTE_WIRED_HEADSET | CallAudioState.ROUTE_BLUETOOTH, // availableRou
                NONE, // speakerInteraction
                NONE, // bluetoothInteraction
                CallAudioRouteStateMachine.DISCONNECT_WIRED_HEADSET, // action
                CallAudioState.ROUTE_EARPIECE, // expectedRoute
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_BLUETOOTH // expectedAvailableR
        ));

        params.add(new TestParameters(
                "Disconnect headset during bluetooth", // name
                CallAudioState.ROUTE_BLUETOOTH, // initialRoute
                CallAudioState.ROUTE_WIRED_HEADSET | CallAudioState.ROUTE_BLUETOOTH, // availableRou
                NONE, // speakerInteraction
                NONE, // bluetoothInteraction
                CallAudioRouteStateMachine.DISCONNECT_WIRED_HEADSET, // action
                CallAudioState.ROUTE_BLUETOOTH, // expectedRoute
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_BLUETOOTH // expectedAvailableR
        ));

        params.add(new TestParameters(
                "Disconnect headset during speakerphone", // name
                CallAudioState.ROUTE_SPEAKER, // initialRoute
                CallAudioState.ROUTE_WIRED_HEADSET, // availableRoutes
                NONE, // speakerInteraction
                NONE, // bluetoothInteraction
                CallAudioRouteStateMachine.DISCONNECT_WIRED_HEADSET, // action
                CallAudioState.ROUTE_SPEAKER, // expectedRoute
                CallAudioState.ROUTE_EARPIECE // expectedAvailableRoutes
        ));

        params.add(new TestParameters(
                "Disconnect headset during speakerphone with bluetooth available", // name
                CallAudioState.ROUTE_SPEAKER, // initialRoute
                CallAudioState.ROUTE_WIRED_HEADSET | CallAudioState.ROUTE_BLUETOOTH, // availableRou
                NONE, // speakerInteraction
                NONE, // bluetoothInteraction
                CallAudioRouteStateMachine.DISCONNECT_WIRED_HEADSET, // action
                CallAudioState.ROUTE_SPEAKER, // expectedRoute
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_BLUETOOTH // expectedAvailableR
        ));

        params.add(new TestParameters(
                "Connect bluetooth during earpiece", // name
                CallAudioState.ROUTE_EARPIECE, // initialRoute
                CallAudioState.ROUTE_EARPIECE, // availableRoutes
                NONE, // speakerInteraction
                ON, // bluetoothInteraction
                CallAudioRouteStateMachine.CONNECT_BLUETOOTH, // action
                CallAudioState.ROUTE_BLUETOOTH, // expectedRoute
                CallAudioState.ROUTE_BLUETOOTH | CallAudioState.ROUTE_EARPIECE // expectedAvailableR
        ));

        params.add(new TestParameters(
                "Connect bluetooth during wired headset", // name
                CallAudioState.ROUTE_WIRED_HEADSET, // initialRoute
                CallAudioState.ROUTE_WIRED_HEADSET, // availableRoutes
                NONE, // speakerInteraction
                ON, // bluetoothInteraction
                CallAudioRouteStateMachine.CONNECT_BLUETOOTH, // action
                CallAudioState.ROUTE_BLUETOOTH, // expectedRoute
                CallAudioState.ROUTE_BLUETOOTH | CallAudioState.ROUTE_WIRED_HEADSET // expectedAvail
        ));

        params.add(new TestParameters(
                "Connect bluetooth during speakerphone", // name
                CallAudioState.ROUTE_SPEAKER, // initialRoute
                CallAudioState.ROUTE_EARPIECE, // availableRoutes
                OFF, // speakerInteraction
                ON, // bluetoothInteraction
                CallAudioRouteStateMachine.CONNECT_BLUETOOTH, // action
                CallAudioState.ROUTE_BLUETOOTH, // expectedRoute
                CallAudioState.ROUTE_BLUETOOTH | CallAudioState.ROUTE_EARPIECE // expectedAvailableR
        ));

        params.add(new TestParameters(
                "Disconnect bluetooth during bluetooth without headset in", // name
                CallAudioState.ROUTE_BLUETOOTH, // initialRoute
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_BLUETOOTH, // availableRoutes
                NONE, // speakerInteraction
                OFF, // bluetoothInteraction
                CallAudioRouteStateMachine.DISCONNECT_BLUETOOTH, // action
                CallAudioState.ROUTE_EARPIECE, // expectedRoute
                CallAudioState.ROUTE_EARPIECE // expectedAvailableRoutes
        ));

        params.add(new TestParameters(
                "Disconnect bluetooth during bluetooth with headset in", // name
                CallAudioState.ROUTE_BLUETOOTH, // initialRoute
                CallAudioState.ROUTE_WIRED_HEADSET | CallAudioState.ROUTE_BLUETOOTH, // availableRou
                NONE, // speakerInteraction
                OFF, // bluetoothInteraction
                CallAudioRouteStateMachine.DISCONNECT_BLUETOOTH, // action
                CallAudioState.ROUTE_WIRED_HEADSET, // expectedRoute
                CallAudioState.ROUTE_WIRED_HEADSET // expectedAvailableRoutes
        ));

        params.add(new TestParameters(
                "Disconnect bluetooth during speakerphone", // name
                CallAudioState.ROUTE_SPEAKER, // initialRoute
                CallAudioState.ROUTE_WIRED_HEADSET | CallAudioState.ROUTE_BLUETOOTH, // availableRou
                NONE, // speakerInteraction
                NONE, // bluetoothInteraction
                CallAudioRouteStateMachine.DISCONNECT_BLUETOOTH, // action
                CallAudioState.ROUTE_SPEAKER, // expectedRoute
                CallAudioState.ROUTE_WIRED_HEADSET // expectedAvailableRoutes
        ));

        params.add(new TestParameters(
                "Disconnect bluetooth during earpiece", // name
                CallAudioState.ROUTE_EARPIECE, // initialRoute
                CallAudioState.ROUTE_EARPIECE| CallAudioState.ROUTE_BLUETOOTH, // availableRoutes
                NONE, // speakerInteraction
                NONE, // bluetoothInteraction
                CallAudioRouteStateMachine.DISCONNECT_BLUETOOTH, // action
                CallAudioState.ROUTE_EARPIECE, // expectedRoute
                CallAudioState.ROUTE_EARPIECE // expectedAvailableRoutes
        ));

        params.add(new TestParameters(
                "Switch to speakerphone from earpiece", // name
                CallAudioState.ROUTE_EARPIECE, // initialRoute
                CallAudioState.ROUTE_EARPIECE, // availableRoutes
                ON, // speakerInteraction
                NONE, // bluetoothInteraction
                CallAudioRouteStateMachine.SWITCH_SPEAKER, // action
                CallAudioState.ROUTE_SPEAKER, // expectedRoute
                CallAudioState.ROUTE_EARPIECE // expectedAvailableRoutes
        ));

        params.add(new TestParameters(
                "Switch to speakerphone from headset", // name
                CallAudioState.ROUTE_WIRED_HEADSET, // initialRoute
                CallAudioState.ROUTE_WIRED_HEADSET, // availableRoutes
                ON, // speakerInteraction
                NONE, // bluetoothInteraction
                CallAudioRouteStateMachine.SWITCH_SPEAKER, // action
                CallAudioState.ROUTE_SPEAKER, // expectedRoute
                CallAudioState.ROUTE_WIRED_HEADSET // expectedAvailableRoutes
        ));

        params.add(new TestParameters(
                "Switch to speakerphone from bluetooth", // name
                CallAudioState.ROUTE_BLUETOOTH, // initialRoute
                CallAudioState.ROUTE_WIRED_HEADSET | CallAudioState.ROUTE_BLUETOOTH, // availableRou
                ON, // speakerInteraction
                OFF, // bluetoothInteraction
                CallAudioRouteStateMachine.SWITCH_SPEAKER, // action
                CallAudioState.ROUTE_SPEAKER, // expectedRoute
                CallAudioState.ROUTE_WIRED_HEADSET | CallAudioState.ROUTE_BLUETOOTH // expectedAvail
        ));

        params.add(new TestParameters(
                "Switch to earpiece from bluetooth", // name
                CallAudioState.ROUTE_BLUETOOTH, // initialRoute
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_BLUETOOTH, // availableRoutes
                NONE, // speakerInteraction
                OFF, // bluetoothInteraction
                CallAudioRouteStateMachine.SWITCH_EARPIECE, // action
                CallAudioState.ROUTE_EARPIECE, // expectedRoute
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_BLUETOOTH // expectedAvailableR
        ));

        params.add(new TestParameters(
                "Switch to earpiece from speakerphone", // name
                CallAudioState.ROUTE_SPEAKER, // initialRoute
                CallAudioState.ROUTE_EARPIECE, // availableRoutes
                OFF, // speakerInteraction
                NONE, // bluetoothInteraction
                CallAudioRouteStateMachine.SWITCH_EARPIECE, // action
                CallAudioState.ROUTE_EARPIECE, // expectedRoute
                CallAudioState.ROUTE_EARPIECE // expectedAvailableRoutes
        ));

        params.add(new TestParameters(
                "Switch to bluetooth from speakerphone", // name
                CallAudioState.ROUTE_SPEAKER, // initialRoute
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_BLUETOOTH, // availableRoutes
                OFF, // speakerInteraction
                ON, // bluetoothInteraction
                CallAudioRouteStateMachine.SWITCH_BLUETOOTH, // action
                CallAudioState.ROUTE_BLUETOOTH, // expectedRoute
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_BLUETOOTH // expectedAvailableR
        ));

        params.add(new TestParameters(
                "Switch to bluetooth from earpiece", // name
                CallAudioState.ROUTE_EARPIECE, // initialRoute
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_BLUETOOTH, // availableRoutes
                NONE, // speakerInteraction
                ON, // bluetoothInteraction
                CallAudioRouteStateMachine.SWITCH_BLUETOOTH, // action
                CallAudioState.ROUTE_BLUETOOTH, // expectedRoute
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_BLUETOOTH // expectedAvailableR
        ));

        params.add(new TestParameters(
                "Switch to bluetooth from wired headset", // name
                CallAudioState.ROUTE_WIRED_HEADSET, // initialRoute
                CallAudioState.ROUTE_WIRED_HEADSET | CallAudioState.ROUTE_BLUETOOTH, // availableRou
                NONE, // speakerInteraction
                ON, // bluetoothInteraction
                CallAudioRouteStateMachine.SWITCH_BLUETOOTH, // action
                CallAudioState.ROUTE_BLUETOOTH, // expectedRoute
                CallAudioState.ROUTE_WIRED_HEADSET | CallAudioState.ROUTE_BLUETOOTH // expectedAvail
        ));

        return params;
    }

    private void runParametrizedTestCaseWithFocus(TestParameters params) {
        resetMocks();

        // Construct a fresh state machine on every case
        CallAudioRouteStateMachine stateMachine = new CallAudioRouteStateMachine(
                mContext,
                mockCallsManager,
                mockBluetoothManager,
                mockWiredHeadsetManager,
                mockStatusBarNotifier,
                mAudioServiceFactory);

        // Set up bluetooth and speakerphone state
        when(mockBluetoothManager.isBluetoothAudioConnectedOrPending()).thenReturn(
                params.initialRoute == CallAudioState.ROUTE_BLUETOOTH);
        when(mockBluetoothManager.isBluetoothAvailable()).thenReturn(
                (params.availableRoutes & CallAudioState.ROUTE_BLUETOOTH) != 0
                        || (params.expectedAvailableRoutes & CallAudioState.ROUTE_BLUETOOTH) != 0);
        when(mockAudioManager.isSpeakerphoneOn()).thenReturn(
                params.initialRoute == CallAudioState.ROUTE_SPEAKER);

        // Set the initial CallAudioState object
        CallAudioState initState = new CallAudioState(false,
                params.initialRoute, (params.availableRoutes | CallAudioState.ROUTE_SPEAKER));
        stateMachine.initialize(initState);
        // Make the state machine have focus so that we actually do something
        stateMachine.sendMessageWithSessionInfo(CallAudioRouteStateMachine.SWITCH_FOCUS,
                CallAudioRouteStateMachine.HAS_FOCUS);
        stateMachine.sendMessageWithSessionInfo(params.action);

        // Verify interactions with the speakerphone and bluetooth systems
        switch(params.bluetoothInteraction) {
            case NONE:
                verify(mockBluetoothManager, never()).disconnectBluetoothAudio();
                verify(mockBluetoothManager, never()).connectBluetoothAudio();
                break;
            case ON:
                verify(mockBluetoothManager, timeout(TEST_TIMEOUT)).connectBluetoothAudio();
                verify(mockBluetoothManager, never()).disconnectBluetoothAudio();
                break;
            case OFF:
                verify(mockBluetoothManager, never()).connectBluetoothAudio();
                verify(mockBluetoothManager, timeout(TEST_TIMEOUT)).disconnectBluetoothAudio();
        }

        switch (params.speakerInteraction) {
            case NONE:
                verify(mockAudioManager, never()).setSpeakerphoneOn(any(Boolean.class));
                break;
            case ON: // fall through
            case OFF:
                verify(mockAudioManager, timeout(TEST_TIMEOUT)).setSpeakerphoneOn(
                        params.speakerInteraction == ON);
        }

        // Verify the end state
        CallAudioState expectedState = new CallAudioState(false, params.expectedRoute,
                params.expectedAvailableRoutes | CallAudioState.ROUTE_SPEAKER);
        verifyNewSystemCallAudioState(initState, expectedState);
    }

    private void runParametrizedTestCaseWithoutFocus(TestParameters params) {
        resetMocks();

        // Construct a fresh state machine on every case
        CallAudioRouteStateMachine stateMachine = new CallAudioRouteStateMachine(
                mContext,
                mockCallsManager,
                mockBluetoothManager,
                mockWiredHeadsetManager,
                mockStatusBarNotifier,
                mAudioServiceFactory);

        // Set up bluetooth and speakerphone state
        when(mockBluetoothManager.isBluetoothAvailable()).thenReturn(
                (params.availableRoutes & CallAudioState.ROUTE_BLUETOOTH) != 0
                || (params.expectedAvailableRoutes & CallAudioState.ROUTE_BLUETOOTH) != 0);
        when(mockAudioManager.isSpeakerphoneOn()).thenReturn(
                params.initialRoute == CallAudioState.ROUTE_SPEAKER);

        // Set the initial CallAudioState object
        CallAudioState initState = new CallAudioState(false,
                params.initialRoute, (params.availableRoutes | CallAudioState.ROUTE_SPEAKER));
        stateMachine.initialize(initState);
        // Omit the focus-getting statement
        stateMachine.sendMessageWithSessionInfo(params.action);
        try {
            Thread.sleep(100L);
        } catch (InterruptedException e) {
            // Just a pause to make sure the state machine handler thread has a chance to update
            // its state. Do nothing.
        }

        // Verify that no substantive interactions have taken place with the rest of the system
        verify(mockBluetoothManager, never()).disconnectBluetoothAudio();
        verify(mockBluetoothManager, never()).connectBluetoothAudio();
        verify(mockAudioManager, never()).setSpeakerphoneOn(any(Boolean.class));
        verify(mockCallsManager, never()).onCallAudioStateChanged(any(CallAudioState.class),
                any(CallAudioState.class));
        verify(mockConnectionServiceWrapper, never()).onCallAudioStateChanged(
                any(Call.class), any(CallAudioState.class));

        // Verify the end state
        CallAudioState expectedState = new CallAudioState(false, params.expectedRoute,
                params.expectedAvailableRoutes | CallAudioState.ROUTE_SPEAKER);
        assertEquals(expectedState, stateMachine.getCurrentCallAudioState());
    }

    private void verifyNewSystemCallAudioState(CallAudioState expectedOldState,
            CallAudioState expectedNewState) {
        ArgumentCaptor<CallAudioState> oldStateCaptor = ArgumentCaptor.forClass(
                CallAudioState.class);
        ArgumentCaptor<CallAudioState> newStateCaptor1 = ArgumentCaptor.forClass(
                CallAudioState.class);
        ArgumentCaptor<CallAudioState> newStateCaptor2 = ArgumentCaptor.forClass(
                CallAudioState.class);
        verify(mockCallsManager, timeout(TEST_TIMEOUT).atLeastOnce()).onCallAudioStateChanged(
                oldStateCaptor.capture(), newStateCaptor1.capture());
        verify(mockConnectionServiceWrapper, timeout(TEST_TIMEOUT).atLeastOnce())
                .onCallAudioStateChanged(same(fakeCall), newStateCaptor2.capture());

        assertTrue(oldStateCaptor.getValue().equals(expectedOldState));
        assertTrue(newStateCaptor1.getValue().equals(expectedNewState));
        assertTrue(newStateCaptor2.getValue().equals(expectedNewState));
    }

    private void resetMocks() {
        reset(mockAudioManager, mockBluetoothManager, mockCallsManager,
                mockConnectionServiceWrapper);
        when(mockCallsManager.getForegroundCall()).thenReturn(fakeCall);
        doNothing().when(mockConnectionServiceWrapper).onCallAudioStateChanged(any(Call.class),
                any(CallAudioState.class));
    }
}