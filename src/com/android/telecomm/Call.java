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

import android.content.ContentUris;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.ContactsContract.Contacts;
import android.telecomm.CallInfo;
import android.telecomm.CallServiceDescriptor;
import android.telecomm.CallState;
import android.telecomm.GatewayInfo;
import android.telecomm.TelecommConstants;
import android.telephony.DisconnectCause;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;

import com.android.internal.telephony.CallerInfo;
import com.android.internal.telephony.CallerInfoAsyncQuery;
import com.android.internal.telephony.CallerInfoAsyncQuery.OnQueryCompleteListener;

import com.android.telecomm.ContactsAsyncHelper.OnImageLoadCompleteListener;
import com.google.android.collect.Sets;
import com.google.common.base.Preconditions;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 *  Encapsulates all aspects of a given phone call throughout its lifecycle, starting
 *  from the time the call intent was received by Telecomm (vs. the time the call was
 *  connected etc).
 */
final class Call {

    /**
     * Listener for events on the call.
     */
    interface Listener {
        void onSuccessfulOutgoingCall(Call call);
        void onFailedOutgoingCall(Call call, boolean isAborted, int errorCode, String errorMsg);
        void onSuccessfulIncomingCall(Call call, CallInfo callInfo);
        void onFailedIncomingCall(Call call);
        void onRequestingRingback(Call call, boolean requestingRingback);
        void onPostDialWait(Call call, String remaining);
        void onIsConferenceCapableChanged(Call call, boolean isConferenceCapable);
        void onExpiredConferenceCall(Call call);
        void onConfirmedConferenceCall(Call call);
        void onParentChanged(Call call);
        void onChildrenChanged(Call call);
    }

    private static final OnQueryCompleteListener sCallerInfoQueryListener =
            new OnQueryCompleteListener() {
                /** ${inheritDoc} */
                @Override
                public void onQueryComplete(int token, Object cookie, CallerInfo callerInfo) {
                    if (cookie != null) {
                        ((Call) cookie).setCallerInfo(callerInfo, token);
                    }
                }
            };

    private static final OnImageLoadCompleteListener sPhotoLoadListener =
            new OnImageLoadCompleteListener() {
                /** ${inheritDoc} */
                @Override
                public void onImageLoadComplete(
                        int token, Drawable photo, Bitmap photoIcon, Object cookie) {
                    if (cookie != null) {
                        ((Call) cookie).setPhoto(photo, photoIcon, token);
                    }
                }
            };

    /** True if this is an incoming call. */
    private final boolean mIsIncoming;

    /**
     * The time this call was created, typically also the time this call was added to the set
     * of pending outgoing calls (mPendingOutgoingCalls) that's maintained by the switchboard.
     * Beyond logging and such, may also be used for bookkeeping and specifically for marking
     * certain call attempts as failed attempts.
     */
    private final long mCreationTimeMillis = System.currentTimeMillis();

    /** The gateway information associated with this call. This stores the original call handle
     * that the user is attempting to connect to via the gateway, the actual handle to dial in
     * order to connect the call via the gateway, as well as the package name of the gateway
     * service. */
    private final GatewayInfo mGatewayInfo;

    private final Handler mHandler = new Handler();

    private long mConnectTimeMillis;

    /** The state of the call. */
    private CallState mState;

    /** The handle with which to establish this call. */
    private Uri mHandle;

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

    /** Set of listeners on this call. */
    private Set<Listener> mListeners = Sets.newHashSet();

    private OutgoingCallProcessor mOutgoingCallProcessor;

    // TODO(santoscordon): The repositories should be changed into singleton types.
    private CallServiceRepository mCallServiceRepository;
    private CallServiceSelectorRepository mSelectorRepository;

    /** Caller information retrieved from the latest contact query. */
    private CallerInfo mCallerInfo;

    /** The latest token used with a contact info query. */
    private int mQueryToken = 0;

    /** Whether this call is requesting that Telecomm play the ringback tone on its behalf. */
    private boolean mRequestingRingback = false;

    /** Incoming call-info to use when direct-to-voicemail query finishes. */
    private CallInfo mPendingDirectToVoicemailCallInfo;

    private boolean mIsConferenceCapable = false;

    private boolean mIsConference = false;

    private Call mParentCall = null;

    private List<Call> mChildCalls = new LinkedList<>();

    /**
     * Creates an empty call object.
     *
     * @param isIncoming True if this is an incoming call.
     */
    Call(boolean isIncoming, boolean isConference) {
        this(null, null, isIncoming, isConference);
    }

    /**
     * Persists the specified parameters and initializes the new instance.
     *
     * @param handle The handle to dial.
     * @param gatewayInfo Gateway information to use for the call.
     * @param isIncoming True if this is an incoming call.
     */
    Call(Uri handle, GatewayInfo gatewayInfo, boolean isIncoming, boolean isConference) {
        mState = isConference ? CallState.ACTIVE : CallState.NEW;
        setHandle(handle);
        mGatewayInfo = gatewayInfo;
        mIsIncoming = isIncoming;
        mIsConference = isConference;
    }

    void addListener(Listener listener) {
        mListeners.add(listener);
    }

    void removeListener(Listener listener) {
        mListeners.remove(listener);
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
        if (mIsConference) {
            if (!mChildCalls.isEmpty()) {
                // If we have child calls, just return the child call.
                return mChildCalls.get(0).getState();
            }
            return CallState.ACTIVE;
        } else {
            return mState;
        }
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

    void setRequestingRingback(boolean requestingRingback) {
        mRequestingRingback = requestingRingback;
        for (Listener l : mListeners) {
            l.onRequestingRingback(this, mRequestingRingback);
        }
    }

    boolean isRequestingRingback() {
        return mRequestingRingback;
    }

    Uri getHandle() {
        return mHandle;
    }

    void setHandle(Uri handle) {
        if ((mHandle == null && handle != null) || (mHandle != null && !mHandle.equals(handle))) {
            mHandle = handle;
            mIsEmergencyCall = mHandle != null && PhoneNumberUtils.isLocalEmergencyNumber(
                    TelecommApp.getInstance(), mHandle.getSchemeSpecificPart());
            startCallerInfoLookup();
        }
    }

    String getName() {
        return mCallerInfo == null ? null : mCallerInfo.name;
    }

    Bitmap getPhotoIcon() {
        return mCallerInfo == null ? null : mCallerInfo.cachedPhotoIcon;
    }

    Drawable getPhoto() {
        return mCallerInfo == null ? null : mCallerInfo.cachedPhoto;
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

    boolean isConferenceCapable() {
        return mIsConferenceCapable;
    }

    void setIsConferenceCapable(boolean isConferenceCapable) {
        if (mIsConferenceCapable != isConferenceCapable) {
            mIsConferenceCapable = isConferenceCapable;
            for (Listener l : mListeners) {
                l.onIsConferenceCapableChanged(this, mIsConferenceCapable);
            }
        }
    }

    Call getParentCall() {
        return mParentCall;
    }

    List<Call> getChildCalls() {
        return mChildCalls;
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
            CallServiceWrapper callServiceTemp = mCallService;
            mCallService = null;
            callServiceTemp.removeCall(this);

            // Decrementing the count can cause the service to unbind, which itself can trigger the
            // service-death code.  Since the service death code tries to clean up any associated
            // calls, we need to make sure to remove that information (e.g., removeCall()) before
            // we decrement. Technically, invoking removeCall() prior to decrementing is all that is
            // necessary, but cleaning up mCallService prior to triggering an unbind is good to do.
            // If you change this, make sure to update {@link clearCallServiceSelector} as well.
            decrementAssociatedCallCount(callServiceTemp);
        }
    }

    CallServiceSelectorWrapper getCallServiceSelector() {
        return mCallServiceSelector;
    }

    void setCallServiceSelector(CallServiceSelectorWrapper selector) {
        Preconditions.checkNotNull(selector);

        clearCallServiceSelector();

        selector.incrementAssociatedCallCount();
        mCallServiceSelector = selector;
        mCallServiceSelector.addCall(this);
    }

    void clearCallServiceSelector() {
        if (mCallServiceSelector != null) {
            CallServiceSelectorWrapper selectorTemp = mCallServiceSelector;
            mCallServiceSelector = null;
            selectorTemp.removeCall(this);

            // See comment on {@link #clearCallService}.
            decrementAssociatedCallCount(selectorTemp);
        }
    }

    /**
     * Starts the incoming call flow through the switchboard. When switchboard completes, it will
     * invoke handle[Un]SuccessfulIncomingCall.
     *
     * @param descriptor The relevant call-service descriptor.
     * @param extras The optional extras passed via
     *         {@link TelecommConstants#EXTRA_INCOMING_CALL_EXTRAS}.
     */
    void startIncoming(CallServiceDescriptor descriptor, Bundle extras) {
        Switchboard.getInstance().retrieveIncomingCall(this, descriptor, extras);
    }

    /**
     * Takes a verified incoming call and uses the handle to lookup direct-to-voicemail property
     * from the contacts provider. The call is not yet exposed to the user at this point and
     * the result of the query will determine if the call is rejected or passed through to the
     * in-call UI.
     */
    void handleVerifiedIncoming(CallInfo callInfo) {
        Preconditions.checkState(callInfo.getState() == CallState.RINGING);

        // We do not handle incoming calls immediately when they are verified by the call service.
        // We allow the caller-info-query code to execute first so that we can read the
        // direct-to-voicemail property before deciding if we want to show the incoming call to the
        // user or if we want to reject the call.
        mPendingDirectToVoicemailCallInfo = callInfo;

        // Setting the handle triggers the caller info lookup code.
        setHandle(callInfo.getHandle());

        // Timeout the direct-to-voicemail lookup execution so that we dont wait too long before
        // showing the user the incoming call screen.
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                processDirectToVoicemail();
            }
        }, Timeouts.getDirectToVoicemailMillis());
    }

    void processDirectToVoicemail() {
        if (mPendingDirectToVoicemailCallInfo != null) {
            if (mCallerInfo != null && mCallerInfo.shouldSendToVoicemail) {
                Log.i(this, "Directing call to voicemail: %s.", this);
                // TODO(santoscordon): Once we move State handling from CallsManager to Call, we
                // will not need to set RINGING state prior to calling reject.
                setState(CallState.RINGING);
                reject();
            } else {
                // TODO(santoscordon): Make this class (not CallsManager) responsible for changing
                // the call state to RINGING.

                // TODO(santoscordon): Replace this with state transition to RINGING.
                for (Listener l : mListeners) {
                    l.onSuccessfulIncomingCall(this, mPendingDirectToVoicemailCallInfo);
                }
            }

            mPendingDirectToVoicemailCallInfo = null;
        }
    }

    void handleFailedIncoming() {
        clearCallService();

        // TODO: Needs more specific disconnect error for this case.
        setDisconnectCause(DisconnectCause.ERROR_UNSPECIFIED, null);
        setState(CallState.DISCONNECTED);

        // TODO(santoscordon): Replace this with state transitions related to "connecting".
        for (Listener l : mListeners) {
            l.onFailedIncomingCall(this);
        }
    }

    /**
     * Starts the outgoing call sequence.  Upon completion, there should exist an active connection
     * through a call service (or the call will have failed).
     */
    void startOutgoing() {
        Preconditions.checkState(mOutgoingCallProcessor == null);

        mOutgoingCallProcessor = new OutgoingCallProcessor(
                this,
                Switchboard.getInstance().getCallServiceRepository(),
                Switchboard.getInstance().getSelectorRepository(),
                new AsyncResultCallback<Boolean>() {
                    @Override
                    public void onResult(Boolean wasCallPlaced, int errorCode, String errorMsg) {
                        if (wasCallPlaced) {
                            handleSuccessfulOutgoing();
                        } else {
                            handleFailedOutgoing(
                                    mOutgoingCallProcessor.isAborted(), errorCode, errorMsg);
                        }
                        mOutgoingCallProcessor = null;
                    }
                });
        mOutgoingCallProcessor.process();
    }

    void handleSuccessfulOutgoing() {
        // TODO(santoscordon): Replace this with state transitions related to "connecting".
        for (Listener l : mListeners) {
            l.onSuccessfulOutgoingCall(this);
        }
    }

    void handleFailedOutgoing(boolean isAborted, int errorCode, String errorMsg) {
        // TODO(santoscordon): Replace this with state transitions related to "connecting".
        for (Listener l : mListeners) {
            l.onFailedOutgoingCall(this, isAborted, errorCode, errorMsg);
        }

        clearCallService();
        clearCallServiceSelector();
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
        if (mState == CallState.NEW) {
            Log.v(this, "Aborting call %s", this);
            abort();
        } else if (mState != CallState.ABORTED && mState != CallState.DISCONNECTED) {
            Preconditions.checkNotNull(mCallService);

            Log.i(this, "Send disconnect to call service for call: %s", this);
            // The call isn't officially disconnected until the call service confirms that the call
            // was actually disconnected. Only then is the association between call and call service
            // severed, see {@link CallsManager#markCallAsDisconnected}.
            mCallService.disconnect(this);
        }
    }

    void abort() {
        if (mOutgoingCallProcessor != null) {
            mOutgoingCallProcessor.abort();
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

    Uri getRingtone() {
        return mCallerInfo == null ? null : mCallerInfo.contactRingtoneUri;
    }

    void onPostDialWait(String remaining) {
        for (Listener l : mListeners) {
            l.onPostDialWait(this, remaining);
        }
    }

    void postDialContinue(boolean proceed) {
        getCallService().onPostDialContinue(this, proceed);
    }

    void conferenceInto(Call conferenceCall) {
        if (mCallService == null) {
            Log.w(this, "conference requested on a call without a call service.");
        } else {
            mCallService.conference(conferenceCall, this);
        }
    }

    void expireConference() {
        // The conference call expired before we got a confirmation of the conference from the
        // call service...so start shutting down.
        clearCallService();
        for (Listener l : mListeners) {
            l.onExpiredConferenceCall(this);
        }
    }

    void confirmConference() {
        Log.v(this, "confirming Conf call %s", mListeners);
        for (Listener l : mListeners) {
            l.onConfirmedConferenceCall(this);
        }
    }

    void splitFromConference() {
        // TODO(santoscordon): todo
    }

    void setParentCall(Call parentCall) {
        if (parentCall == this) {
            Log.e(this, new Exception(), "setting the parent to self");
            return;
        }
        Preconditions.checkState(parentCall == null || mParentCall == null);

        Call oldParent = mParentCall;
        if (mParentCall != null) {
            mParentCall.removeChildCall(this);
        }
        mParentCall = parentCall;
        if (mParentCall != null) {
            mParentCall.addChildCall(this);
        }

        for (Listener l : mListeners) {
            l.onParentChanged(this);
        }
    }

    private void addChildCall(Call call) {
        if (!mChildCalls.contains(call)) {
            mChildCalls.add(call);

            for (Listener l : mListeners) {
                l.onChildrenChanged(this);
            }
        }
    }

    private void removeChildCall(Call call) {
        if (mChildCalls.remove(call)) {
            for (Listener l : mListeners) {
                l.onChildrenChanged(this);
            }
        }
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

    /**
     * Looks up contact information based on the current handle.
     */
    private void startCallerInfoLookup() {
        String number = mHandle == null ? null : mHandle.getSchemeSpecificPart();

        mQueryToken++;  // Updated so that previous queries can no longer set the information.
        mCallerInfo = null;
        if (!TextUtils.isEmpty(number)) {
            Log.v(this, "Looking up information for: %s.", Log.piiHandle(number));
            CallerInfoAsyncQuery.startQuery(
                    mQueryToken,
                    TelecommApp.getInstance(),
                    number,
                    sCallerInfoQueryListener,
                    this);
        }
    }

    /**
     * Saves the specified caller info if the specified token matches that of the last query
     * that was made.
     *
     * @param callerInfo The new caller information to set.
     * @param token The token used with this query.
     */
    private void setCallerInfo(CallerInfo callerInfo, int token) {
        Preconditions.checkNotNull(callerInfo);

        if (mQueryToken == token) {
            mCallerInfo = callerInfo;
            Log.i(this, "CallerInfo received for %s: %s", Log.piiHandle(mHandle), callerInfo);

            if (mCallerInfo.person_id != 0) {
                Uri personUri =
                        ContentUris.withAppendedId(Contacts.CONTENT_URI, mCallerInfo.person_id);
                Log.d(this, "Searching person uri %s for call %s", personUri, this);
                ContactsAsyncHelper.startObtainPhotoAsync(
                        token,
                        TelecommApp.getInstance(),
                        personUri,
                        sPhotoLoadListener,
                        this);
            }

            processDirectToVoicemail();
        }
    }

    /**
     * Saves the specified photo information if the specified token matches that of the last query.
     *
     * @param photo The photo as a drawable.
     * @param photoIcon The photo as a small icon.
     * @param token The token used with this query.
     */
    private void setPhoto(Drawable photo, Bitmap photoIcon, int token) {
        if (mQueryToken == token) {
            mCallerInfo.cachedPhoto = photo;
            mCallerInfo.cachedPhotoIcon = photoIcon;
        }
    }
}
