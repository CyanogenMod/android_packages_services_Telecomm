/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.telecomm.testapps;

import android.net.Uri;
import android.os.Bundle;
import android.telecomm.CallInfo;
import android.telecomm.CallServiceDescriptor;
import android.telecomm.CallServiceSelector;
import android.telecomm.CallServiceSelectorAdapter;
import android.util.Log;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

import java.util.List;

/** Simple selector to exercise Telecomm code. */
public class DummyCallServiceSelector extends CallServiceSelector {
    private static DummyCallServiceSelector sInstance;
    private static final String SCHEME_TEL = "tel";
    private static final String TELEPHONY_PACKAGE_NAME = "com.android.phone";
    private static final String CUSTOM_HANDOFF_KEY = "custom_handoff_key";
    private static final String CUSTOM_HANDOFF_VALUE = "custom_handoff_value";

    public DummyCallServiceSelector() {
        log("constructor");
        sInstance = this;
    }

    static DummyCallServiceSelector getInstance() {
        Preconditions.checkNotNull(sInstance);
        return sInstance;
    }

    @Override
    protected void select(CallInfo callInfo, List<CallServiceDescriptor> descriptors) {
        log("select");
        List<CallServiceDescriptor> orderedList = Lists.newLinkedList();

        boolean shouldHandoffToPstn = false;
        if (callInfo.getCurrentCallServiceDescriptor() != null) {
            // If the current call service is TestCallService then handoff to PSTN, otherwise
            // handoff to TestCallService.
            shouldHandoffToPstn = isTestCallService(callInfo.getCurrentCallServiceDescriptor());
            String extraValue = callInfo.getExtras().getString(CUSTOM_HANDOFF_KEY);
            log("handing off, toPstn: " + shouldHandoffToPstn + ", extraValue: " + extraValue);
            Preconditions.checkState(CUSTOM_HANDOFF_VALUE.equals(extraValue));
        }

        for (CallServiceDescriptor descriptor : descriptors) {
            if (isTestCallService(descriptor) && !shouldHandoffToPstn) {
                orderedList.add(0, descriptor);
            } else if (isPstnCallService(descriptor)) {
                orderedList.add(descriptor);
            } else {
                log("skipping call service: " + descriptor.getServiceComponent());
            }
        }

        getAdapter().setSelectedCallServices(callInfo.getId(), orderedList);
    }

    void sendHandoffInfo(Uri remoteHandle, Uri handoffHandle) {
        log("sendHandoffInfo");
        String callId = findMatchingCall(remoteHandle);
        Preconditions.checkNotNull(callId);
        Bundle extras = new Bundle();
        extras.putString(CUSTOM_HANDOFF_KEY, CUSTOM_HANDOFF_VALUE);
        getAdapter().setHandoffInfo(callId, handoffHandle, extras);
    }

    private String findMatchingCall(Uri remoteHandle) {
        for (CallInfo callInfo : getCalls()) {
            if (remoteHandle.equals(callInfo.getOriginalHandle())) {
                return callInfo.getId();
            }
        }
        return null;
    }

    private boolean isTestCallService(CallServiceDescriptor descriptor) {
        if (descriptor == null) {
            return false;
        }
        return getPackageName().equals(descriptor.getServiceComponent().getPackageName());
    }

    private boolean isPstnCallService(CallServiceDescriptor descriptor) {
        if (descriptor == null) {
            return false;
        }
        return TELEPHONY_PACKAGE_NAME.equals(descriptor.getServiceComponent().getPackageName());
    }

    private static void log(String msg) {
        Log.w("testcallservice", "[DummyCallServiceSelector] " + msg);
    }
}
