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

import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.telecomm.CallServiceDescriptor;
import android.telephony.DisconnectCause;
import android.telephony.PhoneNumberUtils;

import com.android.telecomm.BaseRepository.LookupCallback;
import com.google.android.collect.Sets;
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
 */
final class OutgoingCallProcessor {

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

    /**
     * The duplicate-free list of currently-available call-service descriptors.
     */
    private List<CallServiceDescriptor> mCallServiceDescriptors;

    /**
     * The iterator over the currently-selected ordered list of call-service descriptors.
     */
    private Iterator<CallServiceDescriptor> mCallServiceDescriptorIterator;

    private OutgoingCallResponse mResultCallback;

    private boolean mIsAborted = false;

    private int mLastErrorCode = 0;

    private String mLastErrorMsg = null;

    /**
     * Persists the specified parameters and iterates through the prioritized list of call
     * services. Stops once a matching call service is found. Calls with no matching
     * call service will eventually be killed by the cleanup/monitor switchboard handler.
     *
     * @param call The call to place.
     * @param callServiceRepository
     * @param resultCallback The callback on which to return the result.
     */
    OutgoingCallProcessor(
            Call call,
            CallServiceRepository callServiceRepository,
            OutgoingCallResponse resultCallback) {

        ThreadUtil.checkOnMainThread();

        mCall = call;
        mResultCallback = resultCallback;
        mCallServiceRepository = callServiceRepository;
    }

    /**
     * Initiates the attempt to place the call.  No-op beyond the first invocation.
     */
    void process() {
        Log.v(this, "process, mIsAborted: %b", mIsAborted);
        if (!mIsAborted) {
            // Lookup call services
            mCallServiceRepository.lookupServices(new LookupCallback<CallServiceWrapper>() {
                @Override
                public void onComplete(Collection<CallServiceWrapper> services) {
                    setCallServices(services);
                }
            });
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

            // We consider a deliberate abort to be a "normal" disconnect, not
            // requiring special reporting.
            sendResult(false, DisconnectCause.LOCAL, null);
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

        sendResult(true, DisconnectCause.NOT_DISCONNECTED, null);
    }

    /**
     * Attempts the next call service if the specified call service is the one currently being
     * attempted.
     *
     * @param errorCode The reason for the failure, one of {@link DisconnectCause}.
     * @param errorMsg Optional text reason for the failure.
     */
    void handleFailedCallAttempt(int errorCode, String errorMsg) {
        Log.v(this, "handleFailedCallAttempt %s %s", DisconnectCause.toString(errorCode), errorMsg);
        // Store latest error code and message. If this is our last available attempt at placing
        // a call, these error details will be considered "the" cause of the failure.
        mLastErrorCode = errorCode;
        mLastErrorMsg = errorMsg;
        if (!mIsAborted) {
            ThreadUtil.checkOnMainThread();
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
            // TODO(sail): Remove once there's a way to pick the service.
            if (descriptor.getServiceComponent().getPackageName().equals(
                    "com.google.android.talk")) {
                Log.i(this, "Moving call service %s to top of list", descriptor);
                mCallServiceDescriptors.add(0, descriptor);
            } else {
                mCallServiceDescriptors.add(descriptor);
            }
            mCallServicesById.put(descriptor.getCallServiceId(), callService);
        }

        adjustCallServiceDescriptorsForEmergency();

        mCallServiceDescriptorIterator = mCallServiceDescriptors.iterator();
        attemptNextCallService();
    }

    /**
     * Attempts to place the call using the call service specified by the next call-service
     * descriptor of mCallServiceDescriptorIterator.
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

                Log.i(this, "Attempting to call from %s", callService.getDescriptor());
                callService.call(mCall, new OutgoingCallResponse() {
                    @Override
                    public void onOutgoingCallSuccess() {
                        handleSuccessfulCallAttempt(callService);
                        callService.decrementAssociatedCallCount();
                    }

                    @Override
                    public void onOutgoingCallFailure(int code, String msg) {
                        handleFailedCallAttempt(code, msg);
                        callService.decrementAssociatedCallCount();
                    }

                    @Override
                    public void onOutgoingCallCancel() {
                        abort();
                        callService.decrementAssociatedCallCount();
                    }
                });
            }
        } else {
            Log.v(this, "attemptNextCallService, no more service descriptors, failing");
            mCallServiceDescriptorIterator = null;
            mCall.clearCallService();
            sendResult(false, mLastErrorCode, mLastErrorMsg);
        }
    }

    private void sendResult(boolean wasCallPlaced, int errorCode, String errorMsg) {
        if (mResultCallback != null) {
            if (mIsAborted) {
                mResultCallback.onOutgoingCallCancel();
            } else if (wasCallPlaced) {
                mResultCallback.onOutgoingCallSuccess();
            } else {
                mResultCallback.onOutgoingCallFailure(errorCode, errorMsg);
            }
            mResultCallback = null;
        } else {
            Log.wtf(this, "Attempting to return outgoing result twice for call %s", mCall);
        }
    }

    // If we are possibly attempting to call a local emergency number, ensure that the
    // plain PSTN call service, if it exists, is attempted first.
    private void adjustCallServiceDescriptorsForEmergency()  {
        for (int i = 0; i < mCallServiceDescriptors.size(); i++) {
            if (shouldProcessAsEmergency(mCall.getHandle())) {
                if (TelephonyUtil.isPstnCallService(mCallServiceDescriptors.get(i))) {
                    mCallServiceDescriptors.add(0, mCallServiceDescriptors.remove(i));
                    return;
                }
            } else {
                if (mCallServiceDescriptors.get(i).getServiceComponent().getPackageName().equals(
                        "com.android.telecomm.tests")) {
                    mCallServiceDescriptors.add(0, mCallServiceDescriptors.remove(i));
                }
            }
        }
    }

    private boolean shouldProcessAsEmergency(Uri handle) {
        return PhoneNumberUtils.isPotentialLocalEmergencyNumber(
                TelecommApp.getInstance(), handle.getSchemeSpecificPart());
    }
}
