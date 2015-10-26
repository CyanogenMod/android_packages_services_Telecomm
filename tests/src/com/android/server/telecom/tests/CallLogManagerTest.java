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


import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.IContentProvider;
import android.content.pm.UserInfo;
import android.net.Uri;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.CallLog;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccountHandle;
import android.telecom.VideoProfile;

import com.android.server.telecom.Call;
import com.android.server.telecom.CallLogManager;
import com.android.server.telecom.CallState;
import com.android.server.telecom.TelephonyUtil;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mockito.ArgumentCaptor;

import java.util.Collections;

public class CallLogManagerTest extends TelecomTestCase {

    private CallLogManager mCallLogManager;
    private IContentProvider mContentProvider;
    private PhoneAccountHandle mDefaultAccountHandle;

    private static final Uri TEL_PHONEHANDLE = Uri.parse("tel:5555551234");

    private static final PhoneAccountHandle EMERGENCY_ACCT_HANDLE = TelephonyUtil
            .getDefaultEmergencyPhoneAccount()
            .getAccountHandle();

    private static final int NO_VIDEO_STATE = VideoProfile.STATE_AUDIO_ONLY;
    private static final int BIDIRECTIONAL_VIDEO_STATE = VideoProfile.STATE_BIDIRECTIONAL;
    private static final String POST_DIAL_STRING = ";12345";
    private static final String TEST_PHONE_ACCOUNT_ID= "testPhoneAccountId";

    private static final int TEST_TIMEOUT_MILLIS = 100;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mContext = mComponentContextFixture.getTestDouble().getApplicationContext();
        mCallLogManager = new CallLogManager(mContext);
        mContentProvider = mContext.getContentResolver().acquireProvider("test");
        mDefaultAccountHandle = new PhoneAccountHandle(
                new ComponentName("com.android.server.telecom.tests", "CallLogManagerTest"),
                TEST_PHONE_ACCOUNT_ID
        );

        UserManager userManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        UserInfo userInfo = new UserInfo(UserHandle.USER_CURRENT, "test", 0);
        when(userManager.isUserRunning(any(UserHandle.class))).thenReturn(true);
        when(userManager.hasUserRestriction(any(String.class), any(UserHandle.class)))
                .thenReturn(false);
        when(userManager.getUsers(any(Boolean.class)))
                .thenReturn(Collections.singletonList(userInfo));
    }

    public void testDontLogCancelledCall() {
        Call fakeCall = makeFakeCall(
                DisconnectCause.CANCELED,
                false, // isConference
                false, // isIncoming
                1L, // creationTimeMillis
                1000L, // ageMillis
                TEL_PHONEHANDLE, // callHandle
                mDefaultAccountHandle, // phoneAccountHandle
                NO_VIDEO_STATE, // callVideoState
                POST_DIAL_STRING // postDialDigits
        );
        mCallLogManager.onCallStateChanged(fakeCall, CallState.DIALING, CallState.DISCONNECTED);
        verifyNoInsertion();
        mCallLogManager.onCallStateChanged(fakeCall, CallState.DIALING, CallState.ABORTED);
        verifyNoInsertion();
    }

    public void testDontLogChoosingAccountCall() {
        Call fakeCall = makeFakeCall(
                DisconnectCause.OTHER, // disconnectCauseCode
                false, // isConference
                false, // isIncoming
                1L, // creationTimeMillis
                1000L, // ageMillis
                TEL_PHONEHANDLE, // callHandle
                mDefaultAccountHandle, // phoneAccountHandle
                NO_VIDEO_STATE, // callVideoState
                POST_DIAL_STRING // postDialDigits
        );
        mCallLogManager.onCallStateChanged(fakeCall, CallState.SELECT_PHONE_ACCOUNT,
                CallState.DISCONNECTED);
        verifyNoInsertion();
    }

    public void testDontLogCallsFromEmergencyAccount() {
        mComponentContextFixture.putBooleanResource(R.bool.allow_emergency_numbers_in_call_log,
                false);
        Call fakeCall = makeFakeCall(
                DisconnectCause.OTHER, // disconnectCauseCode
                false, // isConference
                false, // isIncoming
                1L, // creationTimeMillis
                1000L, // ageMillis
                TEL_PHONEHANDLE, // callHandle
                EMERGENCY_ACCT_HANDLE, // phoneAccountHandle
                NO_VIDEO_STATE, // callVideoState
                POST_DIAL_STRING // postDialDigits
        );
        mCallLogManager.onCallStateChanged(fakeCall, CallState.ACTIVE, CallState.DISCONNECTED);
        verifyNoInsertion();
    }

    public void testLogCallDirectionOutgoing() {
        Call fakeOutgoingCall = makeFakeCall(
                DisconnectCause.OTHER, // disconnectCauseCode
                false, // isConference
                false, // isIncoming
                1L, // creationTimeMillis
                1000L, // ageMillis
                TEL_PHONEHANDLE, // callHandle
                mDefaultAccountHandle, // phoneAccountHandle
                NO_VIDEO_STATE, // callVideoState
                POST_DIAL_STRING // postDialDigits
        );
        mCallLogManager.onCallStateChanged(fakeOutgoingCall, CallState.ACTIVE,
                CallState.DISCONNECTED);
        ContentValues insertedValues = verifyInsertionWithCapture();
        assertEquals(insertedValues.getAsInteger(CallLog.Calls.TYPE),
                Integer.valueOf(CallLog.Calls.OUTGOING_TYPE));
    }

    public void testLogCallDirectionIncoming() {
        Call fakeIncomingCall = makeFakeCall(
                DisconnectCause.OTHER, // disconnectCauseCode
                false, // isConference
                true, // isIncoming
                1L, // creationTimeMillis
                1000L, // ageMillis
                TEL_PHONEHANDLE, // callHandle
                mDefaultAccountHandle, // phoneAccountHandle
                NO_VIDEO_STATE, // callVideoState
                POST_DIAL_STRING // postDialDigits
        );
        mCallLogManager.onCallStateChanged(fakeIncomingCall, CallState.ACTIVE,
                CallState.DISCONNECTED);
        ContentValues insertedValues = verifyInsertionWithCapture();
        assertEquals(insertedValues.getAsInteger(CallLog.Calls.TYPE),
                Integer.valueOf(CallLog.Calls.INCOMING_TYPE));
    }

    public void testLogCallDirectionMissed() {
        Call fakeMissedCall = makeFakeCall(
                DisconnectCause.MISSED, // disconnectCauseCode
                false, // isConference
                true, // isIncoming
                1L, // creationTimeMillis
                1000L, // ageMillis
                TEL_PHONEHANDLE, // callHandle
                mDefaultAccountHandle, // phoneAccountHandle
                NO_VIDEO_STATE, // callVideoState
                POST_DIAL_STRING // postDialDigits
        );

        mCallLogManager.onCallStateChanged(fakeMissedCall, CallState.ACTIVE,
                CallState.DISCONNECTED);
        ContentValues insertedValues = verifyInsertionWithCapture();
        assertEquals(insertedValues.getAsInteger(CallLog.Calls.TYPE),
                Integer.valueOf(CallLog.Calls.MISSED_TYPE));
    }

    public void testCreationTimeAndAge() {
        long currentTime = System.currentTimeMillis();
        long duration = 1000L;
        Call fakeCall = makeFakeCall(
                DisconnectCause.OTHER, // disconnectCauseCode
                false, // isConference
                false, // isIncoming
                currentTime, // creationTimeMillis
                duration, // ageMillis
                TEL_PHONEHANDLE, // callHandle
                mDefaultAccountHandle, // phoneAccountHandle
                NO_VIDEO_STATE, // callVideoState
                POST_DIAL_STRING // postDialDigits
        );
        mCallLogManager.onCallStateChanged(fakeCall, CallState.ACTIVE, CallState.DISCONNECTED);
        ContentValues insertedValues = verifyInsertionWithCapture();
        assertEquals(insertedValues.getAsLong(CallLog.Calls.DATE),
                Long.valueOf(currentTime));
        assertEquals(insertedValues.getAsLong(CallLog.Calls.DURATION),
                Long.valueOf(duration / 1000));
    }

    public void testLogPhoneAccountId() {
        Call fakeCall = makeFakeCall(
                DisconnectCause.OTHER, // disconnectCauseCode
                false, // isConference
                true, // isIncoming
                1L, // creationTimeMillis
                1000L, // ageMillis
                TEL_PHONEHANDLE, // callHandle
                mDefaultAccountHandle, // phoneAccountHandle
                NO_VIDEO_STATE, // callVideoState
                POST_DIAL_STRING // postDialDigits
        );
        mCallLogManager.onCallStateChanged(fakeCall, CallState.ACTIVE, CallState.DISCONNECTED);
        ContentValues insertedValues = verifyInsertionWithCapture();
        assertEquals(insertedValues.getAsString(CallLog.Calls.PHONE_ACCOUNT_ID),
                TEST_PHONE_ACCOUNT_ID);
    }

    public void testLogCorrectPhoneNumber() {
        Call fakeCall = makeFakeCall(
                DisconnectCause.OTHER, // disconnectCauseCode
                false, // isConference
                true, // isIncoming
                1L, // creationTimeMillis
                1000L, // ageMillis
                TEL_PHONEHANDLE, // callHandle
                mDefaultAccountHandle, // phoneAccountHandle
                NO_VIDEO_STATE, // callVideoState
                POST_DIAL_STRING // postDialDigits
        );
        mCallLogManager.onCallStateChanged(fakeCall, CallState.ACTIVE, CallState.DISCONNECTED);
        ContentValues insertedValues = verifyInsertionWithCapture();
        assertEquals(insertedValues.getAsString(CallLog.Calls.NUMBER),
                TEL_PHONEHANDLE.getSchemeSpecificPart());
        assertEquals(insertedValues.getAsString(CallLog.Calls.POST_DIAL_DIGITS), POST_DIAL_STRING);
    }

    public void testLogCallVideoFeatures() {
        Call fakeVideoCall = makeFakeCall(
                DisconnectCause.OTHER, // disconnectCauseCode
                false, // isConference
                true, // isIncoming
                1L, // creationTimeMillis
                1000L, // ageMillis
                TEL_PHONEHANDLE, // callHandle
                mDefaultAccountHandle, // phoneAccountHandle
                BIDIRECTIONAL_VIDEO_STATE, // callVideoState
                POST_DIAL_STRING // postDialDigits
        );
        mCallLogManager.onCallStateChanged(fakeVideoCall, CallState.ACTIVE, CallState.DISCONNECTED);
        ContentValues insertedValues = verifyInsertionWithCapture();
        assertTrue((insertedValues.getAsInteger(CallLog.Calls.FEATURES)
                & CallLog.Calls.FEATURES_VIDEO) == CallLog.Calls.FEATURES_VIDEO);
    }

    private void verifyNoInsertion() {
        try {
            verify(mContentProvider, timeout(TEST_TIMEOUT_MILLIS).never()).insert(any(String.class),
                    any(Uri.class), any(ContentValues.class));
        } catch (android.os.RemoteException e) {
            fail("Remote exception occurred during test execution");
        }
    }

    private ContentValues verifyInsertionWithCapture() {
        ArgumentCaptor<ContentValues> captor = ArgumentCaptor.forClass(ContentValues.class);
        try {
            verify(mContentProvider, timeout(TEST_TIMEOUT_MILLIS)).insert(any(String.class),
                    eq(CallLog.Calls.CONTENT_URI), captor.capture());
        } catch (android.os.RemoteException e) {
            fail("Remote exception occurred during test execution");
        }

        return captor.getValue();
    }


    private Call makeFakeCall(int disconnectCauseCode, boolean isConference, boolean isIncoming,
            long creationTimeMillis, long ageMillis, Uri callHandle,
            PhoneAccountHandle phoneAccountHandle, int callVideoState,
            String postDialDigits) {
        Call fakeCall = mock(Call.class);
        when(fakeCall.getDisconnectCause()).thenReturn(
                new DisconnectCause(disconnectCauseCode));
        when(fakeCall.isConference()).thenReturn(isConference);
        when(fakeCall.isIncoming()).thenReturn(isIncoming);
        when(fakeCall.getCreationTimeMillis()).thenReturn(creationTimeMillis);
        when(fakeCall.getAgeMillis()).thenReturn(ageMillis);
        when(fakeCall.getOriginalHandle()).thenReturn(callHandle);
        when(fakeCall.getTargetPhoneAccount()).thenReturn(phoneAccountHandle);
        when(fakeCall.getVideoStateHistory()).thenReturn(callVideoState);
        when(fakeCall.getPostDialDigits()).thenReturn(postDialDigits);

        return fakeCall;
    }
}
