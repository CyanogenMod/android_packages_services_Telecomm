/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.telecomm;

import android.os.Handler;
import android.os.Looper;
import android.telecomm.IInCallAdapter;
import android.util.Log;

/**
 * Receives call commands and updates from in-call app and passes them through to CallsManager.
 * {@link InCallController} creates an instance of this class and passes it to the in-call app after
 * binding to it. This adapter can receive commands and updates until the in-call app is unbound.
 */
class InCallAdapter extends IInCallAdapter.Stub {

    private static final String TAG = InCallAdapter.class.getSimpleName();

    private final CallsManager mCallsManager;

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    /** Persists the specified parameters. */
    public InCallAdapter(CallsManager callsManager) {
        mCallsManager = callsManager;
    }

    /** {@inheritDoc} */
    @Override
    public void answerCall(final String callId) {
        Log.d(TAG, "answerCall(" + callId + ")");
        mHandler.post(new Runnable() {
            @Override public void run() {
                mCallsManager.answerCall(callId);
            }
        });
    }

    /** {@inheritDoc} */
    @Override
    public void rejectCall(final String callId) {
        Log.d(TAG, "rejectCall(" + callId + ")");
        mHandler.post(new Runnable() {
            @Override public void run() {
                mCallsManager.rejectCall(callId);
            }
        });
    }

    /** {@inheritDoc} */
    @Override
    public void disconnectCall(final String callId) {
        mHandler.post(new Runnable() {
            @Override public void run() {
                mCallsManager.disconnectCall(callId);
            }
        });
    }
}
