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

import android.os.Bundle;
import android.telecomm.ConnectionService;
import android.telecomm.ConnectionRequest;

import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import java.util.Set;

/**
 * Used to retrieve details about an incoming call. This is invoked after an incoming call intent.
 */
final class IncomingCallsManager {

    private final Set<Call> mPendingIncomingCalls = Sets.newLinkedHashSet();

    /**
     * Retrieves details of an incoming call through its associated connection service.
     *
     * @param call The call object.
     * @param extras The optional extras passed with the incoming call intent (to be returned to
     *     the connection service via
     *     {@link ConnectionService#createIncomingCall(ConnectionRequest)}.
     */
    void retrieveIncomingCall(final Call call, Bundle extras) {
        ThreadUtil.checkOnMainThread();
        Log.d(this, "retrieveIncomingCall");

        // Just to be safe, lets make sure we're not already processing this call.
        Preconditions.checkState(!mPendingIncomingCalls.contains(call));

        mPendingIncomingCalls.add(call);

        Runnable errorCallback = new Runnable() {
            @Override public void run() {
                handleFailedIncomingCall(call);
            }
        };

        call.getConnectionService().createIncomingCall(call, extras, errorCallback);
    }

    /**
     * Notifies the incoming call of success after removing it from the pending
     * list.
     *
     * @param request The details of the call.
     */
    void handleSuccessfulIncomingCall(Call call, ConnectionRequest request) {
        ThreadUtil.checkOnMainThread();

        if (mPendingIncomingCalls.contains(call)) {
            Log.d(this, "Incoming call %s found.", call);
            mPendingIncomingCalls.remove(call);
            call.handleVerifiedIncoming(request);
        }
    }

    /**
     * Notifies  incoming call of failure after removing it from the pending list.
     */
    void handleFailedIncomingCall(Call call) {
        ThreadUtil.checkOnMainThread();

        if (mPendingIncomingCalls.contains(call)) {
            Log.i(this, "Failed to get details for incoming call %s", call);
            mPendingIncomingCalls.remove(call);
            // The call was found still waiting for details. Consider it failed.
            call.handleFailedIncoming();
        }
    }
}
