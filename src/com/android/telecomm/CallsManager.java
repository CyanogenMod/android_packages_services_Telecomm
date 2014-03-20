/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.telecomm.CallInfo;
import android.telecomm.CallServiceDescriptor;
import android.telecomm.CallState;

import com.android.internal.telecomm.ICallService;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import java.util.List;
import java.util.Map;

/**
 * Singleton.
 *
 * NOTE(gilad): by design most APIs are package private, use the relevant adapter/s to allow
 * access from other packages specifically refraining from passing the CallsManager instance
 * beyond the com.android.telecomm package boundary.
 */
public final class CallsManager {

    interface CallsManagerListener {
        void onCallAdded(Call call);
        void onCallRemoved(Call call);
        void onCallStateChanged(Call call, CallState oldState, CallState newState);
        void onIncomingCallAnswered(Call call);
        void onIncomingCallRejected(Call call);
        void onForegroundCallChanged(Call oldForegroundCall, Call newForegroundCall);
    }

    private static final CallsManager INSTANCE = new CallsManager();

    private final Switchboard mSwitchboard;

    /**
     * The main call repository. Keeps an instance of all live calls keyed by call ID. New incoming
     * and outgoing calls are added to the map and removed when the calls move to the disconnected
     * state.
     * TODO(santoscordon): Add new CallId class and use it in place of String.
     */
    private final Map<String, Call> mCalls = Maps.newHashMap();

    /**
     * The call the user is currently interacting with. This is the call that should have audio
     * focus and be visible in the in-call UI.
     */
    private Call mForegroundCall;

    private final CallsManagerListener mCallLogManager;

    private final CallsManagerListener mPhoneStateBroadcaster;

    private final CallsManagerListener mCallAudioManager;

    private final CallsManagerListener mInCallController;

    private final List<OutgoingCallValidator> mOutgoingCallValidators = Lists.newArrayList();

    private final List<IncomingCallValidator> mIncomingCallValidators = Lists.newArrayList();

    /**
     * Initializes the required Telecomm components.
     */
    private CallsManager() {
        mSwitchboard = new Switchboard(this);
        mCallLogManager = new CallLogManager(TelecommApp.getInstance());
        mPhoneStateBroadcaster = new PhoneStateBroadcaster();
        mCallAudioManager = new CallAudioManager();
        mInCallController = new InCallController();
    }

    static CallsManager getInstance() {
        return INSTANCE;
    }

    ImmutableCollection<Call> getCalls() {
        return ImmutableList.copyOf(mCalls.values());
    }

    Call getForegroundCall() {
        return mForegroundCall;
    }

    /**
     * Starts the incoming call sequence by having switchboard gather more information about the
     * specified call; using the specified call service descriptor. Upon success, execution returns
     * to {@link #handleSuccessfulIncomingCall} to start the in-call UI.
     *
     * @param descriptor The descriptor of the call service to use for this incoming call.
     * @param extras The optional extras Bundle passed with the intent used for the incoming call.
     */
    void processIncomingCallIntent(CallServiceDescriptor descriptor, Bundle extras) {
        Log.d(this, "processIncomingCallIntent");
        // Create a call with no handle. Eventually, switchboard will update the call with
        // additional information from the call service, but for now we just need one to pass around
        // with a unique call ID.
        Call call = new Call(true /* isIncoming */);

        mSwitchboard.retrieveIncomingCall(call, descriptor, extras);
    }

    /**
     * Validates the specified call and, upon no objection to connect it, adds the new call to the
     * list of live calls. Also notifies the in-call app so the user can answer or reject the call.
     *
     * @param call The new incoming call.
     * @param callInfo The details of the call.
     */
    void handleSuccessfulIncomingCall(Call call, CallInfo callInfo) {
        Log.d(this, "handleSuccessfulIncomingCall");
        Preconditions.checkState(callInfo.getState() == CallState.RINGING);

        Uri handle = call.getHandle();
        ContactInfo contactInfo = call.getContactInfo();
        for (IncomingCallValidator validator : mIncomingCallValidators) {
            if (!validator.isValid(handle, contactInfo)) {
                // TODO(gilad): Consider displaying an error message.
                Log.i(this, "Dropping restricted incoming call");
                return;
            }
        }

        // No objection to accept the incoming call, proceed with potentially connecting it (based
        // on the user's action, or lack thereof).
        call.setHandle(callInfo.getHandle());
        setCallState(call, callInfo.getState());
        addCall(call);
    }

    /**
     * Called when an incoming call was not connected.
     *
     * @param call The incoming call.
     */
    void handleUnsuccessfulIncomingCall(Call call) {
        setCallState(call, CallState.DISCONNECTED);
    }

    /**
     * Attempts to issue/connect the specified call.  From an (arbitrary) application standpoint,
     * all that is required to initiate this flow is to fire either of the CALL, CALL_PRIVILEGED,
     * and CALL_EMERGENCY intents. These are listened to by CallActivity.java which then invokes
     * this method.
     *
     * @param handle The handle to dial.
     * @param contactInfo Information about the entity being called.
     */
    void processOutgoingCallIntent(Uri handle, ContactInfo contactInfo) {
        for (OutgoingCallValidator validator : mOutgoingCallValidators) {
            if (!validator.isValid(handle, contactInfo)) {
                // TODO(gilad): Display an error message.
                Log.i(this, "Dropping restricted outgoing call.");
                return;
            }
        }

        // No objection to issue the call, proceed with trying to put it through.
        Call call = new Call(handle, contactInfo, false /* isIncoming */);
        setCallState(call, CallState.DIALING);
        addCall(call);
        mSwitchboard.placeOutgoingCall(call);
    }

    /**
     * Called when a call service acknowledges that it can place a call.
     *
     * @param call The new outgoing call.
     */
    void handleSuccessfulOutgoingCall(Call call) {
        Log.v(this, "handleSuccessfulOutgoingCall, %s", call);
    }

    /**
     * Called when an outgoing call was not placed.
     *
     * @param call The outgoing call.
     * @param isAborted True if the call was unsuccessful because it was aborted.
     */
    void handleUnsuccessfulOutgoingCall(Call call, boolean isAborted) {
        Log.v(this, "handleAbortedOutgoingCall, call: %s, isAborted: %b", call, isAborted);
        if (isAborted) {
            call.abort();
            setCallState(call, CallState.ABORTED);
        } else {
            setCallState(call, CallState.DISCONNECTED);
        }
        removeCall(call);
    }

    /**
     * Instructs Telecomm to answer the specified call. Intended to be invoked by the in-call
     * app through {@link InCallAdapter} after Telecomm notifies it of an incoming call followed by
     * the user opting to answer said call.
     *
     * @param callId The ID of the call.
     */
    void answerCall(String callId) {
        Call call = mCalls.get(callId);
        if (call == null) {
            Log.i(this, "Request to answer a non-existent call %s", callId);
        } else {
            mCallLogManager.onIncomingCallAnswered(call);
            mPhoneStateBroadcaster.onIncomingCallAnswered(call);
            mCallAudioManager.onIncomingCallAnswered(call);
            mInCallController.onIncomingCallAnswered(call);

            // We do not update the UI until we get confirmation of the answer() through
            // {@link #markCallAsActive}. However, if we ever change that to look more responsive,
            // then we need to make sure we add a timeout for the answer() in case the call never
            // comes out of RINGING.
            call.answer();
        }
    }

    /**
     * Instructs Telecomm to reject the specified call. Intended to be invoked by the in-call
     * app through {@link InCallAdapter} after Telecomm notifies it of an incoming call followed by
     * the user opting to reject said call.
     *
     * @param callId The ID of the call.
     */
    void rejectCall(String callId) {
        Call call = mCalls.get(callId);
        if (call == null) {
            Log.i(this, "Request to reject a non-existent call %s", callId);
        } else {
            mCallLogManager.onIncomingCallRejected(call);
            mPhoneStateBroadcaster.onIncomingCallRejected(call);
            mCallAudioManager.onIncomingCallRejected(call);
            mInCallController.onIncomingCallRejected(call);

            call.reject();
        }
    }

    /**
     * Instructs Telecomm to disconnect the specified call. Intended to be invoked by the
     * in-call app through {@link InCallAdapter} for an ongoing call. This is usually triggered by
     * the user hitting the end-call button.
     *
     * @param callId The ID of the call.
     */
    void disconnectCall(String callId) {
        Call call = mCalls.get(callId);
        if (call == null) {
            Log.w(this, "Unknown call (%s) asked to disconnect", callId);
        } else {
            call.disconnect();
        }
    }

    /**
     * Instructs Telecomm to put the specified call on hold. Intended to be invoked by the
     * in-call app through {@link InCallAdapter} for an ongoing call. This is usually triggered by
     * the user hitting the hold button during an active call.
     *
     * @param callId The ID of the call.
     */
    void holdCall(String callId) {
        Call call = mCalls.get(callId);
        if (call == null) {
            Log.w(this, "Unknown call (%s) asked to be put on hold", callId);
        } else {
            Log.d(this, "Putting call on hold: (%s)", callId);
            call.hold();
        }
    }

    /**
     * Instructs Telecomm to release the specified call from hold. Intended to be invoked by
     * the in-call app through {@link InCallAdapter} for an ongoing call. This is usually triggered
     * by the user hitting the hold button during a held call.
     *
     * @param callId The ID of the call
     */
    void unholdCall(String callId) {
        Call call = mCalls.get(callId);
        if (call == null) {
            Log.w(this, "Unknown call (%s) asked to be removed from hold", callId);
        } else {
            Log.d(this, "Removing call from hold: (%s)", callId);
            call.unhold();
        }
    }

    void markCallAsRinging(String callId) {
        setCallState(callId, CallState.RINGING);
    }

    void markCallAsDialing(String callId) {
        setCallState(callId, CallState.DIALING);
    }

    void markCallAsActive(String callId) {
        setCallState(callId, CallState.ACTIVE);
    }

    void markCallAsOnHold(String callId) {
        setCallState(callId, CallState.ON_HOLD);
    }

    /**
     * Marks the specified call as DISCONNECTED and notifies the in-call app. If this was the last
     * live call, then also disconnect from the in-call controller.
     *
     * @param callId The ID of the call.
     */
    void markCallAsDisconnected(String callId) {
        setCallState(callId, CallState.DISCONNECTED);
        removeCall(mCalls.remove(callId));
    }

    /**
     * @return True if there exists a call with the specific state.
     */
    boolean hasCallWithState(CallState... states) {
        for (Call call : mCalls.values()) {
            for (CallState state : states) {
                if (call.getState() == state) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Cleans up any calls currently associated with the specified call service when the
     * call-service binder disconnects unexpectedly.
     *
     * @param callService The call service that disconnected.
     */
    void handleCallServiceDeath(CallServiceWrapper callService) {
        Preconditions.checkNotNull(callService);
        for (Call call : ImmutableList.copyOf(mCalls.values())) {
            if (call.getCallService() == callService) {
                markCallAsDisconnected(call.getId());
            }
        }
    }

    /**
     * Adds the specified call to the main list of live calls.
     *
     * @param call The call to add.
     */
    private void addCall(Call call) {
        mCalls.put(call.getId(), call);

        mCallLogManager.onCallAdded(call);
        mPhoneStateBroadcaster.onCallAdded(call);
        mCallAudioManager.onCallAdded(call);
        mInCallController.onCallAdded(call);
        updateForegroundCall();
    }

    private void removeCall(Call call) {
        call.clearCallService();

        mCallLogManager.onCallRemoved(call);
        mPhoneStateBroadcaster.onCallRemoved(call);
        mCallAudioManager.onCallRemoved(call);
        mInCallController.onCallRemoved(call);
        updateForegroundCall();
    }

    /**
     * Sets the specified state on the specified call.
     *
     * @param callId The ID of the call to update.
     * @param newState The new state of the call.
     */
    private void setCallState(String callId, CallState newState) {
        Preconditions.checkState(!Strings.isNullOrEmpty(callId));
        Preconditions.checkNotNull(newState);

        Call call = mCalls.get(callId);
        if (call == null) {
            Log.w(this, "Call %s was not found while attempting to update the state to %s.",
                    callId, newState );
        } else {
            setCallState(call, newState);
        }
    }

    /**
     * Sets the specified state on the specified call.
     *
     * @param call The call.
     * @param newState The new state of the call.
     */
    private void setCallState(Call call, CallState newState) {
        CallState oldState = call.getState();
        if (newState != oldState) {
            // Unfortunately, in the telephony world the radio is king. So if the call notifies
            // us that the call is in a particular state, we allow it even if it doesn't make
            // sense (e.g., ACTIVE -> RINGING).
            // TODO(santoscordon): Consider putting a stop to the above and turning CallState
            // into a well-defined state machine.
            // TODO(santoscordon): Define expected state transitions here, and log when an
            // unexpected transition occurs.
            call.setState(newState);

            // Only broadcast state change for calls that are being tracked.
            if (mCalls.containsKey(call.getId())) {
                mCallLogManager.onCallStateChanged(call, oldState, newState);
                mPhoneStateBroadcaster.onCallStateChanged(call, oldState, newState);
                mCallAudioManager.onCallStateChanged(call, oldState, newState);
                mInCallController.onCallStateChanged(call, oldState, newState);
                updateForegroundCall();
            }
        }
    }

    /**
     * Checks which call should be visible to the user and have audio focus.
     */
    private void updateForegroundCall() {
        Call newForegroundCall = null;
        for (Call call : mCalls.values()) {
            // Incoming ringing calls have priority.
            if (call.getState() == CallState.RINGING) {
                newForegroundCall = call;
                break;
            }
            if (call.getState() == CallState.ACTIVE) {
                newForegroundCall = call;
                // Don't break in case there's a ringing call that has priority.
            }
        }

        if (newForegroundCall != mForegroundCall) {
            Call oldForegroundCall = mForegroundCall;
            mForegroundCall = newForegroundCall;

            mCallLogManager.onForegroundCallChanged(oldForegroundCall, mForegroundCall);
            mPhoneStateBroadcaster.onForegroundCallChanged(oldForegroundCall, mForegroundCall);
            mCallAudioManager.onForegroundCallChanged(oldForegroundCall, mForegroundCall);
            mInCallController.onForegroundCallChanged(oldForegroundCall, mForegroundCall);
        }
    }
}
