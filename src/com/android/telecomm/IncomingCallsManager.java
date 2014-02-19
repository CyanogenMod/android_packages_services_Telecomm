/*
 * Copyright 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.telecomm;

import android.os.Handler;
import android.os.Looper;
import android.telecomm.CallInfo;
import android.util.Log;

import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;

import java.util.Map;

/**
 * Utility class to retrieve details of an incoming call after receiving an incoming-call intent,
 * see {@link CallActivity}. Binds with the specified call services and requests details of incoming
 * calls. Upon receipt of the details, yields execution back to the switchboard to complete the
 * incoming sequence. The entire process is timeboxed to protect against unresponsive call services.
 */
final class IncomingCallsManager {

    private static final String TAG = IncomingCallsManager.class.getSimpleName();

    /**
     * The amount of time to wait for details of an incoming call, in milliseconds.
     * TODO(santoscordon): Likely needs adjustment.
     */
    private static final int INCOMING_CALL_TIMEOUT_MS = 1000;

    private final Switchboard mSwitchboard;

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    /** Maps call ID to the call. */
    private final Map<String, Call> mPendingIncomingCalls = Maps.newHashMap();

    /**
     * Persists the specified parameters.
     *
     * @param switchboard The switchboard.
     */
    IncomingCallsManager(Switchboard switchboard) {
        mSwitchboard = switchboard;
    }

    /**
     * Retrieves details of an incoming call through its associated call service (asynchronously).
     * Starts the timeout sequence in case the call service is unresponsive.
     *
     * @param call The call object.
     */
    void retrieveIncomingCall(Call call) {
        ThreadUtil.checkOnMainThread();
        Log.d(TAG, "retrieveIncomingCall");

        final String callId = call.getId();
        // Just to be safe, lets make sure we're not already processing this call.
        Preconditions.checkState(!mPendingIncomingCalls.containsKey(callId));

        mPendingIncomingCalls.put(callId, call);

        // TODO(santoscordon): Timeout will not be necessary after cleanup via tick() is implemented
        // in Switchboard.
        startTimeoutForCall(call);

        Runnable errorCallback = getFailedIncomingCallback(call);
        call.getCallService().retrieveIncomingCall(callId, errorCallback);
    }

    /**
     * Notifies the switchboard of a successful incoming call after removing it from the pending
     * list.
     *
     * @param callInfo The details of the call.
     */
    void handleSuccessfulIncomingCall(CallInfo callInfo) {
        ThreadUtil.checkOnMainThread();

        Call call = mPendingIncomingCalls.remove(callInfo.getId());
        if (call != null) {
            Log.d(TAG, "Incoming call " + call.getId() + " found.");
            call.setHandle(callInfo.getHandle());
            call.setState(callInfo.getState());

            mSwitchboard.handleSuccessfulIncomingCall(call);
        }
    }

    /**
     * Notifies switchboard of the failed incoming call after removing it from the pending list.
     *
     * @param call The call.
     */
    private void handleFailedIncomingCall(Call call) {
        ThreadUtil.checkOnMainThread();

        if (mPendingIncomingCalls.remove(call.getId()) != null) {
            Log.i(TAG, "Failed to get details for incoming call " + call);
            // The call was found still waiting for details. Consider it failed.
            mSwitchboard.handleFailedIncomingCall(call);
        }
    }

    /**
     * Starts a timeout to timebox the retrieval of an incoming call. When the timeout expires,
     * it will notify switchboard that the incoming call was not retrieved and thus does not exist
     * as far as Telecomm is concerned.
     *
     * @param call The call.
     */
    private void startTimeoutForCall(Call call) {
        Runnable timeoutCallback = getFailedIncomingCallback(call);
        mHandler.postDelayed(timeoutCallback, INCOMING_CALL_TIMEOUT_MS);
    }

    /**
     * Returns a runnable to be invoked upon failure to get details for an incoming call.
     *
     * @param call The failed incoming call.
     */
    private Runnable getFailedIncomingCallback(final Call call) {
        return new Runnable() {
            @Override public void run() {
                handleFailedIncomingCall(call);
            }
        };
    }
}
