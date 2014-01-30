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

import android.telecomm.CallInfo;
import android.telecomm.CallState;
import android.telecomm.ICallService;

import java.util.Date;

/**
 *  Encapsulates all aspects of a given phone call throughout its lifecycle, starting
 *  from the time the call intent was received by Telecomm (vs. the time the call was
 *  connected etc).
 */
final class Call {

    /**
     * Unique identifier for the call as a UUID string.
     */
    private final String mId;

    /**
     * The state of the call.
     */
    private CallState mState;

    /** The handle with which to establish this call. */
    private final String mHandle;

    /** Additional contact information beyond handle above, optional. */
    private final ContactInfo mContactInfo;

    /**
     * The time this call was created, typically also the time this call was added to the set
     * of pending outgoing calls (mPendingOutgoingCalls) that's maintained by the switchboard.
     * Beyond logging and such, may also be used for bookkeeping and specifically for marking
     * certain call attempts as failed attempts.
     */
    private final Date mCreationTime;

    /**
     * The call service which is currently connecting this call, null as long as the call is not
     * connected.
     */
    private ICallService mCallService;

    /**
     * Read-only and parcelable version of this call.
     */
    private CallInfo mCallInfo;

    /**
     * Persists the specified parameters and initializes the new instance.
     *
     * @param handle The handle to dial.
     * @param contactInfo Information about the entity being called.
     */
    Call(String handle, ContactInfo contactInfo) {
        // TODO(gilad): Pass this in etc.
        mId = "dummy";
        mState = CallState.NEW;
        mHandle = handle;
        mContactInfo = contactInfo;
        mCreationTime = new Date();
    }

    String getId() {
        return mId;
    }

    CallState getState() {
        return mState;
    }

    /**
     * Sets the call state. Although there exists the notion of appropriate state transitions
     * (see {@link CallState}), in practice those expectations break down when cellular systems
     * misbehave and they do this very often. The result is that we do not enforce state transitions
     * and instead keep the code resilient to unexpected state changes.
     */
    void setState(CallState state) {
        mState = state;
        clearCallInfo();
    }

    String getHandle() {
        return mHandle;
    }

    ContactInfo getContactInfo() {
        return mContactInfo;
    }

    /**
     * @return The "age" of this call object in milliseconds, which typically also represents the
     *     period since this call was added to the set pending outgoing calls, see mCreationTime.
     */
    long getAgeInMilliseconds() {
        return new Date().getTime() - mCreationTime.getTime();
    }

    ICallService getCallService() {
        return mCallService;
    }

    void setCallService(ICallService callService) {
        mCallService = callService;
    }

    /**
     * Clears the associated call service.
     */
    void clearCallService() {
        setCallService(null);
    }

    /**
     * @return An object containing read-only information about this call.
     */
    CallInfo toCallInfo() {
        if (mCallInfo == null) {
            mCallInfo = new CallInfo(mId, mState, mHandle);
        }
        return mCallInfo;
    }

    /**
     * Resets the cached read-only version of this call.
     */
    private void clearCallInfo() {
        mCallInfo = null;
    }
}
