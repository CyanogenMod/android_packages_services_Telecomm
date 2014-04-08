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

import android.net.Uri;
import android.os.Bundle;
import android.telecomm.CallAudioState;
import android.telecomm.CallInfo;
import android.telecomm.CallServiceDescriptor;
import android.telecomm.CallState;
import android.telecomm.GatewayInfo;
import android.telephony.DisconnectCause;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Singleton.
 *
 * NOTE(gilad): by design most APIs are package private, use the relevant adapter/s to allow
 * access from other packages specifically refraining from passing the CallsManager instance
 * beyond the com.android.telecomm package boundary.
 */
public final class CallsManager {

    // TODO(santoscordon): Consider renaming this CallsManagerPlugin.
    interface CallsManagerListener {
        void onCallAdded(Call call);
        void onCallRemoved(Call call);
        void onCallStateChanged(Call call, CallState oldState, CallState newState);
        void onIncomingCallAnswered(Call call);
        void onIncomingCallRejected(Call call);
        void onCallHandoffHandleChanged(Call call, Uri oldHandle, Uri newHandle);
        void onForegroundCallChanged(Call oldForegroundCall, Call newForegroundCall);
        void onAudioStateChanged(CallAudioState oldAudioState, CallAudioState newAudioState);
    }

    private static final CallsManager INSTANCE = new CallsManager();

    private final Switchboard mSwitchboard;

    /**
     * The main call repository. Keeps an instance of all live calls. New incoming and outgoing
     * calls are added to the map and removed when the calls move to the disconnected state.
     */
    private final Set<Call> mCalls = Sets.newLinkedHashSet();

    /**
     * Set of new calls created to perform a handoff. The calls are added when handoff is initiated
     * and removed when hadnoff is complete.
     */
    private final Set<Call> mPendingHandoffCalls = Sets.newLinkedHashSet();

    /**
     * The call the user is currently interacting with. This is the call that should have audio
     * focus and be visible in the in-call UI.
     */
    private Call mForegroundCall;

    private final CallAudioManager mCallAudioManager;

    private final Set<CallsManagerListener> mListeners = Sets.newHashSet();

    private final List<OutgoingCallValidator> mOutgoingCallValidators = Lists.newArrayList();

    private final List<IncomingCallValidator> mIncomingCallValidators = Lists.newArrayList();

    /**
     * Initializes the required Telecomm components.
     */
    private CallsManager() {
        TelecommApp app = TelecommApp.getInstance();

        mSwitchboard = new Switchboard(this);
        mCallAudioManager = new CallAudioManager();

        InCallTonePlayer.Factory playerFactory = new InCallTonePlayer.Factory(mCallAudioManager);
        mListeners.add(new CallLogManager(app));
        mListeners.add(new PhoneStateBroadcaster());
        mListeners.add(new InCallController());
        mListeners.add(new Ringer(mCallAudioManager));
        mListeners.add(new RingbackPlayer(this, playerFactory));
        mListeners.add(new InCallToneMonitor(playerFactory, this));
        mListeners.add(mCallAudioManager);
        mListeners.add(app.getMissedCallNotifier());
    }

    static CallsManager getInstance() {
        return INSTANCE;
    }

    ImmutableCollection<Call> getCalls() {
        return ImmutableList.copyOf(mCalls);
    }

    Call getForegroundCall() {
        return mForegroundCall;
    }

    boolean hasEmergencyCall() {
        for (Call call : mCalls) {
            if (call.isEmergencyCall()) {
                return true;
            }
        }
        return false;
    }

    CallAudioState getAudioState() {
        return mCallAudioManager.getAudioState();
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
        // additional information from the call service, but for now we just need one to pass
        // around.
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
        // Incoming calls are not added unless they are successful. We set the state and disconnect
        // cause just as a matter of good bookkeeping. We do not use the specific methods for
        // setting those values so that we do not trigger CallsManagerListener events.
        // TODO: Needs more specific disconnect error for this case.
        call.setDisconnectCause(DisconnectCause.ERROR_UNSPECIFIED, null);
        call.setState(CallState.DISCONNECTED);
    }

    /**
     * Attempts to issue/connect the specified call.
     *
     * @param handle Handle to connect the call with.
     * @param contactInfo Information about the entity being called.
     * @param gatewayInfo Optional gateway information that can be used to route the call to the
     *         actual dialed handle via a gateway provider. May be null.
     */
    void placeOutgoingCall(Uri handle, ContactInfo contactInfo, GatewayInfo gatewayInfo) {
        for (OutgoingCallValidator validator : mOutgoingCallValidators) {
            if (!validator.isValid(handle, contactInfo)) {
                // TODO(gilad): Display an error message.
                Log.i(this, "Dropping restricted outgoing call.");
                return;
            }
        }

        final Uri uriHandle = (gatewayInfo == null) ? handle : gatewayInfo.getGatewayHandle();

        if (gatewayInfo == null) {
            Log.i(this, "Creating a new outgoing call with handle: %s", Log.pii(uriHandle));
        } else {
            Log.i(this, "Creating a new outgoing call with gateway handle: %s, original handle: %s",
                    Log.pii(uriHandle), Log.pii(handle));
        }
        Call call = new Call(uriHandle, contactInfo, gatewayInfo, false /* isIncoming */);
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
            removeCall(call);
        } else {
            // TODO: Replace disconnect cause with more specific disconnect causes.
            markCallAsDisconnected(call, DisconnectCause.ERROR_UNSPECIFIED, null);
        }
    }

    /**
     * Instructs Telecomm to answer the specified call. Intended to be invoked by the in-call
     * app through {@link InCallAdapter} after Telecomm notifies it of an incoming call followed by
     * the user opting to answer said call.
     */
    void answerCall(Call call) {
        if (!mCalls.contains(call)) {
            Log.i(this, "Request to answer a non-existent call %s", call);
        } else {
            for (CallsManagerListener listener : mListeners) {
                listener.onIncomingCallAnswered(call);
            }

            // We do not update the UI until we get confirmation of the answer() through
            // {@link #markCallAsActive}.
            call.answer();
        }
    }

    /**
     * Instructs Telecomm to reject the specified call. Intended to be invoked by the in-call
     * app through {@link InCallAdapter} after Telecomm notifies it of an incoming call followed by
     * the user opting to reject said call.
     */
    void rejectCall(Call call) {
        if (!mCalls.contains(call)) {
            Log.i(this, "Request to reject a non-existent call %s", call);
        } else {
            for (CallsManagerListener listener : mListeners) {
                listener.onIncomingCallRejected(call);
            }
            call.reject();
        }
    }

    /**
     * Instructs Telecomm to play the specified DTMF tone within the specified call.
     *
     * @param digit The DTMF digit to play.
     */
    void playDtmfTone(Call call, char digit) {
        if (!mCalls.contains(call)) {
            Log.i(this, "Request to play DTMF in a non-existent call %s", call);
        } else {
            call.playDtmfTone(digit);
        }
    }

    /**
     * Instructs Telecomm to stop the currently playing DTMF tone, if any.
     */
    void stopDtmfTone(Call call) {
        if (!mCalls.contains(call)) {
            Log.i(this, "Request to stop DTMF in a non-existent call %s", call);
        } else {
            call.stopDtmfTone();
        }
    }

    /**
     * Instructs Telecomm to continue the current post-dial DTMF string, if any.
     */
    void postDialContinue(Call call) {
        if (!mCalls.contains(call)) {
            Log.i(this, "Request to continue post-dial string in a non-existent call %s", call);
        } else {
            // TODO(ihab): Implement this from this level on downwards
            // call.postDialContinue();
            // Must play tones locally -- see DTMFTonePlayer.java in Telephony
        }
    }

    /**
     * Instructs Telecomm to disconnect the specified call. Intended to be invoked by the
     * in-call app through {@link InCallAdapter} for an ongoing call. This is usually triggered by
     * the user hitting the end-call button.
     */
    void disconnectCall(Call call) {
        if (!mCalls.contains(call)) {
            Log.w(this, "Unknown call (%s) asked to disconnect", call);
        } else {
            call.disconnect();
        }
    }

    /**
     * Instructs Telecomm to put the specified call on hold. Intended to be invoked by the
     * in-call app through {@link InCallAdapter} for an ongoing call. This is usually triggered by
     * the user hitting the hold button during an active call.
     */
    void holdCall(Call call) {
        if (!mCalls.contains(call)) {
            Log.w(this, "Unknown call (%s) asked to be put on hold", call);
        } else {
            Log.d(this, "Putting call on hold: (%s)", call);
            call.hold();
        }
    }

    /**
     * Instructs Telecomm to release the specified call from hold. Intended to be invoked by
     * the in-call app through {@link InCallAdapter} for an ongoing call. This is usually triggered
     * by the user hitting the hold button during a held call.
     */
    void unholdCall(Call call) {
        if (!mCalls.contains(call)) {
            Log.w(this, "Unknown call (%s) asked to be removed from hold", call);
        } else {
            Log.d(this, "Removing call from hold: (%s)", call);
            call.unhold();
        }
    }

    /** Called by the in-call UI to change the mute state. */
    void mute(boolean shouldMute) {
        mCallAudioManager.mute(shouldMute);
    }

    /**
      * Called by the in-call UI to change the audio route, for example to change from earpiece to
      * speaker phone.
      */
    void setAudioRoute(int route) {
        mCallAudioManager.setAudioRoute(route);
    }

    void startHandoffForCall(Call originalCall) {
        if (!mCalls.contains(originalCall)) {
            Log.w(this, "Unknown call %s asked to be handed off", originalCall);
            return;
        }

        for (Call handoffCall : mPendingHandoffCalls) {
            if (handoffCall.getOriginalCall() == originalCall) {
                Log.w(this, "Call %s is already being handed off, skipping", originalCall);
                return;
            }
        }

        // Create a new call to be placed in the background. If handoff is successful then the
        // original call will live on but its state will be updated to the new call's state. In
        // particular the original call's call service will be updated to the new call's call
        // service.
        Call tempCall = new Call(originalCall.getHandoffHandle(), originalCall.getContactInfo(),
                originalCall.getGatewayInfo(), false);
        tempCall.setOriginalCall(originalCall);
        tempCall.setExtras(originalCall.getExtras());
        tempCall.setCallServiceSelector(originalCall.getCallServiceSelector());
        mPendingHandoffCalls.add(tempCall);
        Log.d(this, "Placing handoff call");
        mSwitchboard.placeOutgoingCall(tempCall);
    }

    /** Called when the audio state changes. */
    void onAudioStateChanged(CallAudioState oldAudioState, CallAudioState newAudioState) {
        Log.v(this, "onAudioStateChanged, audioState: %s -> %s", oldAudioState, newAudioState);
        for (CallsManagerListener listener : mListeners) {
            listener.onAudioStateChanged(oldAudioState, newAudioState);
        }
    }

    void markCallAsRinging(Call call) {
        setCallState(call, CallState.RINGING);
    }

    void markCallAsDialing(Call call) {
        setCallState(call, CallState.DIALING);
    }

    void markCallAsActive(Call call) {
        setCallState(call, CallState.ACTIVE);

        if (mPendingHandoffCalls.contains(call)) {
            completeHandoff(call);
        }
    }

    void markCallAsOnHold(Call call) {
        setCallState(call, CallState.ON_HOLD);
    }

    /**
     * Marks the specified call as DISCONNECTED and notifies the in-call app. If this was the last
     * live call, then also disconnect from the in-call controller.
     *
     * @param disconnectCause The disconnect reason, see {@link android.telephony.DisconnectCause}.
     * @param disconnectMessage Optional call-service-provided message about the disconnect.
     */
    void markCallAsDisconnected(Call call, int disconnectCause, String disconnectMessage) {
        call.setDisconnectCause(disconnectCause, disconnectMessage);
        setCallState(call, CallState.DISCONNECTED);
        removeCall(call);
    }

    void setHandoffInfo(Call call, Uri handle, Bundle extras) {
        if (!mCalls.contains(call)) {
            Log.w(this, "Unknown call (%s) asked to set handoff info", call);
            return;
        }

        if (extras == null) {
            call.setExtras(Bundle.EMPTY);
        } else {
            call.setExtras(extras);
        }

        Uri oldHandle = call.getHandoffHandle();
        Log.v(this, "set handoff handle %s -> %s, for call: %s", oldHandle, handle, call);
        if (!areUriEqual(oldHandle, handle)) {
            call.setHandoffHandle(handle);
            for (CallsManagerListener listener : mListeners) {
                listener.onCallHandoffHandleChanged(call, oldHandle, handle);
            }
        }
    }

    /**
     * Cleans up any calls currently associated with the specified call service when the
     * call-service binder disconnects unexpectedly.
     *
     * @param callService The call service that disconnected.
     */
    void handleCallServiceDeath(CallServiceWrapper callService) {
        Preconditions.checkNotNull(callService);
        for (Call call : ImmutableList.copyOf(mCalls)) {
            if (call.getCallService() == callService) {
                markCallAsDisconnected(call, DisconnectCause.ERROR_UNSPECIFIED, null);
            }
        }
    }

    /**
     * Adds the specified call to the main list of live calls.
     *
     * @param call The call to add.
     */
    private void addCall(Call call) {
        mCalls.add(call);
        for (CallsManagerListener listener : mListeners) {
            listener.onCallAdded(call);
        }
        updateForegroundCall();
    }

    private void removeCall(Call call) {
        call.clearCallService();
        call.clearCallServiceSelector();

        boolean shouldNotify = false;
        if (mCalls.contains(call)) {
            mCalls.remove(call);
            shouldNotify = true;
            cleanUpHandoffCallsForOriginalCall(call);
        } else if (mPendingHandoffCalls.contains(call)) {
            Log.v(this, "silently removing handoff call %s", call);
            mPendingHandoffCalls.remove(call);
        }

        // Only broadcast changes for calls that are being tracked.
        if (shouldNotify) {
            for (CallsManagerListener listener : mListeners) {
                listener.onCallRemoved(call);
            }
            updateForegroundCall();
        }
    }

    /**
     * Sets the specified state on the specified call.
     *
     * @param call The call.
     * @param newState The new state of the call.
     */
    private void setCallState(Call call, CallState newState) {
        Preconditions.checkNotNull(newState);
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
            if (mCalls.contains(call)) {
                for (CallsManagerListener listener : mListeners) {
                    listener.onCallStateChanged(call, oldState, newState);
                }
                updateForegroundCall();
            }
        }
    }

    /**
     * Checks which call should be visible to the user and have audio focus.
     */
    private void updateForegroundCall() {
        Call newForegroundCall = null;
        for (Call call : mCalls) {
            // Incoming ringing calls have priority.
            if (call.getState() == CallState.RINGING) {
                newForegroundCall = call;
                break;
            }
            if (call.isAlive()) {
                newForegroundCall = call;
                // Don't break in case there's a ringing call that has priority.
            }
        }

        if (newForegroundCall != mForegroundCall) {
            Call oldForegroundCall = mForegroundCall;
            mForegroundCall = newForegroundCall;

            for (CallsManagerListener listener : mListeners) {
                listener.onForegroundCallChanged(oldForegroundCall, mForegroundCall);
            }
        }
    }

    private void completeHandoff(Call handoffCall) {
        Call originalCall = handoffCall.getOriginalCall();
        Log.v(this, "complete handoff, %s -> %s", handoffCall, originalCall);

        // Disconnect.
        originalCall.disconnect();

        // Synchronize.
        originalCall.setCallService(handoffCall.getCallService(), handoffCall);
        setCallState(originalCall, handoffCall.getState());

        // Remove the transient handoff call object (don't disconnect because the call is still
        // live).
        removeCall(handoffCall);

        // Force the foreground call changed notification to be sent.
        for (CallsManagerListener listener : mListeners) {
            listener.onForegroundCallChanged(mForegroundCall, mForegroundCall);
        }
    }

    /** Makes sure there are no dangling handoff calls. */
    private void cleanUpHandoffCallsForOriginalCall(Call originalCall) {
        for (Call handoffCall : ImmutableList.copyOf((mPendingHandoffCalls))) {
            if (handoffCall.getOriginalCall() == originalCall) {
                Log.d(this, "cancelling handoff call %s for originalCall: %s", handoffCall,
                        originalCall);
                if (handoffCall.getState() == CallState.NEW) {
                    handoffCall.abort();
                    handoffCall.setState(CallState.ABORTED);
                } else {
                    handoffCall.disconnect();
                    handoffCall.setDisconnectCause(DisconnectCause.ERROR_UNSPECIFIED, null);
                    handoffCall.setState(CallState.DISCONNECTED);
                }
                removeCall(handoffCall);
            }
        }
    }

    private static boolean areUriEqual(Uri handle1, Uri handle2) {
        if (handle1 == null) {
            return handle2 == null;
        }
        return handle1.equals(handle2);
    }
}
