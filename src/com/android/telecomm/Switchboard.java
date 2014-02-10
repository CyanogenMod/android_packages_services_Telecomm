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

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.telecomm.ICallService;
import android.telecomm.ICallServiceSelector;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Switchboard is responsible for (1) selecting the {@link ICallService} through which to make
 * outgoing calls and (2) switching active calls between transports (each ICallService is
 * considered a different transport type).
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

    private CallServiceFinder mCallServiceFinder;

    private CallServiceSelectorFinder mSelectorFinder;

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
     * The set of currently available call-service implementations, see {@link CallServiceFinder}.
     * TODO(gilad): Null out once the active-call count goes to zero.
     */
    private Set<ICallService> mCallServices;

    /**
     * The set of currently available call-service-selector implementations,
     * see {@link CallServiceSelectorFinder}.
     * TODO(gilad): Null out once the active-call count goes to zero.
     */
    private Set<ICallServiceSelector> mSelectors;

    /**
     * Persists the specified parameters and initializes Switchboard.
     */
    Switchboard(CallsManager callsManager) {
        mCallsManager = callsManager;
        mOutgoingCallsManager = new OutgoingCallsManager(this);
        mCallServiceFinder = new CallServiceFinder(this, mOutgoingCallsManager);
        mSelectorFinder = new CallServiceSelectorFinder(this);
    }

    /**
     * Attempts to place an outgoing call to the specified handle.
     *
     * @param handle The handle to dial.
     * @param contactInfo Information about the entity being called.
     * @param context The application context.
     */
    void placeOutgoingCall(String handle, ContactInfo contactInfo, Context context) {
        ThreadUtil.checkOnMainThread();

        // TODO(gilad): Consider creating the call object even earlier, e.g. in CallsManager.
        Call call = new Call(handle, contactInfo);
        boolean bailout = false;
        if (isNullOrEmpty(mCallServices)) {
            mCallServiceFinder.initiateLookup();
            bailout = true;
        }
        if (isNullOrEmpty(mSelectors)) {
            mSelectorFinder.initiateLookup(context);
            bailout = true;
        }

        if (bailout) {
            // Unable to process the call without either call service, selectors, or both.
            // Store the call for deferred processing and bail out.
            mNewOutgoingCalls.add(call);
            return;
        }

        processNewOutgoingCall(call);
    }

    /**
     * Persists the specified set of call services and attempts to place any pending outgoing
     * calls.  Intended to be invoked by {@link CallServiceFinder} exclusively.
     *
     * @param callServices The potentially-partial set of call services.  Partial since the lookup
     *     process is time-boxed, such that some providers/call-services may be slow to respond and
     *     hence effectively omitted from the specified list.
     */
    void setCallServices(Set<ICallService> callServices) {
        ThreadUtil.checkOnMainThread();

        mCallServices = callServices;
        processNewOutgoingCalls();
    }

    /**
     * Persists the specified list of selectors and attempts to connect any pending outgoing
     * calls.  Intended to be invoked by {@link CallServiceSelectorFinder} exclusively.
     *
     * @param selectors The potentially-partial set of selectors.  Partial since the lookup
     *     procedure is time-boxed such that some selectors may be slow to respond and hence
     *     effectively omitted from the specified set.
     */
    void setSelectors(Set<ICallServiceSelector> selectors) {
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

    /**
     * Places an outgoing call to the handle passed in. Given a list of {@link ICallServices},
     * select one and place a call to the handle.
     * TODO(santoscordon): How does the CallService selection process work?
     * TODO(gilad): Wire this logic from CallServiceFinder.updateSwitchboard.
     *
     * @param handle The handle to dial.
     * @param contactInfo Information about the entity being called.
     * @param callServices The list of available {@link ICallService}s.
     */
//    private void placeOutgoingCallInternal(
//            String handle,
//            ContactInfo contactInfo,
//            List<ICallService> callServices) throws CallServiceUnavailableException {
//
//        Log.i(TAG, "Placing and outgoing call.");
//
//        if (callServices.isEmpty()) {
//            // No call services, bail out.
//            // TODO(contacts-team): Add logging?
//            // TODO(santoscordon): Does this actually go anywhere considering this method is now
//            // asynchronous?
//            throw new CallServiceUnavailableException("No CallService found.");
//        }
//
//        List<ICallService> compatibleCallServices = Lists.newArrayList();
//        for (ICallService service : callServices) {
//            // TODO(santoscordon): This code needs to be updated to an asynchronous response
//            // callback from isCompatibleWith().
//            /* if (service.isCompatibleWith(handle)) {
//                // NOTE(android-contacts): If we end up taking the liberty to issue
//                // calls not using the explicit user input (in case one is provided)
//                // and instead pull an alternative method of communication from the
//                // specified user-info object, it may be desirable to give precedence
//                // to services that can in fact respect the user's intent.
//                compatibleCallServices.add(service);
//            }
//            */
//        }
//
//        if (compatibleCallServices.isEmpty()) {
//            // None of the available call services is suitable for making this call.
//            // TODO(contacts-team): Same here re logging.
//            throw new CallServiceUnavailableException("No compatible CallService found.");
//        }
//
//        // NOTE(android-team): At this point we can also prompt the user for
//        // preference, i.e. instead of the logic just below.
//        if (compatibleCallServices.size() > 1) {
//            compatibleCallServices = sort(compatibleCallServices);
//        }
//        for (ICallService service : compatibleCallServices) {
//            try {
//                service.call(handle);
//                return;
//            } catch (RemoteException e) {
//                // TODO(santoscordon): Need some proxy for ICallService so that we don't have to
//                // avoid RemoteExceptionHandling everywhere. Take a look at how InputMethodService
//                // handles this.
//            }
//            // catch (OutgoingCallException ignored) {
//            // TODO(santoscordon): Figure out how OutgoingCallException falls into this. Should
//            // RemoteExceptions also be converted to OutgoingCallExceptions thrown by call()?
//        }
//    }
}
