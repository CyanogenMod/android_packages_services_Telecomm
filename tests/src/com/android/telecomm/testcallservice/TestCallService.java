/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.telecomm.testcallservice;

import com.google.android.collect.Lists;
import com.google.common.base.Preconditions;

import java.util.Date;

import android.os.RemoteException;
import android.telecomm.CallInfo;
import android.telecomm.CallService;
import android.telecomm.ICallServiceAdapter;
import android.text.TextUtils;
import android.util.Log;

/**
 * Service which provides fake calls to test the ICallService interface.
 */
public class TestCallService extends CallService {
    private static final String TAG = TestCallService.class.getSimpleName();

    /**
     * Adapter to call back into CallsManager.
     */
    private ICallServiceAdapter mCallsManagerAdapter;

    /** {@inheritDoc} */
    @Override
    public void setCallServiceAdapter(ICallServiceAdapter callServiceAdapter) {
        Log.i(TAG, "setCallServiceAdapter()");

        mCallsManagerAdapter = callServiceAdapter;
    }

    /**
     * Responds as compatible for all calls except those starting with the number 7 (arbitrarily
     * chosen for testing purposes).
     *
     * {@inheritDoc}
     */
    @Override
    public void isCompatibleWith(String handle, String callId) {
        Log.i(TAG, "isCompatibleWith(" + handle + ", " + callId + ")");
        Preconditions.checkNotNull(handle);

        // Is compatible if the handle doesn't start with 7.
        boolean isCompatible = (handle.charAt(0) != '7');

        try {
            // Tell CallsManager whether this call service can place the call (is compatible).
            // Returning positively on setCompatibleWith() doesn't guarantee that we will be chosen
            // to place the call. If we *are* chosen then CallsManager will execute the call()
            // method below.
            mCallsManagerAdapter.setCompatibleWith(handle, callId, isCompatible);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to setCompatibleWith().", e);
        }
    }

    /**
     * Starts a call by calling into the adapter. For testing purposes this methods acts as if a
     * call was successfully connected every time.
     *
     * {@inheritDoc}
     */
    @Override
    public void call(String handle, String callId) {
        Log.i(TAG, "call(" + handle + ", " + callId + ")");

        try {
            // This creates a call within CallsManager starting at the DIALING state.
            // TODO(santoscordon): When we define the call states, consider renaming newOutgoingCall
            // to newDialingCall to match the states exactly and as an indication of the starting
            // state for this new call. This depends on what the states are ultimately defined as.
            mCallsManagerAdapter.newOutgoingCall(callId);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to create a newOutgoingCall().", e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void disconnect(String callId) {
        Log.i(TAG, "disconnect(" + callId + ")");

        try {
            mCallsManagerAdapter.setDisconnected(callId);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to setDisconnected().", e);
        }
    }
}
