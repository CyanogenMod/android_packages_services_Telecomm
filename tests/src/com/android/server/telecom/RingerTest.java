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

import android.content.Context;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.net.Uri;
import android.os.Vibrator;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class RingerTest extends TelecomTestCase {

    @Mock Ringtone mMockRingtone;
    @Mock Vibrator mMockVibrator;
    @Mock RingtoneFactory mMockRingtoneFactory;
    @Mock SystemSettingsUtil mMockSystemSettings;
    @Mock CallsManager mMockCallsManager;
    @Mock CallAudioManager mMockCallAudioManager;
    @Mock InCallTonePlayer.Factory mMockToneFactory;

    private Ringer mRingerUnderTest;

    // These tests depend on an async handler to execute play() and stop() on the mock ringtone.
    // In order to verify these results, the test must wait an arbitrary amount of time to make sure
    // these methods are called.
    private static final int TEST_TIMEOUT = 100; //milliseconds

    @Override
    public void setUp() throws Exception {
        super.setUp();
        MockitoAnnotations.initMocks(this);
        // Don't involve CallAudioManager logic in this unit test
        doNothing().when(mMockCallAudioManager).setIsRinging(any(Call.class), any(boolean.class));
        // Assume theatre mode is off for these tests
        when(mMockSystemSettings.isTheaterModeOn(any(Context.class))).thenReturn(false);
        // Assume the system is set to enable vibration when ringing
        when(mMockSystemSettings.canVibrateWhenRinging(any(Context.class))).thenReturn(true);
        when(mMockVibrator.hasVibrator()).thenReturn(true);
        mRingerUnderTest = new Ringer(
                mMockCallAudioManager, mMockCallsManager, mMockToneFactory,
                mComponentContextFixture.getTestDouble().getApplicationContext(),
                mMockSystemSettings, new AsyncRingtonePlayer(), mMockRingtoneFactory,
                mMockVibrator);
    }

    @Override
    public void tearDown() throws Exception {
        mRingerUnderTest = null;
        super.tearDown();
    }

    private Call createMockRingingCall() {
        Call mockCall = mock(Call.class);
        when(mockCall.isIncoming()).thenReturn(true);
        when(mockCall.getState()).thenReturn(CallState.RINGING);
        return mockCall;
    }

    public void testPlayRingtoneOnIncomingCall() throws Exception {
        Call mockCall = createMockRingingCall();
        when(mMockRingtoneFactory.getRingtone(any(Uri.class))).thenReturn(mMockRingtone);
        when(mMockCallsManager.getForegroundCall()).thenReturn(mockCall);

        mRingerUnderTest.onCallAdded(mockCall);

        verify(mMockVibrator).vibrate(any(long[].class), any(int.class),
                any(AudioAttributes.class));
        verify(mMockRingtone, timeout(TEST_TIMEOUT)).play();
    }

    public void testCallAnsweredOnIncomingCall() throws Exception {
        Call mockCall = createMockRingingCall();
        when(mMockRingtoneFactory.getRingtone(any(Uri.class))).thenReturn(mMockRingtone);
        when(mMockCallsManager.getForegroundCall()).thenReturn(mockCall);

        // Make sure the ringtone plays for foreground call
        mRingerUnderTest.onCallAdded(mockCall);

        verify(mMockRingtone, timeout(TEST_TIMEOUT)).play();
        verify(mMockVibrator).vibrate(any(long[].class), any(int.class),
                any(AudioAttributes.class));

        // Answer Call
        mRingerUnderTest.onIncomingCallAnswered(mockCall);

        verify(mMockRingtone, timeout(TEST_TIMEOUT)).stop();
        verify(mMockVibrator).cancel();
    }

    public void testCallWaitingOnBackgroundCall() throws Exception {
        Call mockForegroundCall = mock(Call.class);
        Call mockBackgroundCall = createMockRingingCall();
        // Set foreground call to already answered
        when(mMockCallsManager.getForegroundCall()).thenReturn(mockForegroundCall);
        InCallTonePlayer mockInCallTonePlayer = mock(InCallTonePlayer.class);
        when(mMockToneFactory.createPlayer(any(int.class))).thenReturn(mockInCallTonePlayer);

        // Add new call waiting call
        mRingerUnderTest.onCallAdded(mockBackgroundCall);

        verify(mockInCallTonePlayer).startTone();
    }

    public void testCallWaitingOnBackgroundCallDisconnected() throws Exception {
        Call mockForegroundCall = mock(Call.class);
        Call mockBackgroundCall = createMockRingingCall();
        // Set foreground call to already answered
        when(mMockCallsManager.getForegroundCall()).thenReturn(mockForegroundCall);
        InCallTonePlayer mockInCallTonePlayer = mock(InCallTonePlayer.class);
        when(mMockToneFactory.createPlayer(any(int.class))).thenReturn(mockInCallTonePlayer);

        // Add new call waiting call
        mRingerUnderTest.onCallAdded(mockBackgroundCall);

        verify(mockInCallTonePlayer).startTone();

        // Reject the call waiting call
        mRingerUnderTest.onIncomingCallRejected(mockBackgroundCall, false, "");

        verify(mockInCallTonePlayer).stopTone();
    }

    public void testCallDisconnectedWhileRinging() throws Exception {
        Call mockCall = createMockRingingCall();
        when(mMockRingtoneFactory.getRingtone(any(Uri.class))).thenReturn(mMockRingtone);
        when(mMockCallsManager.getForegroundCall()).thenReturn(mockCall);

        // Make sure the ringtone plays for foreground call
        mRingerUnderTest.onCallAdded(mockCall);

        verify(mMockRingtone, timeout(TEST_TIMEOUT)).play();
        verify(mMockVibrator).vibrate(any(long[].class), any(int.class),
                any(AudioAttributes.class));

        // Call Disconnected
        mRingerUnderTest.onCallRemoved(mockCall);

        verify(mMockRingtone, timeout(TEST_TIMEOUT)).stop();
        verify(mMockVibrator).cancel();
    }

    public void testIncomingCallRejected() throws Exception {
        Call mockCall = createMockRingingCall();
        when(mMockRingtoneFactory.getRingtone(any(Uri.class))).thenReturn(mMockRingtone);
        when(mMockCallsManager.getForegroundCall()).thenReturn(mockCall);

        // Make sure the ringtone plays for foreground call
        mRingerUnderTest.onCallAdded(mockCall);

        verify(mMockRingtone, timeout(TEST_TIMEOUT)).play();
        verify(mMockVibrator).vibrate(any(long[].class), any(int.class),
                any(AudioAttributes.class));

        // Answer Call
        mRingerUnderTest.onIncomingCallRejected(mockCall, false, "");

        verify(mMockRingtone, timeout(TEST_TIMEOUT)).stop();
        verify(mMockVibrator).cancel();
    }
}
