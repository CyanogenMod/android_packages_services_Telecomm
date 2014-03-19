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

import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.telecomm.CallState;
import android.telecomm.CallServiceDescriptor;

import com.android.internal.telecomm.ICallServiceSelectionResponse;
import com.android.internal.telecomm.ICallServiceSelector;
import com.google.android.collect.Sets;
import com.google.common.collect.Maps;
import com.google.common.collect.Lists;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Utility class to place a call using the specified set of call-services and ordered selectors.
 * Iterates through the selectors and gets a sorted list of supported call-service descriptors
 * for each selector. Upon receiving each sorted list (one list per selector), each of the
 * corresponding call services is then attempted until either the outgoing call is placed, the
 * attempted call is aborted (by the switchboard), or the list is exhausted -- whichever occurs
 * first.
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
     * The duplicate-free list of currently-available call-service descriptors.
     */
    private final List<CallServiceDescriptor> mCallServiceDescriptors = Lists.newArrayList();

    /**
     * The map of currently-available call-service implementations keyed by call-service ID.
     */
    private final Map<String, CallServiceWrapper> mCallServicesById = Maps.newHashMap();

    /**
     * The set of attempted call services, used to ensure services are attempted at most once per
     * outgoing-call attempt.
     */
    private final Set<CallServiceWrapper> mAttemptedCallServices = Sets.newHashSet();

    /**
     * The set of incompatible call services, used to suppress unnecessary call switching attempts.
     */
    private final Set<CallServiceWrapper> mIncompatibleCallServices = Sets.newHashSet();

    /**
     * The list of currently-available call-service selector implementations.
     */
    private final List<ICallServiceSelector> mSelectors;

    /** Used to run code (e.g. messages, Runnables) on the main (UI) thread. */
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    /** Manages all outgoing call processors. */
    private final OutgoingCallsManager mOutgoingCallsManager;

    private final Switchboard mSwitchboard;

    private final Runnable mNextCallServiceCallback = new Runnable() {
        @Override public void run() {
            attemptNextCallService();
        }
    };

    /**
     * The iterator over the currently-selected ordered list of call-service descriptors.
     */
    private Iterator<CallServiceDescriptor> mCallServiceDescriptorIterator;

    private Iterator<ICallServiceSelector> mSelectorIterator;

    private boolean mIsAborted = false;

    /**
     * Persists the specified parameters and iterates through the prioritized list of selectors
     * passing to each selector (read-only versions of) the call object and all available call-
     * service descriptors.  Stops once a matching selector is found.  Calls with no matching
     * selectors will eventually be killed by the cleanup/monitor switchboard handler, which will
     * in turn call the abort method of this processor via {@link OutgoingCallsManager}.
     *
     * @param call The call to place.
     * @param callServices The available call-service implementations.
     * @param selectors The available call-service selector implementations.
     * @param outgoingCallsManager Manager of all outgoing call processors.
     * @param switchboard The switchboard.
     */
    OutgoingCallProcessor(
            Call call,
            Set<CallServiceWrapper> callServices,
            List<ICallServiceSelector> selectors,
            OutgoingCallsManager outgoingCallsManager,
            Switchboard switchboard) {

        ThreadUtil.checkOnMainThread();

        mCall = call;
        mSelectors = selectors;
        mOutgoingCallsManager = outgoingCallsManager;
        mSwitchboard = switchboard;

        // Populate the list and map of call-service descriptors.  The list is needed since
        // it's being passed down to selectors.
        for (CallServiceWrapper callService : callServices) {
            CallServiceDescriptor descriptor = callService.getDescriptor();
            mCallServiceDescriptors.add(descriptor);
            mCallServicesById.put(descriptor.getCallServiceId(), callService);
        }
    }

    /**
     * Initiates the attempt to place the call.  No-op beyond the first invocation.
     */
    void process() {
        Log.v(this, "process, mIsAborted: %b", mIsAborted);
        if (!mIsAborted) {
            // Only process un-aborted calls.
            ThreadUtil.checkOnMainThread();

            if (mSelectorIterator == null) {
                mSelectorIterator = mSelectors.iterator();
                attemptNextSelector();
            }
        }
    }

    /**
     * Handles the specified compatibility status from the call-service implementation.
     * TODO(gilad): Consider making this class stateful, potentially rejecting out-of-order/
     * unexpected invocations (i.e. beyond checking for unexpected call IDs).
     *
     * @param callId The ID of the call.
     * @param isCompatible True if the call-service is compatible with the corresponding call and
     *     false otherwise.
     */
    void setIsCompatibleWith(String callId, boolean isCompatible) {
        Log.v(this, "setIsCompatibleWith, callId: %s, isCompatible: %b", callId, isCompatible);
        if (!callId.equals(mCall.getId())) {
            Log.wtf(this, "setIsCompatibleWith invoked with unexpected call ID: %s - expected call"
                  + " ID: %s", callId, mCall.getId());
            return;
        }

        if (!mIsAborted) {
            CallServiceWrapper callService = mCall.getCallService();
            if (callService != null) {
                if (isCompatible) {
                    callService.call(mCall.toCallInfo(), mNextCallServiceCallback);
                    return;
                }
                mIncompatibleCallServices.add(callService);
            }
            attemptNextCallService();
        }
    }

    /**
     * Aborts the attempt to place the relevant call.  Intended to be invoked by
     * switchboard through the outgoing-calls manager.
     */
    void abort() {
        Log.v(this, "abort");
        ThreadUtil.checkOnMainThread();
        if (!mIsAborted) {
            mIsAborted = true;
            mOutgoingCallsManager.handleFailedOutgoingCall(mCall, true /* isAborted */);
        }
    }

    /**
     * Completes the outgoing call sequence by setting the call service on the call object. This is
     * invoked when the call service adapter receives positive confirmation that the call service
     * placed the call.
     */
    void handleSuccessfulCallAttempt() {
        Log.v(this, "handleSuccessfulCallAttempt");
        ThreadUtil.checkOnMainThread();

        if (mIsAborted) {
            // TODO(gilad): Ask the call service to drop the call?
            return;
        }

        for (CallServiceWrapper callService : mIncompatibleCallServices) {
            mCall.addIncompatibleCallService(callService);
        }
        mSwitchboard.handleSuccessfulOutgoingCall(mCall);
    }

    /**
     * Attempts the next call service if the specified call service is the one currently being
     * attempted.
     *
     * @param reason The call-service supplied reason for the failed call attempt.
     */
    void handleFailedCallAttempt(String reason) {
        Log.v(this, "handleFailedCallAttempt");
        if (!mIsAborted) {
            ThreadUtil.checkOnMainThread();

            mCall.clearCallService();
            mCall.clearCallServiceSelector();
            attemptNextCallService();
        }
    }

    /**
     * Attempts to place the call using the next selector, no-op if no other selectors
     * are available.
     */
    private void attemptNextSelector() {
        Log.v(this, "attemptNextSelector, mIsAborted: %b", mIsAborted);
        if (mIsAborted) {
            return;
        }

        if (mSelectorIterator.hasNext()) {
            ICallServiceSelector selector = mSelectorIterator.next();
            mCall.setCallServiceSelector(selector);

            ICallServiceSelectionResponse.Stub response = createSelectionResponse();
            try {
                selector.select(mCall.toCallInfo(), mCallServiceDescriptors, response);
            } catch (RemoteException e) {
                attemptNextSelector();
            }

        } else {
            Log.v(this, "attemptNextSelector, no more selectors, failing");
            mOutgoingCallsManager.handleFailedOutgoingCall(mCall, false /* isAborted */);
        }
    }

    /**
     * @return A new selection-response object that's wired to run on the main (UI) thread.
     */
    private ICallServiceSelectionResponse.Stub createSelectionResponse() {
        return new ICallServiceSelectionResponse.Stub() {
            @Override public void setSelectedCallServiceDescriptors(
                    final List<CallServiceDescriptor> selectedCallServiceDescriptors) {

                Runnable runnable = new Runnable() {
                    @Override public void run() {
                        processSelectedCallServiceDescriptors(selectedCallServiceDescriptors);
                    }
                };

                mHandler.post(runnable);
            }
        };
    }

    /**
     * Persists the ordered-list of call-service descriptor as selected by the current selector and
     * starts iterating through the corresponding call services continuing the attempt to place the
     * call.
     *
     * @selectedCallServiceDescriptors The (ordered) list of call-service descriptor.
     */
    private void processSelectedCallServiceDescriptors(
            List<CallServiceDescriptor> selectedCallServiceDescriptors) {

        Log.v(this, "processSelectedCallServiceDescriptors");
        if (selectedCallServiceDescriptors == null || selectedCallServiceDescriptors.isEmpty()) {
            attemptNextSelector();
        } else if (mCallServiceDescriptorIterator == null) {
            mCallServiceDescriptorIterator = selectedCallServiceDescriptors.iterator();
            attemptNextCallService();
        }
    }

    /**
     * Attempts to place the call using the call service specified by the next call-service
     * descriptor of mCallServiceDescriptorIterator.  If there are no more call services to
     * attempt, the process continues to the next call-service selector via
     * {@link #attemptNextSelector}.
     */
    private void attemptNextCallService() {
        Log.v(this, "attemptNextCallService, mIsAborted: %b", mIsAborted);
        if (mIsAborted) {
            return;
        }

        if (mCallServiceDescriptorIterator != null && mCallServiceDescriptorIterator.hasNext()) {
            CallServiceDescriptor descriptor = mCallServiceDescriptorIterator.next();
            final CallServiceWrapper callService =
                    mCallServicesById.get(descriptor.getCallServiceId());

            if (callService == null || mAttemptedCallServices.contains(callService)) {
                // The next call service is either null or has already been attempted, fast forward
                // to the next.
                attemptNextCallService();
            } else {
                mAttemptedCallServices.add(callService);
                mCall.setCallService(callService);
                callService.isCompatibleWith(mCall.toCallInfo(), mNextCallServiceCallback);
            }
        } else {
            mCallServiceDescriptorIterator = null;
            attemptNextSelector();
        }
    }
}
