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

import android.content.ComponentName;
import android.os.Handler;
import android.os.Message;
import android.telecomm.CallServiceDescriptor;

import com.android.telecomm.BaseRepository.LookupCallback;
import com.google.android.collect.Sets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Utility class to place a call using the specified set of call-services and ordered selectors.
 * Iterates through the selectors and gets a sorted list of supported call-service descriptors
 * for each selector. Upon receiving each sorted list (one list per selector), each of the
 * corresponding call services is then attempted until either the outgoing call is placed, the
 * attempted call is aborted, or the list is exhausted -- whichever occurs first.
 *
 * Except for the abort case, all other scenarios should terminate with the call notified
 * of the result.
 *
 * NOTE(gilad): Currently operating under the assumption that we'll have one timeout per (outgoing)
 * call attempt.  If we (also) like to timeout individual selectors and/or call services, the code
 * here will need to be re-factored (quite a bit) to support that.
 */
final class OutgoingCallProcessor {

    private final static int MSG_EXPIRE = 1;

    /**
     * The outgoing call this processor is tasked with placing.
     */
    private final Call mCall;

    /**
     * The map of currently-available call-service implementations keyed by call-service ID.
     */
    private final Map<String, CallServiceWrapper> mCallServicesById = Maps.newHashMap();

    /**
     * The set of attempted call services, used to ensure services are attempted at most once per
     * outgoing-call attempt.
     */
    private final Set<CallServiceWrapper> mAttemptedCallServices = Sets.newHashSet();

    private final CallServiceRepository mCallServiceRepository;

    private final CallServiceSelectorRepository mSelectorRepository;

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_EXPIRE:
                    abort();
                    break;
            }
        }
    };

    /**
     * The duplicate-free list of currently-available call-service descriptors.
     */
    private List<CallServiceDescriptor> mCallServiceDescriptors;

    /**
     * The iterator over the currently-selected ordered list of call-service descriptors.
     */
    private Iterator<CallServiceDescriptor> mCallServiceDescriptorIterator;

    /**
     * The list of currently-available call-service selector implementations.
     */
    private Collection<CallServiceSelectorWrapper> mSelectors;

    private Iterator<CallServiceSelectorWrapper> mSelectorIterator;

    private AsyncResultCallback<Boolean> mResultCallback;

    private boolean mIsAborted = false;

    /**
     * Persists the specified parameters and iterates through the prioritized list of selectors
     * passing to each selector (read-only versions of) the call object and all available call-
     * service descriptors.  Stops once a matching selector is found.  Calls with no matching
     * selectors will eventually be killed by the cleanup/monitor switchboard handler, which will
     * in turn call the abort method of this processor via {@link OutgoingCallsManager}.
     *
     * @param call The call to place.
     * @param callServiceRepository
     * @param selectorRepository
     * @param resultCallback The callback on which to return the result.
     */
    OutgoingCallProcessor(
            Call call,
            CallServiceRepository callServiceRepository,
            CallServiceSelectorRepository selectorRepository,
            AsyncResultCallback<Boolean> resultCallback) {

        ThreadUtil.checkOnMainThread();

        mCall = call;
        mResultCallback = resultCallback;
        mCallServiceRepository = callServiceRepository;
        mSelectorRepository = selectorRepository;
    }

    /**
     * Initiates the attempt to place the call.  No-op beyond the first invocation.
     */
    void process() {
        Log.v(this, "process, mIsAborted: %b", mIsAborted);
        if (!mIsAborted) {
            // Start the expiration timeout.
            mHandler.sendEmptyMessageDelayed(MSG_EXPIRE, Timeouts.getNewOutgoingCallMillis());

            // Lookup call services
            mCallServiceRepository.lookupServices(new LookupCallback<CallServiceWrapper>() {
                @Override
                public void onComplete(Collection<CallServiceWrapper> services) {
                    setCallServices(services);
                }
            });

            if (mCall.getCallServiceSelector() == null) {
                // Lookup selectors
                mSelectorRepository.lookupServices(
                        new LookupCallback<CallServiceSelectorWrapper>() {
                            @Override
                            public void onComplete(
                                    Collection<CallServiceSelectorWrapper> selectors) {
                                setSelectors(selectors);
                            }
                        });
            } else {
                setSelectors(ImmutableList.of(mCall.getCallServiceSelector()));
            }
        }
    }

    /**
     * Aborts the attempt to place the relevant call.  Intended to be invoked by
     * switchboard through the outgoing-calls manager.
     */
    void abort() {
        Log.v(this, "abort");
        ThreadUtil.checkOnMainThread();
        if (!mIsAborted && mResultCallback != null) {
            mIsAborted = true;

            // On an abort, we need to check if we already told the call service to place the
            // call. If so, we need to tell it to abort.
            // TODO(santoscordon): The call service is saved with the call and so we have to query
            // the call to get it, which is a bit backwards.  Ideally, the call service would be
            // saved inside this class until the whole thing is complete and then set on the call.
            CallServiceWrapper callService = mCall.getCallService();
            if (callService != null) {
                callService.abort(mCall);
            }

            sendResult(false);
        }
    }

    boolean isAborted() {
        return mIsAborted;
    }

    /**
     * Completes the outgoing call sequence by setting the call service on the call object. This is
     * invoked when the call service adapter receives positive confirmation that the call service
     * placed the call.
     */
    void handleSuccessfulCallAttempt(CallServiceWrapper callService) {
        Log.v(this, "handleSuccessfulCallAttempt");
        ThreadUtil.checkOnMainThread();

        if (mIsAborted) {
            // TODO(gilad): Ask the call service to drop the call?
            callService.abort(mCall);
            return;
        }

        sendResult(true);
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
            attemptNextCallService();
        }
    }

    /**
     * Persists the ordered-list of call-service descriptor as selected by the current selector and
     * starts iterating through the corresponding call services continuing the attempt to place the
     * call.
     *
     * @param descriptors The (ordered) list of call-service descriptor.
     */
    void processSelectedCallServices(List<CallServiceDescriptor> descriptors) {
        Log.v(this, "processSelectedCallServices");
        if (descriptors == null || descriptors.isEmpty()) {
            attemptNextSelector();
        } else if (mCallServiceDescriptorIterator == null) {
            mCallServiceDescriptorIterator = descriptors.iterator();
            attemptNextCallService();
        }
    }

    /**
     * Sets the call services to attempt for this outgoing call.
     *
     * @param callServices The call services.
     */
    private void setCallServices(Collection<CallServiceWrapper> callServices) {
        mCallServiceDescriptors = new ArrayList<>();

        // Populate the list and map of call-service descriptors.  The list is needed since
        // it's being passed down to selectors.
        for (CallServiceWrapper callService : callServices) {
            CallServiceDescriptor descriptor = callService.getDescriptor();
            mCallServiceDescriptors.add(descriptor);
            mCallServicesById.put(descriptor.getCallServiceId(), callService);
        }

        onLookupComplete();
    }

    /**
     * Sets the selectors to attemnpt for this outgoing call.
     *
     * @param selectors The call-service selectors.
     */
    private void setSelectors(Collection<CallServiceSelectorWrapper> selectors) {
        mSelectors = adjustForEmergencyCalls(selectors);
        onLookupComplete();
    }

    private void onLookupComplete() {
        if (!mIsAborted && mSelectors != null && mCallServiceDescriptors != null) {
            if (mSelectorIterator == null) {
                mSelectorIterator = mSelectors.iterator();
                attemptNextSelector();
            }
        }
    }

    /**
     * Updates the specified collection of selectors to accomodate for emergency calls and any
     * preferred selectors specified in the call object.
     *
     * @param selectors The selectors found through the selector repository.
     */
    private Collection<CallServiceSelectorWrapper> adjustForEmergencyCalls(
            Collection<CallServiceSelectorWrapper> selectors) {
        boolean useEmergencySelector =
                EmergencyCallServiceSelector.shouldUseSelector(mCall.getHandle());
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
                            CallsManager.getInstance());

            selectorsBuilder.add(emergencySelector);
            selectorsBuilder.addAll(selectors);
            selectors = selectorsBuilder.build();
        }

        return selectors;
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
            CallServiceSelectorWrapper selector = mSelectorIterator.next();
            mCall.setCallServiceSelector(selector);
            selector.select(mCall, mCallServiceDescriptors,
                    new AsyncResultCallback<List<CallServiceDescriptor>>() {
                        @Override
                        public void onResult(List<CallServiceDescriptor> descriptors) {
                            processSelectedCallServices(descriptors);
                        }
                    });
        } else {
            Log.v(this, "attemptNextSelector, no more selectors, failing");
            mCall.clearCallServiceSelector();
            sendResult(false);
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

                // Increment the associated call count until we get a result. This prevents the call
                // service from unbinding while we are using it.
                callService.incrementAssociatedCallCount();

                callService.call(mCall, new AsyncResultCallback<Boolean>() {
                    @Override
                    public void onResult(Boolean wasCallPlaced) {
                        if (wasCallPlaced) {
                            handleSuccessfulCallAttempt(callService);
                        } else {
                            handleFailedCallAttempt("call failed.");
                        }

                        // If successful, the call should not have it's own association to keep
                        // the call service bound.
                        callService.decrementAssociatedCallCount();
                    }
                });
            }
        } else {
            mCallServiceDescriptorIterator = null;
            mCall.clearCallService();
            attemptNextSelector();
        }
    }

    private void sendResult(boolean wasCallPlaced) {
        if (mResultCallback != null) {
            mResultCallback.onResult(wasCallPlaced);
            mResultCallback = null;

            mHandler.removeMessages(MSG_EXPIRE);
        } else {
            Log.wtf(this, "Attempting to return outgoing result twice for call %s", mCall);
        }
    }
}
