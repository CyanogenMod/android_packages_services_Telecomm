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
import android.telecomm.CallServiceSelector;

import java.util.List;

/**
 * Dummy call-service selector which returns the list of call services in the same order in which it
 * was given. Also returns false for every request on switchability.
 */
public class DummyCallServiceSelector extends CallServiceSelector {
    @Override
    protected boolean isSwitchable(CallInfo callInfo) {
        return false;
    }

    @Override
    protected List<String> select(CallInfo callInfo, List<String> callServiceIds) {
        return callServiceIds;
    }
}
