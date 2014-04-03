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

package com.android.telecomm.testcallservice;

import android.telecomm.CallInfo;
import android.telecomm.CallServiceDescriptor;
import android.telecomm.CallServiceSelector;
import android.telecomm.CallServiceSelectorAdapter;

import com.google.common.collect.Lists;

import java.util.List;

/**
 * Dummy call-service selector which returns the list of call services in the same order in which it
 * was given. Also returns false for every request on switchability.
 */
public class DummyCallServiceSelector extends CallServiceSelector {

    @Override
    protected void select(CallInfo callInfo, List<CallServiceDescriptor> descriptors) {
        List<CallServiceDescriptor> orderedList = Lists.newLinkedList();

        // Make sure that the test call services are the only ones
        for (CallServiceDescriptor descriptor : descriptors) {
            String packageName = descriptor.getServiceComponent().getPackageName();
            if (getPackageName().equals(packageName)) {
                orderedList.add(descriptor);
            }
        }

        getAdapter().setSelectedCallServices(callInfo.getId(), orderedList);
    }
}
