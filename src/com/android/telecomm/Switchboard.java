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
import android.os.Looper;
import android.telecomm.CallInfo;
import android.telecomm.CallServiceDescriptor;
import android.telecomm.TelecommConstants;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;

import java.util.Collection;
import java.util.Iterator;
import java.util.Set;

/**
 * Switchboard is responsible for:
 * - gathering the {@link CallServiceWrapper}s and {@link CallServiceSelectorWrapper}s through
 *       which to place outgoing calls
 * - starting outgoing calls (via {@link OutgoingCallsManager}
 * - switching active calls between call services.
 */
final class Switchboard {

    private final CallsManager mCallsManager;

    /** Used to place outgoing calls. */
    private final OutgoingCallsManager mOutgoingCallsManager;

    /** Used to retrieve incoming call details. */
    private final IncomingCallsManager mIncomingCallsManager;

    private final CallServiceRepository mCallServiceRepository;

    private final CallServiceSelectorRepository mSelectorRepository;

    private final BinderDeallocator mBinderDeallocator;

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
     * The set of currently available call-service implementations, see
     * {@link CallServiceRepository}.  Populated during call-service lookup cycles as part of the
     * {@link #placeOutgoingCall} flow and cleared upon zero-remaining new/pending outgoing calls.
     */
    private final Set<CallServiceWrapper> mCallServices = Sets.newHashSet();

    /**
     * The set of currently available call-service-selector implementations,
     * see {@link CallServiceSelectorRepository}.
     * TODO(gilad): Clear once the active-call count goes to zero.
     */
    private ImmutableCollection<CallServiceSelectorWrapper> mSelectors = ImmutableList.of();

    /**
     * The current lookup-cycle ID used with the repositories. Incremented with each invocation
     * of {@link #placeOutgoingCall} and passed to the repositories via initiateLookup().
     */
    private int mLookupId = 0;

    /**
     * Persists the specified parameters and initializes Switchboard.
     */
    Switchboard(CallsManager callsManager) {
        mCallsManager = callsManager;
        mOutgoingCallsManager = new OutgoingCallsManager(this);
        mIncomingCallsManager = new IncomingCallsManager(this);
        mSelectorRepository = new CallServiceSelectorRepository(this, mOutgoingCallsManager);
        mCallServiceRepository =
                new CallServiceRepository(this, mOutgoingCallsManager, mIncomingCallsManager);

        mBinderDeallocator = new BinderDeallocator();
    }

    /**
     * Starts the process of placing an outgoing call by searching for available call services
     * through which the call can be placed. After a lookup for those services completes, execution
     * returns to {@link #setCallServices} where the process of placing the call continues.
     *
     * @param call The yet-to-be-connected outgoing-call object.
     */
    void placeOutgoingCall(Call call) {
        mBinderDeallocator.acquireUsePermit();

        // Reset prior to initiating the next lookup. One case to consider is (1) placeOutgoingCall
        // is invoked with call A, (2) the call-service lookup completes, but the one for selectors
        // does not, (3) placeOutgoingCall is invoked again with call B, (4) mCallServices below is
        // reset, (5) the selector lookup completes but the call-services are missing.  This should
        // be okay since the call-service lookup completed. Specifically the already-available call
        // services are cached and will be provided in response to the second lookup cycle.
        mCallServices.clear();
        mSelectors = ImmutableList.of();

        mNewOutgoingCalls.add(call);

        // Initiate a lookup every time to account for newly-installed apps and/or updated settings.
        mLookupId++;
        mCallServiceRepository.initiateLookup(mLookupId);
        mSelectorRepository.initiateLookup(mLookupId);
    }

    /**
     * Retrieves details about the incoming call through the incoming call manager. The incoming
     * call manager will invoke either {@link #handleSuccessfulIncomingCall} or
     * {@link #handleFailedIncomingCall} depending on the result of the retrieval.
     *
     * @param call The call object.
     * @param descriptor The relevant call-service descriptor.
     * @param extras The optional extras passed via
     *         {@link TelecommConstants#EXTRA_INCOMING_CALL_EXTRAS}
     */
    void retrieveIncomingCall(Call call, CallServiceDescriptor descriptor, Bundle extras) {
        Log.d(this, "retrieveIncomingCall");
        mBinderDeallocator.acquireUsePermit();

        CallServiceWrapper callService = mCallServiceRepository.getCallService(descriptor);
        call.setCallService(callService);
        mIncomingCallsManager.retrieveIncomingCall(call, extras);
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
        mBinderDeallocator.updateBinders(mCallServices);

        mCallServices.clear();
        mCallServices.addAll(callServices);
        processNewOutgoingCalls();
    }

    /**
     * Persists the specified list of selectors and attempts to connect any pending outgoing
     * calls.  Intended to be invoked by {@link CallServiceSelectorRepository} exclusively.
     *
     * @param selectors Collection of selectors. The order of the collection determines the order in
     *     which the selectors are tried.
     */
    void setSelectors(ImmutableCollection<CallServiceSelectorWrapper> selectors) {
        // TODO(santoscordon):: Need to invoke updateBinders(selectors).
        ThreadUtil.checkOnMainThread();
        Preconditions.checkNotNull(selectors);

        // TODO(gilad): Add logic to include the built-in selectors (e.g. for dealing with
        // emergency calls) and order the entire set prior to the assignment below. If the
        // built-in selectors can be implemented in a manner that does not require binding,
        // that's probably preferred.
        mSelectors = selectors;
        processNewOutgoingCalls();
    }

    /**
     * Handles the case where an outgoing call has been successfully placed,
     * see {@link OutgoingCallProcessor}.
     */
    void handleSuccessfulOutgoingCall(Call call) {
        Log.d(this, "handleSuccessfulOutgoingCall");

        mCallsManager.handleSuccessfulOutgoingCall(call);
        finalizeOutgoingCall(call);
    }

    /**
     * Handles the case where an outgoing call could not be proceed by any of the
     * selector/call-service implementations, see {@link OutgoingCallProcessor}.
     */
    void handleFailedOutgoingCall(Call call, boolean isAborted) {
        Log.d(this, "handleFailedOutgoingCall");

        mCallsManager.handleUnsuccessfulOutgoingCall(call, isAborted);
        finalizeOutgoingCall(call);
    }

    /**
     * Handles the case where we successfully receive details of an incoming call. Hands the
     * resulting call to {@link CallsManager} as the final step in the incoming sequence. At that
     * point, {@link CallsManager} should bring up the incoming-call UI.
     */
    void handleSuccessfulIncomingCall(Call call, CallInfo callInfo) {
        Log.d(this, "handleSuccessfulIncomingCall");

        mCallsManager.handleSuccessfulIncomingCall(call, callInfo);
        mBinderDeallocator.releaseUsePermit();
    }

    /**
     * Handles the case where we failed to retrieve an incoming call after receiving an incoming-call
     * intent via {@link CallActivity}.
     *
     * @param call The call.
     */
    void handleFailedIncomingCall(Call call) {
        Log.d(this, "handleFailedIncomingCall");

        // Since we set the call service before calling into incoming-calls manager, we clear it for
        // good measure if an error is reported.
        call.clearCallService();

        mBinderDeallocator.releaseUsePermit();

        mCallsManager.handleUnsuccessfulIncomingCall(call);

        // At the moment there is nothing more to do if an incoming call is not retrieved. We may at
        // a future date bind to the in-call app optimistically during the incoming-call sequence
        // and this method could tell {@link CallsManager} to unbind from the in-call app if the
        // incoming call was not retrieved.
    }

    /**
     * @return True if ticking should continue (or be resumed) and false otherwise.
     */
    private boolean isTicking() {
        // TODO(gilad): return true every time at least one outgoing call is pending (i.e. waiting
        // to be connected by a call service).
        return false;
    }

    /**
     * Schedules the next tick invocation.
     */
    private void scheduleNextTick() {
         mHandler.postDelayed(mTicker, Timeouts.getTickMs());
    }

    /**
     * Attempts to process the next new outgoing calls that have not yet been expired.
     */
    private void processNewOutgoingCalls() {
        if (mCallServices.isEmpty() || mSelectors.isEmpty()) {
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
        Collection<CallServiceSelectorWrapper> selectors;

        // Use the call's selector if it's already tied to one. This is the case for handoff calls.
        if (call.getCallServiceSelector() != null) {
            selectors = ImmutableList.of(call.getCallServiceSelector());
        } else {
            selectors = mSelectors;
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
                            mCallsManager,
                            mOutgoingCallsManager);

            selectorsBuilder.add(emergencySelector);
            selectorsBuilder.addAll(selectors);
            selectors = selectorsBuilder.build();
        }

        mOutgoingCallsManager.placeCall(call, mCallServices, selectors);
    }

    /**
     * Finalizes the outgoing-call sequence, regardless if it succeeded or failed.
     */
    private void finalizeOutgoingCall(Call call) {
        mPendingOutgoingCalls.remove(call);

        mBinderDeallocator.releaseUsePermit();
        processNewOutgoingCalls();  // Process additional (new) calls, if any.
    }

    /**
     * Performs the set of tasks that needs to be executed on polling basis.
     */
    private void tick() {
        // TODO(gilad): Add the necessary logic to support switching.

        expireStaleOutgoingCalls(mNewOutgoingCalls);
        expireStaleOutgoingCalls(mPendingOutgoingCalls);
    }

    /**
     * Identifies stale calls and takes the necessary steps to mark these as expired.
     *
     * @param calls The set of calls to iterate through.
     */
    private void expireStaleOutgoingCalls(Set<Call> calls) {
        if (calls.isEmpty()) {
            return;
        }

        final long newCallTimeoutMs = Timeouts.getNewOutgoingCallMs();
        Iterator<Call> iterator = calls.iterator();
        while (iterator.hasNext()) {
            Call call = iterator.next();
            if (call.getAgeMs() >= newCallTimeoutMs) {
                Log.d(this, "Call %s timed out.", call);
                mOutgoingCallsManager.abort(call);
                calls.remove(call);

                // TODO(gilad): We may also have expired calls that are not yet associated with an
                // OutgoingCallProcessor (e.g. when newer calls are "blocked" on older-yet-expired
                // ones), in which case call.abort may need to be invoked directly.  Alternatively
                // we can also create an OutgoingCallsManager instance for every new call at intent-
                // processing time.

                // TODO(gilad): Notify the user in the relevant cases (at least outgoing).

                mBinderDeallocator.releaseUsePermit();
            }
        }
    }
}
