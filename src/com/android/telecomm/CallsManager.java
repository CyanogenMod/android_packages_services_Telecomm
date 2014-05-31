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
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Singleton.
 *
 * NOTE(gilad): by design most APIs are package private, use the relevant adapter/s to allow
 * access from other packages specifically refraining from passing the CallsManager instance
 * beyond the com.android.telecomm package boundary.
 */
public final class CallsManager implements Call.Listener {

    // TODO(santoscordon): Consider renaming this CallsManagerPlugin.
    interface CallsManagerListener {
        void onCallAdded(Call call);
        void onCallRemoved(Call call);
        void onCallStateChanged(Call call, CallState oldState, CallState newState);
        void onCallHandoffHandleChanged(Call call, Uri oldHandle, Uri newHandle);
        void onCallServiceChanged(
                Call call,
                CallServiceWrapper oldCallService,
                CallServiceWrapper newCallService);
        void onCallHandoffCallServiceDescriptorChanged(
                Call call,
                CallServiceDescriptor oldDescriptor,
                CallServiceDescriptor newDescriptor);
        void onIncomingCallAnswered(Call call);
        void onIncomingCallRejected(Call call);
        void onForegroundCallChanged(Call oldForegroundCall, Call newForegroundCall);
        void onAudioStateChanged(CallAudioState oldAudioState, CallAudioState newAudioState);
        void onRequestingRingback(Call call, boolean ringback);
    }

    private static final CallsManager INSTANCE = new CallsManager();

    /**
     * The main call repository. Keeps an instance of all live calls. New incoming and outgoing
     * calls are added to the map and removed when the calls move to the disconnected state.
     */
    private final Set<Call> mCalls = new LinkedHashSet<>();

    /**
     * Set of new calls created to perform a handoff. The calls are added when handoff is initiated
     * and removed when hadnoff is complete.
     */
    private final Set<Call> mPendingHandoffCalls = new LinkedHashSet<>();


    private final DtmfLocalTonePlayer mDtmfLocalTonePlayer = new DtmfLocalTonePlayer();
    private final InCallController mInCallController = new InCallController();
    private final CallAudioManager mCallAudioManager;
    private final Ringer mRinger;
    private final Set<CallsManagerListener> mListeners = new HashSet<>();

    /**
     * The call the user is currently interacting with. This is the call that should have audio
     * focus and be visible in the in-call UI.
     */
    private Call mForegroundCall;

    /** Singleton accessor. */
    static CallsManager getInstance() {
        return INSTANCE;
    }

    /**
     * Initializes the required Telecomm components.
     */
    private CallsManager() {
        TelecommApp app = TelecommApp.getInstance();

        mCallAudioManager = new CallAudioManager();
        InCallTonePlayer.Factory playerFactory = new InCallTonePlayer.Factory(mCallAudioManager);
        mRinger = new Ringer(mCallAudioManager, this, playerFactory, app);

        mListeners.add(new CallLogManager(app));
        mListeners.add(new PhoneStateBroadcaster());
        mListeners.add(mInCallController);
        mListeners.add(mRinger);
        mListeners.add(new RingbackPlayer(this, playerFactory));
        mListeners.add(new InCallToneMonitor(playerFactory, this));
        mListeners.add(mCallAudioManager);
        mListeners.add(app.getMissedCallNotifier());
        mListeners.add(mDtmfLocalTonePlayer);
    }

    @Override
    public void onSuccessfulOutgoingCall(Call call) {
        Log.v(this, "onSuccessfulOutgoingCall, %s", call);
        if (mCalls.contains(call)) {
            // The call's CallService has been updated.
            for (CallsManagerListener listener : mListeners) {
                listener.onCallServiceChanged(call, null, call.getCallService());
            }
        } else if (mPendingHandoffCalls.contains(call)) {
            updateHandoffCallServiceDescriptor(call.getOriginalCall(),
                    call.getCallService().getDescriptor());
        } else {
            Log.wtf(this, "unexpected successful call notification: %s", call);
            return;
        }

        markCallAsDialing(call);
    }

    @Override
    public void onFailedOutgoingCall(Call call, boolean isAborted) {
        Log.v(this, "onFailedOutgoingCall, call: %s, isAborted: %b", call, isAborted);
        if (isAborted) {
            setCallState(call, CallState.ABORTED);
            removeCall(call);
        } else {
            // TODO: Replace disconnect cause with more specific disconnect causes.
            markCallAsDisconnected(call, DisconnectCause.ERROR_UNSPECIFIED, null);
        }
    }

    @Override
    public void onSuccessfulIncomingCall(Call call, CallInfo callInfo) {
        Log.d(this, "onSuccessfulIncomingCall");
        setCallState(call, callInfo.getState());
        addCall(call);
    }

    @Override
    public void onFailedIncomingCall(Call call) {
        call.removeListener(this);
    }

    @Override
    public void onRequestingRingback(Call call, boolean ringback) {
        for (CallsManagerListener listener : mListeners) {
            listener.onRequestingRingback(call, ringback);
        }
    }

    ImmutableCollection<Call> getCalls() {
        return ImmutableList.copyOf(mCalls);
    }

    Call getForegroundCall() {
        return mForegroundCall;
    }

    Ringer getRinger() {
        return mRinger;
    }

    InCallController getInCallController() {
        return mInCallController;
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
     * to {@link #onSuccessfulIncomingCall} to start the in-call UI.
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
        // TODO(santoscordon): Move this to be a part of addCall()
        call.addListener(this);

        call.startIncoming(descriptor, extras);
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
        final Uri uriHandle = (gatewayInfo == null) ? handle : gatewayInfo.getGatewayHandle();

        if (gatewayInfo == null) {
            Log.i(this, "Creating a new outgoing call with handle: %s", Log.piiHandle(uriHandle));
        } else {
            Log.i(this, "Creating a new outgoing call with gateway handle: %s, original handle: %s",
                    Log.pii(uriHandle), Log.pii(handle));
        }

        Call call = new Call(uriHandle, gatewayInfo, false /* isIncoming */);

        // TODO(santoscordon): Move this to be a part of addCall()
        call.addListener(this);
        addCall(call);

        call.startOutgoing();
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
            // If the foreground call is not the ringing call and it is currently isActive() or
            // DIALING, put it on hold before answering the call.
            if (mForegroundCall != null && mForegroundCall != call &&
                    (mForegroundCall.isActive() ||
                     mForegroundCall.getState() == CallState.DIALING)) {
                Log.v(this, "Holding active/dialing call %s before answering incoming call %s.",
                        mForegroundCall, call);
                mForegroundCall.hold();
                // TODO(santoscordon): Wait until we get confirmation of the active call being
                // on-hold before answering the new call.
                // TODO(santoscordon): Import logic from CallManager.acceptCall()
            }

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
            mDtmfLocalTonePlayer.playTone(call, digit);
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
            mDtmfLocalTonePlayer.stopTone(call);
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
        Log.v(this, "disconnectCall %s", call);

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
            Log.d(this, "unholding call: (%s)", call);
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
        Call tempCall =
                new Call(originalCall.getHandoffHandle(), originalCall.getGatewayInfo(), false);
        tempCall.setOriginalCall(originalCall);
        tempCall.setExtras(originalCall.getExtras());
        tempCall.setCallServiceSelector(originalCall.getCallServiceSelector());
        mPendingHandoffCalls.add(tempCall);
        tempCall.addListener(this);

        Log.d(this, "Placing handoff call");
        tempCall.startOutgoing();
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
        if (call.getConnectTimeMillis() == 0) {
            call.setConnectTimeMillis(System.currentTimeMillis());
        }
        setCallState(call, CallState.ACTIVE);

        if (mPendingHandoffCalls.contains(call)) {
            completeHandoff(call, true);
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

        // Only remove the call if handoff is not pending.
        if (call.getHandoffCallServiceDescriptor() == null) {
            removeCall(call);
        }
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

    boolean hasActiveOrHoldingCall() {
        for (Call call : mCalls) {
            CallState state = call.getState();
            if (state == CallState.ACTIVE || state == CallState.ON_HOLD) {
                return true;
            }
        }
        return false;
    }

    boolean hasRingingCall() {
        for (Call call : mCalls) {
            if (call.getState() == CallState.RINGING) {
                return true;
            }
        }
        return false;
    }

    /**
     * Adds the specified call to the main list of live calls.
     *
     * @param call The call to add.
     */
    private void addCall(Call call) {
        mCalls.add(call);

        // TODO(santoscordon): Update mForegroundCall prior to invoking
        // onCallAdded for calls which immediately take the foreground (like the first call).
        for (CallsManagerListener listener : mListeners) {
            listener.onCallAdded(call);
        }
        updateForegroundCall();
    }

    private void removeCall(Call call) {
        // If a handoff is pending then the original call shouldn't be removed.
        Preconditions.checkState(call.getHandoffCallServiceDescriptor() == null);
        Log.v(this, "removeCall(%s)", call);

        call.removeListener(this);
        call.clearCallService();
        call.clearCallServiceSelector();

        boolean shouldNotify = false;
        if (mCalls.contains(call)) {
            mCalls.remove(call);
            shouldNotify = true;
        } else if (mPendingHandoffCalls.contains(call)) {
            Log.v(this, "removeCall, marking handoff call as failed");
            completeHandoff(call, false);
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
        Log.i(this, "setCallState %s -> %s, call: %s", oldState, newState, call);
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
            // TODO(santoscordon): Foreground-ness needs to be explicitly set. No call, regardless
            // of its state will be foreground by default and instead the call service should be
            // notified when its calls enter and exit foreground state. Foreground will mean that
            // the call should play audio and listen to microphone if it wants.

            // Active calls have priority.
            if (call.isActive()) {
                newForegroundCall = call;
                break;
            }

            if (call.isAlive() || call.getState() == CallState.RINGING) {
                newForegroundCall = call;
                // Don't break in case there's an active call that has priority.
            }
        }

        if (newForegroundCall != mForegroundCall) {
            Log.v(this, "Updating foreground call, %s -> %s.", mForegroundCall, newForegroundCall);
            Call oldForegroundCall = mForegroundCall;
            mForegroundCall = newForegroundCall;

            for (CallsManagerListener listener : mListeners) {
                listener.onForegroundCallChanged(oldForegroundCall, mForegroundCall);
            }
        }
    }

    private void completeHandoff(Call handoffCall, boolean wasSuccessful) {
        Call originalCall = handoffCall.getOriginalCall();
        Log.v(this, "complete handoff, %s -> %s, wasSuccessful: %b", handoffCall, originalCall,
                wasSuccessful);

        // Remove the transient handoff call object (don't disconnect because the call could still
        // be live).
        mPendingHandoffCalls.remove(handoffCall);
        handoffCall.removeListener(this);

        if (wasSuccessful) {
            if (TelephonyUtil.isCurrentlyPSTNCall(originalCall)) {
                originalCall.disconnect();
            }

            // Synchronize.
            originalCall.setCallService(handoffCall.getCallService(), handoffCall);
            setCallState(originalCall, handoffCall.getState());

            // Force the foreground call changed notification to be sent.
            for (CallsManagerListener listener : mListeners) {
                listener.onForegroundCallChanged(mForegroundCall, mForegroundCall);
            }

            updateHandoffCallServiceDescriptor(originalCall, null);
        } else {
            updateHandoffCallServiceDescriptor(originalCall, null);
            if (originalCall.getState() == CallState.DISCONNECTED ||
                    originalCall.getState() == CallState.ABORTED) {
                removeCall(originalCall);
            }
        }
    }

    private void updateHandoffCallServiceDescriptor(
            Call originalCall,
            CallServiceDescriptor newDescriptor) {
        CallServiceDescriptor oldDescriptor = originalCall.getHandoffCallServiceDescriptor();
        Log.v(this, "updateHandoffCallServiceDescriptor, call: %s, pending descriptor: %s -> %s",
                originalCall, oldDescriptor, newDescriptor);

        if (!areDescriptorsEqual(oldDescriptor, newDescriptor)) {
            originalCall.setHandoffCallServiceDescriptor(newDescriptor);
            for (CallsManagerListener listener : mListeners) {
                listener.onCallHandoffCallServiceDescriptorChanged(originalCall, oldDescriptor,
                        newDescriptor);
            }
        }
    }

    private static boolean areDescriptorsEqual(
            CallServiceDescriptor descriptor1,
            CallServiceDescriptor descriptor2) {
        if (descriptor1 == null) {
            return descriptor2 == null;
        }
        return descriptor1.equals(descriptor2);
    }

    private static boolean areUriEqual(Uri handle1, Uri handle2) {
        if (handle1 == null) {
            return handle2 == null;
        }
        return handle1.equals(handle2);
    }
}
