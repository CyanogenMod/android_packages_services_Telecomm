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

import android.content.Context;
import android.os.RemoteException;
import android.telecomm.ICallService;
import android.util.Log;

import com.android.telecomm.CallServiceFinder.CallServiceSearchCallback;
import com.android.telecomm.exceptions.CallServiceUnavailableException;
import com.android.telecomm.exceptions.OutgoingCallException;

import java.util.List;

/**
 * Switchboard is responsible for (1) selecting the {@link ICallService} through which to make
 * outgoing calls and (2) switching active calls between transports (each ICallService is
 * considered a different transport type).
 * TODO(santoscordon): Need to add comments on the switchboard optimizer once that it is place.
 */
class Switchboard {
    /** Used to identify log entries by this class */
    private static final String TAG = Switchboard.class.getSimpleName();

    /**
     * Places an outgoing call to the handle passed in. Method asynchronously collects
     * {@link ICallService} implementations and passes them along with the handle and contactInfo
     * to {@link #placeOutgoingCallInternal} to actually place the call.
     *
     * @param handle The handle to dial.
     * @param contactInfo Information about the entity being called.
     * @param context The application context.
     */
    void placeOutgoingCall(final String handle, final ContactInfo contactInfo,
            final Context context) {

        CallServiceFinder.findCallServices(context, new CallServiceSearchCallback() {
            @Override
            public void onSearchComplete(List<ICallService> callServices) {
                try {
                    placeOutgoingCallInternal(handle, contactInfo, callServices);
                } catch (CallServiceUnavailableException e) {
                    // TODO(santoscordon): Handle error
                }
            }
        });
    }

    /**
     * Places an outgoing call to the handle passed in. Given a list of {@link ICallServices},
     * select one and place a call to the handle.
     * TODO(santoscordon): How does the CallService selection process work?
     *
     * @param handle The handle to dial.
     * @param contactInfo Information about the entity being called.
     * @param callServices The list of available {@link ICallService}s.
     */
    private void placeOutgoingCallInternal(String handle, ContactInfo contactInfo,
            List<ICallService> callServices) throws CallServiceUnavailableException {
        Log.i(TAG, "Placing and outgoing call.");

        if (callServices.isEmpty()) {
            // No call services, bail out.
            // TODO(contacts-team): Add logging?
            // TODO(santoscordon): Does this actually go anywhere considering this method is now
            // asynchronous?
            throw new CallServiceUnavailableException("No CallService found.");
        }

        List<ICallService> compatibleCallServices = Lists.newArrayList();
        for (ICallService service : callServices) {
            // TODO(santoscordon): This code needs to be updated to an asynchronous response
            // callback from isCompatibleWith().
            /* if (service.isCompatibleWith(handle)) {
                // NOTE(android-contacts): If we end up taking the liberty to issue
                // calls not using the explicit user input (in case one is provided)
                // and instead pull an alternative method of communication from the
                // specified user-info object, it may be desirable to give precedence
                // to services that can in fact respect the user's intent.
                compatibleCallServices.add(service);
            }
            */
        }

        if (compatibleCallServices.isEmpty()) {
            // None of the available call services is suitable for making this call.
            // TODO(contacts-team): Same here re logging.
            throw new CallServiceUnavailableException("No compatible CallService found.");
        }

        // NOTE(android-team): At this point we can also prompt the user for
        // preference, i.e. instead of the logic just below.
        if (compatibleCallServices.size() > 1) {
            compatibleCallServices = sort(compatibleCallServices);
        }
        for (ICallService service : compatibleCallServices) {
            try {
                service.call(handle);
                return;
            } catch (RemoteException e) {
                // TODO(santoscordon): Need some proxy for ICallService so that we don't have to
                // avoid RemoteExceptionHandling everywhere. Take a look at how InputMethodService
                // handles this.
            }
            // catch (OutgoingCallException ignored) {
            // TODO(santoscordon): Figure out how OutgoingCallException falls into this. Should
            // RemoteExceptions also be converted to OutgoingCallExceptions thrown by call()?
        }
    }

    /**
     * Sorts a list of {@link ICallService} ordered by the prefered service for dialing the call.
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
