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

import com.android.telecomm.ServiceBinder.BindCallback;
import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;

import java.util.Map;

/**
 * Utility class to confirm the existence of an incoming call after receiving an incoming-call
 * intent, see {@link TelecommReceiver}. Binds with the specified call services and requests
 * confirmation of incoming calls using call tokens provided as part of the intent. Upon receipt of
 * the confirmation, yields execution back to the switchboard to complete the incoming sequence. The
 * entire process is timeboxed to protect against unresponsive call services.
 */
final class IncomingCallsManager {

    /**
     * The amount of time to wait for confirmation of an incoming call, in milliseconds.
     * TODO(santoscordon): Likely needs adjustment.
     */
    private static final int INCOMING_CALL_TIMEOUT_MS = 1000;

    private final Switchboard mSwitchboard;

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    /** Maps incoming calls to their call services. */
    private final Map<Call, CallServiceWrapper> mPendingIncomingCalls = Maps.newHashMap();

    /**
     * Persists the specified parameters.
     *
     * @param switchboard The switchboard.
     */
    IncomingCallsManager(Switchboard switchboard) {
        mSwitchboard = switchboard;
    }


    /**
     * Confirms the existence of an incoming call with the specified call service (asynchronously).
     * Starts the timeout sequence in case the call service is unresponsive.
     *
     * @param call The call object.
     * @param callService The call service.
     * @param callToken The token used by the call service to identify the incoming call.
     */
    void confirmIncomingCall(
            final Call call, final CallServiceWrapper callService, String callToken) {

        ThreadUtil.checkOnMainThread();
        // Just to be safe, lets make sure we're not already processing this call.
        Preconditions.checkState(!mPendingIncomingCalls.containsKey(call));

        mPendingIncomingCalls.put(call, callService);
        startTimeoutForCall(call);

        BindCallback callback = new BindCallback() {
            @Override public void onSuccess() {
                // TODO(santoscordon): ICallService needs to be updated with the following method.
                // Confirmation won't work until this method is filled in.
                // callService.confirmIncomingCall(call.toCallInfo(), callToken);
            }
            @Override public void onFailure() {
                handleFailedIncomingCall(call);
            }
        };

        callService.bind(callback);
    }

    /**
     * Starts a timeout to timebox the confirmation of an incoming call. When the timeout expires,
     * it will notify switchboard that the incoming call was not confirmed and thus does not exist
     * as far as Telecomm is concerned.
     *
     * @param call The call.
     */
    void startTimeoutForCall(final Call call) {
        Runnable timeoutCallback = new Runnable() {
            @Override public void run() {
                handleFailedIncomingCall(call);
            }
        };
        mHandler.postDelayed(timeoutCallback, INCOMING_CALL_TIMEOUT_MS);
    }

    /**
     * Notifies the switchboard of a successful incoming call after removing it from the pending
     * list.
     * TODO(santoscordon): Needs code in CallServiceAdapter to call this method.
     *
     * @param call The call.
     */
    void handleSuccessfulIncomingCall(Call call) {
        ThreadUtil.checkOnMainThread();
        if (mPendingIncomingCalls.remove(call) != null) {
            mSwitchboard.handleSuccessfulIncomingCall(call);
        }
    }

    /**
     * Notifies switchboard of the failed incoming call after removing it from the pending list.
     *
     * @param call The call.
     */
    void handleFailedIncomingCall(Call call) {
        ThreadUtil.checkOnMainThread();
        if (mPendingIncomingCalls.remove(call) != null) {
            // The call was found still waiting for confirmation. Consider it failed.
            mSwitchboard.handleFailedIncomingCall(call);
        }
    }
}
