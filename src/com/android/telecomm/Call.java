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

import android.app.PendingIntent;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.telecomm.CallPropertyPresentation;
import android.telecomm.CallState;
import android.telecomm.Connection;
import android.telecomm.ConnectionRequest;
import android.telecomm.GatewayInfo;
import android.telecomm.ParcelableConnection;
import android.telecomm.PhoneAccountHandle;
import android.telecomm.Response;
import android.telecomm.StatusHints;
import android.telephony.DisconnectCause;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;

import com.android.internal.telecomm.ICallVideoProvider;
import com.android.internal.telephony.CallerInfo;
import com.android.internal.telephony.CallerInfoAsyncQuery;
import com.android.internal.telephony.CallerInfoAsyncQuery.OnQueryCompleteListener;
import com.android.internal.telephony.SmsApplication;
import com.android.telecomm.ContactsAsyncHelper.OnImageLoadCompleteListener;
import com.google.common.base.Preconditions;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 *  Encapsulates all aspects of a given phone call throughout its lifecycle, starting
 *  from the time the call intent was received by Telecomm (vs. the time the call was
 *  connected etc).
 */
final class Call implements CreateConnectionResponse {
    /**
     * Listener for events on the call.
     */
    interface Listener {
        void onSuccessfulOutgoingCall(Call call);
        void onFailedOutgoingCall(Call call, int errorCode, String errorMsg);
        void onCancelledOutgoingCall(Call call);
        void onSuccessfulIncomingCall(Call call);
        void onFailedIncomingCall(Call call);
        void onRequestingRingback(Call call, boolean requestingRingback);
        void onPostDialWait(Call call, String remaining);
        void onCallCapabilitiesChanged(Call call);
        void onExpiredConferenceCall(Call call);
        void onConfirmedConferenceCall(Call call);
        void onParentChanged(Call call);
        void onChildrenChanged(Call call);
        void onCannedSmsResponsesLoaded(Call call);
        void onCallVideoProviderChanged(Call call);
        void onCallerInfoChanged(Call call);
        void onAudioModeIsVoipChanged(Call call);
        void onStatusHintsChanged(Call call);
        void onHandleChanged(Call call);
        void onCallerDisplayNameChanged(Call call);
        void onVideoStateChanged(Call call);
        void onStartActivityFromInCall(Call call, PendingIntent intent);
        void onPhoneAccountChanged(Call call);
    }

    abstract static class ListenerBase implements Listener {
        @Override
        public void onSuccessfulOutgoingCall(Call call) {}
        @Override
        public void onFailedOutgoingCall(Call call, int errorCode, String errorMsg) {}
        @Override
        public void onCancelledOutgoingCall(Call call) {}
        @Override
        public void onSuccessfulIncomingCall(Call call) {}
        @Override
        public void onFailedIncomingCall(Call call) {}
        @Override
        public void onRequestingRingback(Call call, boolean requestingRingback) {}
        @Override
        public void onPostDialWait(Call call, String remaining) {}
        @Override
        public void onCallCapabilitiesChanged(Call call) {}
        @Override
        public void onExpiredConferenceCall(Call call) {}
        @Override
        public void onConfirmedConferenceCall(Call call) {}
        @Override
        public void onParentChanged(Call call) {}
        @Override
        public void onChildrenChanged(Call call) {}
        @Override
        public void onCannedSmsResponsesLoaded(Call call) {}
        @Override
        public void onCallVideoProviderChanged(Call call) {}
        @Override
        public void onCallerInfoChanged(Call call) {}
        @Override
        public void onAudioModeIsVoipChanged(Call call) {}
        @Override
        public void onStatusHintsChanged(Call call) {}
        @Override
        public void onHandleChanged(Call call) {}
        @Override
        public void onCallerDisplayNameChanged(Call call) {}
        @Override
        public void onVideoStateChanged(Call call) {}
        @Override
        public void onStartActivityFromInCall(Call call, PendingIntent intent) {}
        @Override
        public void onPhoneAccountChanged(Call call) {}
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

    private final Runnable mDirectToVoicemailRunnable = new Runnable() {
        @Override
        public void run() {
            processDirectToVoicemail();
        }
    };

    /** True if this is an incoming call. */
    private final boolean mIsIncoming;

    /**
     * The time this call was created. Beyond logging and such, may also be used for bookkeeping
     * and specifically for marking certain call attempts as failed attempts.
     */
    private final long mCreationTimeMillis = System.currentTimeMillis();

    /** The gateway information associated with this call. This stores the original call handle
     * that the user is attempting to connect to via the gateway, the actual handle to dial in
     * order to connect the call via the gateway, as well as the package name of the gateway
     * service. */
    private final GatewayInfo mGatewayInfo;

    private PhoneAccountHandle mPhoneAccountHandle;

    private final Handler mHandler = new Handler();

    private long mConnectTimeMillis;

    /** The state of the call. */
    private CallState mState;

    /** The handle with which to establish this call. */
    private Uri mHandle;

    /** The {@link CallPropertyPresentation} that controls how the handle is shown. */
    private int mHandlePresentation;

    /** The caller display name (CNAP) set by the connection service. */
    private String mCallerDisplayName;

    /** The {@link CallPropertyPresentation} that controls how the caller display name is shown. */
    private int mCallerDisplayNamePresentation;

    /**
     * The connection service which is attempted or already connecting this call.
     */
    private ConnectionServiceWrapper mConnectionService;

    private boolean mIsEmergencyCall;

    private boolean mSpeakerphoneOn;

    /**
     * Tracks the video states which were applicable over the duration of a call.
     * See {@link android.telecomm.VideoCallProfile} for a list of valid video states.
     */
    private int mVideoStateHistory;

    private int mVideoState;

    /**
     * Disconnect cause for the call. Only valid if the state of the call is DISCONNECTED.
     * See {@link android.telephony.DisconnectCause}.
     */
    private int mDisconnectCause = DisconnectCause.NOT_VALID;

    /**
     * Additional disconnect information provided by the connection service.
     */
    private String mDisconnectMessage;

    /** Info used by the connection services. */
    private Bundle mExtras = Bundle.EMPTY;

    /** Set of listeners on this call. */
    private Set<Listener> mListeners = new CopyOnWriteArraySet<>();

    private CreateConnectionProcessor mCreateConnectionProcessor;

    /** Caller information retrieved from the latest contact query. */
    private CallerInfo mCallerInfo;

    /** The latest token used with a contact info query. */
    private int mQueryToken = 0;

    /** Whether this call is requesting that Telecomm play the ringback tone on its behalf. */
    private boolean mRequestingRingback = false;

    /** Whether direct-to-voicemail query is pending. */
    private boolean mDirectToVoicemailQueryPending;

    private int mCallCapabilities;

    private boolean mIsConference = false;

    private Call mParentCall = null;

    private List<Call> mChildCalls = new LinkedList<>();

    /** Set of text message responses allowed for this call, if applicable. */
    private List<String> mCannedSmsResponses = Collections.EMPTY_LIST;

    /** Whether an attempt has been made to load the text message responses. */
    private boolean mCannedSmsResponsesLoadingStarted = false;

    private ICallVideoProvider mCallVideoProvider;

    private boolean mAudioModeIsVoip;
    private StatusHints mStatusHints;
    private final ConnectionServiceRepository mRepository;

    /**
     * Persists the specified parameters and initializes the new instance.
     *
     * @param handle The handle to dial.
     * @param gatewayInfo Gateway information to use for the call.
     * @param accountHandle Account information to use for the call.
     * @param isIncoming True if this is an incoming call.
     */
    Call(
            ConnectionServiceRepository repository,
            Uri handle,
            GatewayInfo gatewayInfo,
            PhoneAccountHandle accountHandle,
            boolean isIncoming,
            boolean isConference) {
        mState = isConference ? CallState.ACTIVE : CallState.NEW;
        mRepository = repository;
        setHandle(handle, CallPropertyPresentation.ALLOWED);
        mGatewayInfo = gatewayInfo;
        mPhoneAccountHandle = accountHandle;
        mIsIncoming = isIncoming;
        mIsConference = isConference;
        maybeLoadCannedSmsResponses();
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
        if (mConnectionService != null && mConnectionService.getComponentName() != null) {
            component = mConnectionService.getComponentName().flattenToShortString();
        }

        return String.format(Locale.US, "[%s, %s, %s, %d]", mState, component,
                Log.piiHandle(mHandle), getVideoState());
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
            maybeLoadCannedSmsResponses();
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

    int getHandlePresentation() {
        return mHandlePresentation;
    }

    void setHandle(Uri handle, int presentation) {
        if (!Objects.equals(handle, mHandle) || presentation != mHandlePresentation) {
            mHandle = handle;
            mHandlePresentation = presentation;
            mIsEmergencyCall = mHandle != null && PhoneNumberUtils.isLocalEmergencyNumber(
                    TelecommApp.getInstance(), mHandle.getSchemeSpecificPart());
            startCallerInfoLookup();
            for (Listener l : mListeners) {
                l.onHandleChanged(this);
            }
        }
    }

    String getCallerDisplayName() {
        return mCallerDisplayName;
    }

    int getCallerDisplayNamePresentation() {
        return mCallerDisplayNamePresentation;
    }

    void setCallerDisplayName(String callerDisplayName, int presentation) {
        if (!TextUtils.equals(callerDisplayName, mCallerDisplayName) ||
                presentation != mCallerDisplayNamePresentation) {
            mCallerDisplayName = callerDisplayName;
            mCallerDisplayNamePresentation = presentation;
            for (Listener l : mListeners) {
                l.onCallerDisplayNameChanged(this);
            }
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
     * @param disconnectMessage Optional message about the disconnect.
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

    PhoneAccountHandle getPhoneAccount() {
        return mPhoneAccountHandle;
    }

    void setPhoneAccount(PhoneAccountHandle accountHandle) {
        if (!Objects.equals(mPhoneAccountHandle, accountHandle)) {
            mPhoneAccountHandle = accountHandle;
            for (Listener l : mListeners) {
                l.onPhoneAccountChanged(this);
            }
        }
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

    int getCallCapabilities() {
        return mCallCapabilities;
    }

    void setCallCapabilities(int callCapabilities) {
        if (mCallCapabilities != callCapabilities) {
            mCallCapabilities = callCapabilities;
            for (Listener l : mListeners) {
                l.onCallCapabilitiesChanged(this);
            }
        }
    }

    Call getParentCall() {
        return mParentCall;
    }

    List<Call> getChildCalls() {
        return mChildCalls;
    }

    ConnectionServiceWrapper getConnectionService() {
        return mConnectionService;
    }

    void setConnectionService(ConnectionServiceWrapper service) {
        Preconditions.checkNotNull(service);

        clearConnectionService();

        service.incrementAssociatedCallCount();
        mConnectionService = service;
        mConnectionService.addCall(this);
    }

    /**
     * Clears the associated connection service.
     */
    void clearConnectionService() {
        if (mConnectionService != null) {
            ConnectionServiceWrapper serviceTemp = mConnectionService;
            mConnectionService = null;
            serviceTemp.removeCall(this);

            // Decrementing the count can cause the service to unbind, which itself can trigger the
            // service-death code.  Since the service death code tries to clean up any associated
            // calls, we need to make sure to remove that information (e.g., removeCall()) before
            // we decrement. Technically, invoking removeCall() prior to decrementing is all that is
            // necessary, but cleaning up mConnectionService prior to triggering an unbind is good
            // to do.
            decrementAssociatedCallCount(serviceTemp);
        }
    }

    private void processDirectToVoicemail() {
        if (mDirectToVoicemailQueryPending) {
            if (mCallerInfo != null && mCallerInfo.shouldSendToVoicemail) {
                Log.i(this, "Directing call to voicemail: %s.", this);
                // TODO(santoscordon): Once we move State handling from CallsManager to Call, we
                // will not need to set RINGING state prior to calling reject.
                setState(CallState.RINGING);
                reject(false, null);
            } else {
                // TODO(santoscordon): Make this class (not CallsManager) responsible for changing
                // the call state to RINGING.

                // TODO(santoscordon): Replace this with state transition to RINGING.
                for (Listener l : mListeners) {
                    l.onSuccessfulIncomingCall(this);
                }
            }

            mDirectToVoicemailQueryPending = false;
        }
    }

    /**
     * Starts the create connection sequence. Upon completion, there should exist an active
     * connection through a connection service (or the call will have failed).
     */
    void startCreateConnection() {
        Preconditions.checkState(mCreateConnectionProcessor == null);
        mCreateConnectionProcessor = new CreateConnectionProcessor(this, mRepository, this);
        mCreateConnectionProcessor.process();
    }

    @Override
    public void handleCreateConnectionSuccessful(
            ConnectionRequest request, ParcelableConnection connection) {
        mCreateConnectionProcessor = null;
        setState(getStateFromConnectionState(connection.getState()));
        setPhoneAccount(connection.getPhoneAccount());
        setHandle(connection.getHandle(), connection.getHandlePresentation());
        setCallerDisplayName(
                connection.getCallerDisplayName(), connection.getCallerDisplayNamePresentation());
        setCallVideoProvider(connection.getCallVideoProvider());
        setVideoState(connection.getVideoState());

        if (mIsIncoming) {
            // We do not handle incoming calls immediately when they are verified by the connection
            // service. We allow the caller-info-query code to execute first so that we can read the
            // direct-to-voicemail property before deciding if we want to show the incoming call to
            // the user or if we want to reject the call.
            mDirectToVoicemailQueryPending = true;

            // Timeout the direct-to-voicemail lookup execution so that we dont wait too long before
            // showing the user the incoming call screen.
            mHandler.postDelayed(mDirectToVoicemailRunnable, Timeouts.getDirectToVoicemailMillis());
        } else {
            for (Listener l : mListeners) {
                l.onSuccessfulOutgoingCall(this);
            }
        }
    }

    @Override
    public void handleCreateConnectionFailed(int code, String msg) {
        mCreateConnectionProcessor = null;
        if (mIsIncoming) {
            clearConnectionService();
            setDisconnectCause(code, null);
            setState(CallState.DISCONNECTED);

            Listener[] listeners = mListeners.toArray(new Listener[mListeners.size()]);
            for (int i = 0; i < listeners.length; i++) {
                listeners[i].onFailedIncomingCall(this);
            }
        } else {
            Listener[] listeners = mListeners.toArray(new Listener[mListeners.size()]);
            for (int i = 0; i < listeners.length; i++) {
                listeners[i].onFailedOutgoingCall(this, code, msg);
            }
            clearConnectionService();
        }
    }

    @Override
    public void handleCreateConnectionCancelled() {
        mCreateConnectionProcessor = null;
        if (mIsIncoming) {
            clearConnectionService();
            setDisconnectCause(DisconnectCause.OUTGOING_CANCELED, null);
            setState(CallState.DISCONNECTED);

            Listener[] listeners = mListeners.toArray(new Listener[mListeners.size()]);
            for (int i = 0; i < listeners.length; i++) {
                listeners[i].onFailedIncomingCall(this);
            }
        } else {
            Listener[] listeners = mListeners.toArray(new Listener[mListeners.size()]);
            for (int i = 0; i < listeners.length; i++) {
                listeners[i].onCancelledOutgoingCall(this);
            }
            clearConnectionService();
        }
    }

    /**
     * Plays the specified DTMF tone.
     */
    void playDtmfTone(char digit) {
        if (mConnectionService == null) {
            Log.w(this, "playDtmfTone() request on a call without a connection service.");
        } else {
            Log.i(this, "Send playDtmfTone to connection service for call %s", this);
            mConnectionService.playDtmfTone(this, digit);
        }
    }

    /**
     * Stops playing any currently playing DTMF tone.
     */
    void stopDtmfTone() {
        if (mConnectionService == null) {
            Log.w(this, "stopDtmfTone() request on a call without a connectino service.");
        } else {
            Log.i(this, "Send stopDtmfTone to connection service for call %s", this);
            mConnectionService.stopDtmfTone(this);
        }
    }

    /**
     * Attempts to disconnect the call through the connection service.
     */
    void disconnect() {
        if (mState == CallState.NEW || mState == CallState.PRE_DIAL_WAIT) {
            Log.v(this, "Aborting call %s", this);
            abort();
        } else if (mState != CallState.ABORTED && mState != CallState.DISCONNECTED) {
            Preconditions.checkNotNull(mConnectionService);

            Log.i(this, "Send disconnect to connection service for call: %s", this);
            // The call isn't officially disconnected until the connection service confirms that the
            // call was actually disconnected. Only then is the association between call and
            // connection service severed, see {@link CallsManager#markCallAsDisconnected}.
            mConnectionService.disconnect(this);
        }
    }

    void abort() {
        if (mCreateConnectionProcessor != null) {
            mCreateConnectionProcessor.abort();
        } else if (mState == CallState.PRE_DIAL_WAIT) {
            handleCreateConnectionFailed(DisconnectCause.LOCAL, null);
        }
    }

    /**
     * Answers the call if it is ringing.
     *
     * @param videoState The video state in which to answer the call.
     */
    void answer(int videoState) {
        Preconditions.checkNotNull(mConnectionService);

        // Check to verify that the call is still in the ringing state. A call can change states
        // between the time the user hits 'answer' and Telecomm receives the command.
        if (isRinging("answer")) {
            // At this point, we are asking the connection service to answer but we don't assume
            // that it will work. Instead, we wait until confirmation from the connectino service
            // that the call is in a non-RINGING state before changing the UI. See
            // {@link ConnectionServiceAdapter#setActive} and other set* methods.
            mConnectionService.answer(this, videoState);
        }
    }

    /**
     * Rejects the call if it is ringing.
     *
     * @param rejectWithMessage Whether to send a text message as part of the call rejection.
     * @param textMessage An optional text message to send as part of the rejection.
     */
    void reject(boolean rejectWithMessage, String textMessage) {
        Preconditions.checkNotNull(mConnectionService);

        // Check to verify that the call is still in the ringing state. A call can change states
        // between the time the user hits 'reject' and Telecomm receives the command.
        if (isRinging("reject")) {
            mConnectionService.reject(this);
        }
    }

    /**
     * Puts the call on hold if it is currently active.
     */
    void hold() {
        Preconditions.checkNotNull(mConnectionService);

        if (mState == CallState.ACTIVE) {
            mConnectionService.hold(this);
        }
    }

    /**
     * Releases the call from hold if it is currently active.
     */
    void unhold() {
        Preconditions.checkNotNull(mConnectionService);

        if (mState == CallState.ON_HOLD) {
            mConnectionService.unhold(this);
        }
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
        return mState == CallState.ACTIVE;
    }

    Bundle getExtras() {
        return mExtras;
    }

    void setExtras(Bundle extras) {
        mExtras = extras;
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
        mConnectionService.onPostDialContinue(this, proceed);
    }

    void phoneAccountClicked() {
        mConnectionService.onPhoneAccountClicked(this);
    }

    void conferenceInto(Call conferenceCall) {
        if (mConnectionService == null) {
            Log.w(this, "conference requested on a call without a connection service.");
        } else {
            mConnectionService.conference(conferenceCall, this);
        }
    }

    void expireConference() {
        // The conference call expired before we got a confirmation of the conference from the
        // connection service...so start shutting down.
        clearConnectionService();
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

    void swapWithBackgroundCall() {
        mConnectionService.swapWithBackgroundCall(this);
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
     * Return whether the user can respond to this {@code Call} via an SMS message.
     *
     * @return true if the "Respond via SMS" feature should be enabled
     * for this incoming call.
     *
     * The general rule is that we *do* allow "Respond via SMS" except for
     * the few (relatively rare) cases where we know for sure it won't
     * work, namely:
     *   - a bogus or blank incoming number
     *   - a call from a SIP address
     *   - a "call presentation" that doesn't allow the number to be revealed
     *
     * In all other cases, we allow the user to respond via SMS.
     *
     * Note that this behavior isn't perfect; for example we have no way
     * to detect whether the incoming call is from a landline (with most
     * networks at least), so we still enable this feature even though
     * SMSes to that number will silently fail.
     */
    boolean isRespondViaSmsCapable() {
        if (mState != CallState.RINGING) {
            return false;
        }

        if (getHandle() == null) {
            // No incoming number known or call presentation is "PRESENTATION_RESTRICTED", in
            // other words, the user should not be able to see the incoming phone number.
            return false;
        }

        if (PhoneNumberUtils.isUriNumber(getHandle().toString())) {
            // The incoming number is actually a URI (i.e. a SIP address),
            // not a regular PSTN phone number, and we can't send SMSes to
            // SIP addresses.
            // (TODO: That might still be possible eventually, though. Is
            // there some SIP-specific equivalent to sending a text message?)
            return false;
        }

        // Is there a valid SMS application on the phone?
        if (SmsApplication.getDefaultRespondViaMessageApplication(TelecommApp.getInstance(),
                true /*updateIfNeeded*/) == null) {
            return false;
        }

        // TODO: with some carriers (in certain countries) you *can* actually
        // tell whether a given number is a mobile phone or not. So in that
        // case we could potentially return false here if the incoming call is
        // from a land line.

        // If none of the above special cases apply, it's OK to enable the
        // "Respond via SMS" feature.
        return true;
    }

    List<String> getCannedSmsResponses() {
        return mCannedSmsResponses;
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

            if (mCallerInfo.contactDisplayPhotoUri != null) {
                Log.d(this, "Searching person uri %s for call %s",
                        mCallerInfo.contactDisplayPhotoUri, this);
                ContactsAsyncHelper.startObtainPhotoAsync(
                        token,
                        TelecommApp.getInstance(),
                        mCallerInfo.contactDisplayPhotoUri,
                        sPhotoLoadListener,
                        this);
                // Do not call onCallerInfoChanged yet in this case.  We call it in setPhoto().
            } else {
                for (Listener l : mListeners) {
                    l.onCallerInfoChanged(this);
                }
            }

            processDirectToVoicemail();
        }
    }

    CallerInfo getCallerInfo() {
        return mCallerInfo;
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

            for (Listener l : mListeners) {
                l.onCallerInfoChanged(this);
            }
        }
    }

    private void maybeLoadCannedSmsResponses() {
        if (mIsIncoming && isRespondViaSmsCapable() && !mCannedSmsResponsesLoadingStarted) {
            Log.d(this, "maybeLoadCannedSmsResponses: starting task to load messages");
            mCannedSmsResponsesLoadingStarted = true;
            RespondViaSmsManager.getInstance().loadCannedTextMessages(
                    new Response<Void, List<String>>() {
                        @Override
                        public void onResult(Void request, List<String>... result) {
                            if (result.length > 0) {
                                Log.d(this, "maybeLoadCannedSmsResponses: got %s", result[0]);
                                mCannedSmsResponses = result[0];
                                for (Listener l : mListeners) {
                                    l.onCannedSmsResponsesLoaded(Call.this);
                                }
                            }
                        }

                        @Override
                        public void onError(Void request, int code, String msg) {
                            Log.w(Call.this, "Error obtaining canned SMS responses: %d %s", code,
                                    msg);
                        }
                    }
            );
        } else {
            Log.d(this, "maybeLoadCannedSmsResponses: doing nothing");
        }
    }

    /**
     * Sets speakerphone option on when call begins.
     */
    public void setStartWithSpeakerphoneOn(boolean startWithSpeakerphone) {
        mSpeakerphoneOn = startWithSpeakerphone;
    }

    /**
     * Returns speakerphone option.
     *
     * @return Whether or not speakerphone should be set automatically when call begins.
     */
    public boolean getStartWithSpeakerphoneOn() {
        return mSpeakerphoneOn;
    }

    /**
     * Sets a call video provider for the call.
     */
    public void setCallVideoProvider(ICallVideoProvider callVideoProvider) {
        mCallVideoProvider = callVideoProvider;
        for (Listener l : mListeners) {
            l.onCallVideoProviderChanged(Call.this);
        }
    }

    /**
     * @return Return the call video Provider binder.
     */
    public ICallVideoProvider getCallVideoProvider() {
        return mCallVideoProvider;
    }

    /**
     * The current video state for the call.
     * Valid values: {@link android.telecomm.VideoCallProfile#VIDEO_STATE_AUDIO_ONLY},
     * {@link android.telecomm.VideoCallProfile#VIDEO_STATE_BIDIRECTIONAL},
     * {@link android.telecomm.VideoCallProfile#VIDEO_STATE_TX_ENABLED},
     * {@link android.telecomm.VideoCallProfile#VIDEO_STATE_RX_ENABLED}.
     *
     * @return True if video is enabled.
     */
    public int getVideoState() {
        return mVideoState;
    }

    /**
     * Returns the video states which were applicable over the duration of a call.
     * See {@link android.telecomm.VideoCallProfile} for a list of valid video states.
     *
     * @return The video states applicable over the duration of the call.
     */
    public int getVideoStateHistory() {
        return mVideoStateHistory;
    }

    /**
     * Determines the current video state for the call.
     * For an outgoing call determines the desired video state for the call.
     * Valid values: {@link android.telecomm.VideoCallProfile#VIDEO_STATE_AUDIO_ONLY},
     * {@link android.telecomm.VideoCallProfile#VIDEO_STATE_BIDIRECTIONAL},
     * {@link android.telecomm.VideoCallProfile#VIDEO_STATE_TX_ENABLED},
     * {@link android.telecomm.VideoCallProfile#VIDEO_STATE_RX_ENABLED}.
     *
     * @param videoState The video state for the call.
     */
    public void setVideoState(int videoState) {
        // Track which video states were applicable over the duration of the call.
        mVideoStateHistory = mVideoStateHistory | videoState;

        mVideoState = videoState;
        for (Listener l : mListeners) {
            l.onVideoStateChanged(this);
        }
    }

    public boolean getAudioModeIsVoip() {
        return mAudioModeIsVoip;
    }

    public void setAudioModeIsVoip(boolean audioModeIsVoip) {
        mAudioModeIsVoip = audioModeIsVoip;
        for (Listener l : mListeners) {
            l.onAudioModeIsVoipChanged(this);
        }
    }

    public StatusHints getStatusHints() {
        return mStatusHints;
    }

    public void setStatusHints(StatusHints statusHints) {
        mStatusHints = statusHints;
        for (Listener l : mListeners) {
            l.onStatusHintsChanged(this);
        }
    }

    public void startActivityFromInCall(PendingIntent intent) {
        if (intent.isActivity()) {
            for (Listener l : mListeners) {
                l.onStartActivityFromInCall(this, intent);
            }
        } else {
            Log.w(this, "startActivityFromInCall, activity intent required");
        }
    }

    private CallState getStateFromConnectionState(int state) {
        switch (state) {
            case Connection.State.ACTIVE:
                return CallState.ACTIVE;
            case Connection.State.DIALING:
                return CallState.DIALING;
            case Connection.State.DISCONNECTED:
                return CallState.DISCONNECTED;
            case Connection.State.HOLDING:
                return CallState.ON_HOLD;
            case Connection.State.NEW:
                return CallState.NEW;
            case Connection.State.RINGING:
                return CallState.RINGING;
        }
        return CallState.DISCONNECTED;
    }
}
