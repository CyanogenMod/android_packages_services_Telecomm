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

import com.android.internal.telecom.IConnectionService;
import com.android.internal.telecom.IConnectionServiceAdapter;
import com.android.internal.telecom.IInCallAdapter;
import com.android.internal.telecom.IInCallService;
import com.android.internal.telecom.IVideoProvider;
import com.android.server.telecom.CallsManager;
import com.android.server.telecom.HeadsetMediaButton;
import com.android.server.telecom.HeadsetMediaButtonFactory;
import com.android.server.telecom.InCallWakeLockControllerFactory;
import com.android.server.telecom.MissedCallNotifier;
import com.android.server.telecom.ProximitySensorManagerFactory;
import com.android.server.telecom.InCallWakeLockController;
import com.android.server.telecom.ProximitySensorManager;
import com.android.server.telecom.TelecomSystem;

import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.telecom.CallState;
import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.DisconnectCause;
import android.telecom.ParcelableCall;
import android.telecom.ParcelableConnection;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.StatusHints;
import android.telecom.TelecomManager;
import android.test.AndroidTestCase;
import android.util.Log;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;
import java.util.List;

import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class SimpleTelecomTest extends AndroidTestCase {

    private static final String TAG = "Telecom-TEST";

    ///////////////////////////////////////////////////////////////////////////
    // Telecom specific mock objects

    @Mock
    MissedCallNotifier mMissedCallNotifier;
    @Mock
    HeadsetMediaButtonFactory mHeadsetMediaButtonFactory;
    @Mock
    ProximitySensorManagerFactory mProximitySensorManagerFactory;
    @Mock
    InCallWakeLockControllerFactory mInCallWakeLockControllerFactory;
    @Mock HeadsetMediaButton mHeadsetMediaButton;
    @Mock ProximitySensorManager mProximitySensorManager;
    @Mock InCallWakeLockController mInCallWakeLockController;

    ///////////////////////////////////////////////////////////////////////////
    // Connection service

    PhoneAccount mTestPhoneAccount = PhoneAccount.builder(
            new PhoneAccountHandle(
                    new ComponentName("connection-service-package", "connection-service-class"),
                    "test-account-id"),
            "test phone account")
            .addSupportedUriScheme("tel")
            .setCapabilities(
                    PhoneAccount.CAPABILITY_CALL_PROVIDER |
                    PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)
            .build();
    @Mock IConnectionService.Stub mConnectionService;
    IConnectionServiceAdapter mConnectionServiceAdapter;

    ///////////////////////////////////////////////////////////////////////////
    // In-Call service

    ComponentName mIncallComponentName = new ComponentName("incall-package", "incall-class");
    @Mock IInCallService.Stub mInCallService;
    IInCallAdapter mIInCallAdapter;

    private ComponentContextHolder mContextHolder;
    private TelecomSystem mSystem;

    private ConnectionRequest mConnectionRequest;
    private String mConnectionId;

    private ParcelableCall mParcelableCall;

    private Looper mMainLooper;
    private Looper mTestLooper;

    ///////////////////////////////////////////////////////////////////////////
    // Captured values for outgoing call processing

    Intent mNewOutgoingCallIntent;
    BroadcastReceiver mNewOutgoingCallReceiver;

    private MockitoHelper mMockitoHelper = new MockitoHelper();

    @Override
    public void setUp() throws Exception {
        mMockitoHelper.setUp(getContext(), getClass());

        mMainLooper = Looper.getMainLooper();
        mTestLooper = Looper.myLooper();

        mContextHolder = new ComponentContextHolder();
        MockitoAnnotations.initMocks(this);

        mContextHolder.putResource(
                com.android.server.telecom.R.string.ui_default_package,
                mIncallComponentName.getPackageName());
        mContextHolder.putResource(
                com.android.server.telecom.R.string.incall_default_class,
                mIncallComponentName.getClassName());

        com.android.server.telecom.Log.setTag(TAG);

        when(mHeadsetMediaButtonFactory.create(
                any(Context.class),
                any(CallsManager.class)))
                .thenReturn(mHeadsetMediaButton);

        when(mInCallWakeLockControllerFactory.create(
                any(Context.class),
                any(CallsManager.class)))
                .thenReturn(mInCallWakeLockController);

        when(mProximitySensorManagerFactory.create((Context) any(), (CallsManager) any()))
                .thenReturn(mProximitySensorManager);

        // Set up connection service

        mContextHolder.addConnectionService(
                mTestPhoneAccount.getAccountHandle().getComponentName(),
                mConnectionService);
        when(mConnectionService.asBinder()).thenReturn(mConnectionService);
        when(mConnectionService.queryLocalInterface(anyString()))
                .thenReturn(mConnectionService);

        // Set up in-call service

        mContextHolder.addInCallService(
                mIncallComponentName,
                mInCallService);
        when(mInCallService.asBinder()).thenReturn(mInCallService);
        when(mInCallService.queryLocalInterface(anyString()))
                .thenReturn(mInCallService);

        runOnMainThreadAndWait(new Runnable() {
            @Override
            public void run() {
                mSystem = new TelecomSystem(
                        mContextHolder.getTestDouble(),
                        mMissedCallNotifier,
                        mHeadsetMediaButtonFactory,
                        mProximitySensorManagerFactory,
                        mInCallWakeLockControllerFactory);
                mSystem.getPhoneAccountRegistrar().registerPhoneAccount(mTestPhoneAccount);
            }
        });
    }

    @Override
    public void tearDown() throws Exception {
        mMockitoHelper.tearDown();
        mSystem = null;
    }

    public void testSimpleOutgoingCall() throws Exception {

        // Arrange to receive the first set of notifications when Telecom receives an Intent
        // to make an outgoing call
        doAnswer(new Answer<Object>() {
            @Override
            public Object answer(final InvocationOnMock invocation) {
                mIInCallAdapter = (IInCallAdapter) invocation.getArguments()[0];
                return null;
            }
        }).when(mInCallService).setInCallAdapter((IInCallAdapter) any());
        verify(mInCallService, never()).addCall((ParcelableCall) any());

        doAnswer(new Answer<Object>() {
            @Override
            public Object answer(final InvocationOnMock invocation) {
                mNewOutgoingCallIntent = (Intent) invocation.getArguments()[0];
                mNewOutgoingCallReceiver = (BroadcastReceiver) invocation.getArguments()[3];
                return null;
            }
        }).when(mContextHolder.getTestDouble().getApplicationContext())
                .sendOrderedBroadcastAsUser(
                        any(Intent.class),
                        any(UserHandle.class),
                        anyString(),
                        any(BroadcastReceiver.class),
                        any(Handler.class),
                        anyInt(),
                        anyString(),
                        any(Bundle.class));

        doAnswer(new Answer<Object>() {
            @Override
            public Object answer(final InvocationOnMock invocation) {
                mParcelableCall = (ParcelableCall) invocation.getArguments()[0];
                return null;
            }
        }).when(mInCallService).addCall((ParcelableCall) any());

        doAnswer(new Answer<Object>() {
            @Override
            public Object answer(final InvocationOnMock invocation) {
                mParcelableCall = (ParcelableCall) invocation.getArguments()[0];
                return null;
            }
        }).when(mInCallService).updateCall((ParcelableCall) any());

        runOnMainThreadAndWait(new Runnable() {
            @Override
            public void run() {
                // Start an outgoing phone call
                String number = "650-555-1212";
                Intent actionCallIntent = new Intent();
                actionCallIntent.setData(Uri.parse("tel:" + number));
                actionCallIntent.putExtra(Intent.EXTRA_PHONE_NUMBER, number);
                actionCallIntent.putExtra(
                        TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE,
                        mTestPhoneAccount.getAccountHandle());
                actionCallIntent.setAction(Intent.ACTION_CALL);
                mSystem.getCallIntentProcessor().processIntent(actionCallIntent);
            }
        });

        // Sanity check that the in-call adapter is now set
        assertNotNull(mIInCallAdapter);
        assertNotNull(mNewOutgoingCallIntent);
        assertNotNull(mNewOutgoingCallReceiver);

        // Arrange to receive the Connection Service adapter
        doAnswer(new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock invocation) {
                mConnectionServiceAdapter = (IConnectionServiceAdapter) invocation
                        .getArguments()[0];
                return null;
            }
        }).when(mConnectionService).addConnectionServiceAdapter((IConnectionServiceAdapter) any());

        doAnswer(new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock invocation) {
                mConnectionId = (String) invocation.getArguments()[1];
                mConnectionRequest = (ConnectionRequest) invocation.getArguments()[2];
                return null;
            }
        }).when(mConnectionService).createConnection(
                any(PhoneAccountHandle.class),
                anyString(),
                any(ConnectionRequest.class),
                anyBoolean(),
                anyBoolean());

        // Pass on the new outgoing call Intent
        runOnMainThreadAndWait(new Runnable() {
            @Override
            public void run() {
                // Set a dummy PendingResult so the BroadcastReceiver agrees to accept onReceive()
                mNewOutgoingCallReceiver.setPendingResult(
                        new BroadcastReceiver.PendingResult(0, "", null, 0, true, false, null, 0));
                mNewOutgoingCallReceiver.setResultData(
                        mNewOutgoingCallIntent.getStringExtra(Intent.EXTRA_PHONE_NUMBER));
                mNewOutgoingCallReceiver.onReceive(
                        mContextHolder.getTestDouble(),
                        mNewOutgoingCallIntent);
            }
        });

        runOnMainThreadAndWait(new Runnable() {
            @Override
            public void run() {
                assertNotNull(mConnectionServiceAdapter);
                assertNotNull(mConnectionRequest);
                assertNotNull(mConnectionId);
            }
        });

        mConnectionServiceAdapter.handleCreateConnectionComplete(
                mConnectionId,
                mConnectionRequest,
                new ParcelableConnection(
                        mConnectionRequest.getAccountHandle(),
                        Connection.STATE_DIALING,
                        0,
                        (Uri) null,
                        0,
                        "caller display name",
                        0,
                        (IVideoProvider) null,
                        0,
                        false,
                        false,
                        (StatusHints) null,
                        (DisconnectCause) null,
                        (List<String>) Collections.EMPTY_LIST));
        mConnectionServiceAdapter.setDialing(mConnectionId);
        mConnectionServiceAdapter.setActive(mConnectionId);

        runOnMainThreadAndWait(new Runnable() {
            @Override
            public void run() {
                assertNotNull(mParcelableCall);
                assertEquals(CallState.ACTIVE, mParcelableCall.getState());
            }
        });

        runOnMainThreadAndWait(new Runnable() {
            @Override
            public void run() {
                try {
                    mIInCallAdapter.disconnectCall(mParcelableCall.getId());
                } catch (RemoteException e) {
                    throw new RuntimeException(e);
                }
            }
        });

        runOnMainThreadAndWait(new Runnable() {
            @Override
            public void run() {
                assertNotNull(mParcelableCall);
                assertEquals(CallState.ACTIVE, mParcelableCall.getState());
                try {
                    verify(mConnectionService).disconnect(mConnectionId);
                } catch (RemoteException e) {
                    throw new RuntimeException(e);
                }
            }
        });

        mConnectionServiceAdapter.setDisconnected(
                mConnectionId,
                new DisconnectCause(DisconnectCause.LOCAL));

        runOnMainThreadAndWait(new Runnable() {
            @Override
            public void run() {
                assertEquals(CallState.DISCONNECTED, mParcelableCall.getState());
            }
        });
    }

    private void runOnMainThreadAndWait(Runnable task) {
        runOn(mMainLooper, task);
    }

    private void runOnTestThreadAndWait(Runnable task) {
        runOn(mTestLooper, task);
    }

    private  void runOn(Looper looper, final Runnable task) {
        final Object lock = new Object();
        synchronized (lock) {
            new Handler(looper).post(new Runnable() {
                @Override
                public void run() {
                    task.run();
                    synchronized (lock) {
                        lock.notifyAll();
                    }
                }
            });
            try {
                lock.wait();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private String exceptionToString(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private void log(String msg) {
        Log.i(TAG, getClass().getSimpleName() + " - " + msg);
    }
}
