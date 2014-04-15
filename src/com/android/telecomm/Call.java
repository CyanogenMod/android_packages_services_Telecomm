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

import android.net.Uri;
import android.os.Bundle;
import android.telecomm.CallInfo;
import android.telecomm.CallServiceDescriptor;
import android.telecomm.CallState;
import android.telecomm.GatewayInfo;
import android.telephony.DisconnectCause;
import android.telephony.PhoneNumberUtils;

import com.google.android.collect.Sets;
import com.google.common.base.Preconditions;

import java.util.Locale;
import java.util.Set;

/**
 *  Encapsulates all aspects of a given phone call throughout its lifecycle, starting
 *  from the time the call intent was received by Telecomm (vs. the time the call was
 *  connected etc).
 */
final class Call {
    /** Additional contact information beyond handle above, optional. */
    private final ContactInfo mContactInfo;

    /** True if this is an incoming call. */
    private final boolean mIsIncoming;

    /**
     * The time this call was created, typically also the time this call was added to the set
     * of pending outgoing calls (mPendingOutgoingCalls) that's maintained by the switchboard.
     * Beyond logging and such, may also be used for bookkeeping and specifically for marking
     * certain call attempts as failed attempts.
     */
    private final long mCreationTimeMillis = System.currentTimeMillis();

    private long mConnectTimeMillis;

    /** The state of the call. */
    private CallState mState;

    /** The handle with which to establish this call. */
    private Uri mHandle;

    /** The gateway information associated with this call. This stores the original call handle
     * that the user is attempting to connect to via the gateway, the actual handle to dial in
     * order to connect the call via the gateway, as well as the package name of the gateway
     * service. */
    private final GatewayInfo mGatewayInfo;

    /**
     * The call service which is attempted or already connecting this call.
     */
    private CallServiceWrapper mCallService;

    /**
     * The call-service selector for this call.
     */
    private CallServiceSelectorWrapper mCallServiceSelector;

    /**
     * The set of call services that were attempted in the process of placing/switching this call
     * but turned out unsuitable.  Only used in the context of call switching.
     */
    private Set<CallServiceWrapper> mIncompatibleCallServices;

    private boolean mIsEmergencyCall;

    /**
     * Disconnect cause for the call. Only valid if the state of the call is DISCONNECTED.
     * See {@link android.telephony.DisconnectCause}.
     */
    private int mDisconnectCause = DisconnectCause.NOT_VALID;

    /**
     * Additional disconnect information provided by the call service.
     */
    private String mDisconnectMessage;

    /** Info used by the call services. */
    private Bundle mExtras = Bundle.EMPTY;

    /** The Uri to dial to perform the handoff. If this is null then handoff is not supported. */
    private Uri mHandoffHandle;

    /**
     * References the call that is being handed off. This value is non-null for untracked calls
     * that are being used to perform a handoff.
     */
    private Call mOriginalCall;

    /**
     * The descriptor for the call service that this call is being switched to, null if handoff is
     * not in progress.
     */
    private CallServiceDescriptor mHandoffCallServiceDescriptor;

    /**
     * Creates an empty call object.
     *
     * @param isIncoming True if this is an incoming call.
     */
    Call(boolean isIncoming) {
        this(null, null, null, isIncoming);
    }

    /**
     * Persists the specified parameters and initializes the new instance.
     *
     * @param handle The handle to dial.
     * @param contactInfo Information about the entity being called.
     * @param gatewayInfo Gateway information to use for the call.
     * @param isIncoming True if this is an incoming call.
     */
    Call(Uri handle, ContactInfo contactInfo, GatewayInfo gatewayInfo, boolean isIncoming) {
        mState = CallState.NEW;
        setHandle(handle);
        mContactInfo = contactInfo;
        mGatewayInfo = gatewayInfo;
        mIsIncoming = isIncoming;
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        String component = null;
        if (mCallService != null && mCallService.getComponentName() != null) {
            component = mCallService.getComponentName().flattenToShortString();
        }
        return String.format(Locale.US, "[%s, %s, %s]", mState, component, Log.piiHandle(mHandle));
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
    void setState(CallState newState) {
        Preconditions.checkState(newState != CallState.DISCONNECTED ||
                mDisconnectCause != DisconnectCause.NOT_VALID);
        if (mState != newState) {
            Log.v(this, "setState %s -> %s", mState, newState);
            mState = newState;
        }
    }

    Uri getHandle() {
        return mHandle;
    }

    void setHandle(Uri handle) {
        mHandle = handle;
        mIsEmergencyCall = mHandle != null && PhoneNumberUtils.isLocalEmergencyNumber(
                mHandle.getSchemeSpecificPart(), TelecommApp.getInstance());
    }

    /**
     * @param disconnectCause The reason for the disconnection, any of
     *         {@link android.telephony.DisconnectCause}.
     * @param disconnectMessage Optional call-service-provided message about the disconnect.
     */
    void setDisconnectCause(int disconnectCause, String disconnectMessage) {
        // TODO: Consider combining this method with a setDisconnected() method that is totally
        // separate from setState.
        mDisconnectCause = disconnectCause;
        mDisconnectMessage = disconnectMessage;
    }

    int getDisconnectCause() {
        return mDisconnectCause;
    }

    String getDisconnectMessage() {
        return mDisconnectMessage;
    }

    boolean isEmergencyCall() {
        return mIsEmergencyCall;
    }

    /**
     * @return The original handle this call is associated with. In-call services should use this
     * handle when indicating in their UI the handle that is being called.
     */
    public Uri getOriginalHandle() {
        if (mGatewayInfo != null && !mGatewayInfo.isEmpty()) {
            return mGatewayInfo.getOriginalHandle();
        }
        return getHandle();
    }

    GatewayInfo getGatewayInfo() {
        return mGatewayInfo;
    }

    ContactInfo getContactInfo() {
        return mContactInfo;
    }

    boolean isIncoming() {
        return mIsIncoming;
    }

    /**
     * @return The "age" of this call object in milliseconds, which typically also represents the
     *     period since this call was added to the set pending outgoing calls, see
     *     mCreationTimeMillis.
     */
    long getAgeMillis() {
        return System.currentTimeMillis() - mCreationTimeMillis;
    }

    /**
     * @return The time when this call object was created and added to the set of pending outgoing
     *     calls.
     */
    long getCreationTimeMillis() {
        return mCreationTimeMillis;
    }

    long getConnectTimeMillis() {
        return mConnectTimeMillis;
    }

    void setConnectTimeMillis(long connectTimeMillis) {
        mConnectTimeMillis = connectTimeMillis;
    }

    CallServiceWrapper getCallService() {
        return mCallService;
    }

    void setCallService(CallServiceWrapper callService) {
        setCallService(callService, null);
    }

    /**
     * Changes the call service this call is associated with. If callToReplace is non-null then this
     * call takes its place within the call service.
     */
    void setCallService(CallServiceWrapper callService, Call callToReplace) {
        Preconditions.checkNotNull(callService);

        clearCallService();

        callService.incrementAssociatedCallCount();
        mCallService = callService;
        if (callToReplace == null) {
            mCallService.addCall(this);
        } else {
            mCallService.replaceCall(this, callToReplace);
        }
    }

    /**
     * Clears the associated call service.
     */
    void clearCallService() {
        if (mCallService != null) {
            decrementAssociatedCallCount(mCallService);
            mCallService.removeCall(this);
            mCallService = null;
        }
    }

    CallServiceSelectorWrapper getCallServiceSelector() {
        return mCallServiceSelector;
    }

    void setCallServiceSelector(CallServiceSelectorWrapper selector) {
        Preconditions.checkNotNull(selector);

        clearCallServiceSelector();

        mCallServiceSelector = selector;
        mCallServiceSelector.addCall(this);
    }

    void clearCallServiceSelector() {
        if (mCallServiceSelector != null) {
            // TODO(sail): Stop leaking selectors.
            // decrementAssociatedCallCount(mCallServiceSelector);
            mCallServiceSelector.removeCall(this);
            mCallServiceSelector = null;
        }
    }

    /**
     * Adds the specified call service to the list of incompatible services.  The set is used when
     * attempting to switch a phone call between call services such that incompatible services can
     * be avoided.
     *
     * @param callService The incompatible call service.
     */
    void addIncompatibleCallService(CallServiceWrapper callService) {
        if (mIncompatibleCallServices == null) {
            mIncompatibleCallServices = Sets.newHashSet();
        }
        mIncompatibleCallServices.add(callService);
    }

    /**
     * Checks whether or not the specified callService was identified as incompatible in the
     * context of this call.
     *
     * @param callService The call service to evaluate.
     * @return True upon incompatible call services and false otherwise.
     */
    boolean isIncompatibleCallService(CallServiceWrapper callService) {
        return mIncompatibleCallServices != null &&
                mIncompatibleCallServices.contains(callService);
    }

    /**
     * Aborts ongoing attempts to connect this call. Only applicable to {@link CallState#NEW}
     * outgoing calls.  See {@link #disconnect} for already-connected calls.
     */
    void abort() {
        if (mState == CallState.NEW) {
            if (mCallService != null) {
                mCallService.abort(this);
            }
            clearCallService();
            clearCallServiceSelector();
        }
    }

    /**
     * Plays the specified DTMF tone.
     */
    void playDtmfTone(char digit) {
        if (mCallService == null) {
            Log.w(this, "playDtmfTone() request on a call without a call service.");
        } else {
            Log.i(this, "Send playDtmfTone to call service for call %s", this);
            mCallService.playDtmfTone(this, digit);
        }
    }

    /**
     * Stops playing any currently playing DTMF tone.
     */
    void stopDtmfTone() {
        if (mCallService == null) {
            Log.w(this, "stopDtmfTone() request on a call without a call service.");
        } else {
            Log.i(this, "Send stopDtmfTone to call service for call %s", this);
            mCallService.stopDtmfTone(this);
        }
    }

    /**
     * Attempts to disconnect the call through the call service.
     */
    void disconnect() {
        if (mCallService == null) {
            Log.w(this, "disconnect() request on a call without a call service.");
        } else {
            Log.i(this, "Send disconnect to call service for call: %s", this);
            // The call isn't officially disconnected until the call service confirms that the call
            // was actually disconnected. Only then is the association between call and call service
            // severed, see {@link CallsManager#markCallAsDisconnected}.
            mCallService.disconnect(this);
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
            mCallService.answer(this);
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
            mCallService.reject(this);
        }
    }

    /**
     * Puts the call on hold if it is currently active.
     */
    void hold() {
        Preconditions.checkNotNull(mCallService);

        if (mState == CallState.ACTIVE) {
            mCallService.hold(this);
        }
    }

    /**
     * Releases the call from hold if it is currently active.
     */
    void unhold() {
        Preconditions.checkNotNull(mCallService);

        if (mState == CallState.ON_HOLD) {
            mCallService.unhold(this);
        }
    }

    /**
     * @return An object containing read-only information about this call.
     */
    CallInfo toCallInfo(String callId) {
        CallServiceDescriptor descriptor = null;
        if (mCallService != null) {
            descriptor = mCallService.getDescriptor();
        } else if (mOriginalCall != null && mOriginalCall.mCallService != null) {
            descriptor = mOriginalCall.mCallService.getDescriptor();
        }
        return new CallInfo(callId, mState, mHandle, mGatewayInfo, mExtras, descriptor);
    }

    /** Checks if this is a live call or not. */
    boolean isAlive() {
        switch (mState) {
            case NEW:
            case RINGING:
            case DISCONNECTED:
            case ABORTED:
                return false;
            default:
                return true;
        }
    }

    boolean isActive() {
        switch (mState) {
            case ACTIVE:
            case POST_DIAL:
            case POST_DIAL_WAIT:
                return true;
            default:
                return false;
        }
    }

    Bundle getExtras() {
        return mExtras;
    }

    void setExtras(Bundle extras) {
        mExtras = extras;
    }

    Uri getHandoffHandle() {
        return mHandoffHandle;
    }

    void setHandoffHandle(Uri handoffHandle) {
        mHandoffHandle = handoffHandle;
    }

    Call getOriginalCall() {
        return mOriginalCall;
    }

    void setOriginalCall(Call originalCall) {
        mOriginalCall = originalCall;
    }

    CallServiceDescriptor getHandoffCallServiceDescriptor() {
        return mHandoffCallServiceDescriptor;
    }

    void setHandoffCallServiceDescriptor(CallServiceDescriptor descriptor) {
        mHandoffCallServiceDescriptor = descriptor;
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

    @SuppressWarnings("rawtypes")
    private void decrementAssociatedCallCount(ServiceBinder binder) {
        if (binder != null) {
            binder.decrementAssociatedCallCount();
        }
    }
}
