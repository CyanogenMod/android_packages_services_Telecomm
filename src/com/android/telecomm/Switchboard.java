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

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import android.content.Context;
import android.os.RemoteException;
import android.telecomm.ICallService;
import android.util.Log;

import com.android.telecomm.exceptions.CallServiceUnavailableException;
import com.android.telecomm.exceptions.OutgoingCallException;

import java.util.List;
import java.util.Set;

/**
 * Switchboard is responsible for (1) selecting the {@link ICallService} through which to make
 * outgoing calls and (2) switching active calls between transports (each ICallService is
 * considered a different transport type).
 * TODO(santoscordon): Need to add comments on the switchboard optimizer once that it is place.
 * TODO(gilad): Add a monitor thread to wake up periodically and check for stale pending calls
 *     that may need to be terminated, see mPendingOutgoingCalls.
 */
final class Switchboard {

    private static final String TAG = Switchboard.class.getSimpleName();

    private CallServiceFinder callServiceFinder = new CallServiceFinder(this);

    private Set<Call> mPendingOutgoingCalls = Sets.newHashSet();

    /**
     * Places an outgoing call to the handle passed in. Method asynchronously collects
     * {@link ICallService} implementations and passes them along with the handle and contactInfo
     * to {@link #placeOutgoingCallInternal} to actually place the call.
     *
     * @param handle The handle to dial.
     * @param contactInfo Information about the entity being called.
     * @param context The application context.
     */
    void placeOutgoingCall(String handle, ContactInfo contactInfo, Context context) {
        synchronized (mPendingOutgoingCalls) {
            mPendingOutgoingCalls.add(new Call(handle, contactInfo));
        }
        callServiceFinder.initiateLookup(context);
    }

    /**
     * Persists the specified list of call services and attempts to connect any pending outgoing
     * calls still waiting for a matching call-service to be initiated.
     *
     * @param callServices The potentially-partial list of call services the switchboard should
     *     feel free to make use of.  Partial since the lookup procedure is time-boxed such that
     *     some providers/call-services may be too slow to respond and hence effectively omitted
     *     from the specified list.  If the switchboard has previous/reliable knowledge of other
     *     call-services, it should be at liberty to use these just as well.
     */
    void setCallServices(List<ICallService> callServices) {
        synchronized (mPendingOutgoingCalls) {
            for (Call pendingCall : mPendingOutgoingCalls) {
                // TODO(gilad): Iterate through the prioritized list of switchboard policies passing
                // to each policy the call object as well as all known call services.  Break out of
                // the inner/policy loop as soon as the first matching policy for the call is found.
                // Calls for which no matching policy can be found will be killed by cleanup/monitor
                // thread, see the "monitor" to-do at the top of the file.

                // Psuedo code (assuming connect to be a future switchboard method):
                //
                //   FOR policy IN prioritizedPolicies:
                //     IF policy.is_applicable_to(pendingCall, callServices):
                //       TRY
                //         connect(pendingCall, callServices, policy)
                //         mPendingOutgoingCalls.remove(pendingCall)
                //         BREAK
            }
        }
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

    /**
     * Sorts a list of {@link ICallService} ordered by the preferred service for dialing the call.
     *
     * @param callServices The list to order.
     */
    private List<ICallService> sort(List<ICallService> callServices) {
        // TODO(android-contacts): Sort by reliability, cost, and ultimately
        // the desirability to issue a given call over each of the specified
        // call services.
        return callServices;
    }
}
