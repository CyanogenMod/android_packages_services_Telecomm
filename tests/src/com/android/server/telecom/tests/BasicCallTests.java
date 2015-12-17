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

import com.android.internal.util.IndentingPrintWriter;
import com.android.server.telecom.Analytics;
import com.android.server.telecom.Log;

import android.content.Context;
import android.media.AudioManager;
import android.os.Process;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.Connection;
import android.telecom.DisconnectCause;
import android.telecom.ParcelableCall;
import android.telecom.TelecomManager;
import android.telecom.VideoProfile;

import com.android.internal.telecom.IInCallAdapter;

import java.io.StringWriter;
import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Performs various basic call tests in Telecom.
 */
public class BasicCallTests extends TelecomSystemTest {
    public void testSingleOutgoingCallLocalDisconnect() throws Exception {
        IdPair ids = startAndMakeActiveOutgoingCall("650-555-1212",
                mPhoneAccountA0.getAccountHandle(), mConnectionServiceFixtureA);

        mInCallServiceFixtureX.mInCallAdapter.disconnectCall(ids.mCallId);
        assertEquals(Call.STATE_ACTIVE, mInCallServiceFixtureX.getCall(ids.mCallId).getState());
        assertEquals(Call.STATE_ACTIVE, mInCallServiceFixtureY.getCall(ids.mCallId).getState());

        mConnectionServiceFixtureA.sendSetDisconnected(ids.mConnectionId, DisconnectCause.LOCAL);
        assertEquals(Call.STATE_DISCONNECTED,
                mInCallServiceFixtureX.getCall(ids.mCallId).getState());
        assertEquals(Call.STATE_DISCONNECTED,
                mInCallServiceFixtureY.getCall(ids.mCallId).getState());
    }

    public void testSingleOutgoingCallRemoteDisconnect() throws Exception {
        IdPair ids = startAndMakeActiveOutgoingCall("650-555-1212",
                mPhoneAccountA0.getAccountHandle(), mConnectionServiceFixtureA);

        mConnectionServiceFixtureA.sendSetDisconnected(ids.mConnectionId, DisconnectCause.LOCAL);
        assertEquals(Call.STATE_DISCONNECTED,
                mInCallServiceFixtureX.getCall(ids.mCallId).getState());
        assertEquals(Call.STATE_DISCONNECTED,
                mInCallServiceFixtureY.getCall(ids.mCallId).getState());
    }

    /**
     * Tests the {@link TelecomManager#acceptRingingCall()} API.  Tests simple case of an incoming
     * audio-only call.
     *
     * @throws Exception
     */
    public void testTelecomManagerAcceptRingingCall() throws Exception {
        IdPair ids = startIncomingPhoneCall("650-555-1212", mPhoneAccountA0.getAccountHandle(),
                mConnectionServiceFixtureA);

        assertEquals(Call.STATE_RINGING, mInCallServiceFixtureX.getCall(ids.mCallId).getState());
        assertEquals(Call.STATE_RINGING, mInCallServiceFixtureY.getCall(ids.mCallId).getState());

        // Use TelecomManager API to answer the ringing call.
        TelecomManager telecomManager = (TelecomManager) mComponentContextFixture.getTestDouble()
                .getApplicationContext().getSystemService(Context.TELECOM_SERVICE);
        telecomManager.acceptRingingCall();

        verify(mConnectionServiceFixtureA.getTestDouble(), timeout(TEST_TIMEOUT))
                .answer(ids.mCallId);
        mConnectionServiceFixtureA.sendSetActive(ids.mConnectionId);

        mInCallServiceFixtureX.mInCallAdapter.disconnectCall(ids.mCallId);
    }

    /**
     * Tests the {@link TelecomManager#acceptRingingCall()} API.  Tests simple case of an incoming
     * video call, which should be answered as video.
     *
     * @throws Exception
     */
    public void testTelecomManagerAcceptRingingVideoCall() throws Exception {
        IdPair ids = startIncomingPhoneCall("650-555-1212", mPhoneAccountA0.getAccountHandle(),
                VideoProfile.STATE_BIDIRECTIONAL, mConnectionServiceFixtureA);

        assertEquals(Call.STATE_RINGING, mInCallServiceFixtureX.getCall(ids.mCallId).getState());
        assertEquals(Call.STATE_RINGING, mInCallServiceFixtureY.getCall(ids.mCallId).getState());

        // Use TelecomManager API to answer the ringing call; the default expected behavior is to
        // answer using whatever video state the ringing call requests.
        TelecomManager telecomManager = (TelecomManager) mComponentContextFixture.getTestDouble()
                .getApplicationContext().getSystemService(Context.TELECOM_SERVICE);
        telecomManager.acceptRingingCall();

        // Answer video API should be called
        verify(mConnectionServiceFixtureA.getTestDouble(), timeout(TEST_TIMEOUT))
                .answerVideo(eq(ids.mCallId), eq(VideoProfile.STATE_BIDIRECTIONAL));
        mConnectionServiceFixtureA.sendSetActive(ids.mConnectionId);

        mInCallServiceFixtureX.mInCallAdapter.disconnectCall(ids.mCallId);
    }

    /**
     * Tests the {@link TelecomManager#acceptRingingCall(int)} API.  Tests answering a video call
     * as an audio call.
     *
     * @throws Exception
     */
    public void testTelecomManagerAcceptRingingVideoCallAsAudio() throws Exception {
        IdPair ids = startIncomingPhoneCall("650-555-1212", mPhoneAccountA0.getAccountHandle(),
                VideoProfile.STATE_BIDIRECTIONAL, mConnectionServiceFixtureA);

        assertEquals(Call.STATE_RINGING, mInCallServiceFixtureX.getCall(ids.mCallId).getState());
        assertEquals(Call.STATE_RINGING, mInCallServiceFixtureY.getCall(ids.mCallId).getState());

        // Use TelecomManager API to answer the ringing call.
        TelecomManager telecomManager = (TelecomManager) mComponentContextFixture.getTestDouble()
                .getApplicationContext().getSystemService(Context.TELECOM_SERVICE);
        telecomManager.acceptRingingCall(VideoProfile.STATE_AUDIO_ONLY);

        // The generic answer method on the ConnectionService is used to answer audio-only calls.
        verify(mConnectionServiceFixtureA.getTestDouble(), timeout(TEST_TIMEOUT))
                .answer(eq(ids.mCallId));
        mConnectionServiceFixtureA.sendSetActive(ids.mConnectionId);

        mInCallServiceFixtureX.mInCallAdapter.disconnectCall(ids.mCallId);
    }

    /**
     * Tests the {@link TelecomManager#acceptRingingCall()} API.  Tests simple case of an incoming
     * video call, where an attempt is made to answer with an invalid video state.
     *
     * @throws Exception
     */
    public void testTelecomManagerAcceptRingingInvalidVideoState() throws Exception {
        IdPair ids = startIncomingPhoneCall("650-555-1212", mPhoneAccountA0.getAccountHandle(),
                VideoProfile.STATE_BIDIRECTIONAL, mConnectionServiceFixtureA);

        assertEquals(Call.STATE_RINGING, mInCallServiceFixtureX.getCall(ids.mCallId).getState());
        assertEquals(Call.STATE_RINGING, mInCallServiceFixtureY.getCall(ids.mCallId).getState());

        // Use TelecomManager API to answer the ringing call; the default expected behavior is to
        // answer using whatever video state the ringing call requests.
        TelecomManager telecomManager = (TelecomManager) mComponentContextFixture.getTestDouble()
                .getApplicationContext().getSystemService(Context.TELECOM_SERVICE);
        telecomManager.acceptRingingCall(999 /* invalid videostate */);

        // Answer video API should be called
        verify(mConnectionServiceFixtureA.getTestDouble(), timeout(TEST_TIMEOUT))
                .answerVideo(eq(ids.mCallId), eq(VideoProfile.STATE_BIDIRECTIONAL));
        mConnectionServiceFixtureA.sendSetActive(ids.mConnectionId);
        mInCallServiceFixtureX.mInCallAdapter.disconnectCall(ids.mCallId);
    }

    public void testSingleIncomingCallLocalDisconnect() throws Exception {
        IdPair ids = startAndMakeActiveIncomingCall("650-555-1212",
                mPhoneAccountA0.getAccountHandle(), mConnectionServiceFixtureA);
        mInCallServiceFixtureX.mInCallAdapter.disconnectCall(ids.mCallId);
        assertEquals(Call.STATE_ACTIVE, mInCallServiceFixtureX.getCall(ids.mCallId).getState());
        assertEquals(Call.STATE_ACTIVE, mInCallServiceFixtureY.getCall(ids.mCallId).getState());

        mConnectionServiceFixtureA.sendSetDisconnected(ids.mConnectionId, DisconnectCause.LOCAL);
        assertEquals(Call.STATE_DISCONNECTED,
                mInCallServiceFixtureX.getCall(ids.mCallId).getState());
        assertEquals(Call.STATE_DISCONNECTED,
                mInCallServiceFixtureY.getCall(ids.mCallId).getState());
    }

    public void testSingleIncomingCallRemoteDisconnect() throws Exception {
        IdPair ids = startAndMakeActiveIncomingCall("650-555-1212",
                mPhoneAccountA0.getAccountHandle(), mConnectionServiceFixtureA);

        mConnectionServiceFixtureA.sendSetDisconnected(ids.mConnectionId, DisconnectCause.LOCAL);
        assertEquals(Call.STATE_DISCONNECTED,
                mInCallServiceFixtureX.getCall(ids.mCallId).getState());
        assertEquals(Call.STATE_DISCONNECTED,
                mInCallServiceFixtureY.getCall(ids.mCallId).getState());
    }

    public void do_testDeadlockOnOutgoingCall() throws Exception {
        final IdPair ids = startOutgoingPhoneCall("650-555-1212",
                mPhoneAccountA0.getAccountHandle(), mConnectionServiceFixtureA,
                Process.myUserHandle());
        rapidFire(
                new Runnable() {
                    @Override
                    public void run() {
                        while (mCallerInfoAsyncQueryFactoryFixture.mRequests.size() > 0) {
                            mCallerInfoAsyncQueryFactoryFixture.mRequests.remove(0).reply();
                        }
                    }
                },
                new Runnable() {
                    @Override
                    public void run() {
                        try {
                            mConnectionServiceFixtureA.sendSetActive(ids.mConnectionId);
                        } catch (Exception e) {
                            Log.e(this, e, "");
                        }
                    }
                });
    }

    public void testDeadlockOnOutgoingCall() throws Exception {
        for (int i = 0; i < 100; i++) {
            BasicCallTests test = new BasicCallTests();
            test.setContext(getContext());
            test.setTestContext(getTestContext());
            test.setName(getName());
            test.setUp();
            test.do_testDeadlockOnOutgoingCall();
            test.tearDown();
        }
    }

    public void testIncomingThenOutgoingCalls() throws Exception {
        // TODO: We have to use the same PhoneAccount for both; see http://b/18461539
        IdPair incoming = startAndMakeActiveIncomingCall("650-555-2323",
                mPhoneAccountA0.getAccountHandle(), mConnectionServiceFixtureA);
        IdPair outgoing = startAndMakeActiveOutgoingCall("650-555-1212",
                mPhoneAccountA0.getAccountHandle(),                mConnectionServiceFixtureA);

        mInCallServiceFixtureX.mInCallAdapter.disconnectCall(incoming.mCallId);
        mInCallServiceFixtureX.mInCallAdapter.disconnectCall(outgoing.mCallId);
    }

    public void testOutgoingThenIncomingCalls() throws Exception {
        // TODO: We have to use the same PhoneAccount for both; see http://b/18461539
        IdPair outgoing = startAndMakeActiveOutgoingCall("650-555-1212",
                mPhoneAccountA0.getAccountHandle(), mConnectionServiceFixtureA);
        IdPair incoming = startAndMakeActiveIncomingCall("650-555-2323",
                mPhoneAccountA0.getAccountHandle(), mConnectionServiceFixtureA);
        verify(mConnectionServiceFixtureA.getTestDouble())
                .hold(outgoing.mConnectionId);
        mConnectionServiceFixtureA.mConnectionById.get(outgoing.mConnectionId).state =
                Connection.STATE_HOLDING;
        mConnectionServiceFixtureA.sendSetOnHold(outgoing.mConnectionId);
        assertEquals(Call.STATE_HOLDING,
                mInCallServiceFixtureX.getCall(outgoing.mCallId).getState());
        assertEquals(Call.STATE_HOLDING,
                mInCallServiceFixtureY.getCall(outgoing.mCallId).getState());

        mInCallServiceFixtureX.mInCallAdapter.disconnectCall(incoming.mCallId);
        mInCallServiceFixtureX.mInCallAdapter.disconnectCall(outgoing.mCallId);
    }

    public void testAudioManagerOperations() throws Exception {
        AudioManager audioManager = (AudioManager) mComponentContextFixture.getTestDouble()
                .getApplicationContext().getSystemService(Context.AUDIO_SERVICE);

        IdPair outgoing = startAndMakeActiveOutgoingCall("650-555-1212",
                mPhoneAccountA0.getAccountHandle(), mConnectionServiceFixtureA);

        verify(audioManager, timeout(TEST_TIMEOUT)).requestAudioFocusForCall(anyInt(), anyInt());
        verify(audioManager, timeout(TEST_TIMEOUT).atLeastOnce())
                .setMode(AudioManager.MODE_IN_CALL);

        mInCallServiceFixtureX.mInCallAdapter.mute(true);
        verify(mAudioService, timeout(TEST_TIMEOUT))
                .setMicrophoneMute(eq(true), any(String.class), any(Integer.class));
        mInCallServiceFixtureX.mInCallAdapter.mute(false);
        verify(mAudioService, timeout(TEST_TIMEOUT))
                .setMicrophoneMute(eq(false), any(String.class), any(Integer.class));

        mInCallServiceFixtureX.mInCallAdapter.setAudioRoute(CallAudioState.ROUTE_SPEAKER);
        verify(audioManager, timeout(TEST_TIMEOUT))
                .setSpeakerphoneOn(true);
        mInCallServiceFixtureX.mInCallAdapter.setAudioRoute(CallAudioState.ROUTE_EARPIECE);
        verify(audioManager, timeout(TEST_TIMEOUT))
                .setSpeakerphoneOn(false);

        mConnectionServiceFixtureA.
                sendSetDisconnected(outgoing.mConnectionId, DisconnectCause.REMOTE);

        verify(audioManager, timeout(TEST_TIMEOUT))
                .abandonAudioFocusForCall();
        verify(audioManager, timeout(TEST_TIMEOUT).atLeastOnce())
                .setMode(AudioManager.MODE_NORMAL);
    }

    private void rapidFire(Runnable... tasks) {
        final CyclicBarrier barrier = new CyclicBarrier(tasks.length);
        final CountDownLatch latch = new CountDownLatch(tasks.length);
        for (int i = 0; i < tasks.length; i++) {
            final Runnable task = tasks[i];
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        barrier.await();
                        task.run();
                    } catch (InterruptedException | BrokenBarrierException e){
                        Log.e(BasicCallTests.this, e, "Unexpectedly interrupted");
                    } finally {
                        latch.countDown();
                    }
                }
            }).start();
        }
        try {
            latch.await();
        } catch (InterruptedException e) {
            Log.e(BasicCallTests.this, e, "Unexpectedly interrupted");
        }
    }

        public void testBasicConferenceCall() throws Exception {
        makeConferenceCall();
    }

    public void testAddCallToConference1() throws Exception {
        ParcelableCall conferenceCall = makeConferenceCall();
        IdPair callId3 = startAndMakeActiveOutgoingCall("650-555-1214",
                mPhoneAccountA0.getAccountHandle(), mConnectionServiceFixtureA);
        // testAddCallToConference{1,2} differ in the order of arguments to InCallAdapter#conference
        mInCallServiceFixtureX.getInCallAdapter().conference(
                conferenceCall.getId(), callId3.mCallId);
        Thread.sleep(200);

        ParcelableCall call3 = mInCallServiceFixtureX.getCall(callId3.mCallId);
        ParcelableCall updatedConference = mInCallServiceFixtureX.getCall(conferenceCall.getId());
        assertEquals(conferenceCall.getId(), call3.getParentCallId());
        assertEquals(3, updatedConference.getChildCallIds().size());
        assertTrue(updatedConference.getChildCallIds().contains(callId3.mCallId));
    }

    public void testAddCallToConference2() throws Exception {
        ParcelableCall conferenceCall = makeConferenceCall();
        IdPair callId3 = startAndMakeActiveOutgoingCall("650-555-1214",
                mPhoneAccountA0.getAccountHandle(), mConnectionServiceFixtureA);
        mInCallServiceFixtureX.getInCallAdapter()
                .conference(callId3.mCallId, conferenceCall.getId());
        Thread.sleep(200);

        ParcelableCall call3 = mInCallServiceFixtureX.getCall(callId3.mCallId);
        ParcelableCall updatedConference = mInCallServiceFixtureX.getCall(conferenceCall.getId());
        assertEquals(conferenceCall.getId(), call3.getParentCallId());
        assertEquals(3, updatedConference.getChildCallIds().size());
        assertTrue(updatedConference.getChildCallIds().contains(callId3.mCallId));
    }

    private ParcelableCall makeConferenceCall() throws Exception {
        IdPair callId1 = startAndMakeActiveOutgoingCall("650-555-1212",
                mPhoneAccountA0.getAccountHandle(), mConnectionServiceFixtureA);

        IdPair callId2 = startAndMakeActiveOutgoingCall("650-555-1213",
                mPhoneAccountA0.getAccountHandle(), mConnectionServiceFixtureA);

        IInCallAdapter inCallAdapter = mInCallServiceFixtureX.getInCallAdapter();
        inCallAdapter.conference(callId1.mCallId, callId2.mCallId);
        // Wait for wacky non-deterministic behavior
        Thread.sleep(200);
        ParcelableCall call1 = mInCallServiceFixtureX.getCall(callId1.mCallId);
        ParcelableCall call2 = mInCallServiceFixtureX.getCall(callId2.mCallId);
        // Check that the two calls end up with a parent in the end
        assertNotNull(call1.getParentCallId());
        assertNotNull(call2.getParentCallId());
        assertEquals(call1.getParentCallId(), call2.getParentCallId());

        // Check to make sure that the parent call made it to the in-call service
        String parentCallId = call1.getParentCallId();
        ParcelableCall conferenceCall = mInCallServiceFixtureX.getCall(parentCallId);
        assertEquals(2, conferenceCall.getChildCallIds().size());
        assertTrue(conferenceCall.getChildCallIds().contains(callId1.mCallId));
        assertTrue(conferenceCall.getChildCallIds().contains(callId2.mCallId));
        return conferenceCall;
    }

    public void testAnalyticsSingleCall() throws Exception {
        IdPair testCall = startAndMakeActiveIncomingCall(
                "650-555-1212",
                mPhoneAccountA0.getAccountHandle(),
                mConnectionServiceFixtureA);
        Map<String, Analytics.CallInfoImpl> analyticsMap = Analytics.cloneData();

        assertTrue(analyticsMap.containsKey(testCall.mCallId));

        Analytics.CallInfoImpl callAnalytics = analyticsMap.get(testCall.mCallId);
        assertTrue(callAnalytics.startTime > 0);
        assertEquals(0, callAnalytics.endTime);
        assertEquals(Analytics.INCOMING_DIRECTION, callAnalytics.callDirection);
        assertFalse(callAnalytics.isInterrupted);
        assertNull(callAnalytics.callTerminationReason);
        assertEquals(mConnectionServiceComponentNameA.flattenToShortString(),
                callAnalytics.connectionService);

        mConnectionServiceFixtureA.
                sendSetDisconnected(testCall.mConnectionId, DisconnectCause.ERROR);

        analyticsMap = Analytics.cloneData();
        callAnalytics = analyticsMap.get(testCall.mCallId);
        assertTrue(callAnalytics.endTime > 0);
        assertNotNull(callAnalytics.callTerminationReason);
        assertEquals(DisconnectCause.ERROR, callAnalytics.callTerminationReason.getCode());

        StringWriter sr = new StringWriter();
        IndentingPrintWriter ip = new IndentingPrintWriter(sr, "    ");
        Analytics.dump(ip);
        String dumpResult = sr.toString();
        String[] expectedFields = {"startTime", "endTime", "direction", "isAdditionalCall",
                "isInterrupted", "callTechnologies", "callTerminationReason", "connectionServices"};
        for (String field : expectedFields) {
            assertTrue(dumpResult.contains(field));
        }
    }

    public void testAnalyticsTwoCalls() throws Exception {
        IdPair testCall1 = startAndMakeActiveIncomingCall(
                "650-555-1212",
                mPhoneAccountA0.getAccountHandle(),
                mConnectionServiceFixtureA);
        IdPair testCall2 = startAndMakeActiveOutgoingCall(
                "650-555-1213",
                mPhoneAccountA0.getAccountHandle(),
                mConnectionServiceFixtureA);

        Map<String, Analytics.CallInfoImpl> analyticsMap = Analytics.cloneData();
        assertTrue(analyticsMap.containsKey(testCall1.mCallId));
        assertTrue(analyticsMap.containsKey(testCall2.mCallId));

        Analytics.CallInfoImpl callAnalytics1 = analyticsMap.get(testCall1.mCallId);
        Analytics.CallInfoImpl callAnalytics2 = analyticsMap.get(testCall2.mCallId);
        assertTrue(callAnalytics1.startTime > 0);
        assertTrue(callAnalytics2.startTime > 0);
        assertEquals(0, callAnalytics1.endTime);
        assertEquals(0, callAnalytics2.endTime);

        assertEquals(Analytics.INCOMING_DIRECTION, callAnalytics1.callDirection);
        assertEquals(Analytics.OUTGOING_DIRECTION, callAnalytics2.callDirection);

        assertTrue(callAnalytics1.isInterrupted);
        assertTrue(callAnalytics2.isAdditionalCall);

        assertNull(callAnalytics1.callTerminationReason);
        assertNull(callAnalytics2.callTerminationReason);

        assertEquals(mConnectionServiceComponentNameA.flattenToShortString(),
                callAnalytics1.connectionService);
        assertEquals(mConnectionServiceComponentNameA.flattenToShortString(),
                callAnalytics1.connectionService);

        mConnectionServiceFixtureA.
                sendSetDisconnected(testCall2.mConnectionId, DisconnectCause.REMOTE);
        mConnectionServiceFixtureA.
                sendSetDisconnected(testCall1.mConnectionId, DisconnectCause.ERROR);

        analyticsMap = Analytics.cloneData();
        callAnalytics1 = analyticsMap.get(testCall1.mCallId);
        callAnalytics2 = analyticsMap.get(testCall2.mCallId);
        assertTrue(callAnalytics1.endTime > 0);
        assertTrue(callAnalytics2.endTime > 0);
        assertNotNull(callAnalytics1.callTerminationReason);
        assertNotNull(callAnalytics2.callTerminationReason);
        assertEquals(DisconnectCause.ERROR, callAnalytics1.callTerminationReason.getCode());
        assertEquals(DisconnectCause.REMOTE, callAnalytics2.callTerminationReason.getCode());
    }
}
