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
import android.telecomm.CallInfo;
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
 * Switchboard is responsible for:
 * - gathering the {@link CallServiceWrapper}s and {@link CallServiceSelectorWrapper}s through
 *       which to place outgoing calls
 * - starting outgoing calls (via {@link OutgoingCallsManager}
 */
final class Switchboard {
    /**
     * Encapsulates a request to place an outgoing call.
     * TODO(santoscordon): Move this state into Call and remove this class.
     */
    private final class OutgoingCallEntry {
        final Call call;

        private Collection<CallServiceWrapper> mCallServices;
        private Collection<CallServiceSelectorWrapper> mSelectors;
        private boolean mIsCallPending = true;

        OutgoingCallEntry(Call call) {
            this.call = call;
        }

        /**
         * Sets the call services to attempt for this outgoing call.
         *
         * @param callServices The call services.
         */
        void setCallServices(Collection<CallServiceWrapper> callServices) {
            mCallServices = callServices;
            onLookupComplete();
        }

        Collection<CallServiceWrapper> getCallServices() {
            return mCallServices;
        }

        /**
         * Sets the selectors to attemnpt for this outgoing call.
         *
         * @param selectors The call-service selectors.
         */
        void setSelectors(Collection<CallServiceSelectorWrapper> selectors) {
            mSelectors = selectors;
            onLookupComplete();
        }

        Collection<CallServiceSelectorWrapper> getSelectors() {
            return mSelectors;
        }

        /** Expires the pending outgoing call and stops it from being made. */
        void expire() {
            // This can be executed in three states:
            // 1) We are still waiting for the list of CSs (Call Services)
            // 2) We told outgoing calls manager to place the call using the CSs
            // 3) Outgoing calls manager already successfully placed the call.
            if (mIsCallPending) {
                // Handle state (1), tell the call to clean itself up and shut everything down.
                mIsCallPending = false;
                call.handleFailedOutgoing(true /* isAborted */);
            } else {
                // Handle states (2) & (3). We can safely call abort() in either case. If the call
                // is not yet successful, then it will abort.  If the call was already placed, then
                // outgoing calls manager will do nothing (and return false which we ignore).
                boolean isAborted = mOutgoingCallsManager.abort(call);
                Log.v(this, "expire() caused abort: %b", isAborted);
            }
        }

        /** Initiates processing of the call once call-services and selectors are set. */
        private void onLookupComplete() {
            if (mIsCallPending) {
                if (mSelectors != null && mCallServices != null) {
                    mIsCallPending = false;
                    processNewOutgoingCall(this);
                }
            }
        }
    }

    private final static int MSG_EXPIRE_STALE_CALL = 1;

    private final static Switchboard sInstance = new Switchboard();

    /** Used to place outgoing calls. */
    private final OutgoingCallsManager mOutgoingCallsManager;

    /** Used to retrieve incoming call details. */
    private final IncomingCallsManager mIncomingCallsManager;

    private final CallServiceRepository mCallServiceRepository;

    private final CallServiceSelectorRepository mSelectorRepository;

    /** Used to schedule tasks on the main (UI) thread. */
    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch(msg.what) {
                case MSG_EXPIRE_STALE_CALL:
                    ((OutgoingCallEntry) msg.obj).expire();
                    break;
                default:
                    Log.wtf(Switchboard.this, "Unexpected message %d.", msg.what);
            }
        }
    };

    /** Singleton accessor. */
    static Switchboard getInstance() {
        return sInstance;
    }

    /**
     * Persists the specified parameters and initializes Switchboard.
     */
    private Switchboard() {
        ThreadUtil.checkOnMainThread();

        mOutgoingCallsManager = new OutgoingCallsManager();
        mIncomingCallsManager = new IncomingCallsManager();
        mSelectorRepository = new CallServiceSelectorRepository(mOutgoingCallsManager);
        mCallServiceRepository =
                new CallServiceRepository(mOutgoingCallsManager, mIncomingCallsManager);
    }

    /**
     * Starts the process of placing an outgoing call by searching for available call services
     * through which the call can be placed. After a lookup for those services completes, execution
     * returns to {@link #setCallServices} where the process of placing the call continues.
     *
     * @param call The yet-to-be-connected outgoing-call object.
     */
    void placeOutgoingCall(Call call) {
        final OutgoingCallEntry callEntry = new OutgoingCallEntry(call);

        // Lookup call services
        mCallServiceRepository.lookupServices(new LookupCallback<CallServiceWrapper>() {
            @Override
            public void onComplete(Collection<CallServiceWrapper> services) {
                callEntry.setCallServices(services);
            }
        });

        // Lookup selectors
        mSelectorRepository.lookupServices(new LookupCallback<CallServiceSelectorWrapper>() {
            @Override
            public void onComplete(Collection<CallServiceSelectorWrapper> selectors) {
                callEntry.setSelectors(selectors);
            }
        });

        Message msg = mHandler.obtainMessage(MSG_EXPIRE_STALE_CALL, callEntry);
        mHandler.sendMessageDelayed(msg, Timeouts.getNewOutgoingCallMillis());
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
        CallServiceWrapper callService = mCallServiceRepository.getService(descriptor);
        call.setCallService(callService);
        mIncomingCallsManager.retrieveIncomingCall(call, extras);
    }

    /**
     * Ensures any state regarding a call is cleaned up.
     *
     * @param call The call.
     */
    void abortCall(Call call) {
        Log.d(this, "abortCall");
        mOutgoingCallsManager.abort(call);
    }

    /**
     * Attempts to place the specified call.
     *
     * @param callEntry The call entry to place.
     */
    private void processNewOutgoingCall(OutgoingCallEntry callEntry) {
        Collection<CallServiceSelectorWrapper> selectors;
        Call call = callEntry.call;

        // Use the call's selector if it's already tied to one. This is the case for handoff calls.
        if (call.getCallServiceSelector() != null) {
            selectors = ImmutableList.of(call.getCallServiceSelector());
        } else {
            selectors = callEntry.getSelectors();
        }

        boolean useEmergencySelector =
                EmergencyCallServiceSelector.shouldUseSelector(call.getHandle());
        Log.d(this, "processNewOutgoingCall, isEmergency=%b", useEmergencySelector);

        if (useEmergencySelector) {
            // This is potentially an emergency call so add the emergency selector before the
            // other selectors.
            ImmutableList.Builder<CallServiceSelectorWrapper> selectorsBuilder =
                    ImmutableList.builder();

            ComponentName componentName = new ComponentName(
                    TelecommApp.getInstance(), EmergencyCallServiceSelector.class);
            CallServiceSelectorWrapper emergencySelector =
                    new CallServiceSelectorWrapper(
                            componentName.flattenToShortString(),
                            componentName,
                            CallsManager.getInstance(),
                            mOutgoingCallsManager);

            selectorsBuilder.add(emergencySelector);
            selectorsBuilder.addAll(selectors);
            selectors = selectorsBuilder.build();
        }

        mOutgoingCallsManager.placeCall(call, callEntry.getCallServices(), selectors);
    }
}
