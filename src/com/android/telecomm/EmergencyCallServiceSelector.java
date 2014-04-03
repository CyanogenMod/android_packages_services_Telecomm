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
import android.telecomm.CallInfo;
import android.telecomm.CallServiceDescriptor;
import android.telecomm.CallServiceSelector;
import android.telecomm.CallServiceSelectorAdapter;
import android.telephony.PhoneNumberUtils;

import com.google.common.collect.Lists;

import java.util.List;

/**
 * Selects call-services which can place emergency calls. For emergency handles, this selector is
 * always the first selector attempted when placing a call, see
 * {@link Switchboard#processNewOutgoingCall}. If this is ever invoked for a non-emergency handle,
 * this selector returns zero call services so that call-service selection continues to the next
 * selector. For emergency calls, it selects telephony's PSTN call services so that they are the
 * first ones to try to place the call.
 */
public class EmergencyCallServiceSelector extends CallServiceSelector {

    /**
     * Returns true if the handle passed in is to a potential emergency number.
     */
    static boolean shouldUseSelector(Uri handle) {
        return PhoneNumberUtils.isPotentialLocalEmergencyNumber(
                handle.getSchemeSpecificPart(), TelecommApp.getInstance());
    }

    /** {@inheritDoc} */
    @Override
    protected void select(CallInfo callInfo, List<CallServiceDescriptor> descriptors) {
        List<CallServiceDescriptor> selectedDescriptors = Lists.newArrayList();

        // We check to see if the handle is potentially for an emergency call. *Potentially* means
        // that we match both the full number and prefix (e.g., "911444" matches 911). After the
        // call is made, the telephony call services will inform us of the actual number that was
        // connected, which would be 911. This is why we check *potential* APIs before, but use the
        // exact {@link PhoneNumberUtils#isLocalEmergencyNumber} once the call is connected.
        if (shouldUseSelector(callInfo.getHandle())) {
            // Search for and return the pstn call services if found.
            for (CallServiceDescriptor descriptor : descriptors) {
                // TODO(santoscordon): Consider adding some type of CAN_MAKE_EMERGENCY_CALLS
                // capability for call services and relying on that. Also consider combining this
                // with a permission so that we can check that instead of relying on hardcoded
                // paths.
                if (TelephonyUtil.isPstnCallService(descriptor)) {
                    selectedDescriptors.add(descriptor);
                }
            }
        }

        getAdapter().setSelectedCallServices(callInfo.getId(), selectedDescriptors);
    }
}
