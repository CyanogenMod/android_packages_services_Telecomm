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
import android.telecomm.ICallService;
import android.telecomm.ICallServiceSelectionResponse;
import android.telecomm.ICallServiceSelector;

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
 * Except for the abort case, all other scenarios should terminate with the switchboard notified.
 *
 * NOTE(gilad): Currently operating under the assumption that we'll have one timeout per (outgoing)
 * call attempt.  If we (also) like to timeout individual selectors and/or call services, the code
 * here will need to be re-factored (quite a bit) to support that.
 */
final class OutgoingCallProcessor {

    /**
     * The (singleton) Telecomm switchboard instance.
     */
    private final Switchboard mSwitchboard;

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

    /**
     * The iterator over the currently-selected ordered list of call-service IDs.
     */
    private Iterator<String> mCallServiceIdIterator;

    private Iterator<ICallServiceSelector> mSelectorIterator;

    private boolean mIsAborted = false;

    /**
     * Persists the specified parameters and iterates through the prioritized list of selectors
     * passing to each selector (read-only versions of) the call object and all available call-
     * service IDs.  Stops once a matching selector is found.  Calls with no matching selectors
     * will eventually be killed by the cleanup/monitor switchboard handler, which will in turn
     * call the abort method of this processor.
     *
     * @param call The call to place.
     * @param callServices The available call-service implementations.
     * @param selectors The available call-service selector implementations.
     * @param switchboard The switchboard for this processor to work against.
     */
    OutgoingCallProcessor(
            Call call,
            Set<ICallService> callServices,
            List<ICallServiceSelector> selectors,
            Switchboard switchboard) {

        ThreadUtil.checkOnMainThread();

        Preconditions.checkNotNull(callServices);
        Preconditions.checkNotNull(selectors);

        mCall = call;
        mSelectors = selectors;
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
            mSwitchboard.handleFailedOutgoingCall(mCall);
        } else if (mSelectorIterator == null) {
            mSelectorIterator = mSelectors.iterator();
            attemptNextSelector();
        }
    }

    /**
     * Aborts the attempt to place the relevant call.  Intended to be invoked by
     * switchboard.
     */
    void abort() {
        ThreadUtil.checkOnMainThread();
        mIsAborted = true;
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
            mSwitchboard.handleFailedOutgoingCall(mCall);
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
     * TODO(gilad): Add comment.
     */
    private void attemptNextCallService() {
        if (mIsAborted) {
            return;
        }

        if (mCallServiceIdIterator.hasNext()) {
            String id = mCallServiceIdIterator.next();
            ICallService callService = mCallServicesById.get(id);
            if (callService != null) {
                try {
                    callService.call(mCall.toCallInfo());
                } catch (RemoteException e) {
                    // TODO(gilad): Log etc.
                    attemptNextCallService();
                }
            }
        } else {
            mCallServiceIdIterator = null;
            attemptNextSelector();
        }
    }

    /**
     * Handles the successful outgoing-call case.
     */
    private void handleSuccessfulOutgoingCall() {
        // TODO(gilad): More here?

        abort();  // Shouldn't be necessary but better safe than sorry.
        mSwitchboard.handleSuccessfulOutgoingCall(mCall);
    }

    /**
     * Handles the failed outgoing-call case.
     */
    private void handleFailedOutgoingCall() {
        // TODO(gilad): Implement.
        attemptNextCallService();
    }
}
