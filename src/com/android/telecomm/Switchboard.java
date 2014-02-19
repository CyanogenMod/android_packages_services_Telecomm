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

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import android.os.Handler;
import android.os.Looper;
import android.telecomm.CallServiceInfo;
import android.telecomm.ICallServiceSelector;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Switchboard is responsible for (1) gathering the {@link CallServiceWrapper}s and
 * {@link ICallServiceSelector}s through which to place outgoing calls, (2) starting outgoing calls
 * (via {@link OutgoingCallsManager} and (3) switching active calls between call services.
 */
final class Switchboard {

    /**
     * The frequency of invoking tick in milliseconds.
     * TODO(gilad): May require tuning.
     */
    private final static int TICK_FREQUENCY = 250;

    private final CallsManager mCallsManager;

    /** Used to place outgoing calls. */
    private final OutgoingCallsManager mOutgoingCallsManager;

    /** Used to confirm incoming calls. */
    private final IncomingCallsManager mIncomingCallsManager;

    private final CallServiceRepository mCallServiceRepository;

    private final CallServiceSelectorRepository mSelectorRepository;

    /** Used to schedule tasks on the main (UI) thread. */
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    /**
     * Executes a single tick task and potentially schedules the next such that polling continues
     * as long as necessary.
     * NOTE(gilad): by design no two tick invocations should ever overlap.
     */
    private final Runnable mTicker = new Runnable() {
        @Override
        public void run() {
            tick();
            if (isTicking()) {
                scheduleNextTick();
            }
        }
    };

    private final Set<Call> mNewOutgoingCalls = Sets.newLinkedHashSet();

    private final Set<Call> mPendingOutgoingCalls = Sets.newLinkedHashSet();

    /**
     * The set of currently available call service implementations, see
     * {@link CallServiceRepository}. Populated after a lookup for call services as part of
     * {@link #placeCall}. It is cleared periodically when there are no more new or pending outgoing
     * calls.
     */
    private Set<CallServiceWrapper> mCallServices;

    /**
     * The set of currently available call-service-selector implementations,
     * see {@link CallServiceSelectorRepository}.
     * TODO(gilad): Null out once the active-call count goes to zero.
     */
    private Set<ICallServiceSelector> mSelectors;

    /**
     * The current lookup-cycle ID used with the repositories. Incremented with each invocation
     * of {@link #placeCall} and passed to the repositories via initiateLookup().
     */
    private int mLookupId = 0;

    /**
     * Persists the specified parameters and initializes Switchboard.
     */
    Switchboard(CallsManager callsManager) {
        mCallsManager = callsManager;
        mOutgoingCallsManager = new OutgoingCallsManager(this);
        mIncomingCallsManager = new IncomingCallsManager(this);
        mCallServiceRepository = new CallServiceRepository(this, mOutgoingCallsManager);
        mSelectorRepository = new CallServiceSelectorRepository(this);
    }

    /**
     * Starts the process of placing an outgoing call by searching for available call services
     * through which the call can be placed. After a lookup for those services completes, execution
     * returns to {@link #setCallServices} where the process of placing the call continues.
     *
     * @param call The yet-to-be-connected outgoing-call object.
     */
    void placeOutgoingCall(Call call) {
        ThreadUtil.checkOnMainThread();

        mNewOutgoingCalls.add(call);

        // We initialize a lookup every time because between calls the set of available call
        // services can change between calls.
        mLookupId++;
        mCallServiceRepository.initiateLookup(mLookupId);
        mSelectorRepository.initiateLookup(mLookupId);
    }

    /**
     * Confirms with incoming call manager that an incoming call exists for the specified call
     * service and call token. The incoming call manager will invoke either
     * {@link #handleSuccessfulIncomingCall} or {@link #handleFailedIncomingCall} depending
     * on the result.
     *
     * @param call The call object.
     * @param callServiceInfo The details of the call service.
     * @param callToken The token used by the call service to identify the incoming call.
     */
    void confirmIncomingCall(Call call, CallServiceInfo callServiceInfo, String callToken) {
        CallServiceWrapper callService = mCallServiceRepository.getCallService(callServiceInfo);
        mIncomingCallsManager.confirmIncomingCall(call, callService, callToken);
    }

    /**
     * Persists the specified set of call services and attempts to place any pending outgoing
     * calls.  Intended to be invoked by {@link CallServiceRepository} exclusively.
     *
     * @param callServices The potentially-partial set of call services.  Partial since the lookup
     *     process is time-boxed, such that some providers/call-services may be slow to respond and
     *     hence effectively omitted from the specified list.
     */
    void setCallServices(Set<CallServiceWrapper> callServices) {
        ThreadUtil.checkOnMainThread();

        mCallServices = callServices;
        processNewOutgoingCalls();
    }

    /**
     * Persists the specified list of selectors and attempts to connect any pending outgoing
     * calls.  Intended to be invoked by {@link CallServiceSelectorRepository} exclusively.
     *
     * @param selectors The potentially-partial set of selectors.  Partial since the lookup
     *     procedure is time-boxed such that some selectors may be slow to respond and hence
     *     effectively omitted from the specified set.
     */
    void setSelectors(Set<ICallServiceSelector> selectors) {
        // TODO(santoscordon): This should take in CallServiceSelectorWrapper instead of the direct
        // ICallServiceSelector implementation. Copy what we have for CallServiceWrapper.
        ThreadUtil.checkOnMainThread();

        // TODO(gilad): Add logic to include the built-in selectors (e.g. for dealing with
        // emergency calls) and order the entire set prior to the assignment below. If the
        // built-in selectors can be implemented in a manner that does not require binding,
        // that's probably preferred.  May want to use a LinkedHashSet for the sorted set.
        mSelectors = selectors;
        processNewOutgoingCalls();
    }

    /**
     * Handles the case where an outgoing call has been successfully placed,
     * see {@link OutgoingCallProcessor}.
     */
    void handleSuccessfulOutgoingCall(Call call) {
        mCallsManager.handleSuccessfulOutgoingCall(call);

        // Process additional (new) calls, if any.
        processNewOutgoingCalls();
    }

    /**
     * Handles the case where an outgoing call could not be proceed by any of the
     * selector/call-service implementations, see {@link OutgoingCallProcessor}.
     */
    void handleFailedOutgoingCall(Call call) {
        // TODO(gilad): More here.

        // Process additional (new) calls, if any.
        processNewOutgoingCalls();
    }

    /**
     * Handles the case where we received confirmation of an incoming call. Hands the resulting
     * call to {@link CallsManager} as the final step in the incoming sequence. At that point,
     * {@link CallsManager} should bring up the incoming-call UI.
     */
    void handleSuccessfulIncomingCall(Call call) {
        mCallsManager.handleSuccessfulIncomingCall(call);
    }

    /**
     * Handles the case where we failed to confirm an incoming call after receiving an incoming-call
     * intent via {@link TelecommReceiver}.
     *
     * @param call The call.
     */
    void handleFailedIncomingCall(Call call) {
        // At the moment there is nothing to do if an incoming call is not confirmed. We may at a
        // future date bind to the in-call app optimistically during the incoming-call sequence and
        // this method could tell {@link CallsManager} to unbind from the in-call app if the
        // incoming call was not confirmed. It's worth keeping this method for parity with the
        // outgoing call sequence.
    }

    /**
     * @return True if ticking should continue (or be resumed) and false otherwise.
     */
    private boolean isTicking() {
        // TODO(gilad): return true every time at least one outgoing call is pending (i.e. waiting
        // to be connected by a call service) and also when at least one active call is switch-able
        // between call services, see {@link ICallServiceSelector#isSwitchable}.
        return false;
    }

    /**
     * Schedules the next tick invocation.
     */
    private void scheduleNextTick() {
         mHandler.postDelayed(mTicker, TICK_FREQUENCY);
    }

    /**
     * Performs the set of tasks that needs to be executed on polling basis.
     * TODO(gilad): Check for stale pending calls that may need to be terminated etc, see
     * mNewOutgoingCalls and mPendingOutgoingCalls.
     * TODO(gilad): Also intended to trigger the call switching/hand-off logic when applicable.
     */
    private void tick() {
        // TODO(gilad): More here.
        // TODO(santoscordon): Clear mCallServices if there exist no more new or pending outgoing
        // calls.
    }

    /**
     * Attempts to process the next new outgoing calls that have not yet been expired.
     */
    private void processNewOutgoingCalls() {
        if (isNullOrEmpty(mCallServices) || isNullOrEmpty(mSelectors)) {
            // At least one call service and one selector are required to process outgoing calls.
            return;
        }

        if (!mNewOutgoingCalls.isEmpty()) {
            Call call = mNewOutgoingCalls.iterator().next();
            mNewOutgoingCalls.remove(call);
            mPendingOutgoingCalls.add(call);

            // Specifically only attempt to place one call at a time such that call services
            // can be freed from needing to deal with concurrent requests.
            processNewOutgoingCall(call);
        }
    }

    /**
     * Attempts to place the specified call.
     *
     * @param call The call to place.
     */
    private void processNewOutgoingCall(Call call) {
        Preconditions.checkNotNull(mCallServices);
        Preconditions.checkNotNull(mSelectors);

        // Convert to (duplicate-free) list to aid index-based iteration, see the comment under
        // setSelectors regarding using LinkedHashSet instead.
        List<ICallServiceSelector> selectors = Lists.newArrayList();
        selectors.addAll(mSelectors);

        mOutgoingCallsManager.placeCall(call, mCallServices, selectors);
    }

    /**
     * Determines whether or not the specified collection is either null or empty.
     *
     * @param collection Either null or the collection object to be evaluated.
     * @return True if the collection is null or empty.
     */
    @SuppressWarnings("rawtypes")
    private boolean isNullOrEmpty(Collection collection) {
        return collection == null || collection.isEmpty();
    }
}
