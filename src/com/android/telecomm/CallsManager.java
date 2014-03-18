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

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.telecomm.CallService;
import android.telecomm.CallServiceDescriptor;
import android.telecomm.CallState;
import android.telecomm.TelecommConstants;
import android.telephony.TelephonyManager;

import com.android.internal.telecomm.ICallService;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
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

    private static final CallsManager INSTANCE = new CallsManager();

    private final Switchboard mSwitchboard;

    /** Used to control the in-call app. */
    private final InCallController mInCallController;

    private final Ringer mRinger;

    /**
     * The main call repository. Keeps an instance of all live calls keyed by call ID. New incoming
     * and outgoing calls are added to the map and removed when the calls move to the disconnected
     * state.
     * TODO(santoscordon): Add new CallId class and use it in place of String.
     */
    private final Map<String, Call> mCalls = Maps.newHashMap();

    /**
     * Used to keep ordering of unanswered incoming calls. The existence of multiple call services
     * means that there can easily exist multiple incoming calls and explicit ordering is useful for
     * maintaining the proper state of the ringer.
     * TODO(santoscordon): May want to add comments about ITelephony.answerCall() method since
     * ordering may also apply to that case.
     */
    private final List<Call> mUnansweredIncomingCalls = Lists.newLinkedList();

    /**
     * May be unnecessary per off-line discussions (between santoscordon and gilad) since the set
     * of CallsManager APIs that need to be exposed to the dialer (or any application firing call
     * intents) may be empty.
     */
    private DialerAdapter mDialerAdapter;

    private InCallAdapter mInCallAdapter;

    private CallLogManager mCallLogManager;

    private VoicemailManager mVoicemailManager;

    private final List<OutgoingCallValidator> mOutgoingCallValidators = Lists.newArrayList();

    private final List<IncomingCallValidator> mIncomingCallValidators = Lists.newArrayList();

    /**
     * Initializes the required Telecomm components.
     */
    private CallsManager() {
        mSwitchboard = new Switchboard(this);
        mInCallController = new InCallController(this);
        mRinger = new Ringer();
        mCallLogManager = new CallLogManager(TelecommApp.getInstance());
    }

    static CallsManager getInstance() {
        return INSTANCE;
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
        Call call = new Call();

        mSwitchboard.retrieveIncomingCall(call, descriptor, extras);
    }

    /**
     * Validates the specified call and, upon no objection to connect it, adds the new call to the
     * list of live calls. Also notifies the in-call app so the user can answer or reject the call.
     *
     * @param call The new incoming call.
     */
    void handleSuccessfulIncomingCall(Call call) {
        Log.d(this, "handleSuccessfulIncomingCall");
        Preconditions.checkState(call.getState() == CallState.RINGING);

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
        addCall(call);

        mUnansweredIncomingCalls.add(call);
        if (mUnansweredIncomingCalls.size() == 1) {
            // Start the ringer if we are the top-most incoming call (the only one in this case).
            mRinger.startRinging();
        }
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
        Call call = new Call(handle, contactInfo);
        mSwitchboard.placeOutgoingCall(call);
    }

    /**
     * Adds a new outgoing call to the list of live calls and notifies the in-call app.
     *
     * @param call The new outgoing call.
     */
    void handleSuccessfulOutgoingCall(Call call) {
        // OutgoingCallProcessor sets the call state to DIALING when it receives confirmation of the
        // placed call from the call service so there is no need to set it here. Instead, check that
        // the state is appropriate.
        Preconditions.checkState(call.getState() == CallState.DIALING);
        addCall(call);
    }

    /**
     * Informs mCallLogManager about the outgoing call that failed, so that it can be logged.
     *
     * @param call The failed outgoing call.
     */
    void handleFailedOutgoingCall(Call call) {
        mCallLogManager.logFailedOutgoingCall(call);
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
            stopRinging(call);

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
            stopRinging(call);
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

        // TODO(sail): Replace with CallAudioManager.
        Log.v(this, "disconnectCall, abandoning audio focus");
        Context context = TelecommApp.getInstance().getApplicationContext();
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        audioManager.setMode(AudioManager.MODE_NORMAL);
        audioManager.abandonAudioFocusForCall();
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
        removeFromUnansweredCalls(callId);
        mInCallController.markCallAsActive(callId);

        // TODO(sail): Replace with CallAudioManager.
        Log.v(this, "markCallAsActive, requesting audio focus");
        Context context = TelecommApp.getInstance().getApplicationContext();
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        audioManager.requestAudioFocusForCall(AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        audioManager.setMode(AudioManager.MODE_IN_CALL);
        audioManager.setMicrophoneMute(false);
        audioManager.setSpeakerphoneOn(false);
    }

    void markCallAsOnHold(String callId) {
        setCallState(callId, CallState.ON_HOLD);

        // Notify the in-call UI
        mInCallController.markCallAsOnHold(callId);
    }

    /**
     * Marks the specified call as DISCONNECTED and notifies the in-call app. If this was the last
     * live call, then also disconnect from the in-call controller.
     *
     * @param callId The ID of the call.
     */
    void markCallAsDisconnected(String callId) {
        setCallState(callId, CallState.DISCONNECTED);
        removeFromUnansweredCalls(callId);

        Call call = mCalls.remove(callId);
        // At this point the call service has confirmed that the call is disconnected to it is
        // safe to disassociate the call from its call service.
        call.clearCallService();

        // Notify the in-call UI
        mInCallController.markCallAsDisconnected(callId);
        if (mCalls.isEmpty()) {
            mInCallController.unbind();
        }

        // Log the call in the call log.
        mCallLogManager.logDisconnectedCall(call);
    }

    /**
     * Sends all the live calls to the in-call app if any exist. If there are no live calls, then
     * tells the in-call controller to unbind since it is not needed.
     */
    void updateInCall() {
        if (mCalls.isEmpty()) {
            mInCallController.unbind();
        } else {
            for (Call call : mCalls.values()) {
                addInCallEntry(call);
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
        addInCallEntry(call);
    }

    /**
     * Notifies the in-call app of the specified (new) call.
     */
    private void addInCallEntry(Call call) {
        mInCallController.addCall(call.toCallInfo());
    }

    /**
     * Sets the specified state on the specified call. Updates the ringer if the call is exiting
     * the RINGING state.
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
            if (newState != call.getState()) {
                // Unfortunately, in the telephony world the radio is king. So if the call notifies
                // us that the call is in a particular state, we allow it even if it doesn't make
                // sense (e.g., ACTIVE -> RINGING).
                // TODO(santoscordon): Consider putting a stop to the above and turning CallState
                // into a well-defined state machine.
                // TODO(santoscordon): Define expected state transitions here, and log when an
                // unexpected transition occurs.
                call.setState(newState);
                // TODO(santoscordon): Notify the in-call app whenever a call changes state.

                broadcastState(call);
            }
        }
    }

    /**
     * Send a {@link TelephonyManager#ACTION_PHONE_STATE_CHANGED} broadcast when the call state
     * changes. TODO: Split this out into a separate class and do it properly; this is just a first
     * pass over the functionality.
     *
     * @param call The {@link Call} being updated.
     */
    private void broadcastState(Call call) {
        final String callState;
        switch (call.getState()) {
            case DIALING:
            case ACTIVE:
            case ON_HOLD:
                callState = TelephonyManager.EXTRA_STATE_OFFHOOK;
                break;

            case RINGING:
                callState = TelephonyManager.EXTRA_STATE_RINGING;
                break;

            case DISCONNECTED:
            case ABORTED:
                callState = TelephonyManager.EXTRA_STATE_IDLE;
                break;

            default:
                Log.w(this, "Call is in an unknown state (%s), not broadcasting: %s", call.getState(),
                        call.getId());
                return;
        }

        Intent updateIntent = new Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
        updateIntent.putExtra(TelephonyManager.EXTRA_STATE, callState);
        // TODO: See if we can add this (the current API doesn't have a callId).
        updateIntent.putExtra(TelecommConstants.EXTRA_CALL_ID, call.getId());

        // Populate both, since the original API was needlessly complicated.
        updateIntent.putExtra(TelephonyManager.EXTRA_INCOMING_NUMBER, call.getHandle());
        // TODO: See if we can add this (the current API only sets this on NEW_OUTGOING_CALL).
        updateIntent.putExtra(Intent.EXTRA_PHONE_NUMBER, call.getHandle());

        // TODO: Replace these with real constants once this API has been vetted.
        CallServiceWrapper callService = call.getCallService();
        if (callService != null) {
            updateIntent.putExtra(CallService.class.getName(), callService.getComponentName());
        }
        TelecommApp.getInstance().sendBroadcast(updateIntent, Manifest.permission.READ_PHONE_STATE);
        Log.i(this, "Broadcasted state change: %s", callState);
    }

    /**
     * Removes the specified call from the list of unanswered incoming calls and updates the ringer
     * based on the new state of {@link #mUnansweredIncomingCalls}. Safe to call with a call ID that
     * is not present in the list of incoming calls.
     *
     * @param callId The ID of the call.
     */
    private void removeFromUnansweredCalls(String callId) {
        Call call = mCalls.get(callId);
        if (call != null && mUnansweredIncomingCalls.remove(call)) {
            if (mUnansweredIncomingCalls.isEmpty()) {
                mRinger.stopRinging();
            } else {
                mRinger.startRinging();
            }
        }
    }

    /**
     * Stops playing the ringer if the specified call is the top-most incoming call. This exists
     * separately from {@link #removeFromUnansweredCalls} to allow stopping the ringer for calls
     * that should remain in {@link #mUnansweredIncomingCalls}, invoked from {@link #answerCall}
     * and {@link #rejectCall}.
     *
     * @param call The call for which we should stop ringing.
     */
    private void stopRinging(Call call) {
        // Only stop the ringer if this call is the top-most incoming call.
        if (!mUnansweredIncomingCalls.isEmpty() && mUnansweredIncomingCalls.get(0) == call) {
            mRinger.stopRinging();
        }
    }
}
