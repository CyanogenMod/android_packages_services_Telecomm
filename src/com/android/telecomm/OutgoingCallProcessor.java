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

import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import com.google.common.collect.Lists;

import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.telecomm.CallInfo;
import android.telecomm.CallState;
import android.telecomm.ICallService;
import android.telecomm.ICallServiceSelectionResponse;
import android.telecomm.ICallServiceSelector;
import android.util.Log;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Utility class to place a call using the specified set of call-services and ordered selectors.
 * Iterates through the selectors and gets a sorted list of supported call service IDs for each
 * selector. Upon receiving each sorted list (one list per selector), each of the corresponding
 * call services is then attempted until either the outgoing call is placed, the attempted call
 * is aborted (by the switchboard), or the list is exhausted -- whichever occurs first.
 *
 * Except for the abort case, all other scenarios should terminate with the switchboard notified
 * of the result.
 *
 * NOTE(gilad): Currently operating under the assumption that we'll have one timeout per (outgoing)
 * call attempt.  If we (also) like to timeout individual selectors and/or call services, the code
 * here will need to be re-factored (quite a bit) to support that.
 */
final class OutgoingCallProcessor {

    /**
     * The outgoing call this processor is tasked with placing.
     */
    private final Call mCall;

    /**
     * The duplicate-free list of currently-available call-service IDs.
     */
    private final List<String> mCallServiceIds = Lists.newArrayList();

    /**
     * The map of currently-available call-service implementations keyed by call-service ID.
     */
    private final Map<String, ICallService> mCallServicesById = Maps.newHashMap();

    /**
     * The set of currently-available call-service selector implementations.
     */
    private final List<ICallServiceSelector> mSelectors;

    /** Used to run code (e.g. messages, Runnables) on the main (UI) thread. */
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    /** Manages all outgoing call processors. */
    private final OutgoingCallsManager mOutgoingCallsManager;

    private final Switchboard mSwitchboard;

    /**
     * The iterator over the currently-selected ordered list of call-service IDs.
     */
    private Iterator<String> mCallServiceIdIterator;

    private Iterator<ICallServiceSelector> mSelectorIterator;

    /**
     * The last call service which we asked to place the call. If null, it indicates that there
     * exists no call service that we expect to place this call.
     */
    private ICallService mCallService;

    private boolean mIsAborted = false;

    /**
     * Persists the specified parameters and iterates through the prioritized list of selectors
     * passing to each selector (read-only versions of) the call object and all available call-
     * service IDs.  Stops once a matching selector is found.  Calls with no matching selectors
     * will eventually be killed by the cleanup/monitor switchboard handler, which will in turn
     * call the abort method of this processor via {@link OutgoingCallsManager}.
     *
     * @param call The call to place.
     * @param callServices The available call-service implementations.
     * @param selectors The available call-service selector implementations.
     * @param outgoingCallsManager Manager of all outgoing call processors.
     * @param switchboard The switchboard.
     */
    OutgoingCallProcessor(
            Call call,
            Set<ICallService> callServices,
            List<ICallServiceSelector> selectors,
            OutgoingCallsManager outgoingCallsManager,
            Switchboard switchboard) {

        ThreadUtil.checkOnMainThread();

        Preconditions.checkNotNull(callServices);
        Preconditions.checkNotNull(selectors);

        mCall = call;
        mSelectors = selectors;
        mOutgoingCallsManager = outgoingCallsManager;
        mSwitchboard = switchboard;

        // Populate the list and map of call-service IDs.  The list is needed since
        // it's being passed down to selectors.
        for (ICallService callService : callServices) {
            // TOOD(gilad): Implement callService.getId() and use that instead.
            String id = "xyz";
            mCallServiceIds.add(id);
            mCallServicesById.put(id, callService);
        }
    }

    /**
     * Initiates the attempt to place the call.  No-op beyond the first invocation.
     */
    void process() {
        ThreadUtil.checkOnMainThread();

        if (mSelectors.isEmpty() || mCallServiceIds.isEmpty()) {
            // TODO(gilad): Consider adding a failure message/type to differentiate the various
            // cases, or potentially throw an exception in this case.
            mOutgoingCallsManager.handleFailedOutgoingCall(mCall);
        } else if (mSelectorIterator == null) {
            mSelectorIterator = mSelectors.iterator();
            attemptNextSelector();
        }
    }

    /**
     * Aborts the attempt to place the relevant call.  Intended to be invoked by
     * switchboard through the outgoing-calls manager.
     */
    void abort() {
        ThreadUtil.checkOnMainThread();
        resetCallService();
        mIsAborted = true;
    }

    /**
     * Completes the outgoing call sequence by setting the call service on the call object. This is
     * invoked when the call service adapter receives positive confirmation that the call service
     * placed the call.
     */
    void handleSuccessfulCallAttempt() {
        abort();  // Technically not needed but playing it safe.
        mCall.setCallService(mCallService);
        mCall.setState(CallState.DIALING);
        resetCallService();

        mSwitchboard.handleSuccessfulOutgoingCall(mCall);
    }

    /**
     * Attempts the next call service if the specified call service is the one currently being
     * attempted.
     *
     * @param reason The call-service supplied reason for the failed call attempt.
     */
    void handleFailedCallAttempt(String reason) {
        attemptNextCallService();
    }

    /**
     * Attempts to place the call using the next selector, no-op if no other selectors
     * are available.
     */
    private void attemptNextSelector() {
        if (mIsAborted) {
            return;
        }

        if (mSelectorIterator.hasNext()) {
            ICallServiceSelector selector = mSelectorIterator.next();
            ICallServiceSelectionResponse.Stub response = createSelectionResponse();
            try {
                selector.select(mCall.toCallInfo(), mCallServiceIds, response);
            } catch (RemoteException e) {
                attemptNextSelector();
            }

        } else {
            mOutgoingCallsManager.handleFailedOutgoingCall(mCall);
        }
    }

    /**
     * @return A new selection-response object that's wired to run on the main (UI) thread.
     */
    private ICallServiceSelectionResponse.Stub createSelectionResponse() {
        return new ICallServiceSelectionResponse.Stub() {
            @Override public void setSelectedCallServiceIds(
                    final List<String> selectedCallServiceIds) {

                Runnable runnable = new Runnable() {
                    @Override public void run() {
                        processSelectedCallServiceIds(selectedCallServiceIds);
                    }
                };

                mHandler.post(runnable);
            }
        };
    }

    /**
     * Persists the ordered-list of call service IDs as selected by the current selector and
     * starts iterating through the corresponding call services in the continuing attempt to
     * place the call.
     *
     * @selectedCallServiceIds The (ordered) list of call service IDs.
     */
    private void processSelectedCallServiceIds(List<String> selectedCallServiceIds) {
        if (selectedCallServiceIds == null || selectedCallServiceIds.isEmpty()) {
            attemptNextSelector();
        } else if (mCallServiceIdIterator == null) {
            mCallServiceIdIterator = selectedCallServiceIds.iterator();
            attemptNextCallService();
        }
    }

    /**
     * Attempts to place the call using the call service specified by the next call-service ID of
     * mCallServiceIdIterator. If there are no more call services to attempt, the process continues
     * to the next call-service selector via {@link #attemptNextSelector}.
     */
    private void attemptNextCallService() {
        if (mIsAborted) {
            return;
        }

        if (mCallServiceIdIterator.hasNext()) {
            String id = mCallServiceIdIterator.next();
            mCallService = mCallServicesById.get(id);
            if (mCallService == null) {
                attemptNextCallService();
            } else {
                try {
                    mCallService.call(mCall.toCallInfo());
                } catch (RemoteException e) {
                    // TODO(gilad): Log etc.
                    attemptNextCallService();
                }
            }
        } else {
            mCallServiceIdIterator = null;
            resetCallService();
            attemptNextSelector();
        }
    }

    /**
     * Nulls out the reference to the current call service. Invoked when the call service is no longer
     * expected to place the outgoing call.
     */
    private void resetCallService() {
        mCallService = null;
    }
}
