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

package com.android.telecomm;

import com.google.common.base.Preconditions;
import com.google.common.collect.HashBiMap;

/** Utility to map {@link Call} objects to unique IDs. IDs are generated when a call is added. */
class CallIdMapper {
    private final HashBiMap<String, Call> mCalls = HashBiMap.create();
    private final String mCallIdPrefix;
    private static int sIdCount;

    CallIdMapper(String callIdPrefix) {
        ThreadUtil.checkOnMainThread();
        mCallIdPrefix = callIdPrefix + "@";
    }

    void replaceCall(Call newCall, Call callToReplace) {
        ThreadUtil.checkOnMainThread();

        // Use the old call's ID for the new call.
        String callId = getCallId(callToReplace);
        mCalls.put(callId, newCall);
    }

    void addCall(Call call, String id) {
        Preconditions.checkNotNull(call);
        ThreadUtil.checkOnMainThread();
        mCalls.put(id, call);
    }

    void addCall(Call call) {
        ThreadUtil.checkOnMainThread();
        addCall(call, getNewId());
    }

    void removeCall(Call call) {
        ThreadUtil.checkOnMainThread();
        Preconditions.checkNotNull(call);
        mCalls.inverse().remove(call);
    }

    void removeCall(String callId) {
        ThreadUtil.checkOnMainThread();
        mCalls.remove(callId);
    }

    String getCallId(Call call) {
        ThreadUtil.checkOnMainThread();
        Preconditions.checkNotNull(call);
        return mCalls.inverse().get(call);
    }

    Call getCall(Object objId) {
        ThreadUtil.checkOnMainThread();

        String callId = null;
        if (objId instanceof String) {
            callId = (String) objId;
        }
        checkValidCallId(callId);

        return mCalls.get(callId);
    }

    void clear() {
        mCalls.clear();
    }

    void checkValidCallId(String callId) {
        // Note, no need for thread check, this method is thread safe.
        if (!isValidCallId(callId)) {
            throw new IllegalArgumentException(
                    "Invalid call ID for " + mCallIdPrefix + ": " + callId);
        }
    }

    boolean isValidCallId(String callId) {
        // Note, no need for thread check, this method is thread safe.
        return callId != null && callId.startsWith(mCallIdPrefix);
    }

    String getNewId() {
        sIdCount++;
        return mCallIdPrefix + sIdCount;
    }
}
