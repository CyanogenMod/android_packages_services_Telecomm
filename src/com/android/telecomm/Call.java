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

import com.android.internal.telecomm.ICallServiceSelector;
import com.google.common.base.Preconditions;

import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/**
 *  Encapsulates all aspects of a given phone call throughout its lifecycle, starting
 *  from the time the call intent was received by Telecomm (vs. the time the call was
 *  connected etc).
 */
final class Call {
    /** Unique identifier for the call as a UUID string. */
    private final String mId;

    /** Additional contact information beyond handle above, optional. */
    private final ContactInfo mContactInfo;

    /**
     * The time this call was created, typically also the time this call was added to the set
     * of pending outgoing calls (mPendingOutgoingCalls) that's maintained by the switchboard.
     * Beyond logging and such, may also be used for bookkeeping and specifically for marking
     * certain call attempts as failed attempts.
     */
    private final Date mCreationTime;

    /** The state of the call. */
    private CallState mState;

    /** The handle with which to establish this call. */
    private String mHandle;

    /**
     * The call service which is attempted or already connecting this call.
     */
    private CallServiceWrapper mCallService;

    /**
     * The call-service selector for this call.
     * TODO(gilad): Switch to using a wrapper object, see {@link #mCallService}.
     */
    private ICallServiceSelector mCallServiceSelector;

    /** Read-only and parcelable version of this call. */
    private CallInfo mCallInfo;

    /**
     * Creates an empty call object with a unique call ID.
     */
    Call() {
        this(null, null);
    }

    /**
     * Persists the specified parameters and initializes the new instance.
     *
     * @param handle The handle to dial.
     * @param contactInfo Information about the entity being called.
     */
    Call(String handle, ContactInfo contactInfo) {
        mId = UUID.randomUUID().toString();  // UUIDs should provide sufficient uniqueness.
        mState = CallState.NEW;
        mHandle = handle;
        mContactInfo = contactInfo;
        mCreationTime = new Date();
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return String.format(Locale.US, "[%s, %s, %s, %s]", mId, mState,
                mCallService.getComponentName(), Log.pii(mHandle));
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

    void setHandle(String handle) {
        mHandle = handle;
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

    CallServiceWrapper getCallService() {
        return mCallService;
    }

    void setCallService(CallServiceWrapper callService) {
        Preconditions.checkNotNull(callService);

        if (mCallService != null) {
            // Should never be the case, basically covering for potential programming errors.
            decrementAssociatedCallCount(mCallService);
        }

        callService.incrementAssociatedCallCount();
        mCallService = callService;
    }

    /**
     * Clears the associated call service.
     */
    void clearCallService() {
        decrementAssociatedCallCount(mCallService);
        mCallService = null;
    }

    void setCallServiceSelector(ICallServiceSelector selector) {
        Preconditions.checkNotNull(selector);
        mCallServiceSelector = selector;
    }

    void clearCallServiceSelector() {
        // TODO(gilad): Un-comment once selectors are converted into wrappers.
        // decrementAssociatedCallCount(mCallServiceSelector);

        mCallServiceSelector = null;
    }

    /**
     * Aborts ongoing attempts to connect this call. Only applicable to {@link CallState#NEW}
     * outgoing calls.  See {@link #disconnect} for already-connected calls.
     */
    void abort() {
        if (mState == CallState.NEW) {
            if (mCallService != null) {
                mCallService.abort(mId);
            }
            clearCallService();
            clearCallServiceSelector();
            mState = CallState.ABORTED;
        }
    }

    /**
     * Attempts to disconnect the call through the call service.
     */
    void disconnect() {
        if (mCallService == null) {
            Log.w(this, "disconnect() request on a call without a call service.");
        } else {
            Log.i(this, "Send disconnect to call service for call with id %s", mId);
            // The call isn't officially disconnected until the call service confirms that the call
            // was actually disconnected. Only then is the association between call and call service
            // severed, see {@link CallsManager#markCallAsDisconnected}.
            mCallService.disconnect(mId);
        }
    }

    /**
     * Answers the call if it is ringing.
     */
    void answer() {
        Preconditions.checkNotNull(mCallService);

        // Check to verify that the call is still in the ringing state. A call can change states
        // between the time the user hits 'answer' and Telecomm receives the command.
        if (isRinging("answer")) {
            // At this point, we are asking the call service to answer but we don't assume that
            // it will work. Instead, we wait until confirmation from the call service that the
            // call is in a non-RINGING state before changing the UI. See
            // {@link CallServiceAdapter#setActive} and other set* methods.
            mCallService.answer(mId);
        }
    }

    /**
     * Rejects the call if it is ringing.
     */
    void reject() {
        Preconditions.checkNotNull(mCallService);

        // Check to verify that the call is still in the ringing state. A call can change states
        // between the time the user hits 'reject' and Telecomm receives the command.
        if (isRinging("reject")) {
            mCallService.reject(mId);
        }
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
     * @return True if the call is ringing, else logs the action name.
     */
    private boolean isRinging(String actionName) {
        if (mState == CallState.RINGING) {
            return true;
        }

        Log.i(this, "Request to %s a non-ringing call %s", actionName, this);
        return false;
    }

    /**
     * Resets the cached read-only version of this call.
     */
    private void clearCallInfo() {
        mCallInfo = null;
    }

    @SuppressWarnings("rawtypes")
    private void decrementAssociatedCallCount(ServiceBinder binder) {
        if (binder != null) {
            binder.decrementAssociatedCallCount();
        }
    }
}
