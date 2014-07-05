/*
 * Copyright 2013, The Android Open Source Project
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

import android.content.ComponentName;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.telecomm.CallServiceDescriptor;
import android.telecomm.TelecommConstants;

import com.android.telecomm.BaseRepository.LookupCallback;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;

import java.util.Collection;
import java.util.Set;

/**
 * Switchboard is responsible for gathering the {@link ConnectionServiceWrapper}s through
 *       which to place outgoing calls
 */
final class Switchboard {
    private final static Switchboard sInstance = new Switchboard();

    /** Used to retrieve incoming call details. */
    private final IncomingCallsManager mIncomingCallsManager;

    private final CallServiceRepository mCallServiceRepository;

    /** Singleton accessor. */
    static Switchboard getInstance() {
        return sInstance;
    }

    /**
     * Persists the specified parameters and initializes Switchboard.
     */
    private Switchboard() {
        ThreadUtil.checkOnMainThread();

        mIncomingCallsManager = new IncomingCallsManager();
        mCallServiceRepository =
                new CallServiceRepository(mIncomingCallsManager);
    }

    CallServiceRepository getCallServiceRepository() {
        return mCallServiceRepository;
    }

    /**
     * Retrieves details about the incoming call through the incoming call manager.
     *
     * @param call The call object.
     * @param descriptor The relevant call-service descriptor.
     * @param extras The optional extras passed via
     *         {@link TelecommConstants#EXTRA_INCOMING_CALL_EXTRAS}
     */
    void retrieveIncomingCall(Call call, CallServiceDescriptor descriptor, Bundle extras) {
        Log.d(this, "retrieveIncomingCall");
        ConnectionServiceWrapper service = mCallServiceRepository.getService(descriptor);
        call.setConnectionService(service);
        mIncomingCallsManager.retrieveIncomingCall(call, extras);
    }
}
