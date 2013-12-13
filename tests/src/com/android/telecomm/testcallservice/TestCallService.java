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

import android.telecomm.CallService;
import android.telecomm.ICallServiceAdapter;
import android.util.Log;

/**
 * Service which provides fake calls to test the ICallService interface.
 */
public class TestCallService extends CallService {
    /** Unique identifying tag used for logging. */
    private static final String TAG = TestCallService.class.getSimpleName();

    /** {@inheritDoc} */
    @Override
    public void setCallServiceAdapter(ICallServiceAdapter callServiceAdapter) {
        Log.i(TAG, "setCallServiceAdapter()");
    }

    /** {@inheritDoc} */
    @Override
    public void isCompatibleWith(String handle) {
        Log.i(TAG, "isCompatibleWith(" + handle + ")");
    }

    /** {@inheritDoc} */
    @Override
    public void call(String handle) {
        Log.i(TAG, "call(" + handle + ")");
    }

    /** {@inheritDoc} */
    @Override
    public void disconnect(String callId) {
        Log.i(TAG, "disconnect(" + callId + ")");
    }
}
