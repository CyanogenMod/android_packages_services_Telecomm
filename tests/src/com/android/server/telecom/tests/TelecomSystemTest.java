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
 * limitations under the License.
 */

package com.android.server.telecom.tests;

import com.android.internal.telecom.IInCallAdapter;
import com.android.internal.telephony.CallerInfo;
import com.android.internal.telephony.CallerInfoAsyncQuery;
import com.android.server.telecom.CallerInfoAsyncQueryFactory;
import com.android.server.telecom.CallsManager;
import com.android.server.telecom.HeadsetMediaButton;
import com.android.server.telecom.HeadsetMediaButtonFactory;
import com.android.server.telecom.InCallWakeLockController;
import com.android.server.telecom.InCallWakeLockControllerFactory;
import com.android.server.telecom.Log;
import com.android.server.telecom.MissedCallNotifier;
import com.android.server.telecom.ProximitySensorManager;
import com.android.server.telecom.ProximitySensorManagerFactory;
import com.android.server.telecom.TelecomSystem;

import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.UserHandle;
import android.telecom.CallState;
import android.telecom.ConnectionRequest;
import android.telecom.DisconnectCause;
import android.telecom.ParcelableCall;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;

import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;

import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TelecomSystemTest extends TelecomTestCase {

    static final int TEST_TIMEOUT = 1000;  // milliseconds

    @Mock MissedCallNotifier mMissedCallNotifier;
    @Mock HeadsetMediaButton mHeadsetMediaButton;
    @Mock ProximitySensorManager mProximitySensorManager;
    @Mock InCallWakeLockController mInCallWakeLockController;

    final ComponentName mInCallServiceComponentNameX =
            new ComponentName(
                    "incall-service-package-X",
                    "incall-service-class-X");
    final ComponentName mInCallServiceComponentNameY =
            new ComponentName(
                    "incall-service-package-Y",
                    "incall-service-class-Y");

    InCallServiceFixture mInCallServiceFixtureX;
    InCallServiceFixture mInCallServiceFixtureY;

    final ComponentName mConnectionServiceComponentNameA =
            new ComponentName(
                    "connection-service-package-A",
                    "connection-service-class-A");
    final ComponentName mConnectionServiceComponentNameB =
            new ComponentName(
                    "connection-service-package-B",
                    "connection-service-class-B");

    final PhoneAccount mPhoneAccountA0 =
            PhoneAccount.builder(
                    new PhoneAccountHandle(
                            mConnectionServiceComponentNameA,
                            "id A 0"),
                    "Phone account service A ID 0")
                    .addSupportedUriScheme("tel")
                    .setCapabilities(
                            PhoneAccount.CAPABILITY_CALL_PROVIDER |
                            PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)
                    .build();
    final PhoneAccount mPhoneAccountA1 =
            PhoneAccount.builder(
                    new PhoneAccountHandle(
                            mConnectionServiceComponentNameA,
                            "id A 1"),
                    "Phone account service A ID 1")
                    .addSupportedUriScheme("tel")
                    .setCapabilities(
                            PhoneAccount.CAPABILITY_CALL_PROVIDER |
                            PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)
                    .build();
    final PhoneAccount mPhoneAccountB0 =
            PhoneAccount.builder(
                    new PhoneAccountHandle(
                            mConnectionServiceComponentNameA,
                            "id B 0"),
                    "Phone account service B ID 0")
                    .addSupportedUriScheme("tel")
                    .setCapabilities(
                            PhoneAccount.CAPABILITY_CALL_PROVIDER |
                            PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)
                    .build();

    ConnectionServiceFixture mConnectionServiceFixtureA;
    ConnectionServiceFixture mConnectionServiceFixtureB;

    CallerInfoAsyncQueryFactoryFixture mCallerInfoAsyncQueryFactoryFixture;

    TelecomSystem mTelecomSystem;

    @Override
    public void setUp() throws Exception {
        super.setUp();

        // First set up information about the In-Call services in the mock Context, since
        // Telecom will search for these as soon as it is instantiated
        setupInCallServices();

        // Next, create the TelecomSystem, our system under test
        setupTelecomSystem();

        // Finally, register the ConnectionServices with the PhoneAccountRegistrar of the
        // now-running TelecomSystem
        setupConnectionServices();
    }

    @Override
    public void tearDown() throws Exception {
        mTelecomSystem = null;
        super.tearDown();
    }

    private void setupTelecomSystem() throws Exception {
        HeadsetMediaButtonFactory headsetMediaButtonFactory =
                mock(HeadsetMediaButtonFactory.class);
        ProximitySensorManagerFactory proximitySensorManagerFactory =
                mock(ProximitySensorManagerFactory.class);
        InCallWakeLockControllerFactory inCallWakeLockControllerFactory =
                mock(InCallWakeLockControllerFactory.class);

        mCallerInfoAsyncQueryFactoryFixture = new CallerInfoAsyncQueryFactoryFixture();

        when(headsetMediaButtonFactory.create(
                any(Context.class),
                any(CallsManager.class)))
                .thenReturn(mHeadsetMediaButton);
        when(proximitySensorManagerFactory.create(
                any(Context.class),
                any(CallsManager.class)))
                .thenReturn(mProximitySensorManager);
        when(inCallWakeLockControllerFactory.create(
                any(Context.class),
                any(CallsManager.class)))
                .thenReturn(mInCallWakeLockController);

        mTelecomSystem = new TelecomSystem(
                mComponentContextFixture.getTestDouble(),
                mMissedCallNotifier,
                mCallerInfoAsyncQueryFactoryFixture.getTestDouble(),
                headsetMediaButtonFactory,
                proximitySensorManagerFactory,
                inCallWakeLockControllerFactory);

        verify(headsetMediaButtonFactory).create(
                eq(mComponentContextFixture.getTestDouble().getApplicationContext()),
                any(CallsManager.class));
        verify(proximitySensorManagerFactory).create(
                eq(mComponentContextFixture.getTestDouble().getApplicationContext()),
                any(CallsManager.class));
        verify(inCallWakeLockControllerFactory).create(
                eq(mComponentContextFixture.getTestDouble().getApplicationContext()),
                any(CallsManager.class));
    }

    private void setupConnectionServices() throws Exception {
        mConnectionServiceFixtureA = new ConnectionServiceFixture();
        mConnectionServiceFixtureB = new ConnectionServiceFixture();

        mComponentContextFixture.addConnectionService(
                mConnectionServiceComponentNameA,
                mConnectionServiceFixtureA.getTestDouble());
        mComponentContextFixture.addConnectionService(
                mConnectionServiceComponentNameB,
                mConnectionServiceFixtureB.getTestDouble());

        mTelecomSystem.getPhoneAccountRegistrar().registerPhoneAccount(mPhoneAccountA0);
        mTelecomSystem.getPhoneAccountRegistrar().registerPhoneAccount(mPhoneAccountA1);
        mTelecomSystem.getPhoneAccountRegistrar().registerPhoneAccount(mPhoneAccountB0);
    }

    private void setupInCallServices() throws Exception {
        mComponentContextFixture.putResource(
                com.android.server.telecom.R.string.ui_default_package,
                mInCallServiceComponentNameX.getPackageName());
        mComponentContextFixture.putResource(
                com.android.server.telecom.R.string.incall_default_class,
                mInCallServiceComponentNameX.getClassName());

        mInCallServiceFixtureX = new InCallServiceFixture();
        mInCallServiceFixtureY = new InCallServiceFixture();

        mComponentContextFixture.addInCallService(
                mInCallServiceComponentNameX,
                mInCallServiceFixtureX.getTestDouble());
        mComponentContextFixture.addInCallService(
                mInCallServiceComponentNameY,
                mInCallServiceFixtureY.getTestDouble());
    }

    private String startOutgoingPhoneCall(
            String number,
            PhoneAccountHandle phoneAccountHandle,
            ConnectionServiceFixture connectionServiceFixture) throws Exception {
        Intent actionCallIntent = new Intent();
        actionCallIntent.setData(Uri.parse("tel:" + number));
        actionCallIntent.putExtra(Intent.EXTRA_PHONE_NUMBER, number);
        actionCallIntent.setAction(Intent.ACTION_CALL);
        if (phoneAccountHandle != null) {
            actionCallIntent.putExtra(
                    TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE,
                    phoneAccountHandle);
        }

        mTelecomSystem.getCallIntentProcessor().processIntent(actionCallIntent);

        ArgumentCaptor<Intent> newOutgoingCallIntent =
                ArgumentCaptor.forClass(Intent.class);
        ArgumentCaptor<BroadcastReceiver> newOutgoingCallReceiver =
                ArgumentCaptor.forClass(BroadcastReceiver.class);

        verify(mComponentContextFixture.getTestDouble().getApplicationContext())
                .sendOrderedBroadcastAsUser(
                        newOutgoingCallIntent.capture(),
                        any(UserHandle.class),
                        anyString(),
                        newOutgoingCallReceiver.capture(),
                        any(Handler.class),
                        anyInt(),
                        anyString(),
                        any(Bundle.class));

        assertNotNull(mInCallServiceFixtureX.mInCallAdapter);
        assertNotNull(mInCallServiceFixtureY.mInCallAdapter);

        // Pass on the new outgoing call Intent
        // Set a dummy PendingResult so the BroadcastReceiver agrees to accept onReceive()
        newOutgoingCallReceiver.getValue().setPendingResult(
                new BroadcastReceiver.PendingResult(0, "", null, 0, true, false, null, 0));
        newOutgoingCallReceiver.getValue().setResultData(
                newOutgoingCallIntent.getValue().getStringExtra(Intent.EXTRA_PHONE_NUMBER));
        newOutgoingCallReceiver.getValue().onReceive(
                mComponentContextFixture.getTestDouble(),
                newOutgoingCallIntent.getValue());

        verify(connectionServiceFixture.getTestDouble()).createConnection(
                eq(phoneAccountHandle),
                anyString(),
                any(ConnectionRequest.class),
                anyBoolean(),
                anyBoolean());

        String id = connectionServiceFixture.mLatestConnectionId;

        connectionServiceFixture.sendHandleCreateConnectionComplete(id);

        return id;
    }

    private String startIncomingPhoneCall(
            String number,
            PhoneAccountHandle phoneAccountHandle,
            ConnectionServiceFixture connectionServiceFixture) throws Exception {
        Bundle extras = new Bundle();
        extras.putParcelable(
                TelephonyManager.EXTRA_INCOMING_NUMBER,
                Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null));
        mTelecomSystem.getTelecomServiceImpl().getBinder()
                .addNewIncomingCall(phoneAccountHandle, extras);

        verify(connectionServiceFixture.getTestDouble()).createConnection(
                any(PhoneAccountHandle.class),
                anyString(),
                any(ConnectionRequest.class),
                eq(true),
                eq(false));

        String id = connectionServiceFixture.mLatestConnectionId;

        connectionServiceFixture.sendHandleCreateConnectionComplete(id);
        connectionServiceFixture.sendSetRinging(id);

        // For the case of incoming calls, Telecom connecting the InCall services and adding the
        // Call is triggered by the async completion of the CallerInfoAsyncQuery. Once the Call
        // is added, future interactions as triggered by the ConnectionService, through the various
        // test fixtures, will be synchronous.

        verify(
                mInCallServiceFixtureX.getTestDouble(),
                timeout(TEST_TIMEOUT))
                .setInCallAdapter(
                        any(IInCallAdapter.class));
        verify(
                mInCallServiceFixtureY.getTestDouble(),
                timeout(TEST_TIMEOUT))
                .setInCallAdapter(
                        any(IInCallAdapter.class));

        assertNotNull(mInCallServiceFixtureX.mInCallAdapter);
        assertNotNull(mInCallServiceFixtureY.mInCallAdapter);

        verify(
                mInCallServiceFixtureX.getTestDouble(),
                timeout(TEST_TIMEOUT))
                .addCall(
                        any(ParcelableCall.class));
        verify(
                mInCallServiceFixtureY.getTestDouble(),
                timeout(TEST_TIMEOUT))
                .addCall(
                        any(ParcelableCall.class));

        return id;
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
                        Log.e(TelecomSystemTest.this, e, "Unexpectedly interrupted");
                    } finally {
                        latch.countDown();
                    }
                }
            }).start();
        }
        try {
            latch.await();
        } catch (InterruptedException e) {
            Log.e(TelecomSystemTest.this, e, "Unexpectedly interrupted");
        }
    }

    // A simple outgoing call, verifying that the appropriate connection service is contacted,
    // the proper lifecycle is followed, and both In-Call Services are updated correctly.
    public void testSingleOutgoingCall() throws Exception {
        String connectionId = startOutgoingPhoneCall(
                "650-555-1212",
                mPhoneAccountA0.getAccountHandle(),
                mConnectionServiceFixtureA);

        assertEquals(1, mConnectionServiceFixtureA.mConnectionServiceAdapters.size());
        assertEquals(1, mConnectionServiceFixtureA.mConnectionById.size());

        mConnectionServiceFixtureA.sendSetDialing(connectionId);

        assertEquals(1, mInCallServiceFixtureX.mCallById.size());
        String callId = mInCallServiceFixtureX.mLatestCallId;

        assertEquals(CallState.DIALING, mInCallServiceFixtureX.getCall(callId).getState());
        assertEquals(CallState.DIALING, mInCallServiceFixtureY.getCall(callId).getState());

        mConnectionServiceFixtureA.sendSetActive(connectionId);
        assertEquals(CallState.ACTIVE, mInCallServiceFixtureX.getCall(callId).getState());
        assertEquals(CallState.ACTIVE, mInCallServiceFixtureY.getCall(callId).getState());

        mInCallServiceFixtureX.mInCallAdapter.disconnectCall(callId);;
        assertEquals(CallState.ACTIVE, mInCallServiceFixtureX.getCall(callId).getState());
        assertEquals(CallState.ACTIVE, mInCallServiceFixtureY.getCall(callId).getState());

        mConnectionServiceFixtureA.sendSetDisconnected(connectionId, DisconnectCause.LOCAL);
        assertEquals(CallState.DISCONNECTED, mInCallServiceFixtureX.getCall(callId).getState());
        assertEquals(CallState.DISCONNECTED, mInCallServiceFixtureY.getCall(callId).getState());
    }

    // A simple incoming call, similar in scope to the previous test
    public void testSingleIncomingCall() throws Exception {
        String connectionId = startIncomingPhoneCall(
                "650-555-1212",
                mPhoneAccountA0.getAccountHandle(),
                mConnectionServiceFixtureA);

        assertEquals(1, mConnectionServiceFixtureA.mConnectionServiceAdapters.size());
        assertEquals(1, mConnectionServiceFixtureA.mConnectionById.size());

        assertEquals(1, mInCallServiceFixtureX.mCallById.size());
        String callId = mInCallServiceFixtureX.mLatestCallId;

        assertEquals(CallState.RINGING, mInCallServiceFixtureX.getCall(callId).getState());
        assertEquals(CallState.RINGING, mInCallServiceFixtureY.getCall(callId).getState());

        mConnectionServiceFixtureA.sendSetActive(connectionId);
        assertEquals(CallState.ACTIVE, mInCallServiceFixtureX.getCall(callId).getState());
        assertEquals(CallState.ACTIVE, mInCallServiceFixtureY.getCall(callId).getState());

        mInCallServiceFixtureX.mInCallAdapter.disconnectCall(callId);;
        assertEquals(CallState.ACTIVE, mInCallServiceFixtureX.getCall(callId).getState());
        assertEquals(CallState.ACTIVE, mInCallServiceFixtureY.getCall(callId).getState());

        mConnectionServiceFixtureA.sendSetDisconnected(connectionId, DisconnectCause.LOCAL);
        assertEquals(CallState.DISCONNECTED, mInCallServiceFixtureX.getCall(callId).getState());
        assertEquals(CallState.DISCONNECTED, mInCallServiceFixtureY.getCall(callId).getState());
    }

    public void testDeadlockOnOutgoingCall() throws Exception {
        for (int i = 0; i < 100; i++) {
            TelecomSystemTest test = new TelecomSystemTest();
            test.setContext(getContext());
            test.setTestContext(getTestContext());
            test.setName(getName());
            test.setUp();
            test.do_testDeadlockOnOutgoingCall();
            test.tearDown();
        }
    }

    public void do_testDeadlockOnOutgoingCall() throws Exception {
        final String connectionId = startOutgoingPhoneCall(
                "650-555-1212",
                mPhoneAccountA0.getAccountHandle(),
                mConnectionServiceFixtureA);
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
                            mConnectionServiceFixtureA.sendSetActive(connectionId);
                        } catch (Exception e) {
                            Log.e(this, e, "");
                        }
                    }
                });
    }
}
