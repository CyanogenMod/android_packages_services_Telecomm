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
import android.telecomm.AudioState;
import android.telecomm.CallState;
import android.telecomm.GatewayInfo;
import android.telecomm.ParcelableConference;
import android.telecomm.PhoneAccountHandle;
import android.telephony.DisconnectCause;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Singleton.
 *
 * NOTE: by design most APIs are package private, use the relevant adapter/s to allow
 * access from other packages specifically refraining from passing the CallsManager instance
 * beyond the com.android.telecomm package boundary.
 */
public final class CallsManager extends Call.ListenerBase {

    // TODO: Consider renaming this CallsManagerPlugin.
    interface CallsManagerListener {
        void onCallAdded(Call call);
        void onCallRemoved(Call call);
        void onCallStateChanged(Call call, int oldState, int newState);
        void onConnectionServiceChanged(
                Call call,
                ConnectionServiceWrapper oldService,
                ConnectionServiceWrapper newService);
        void onIncomingCallAnswered(Call call);
        void onIncomingCallRejected(Call call, boolean rejectWithMessage, String textMessage);
        void onForegroundCallChanged(Call oldForegroundCall, Call newForegroundCall);
        void onAudioStateChanged(AudioState oldAudioState, AudioState newAudioState);
        void onRequestingRingback(Call call, boolean ringback);
        void onIsConferencedChanged(Call call);
        void onAudioModeIsVoipChanged(Call call);
        void onVideoStateChanged(Call call);
    }

    private static final CallsManager INSTANCE = new CallsManager();

    /**
     * The main call repository. Keeps an instance of all live calls. New incoming and outgoing
     * calls are added to the map and removed when the calls move to the disconnected state.
    *
     * ConcurrentHashMap constructor params: 8 is initial table size, 0.9f is
     * load factor before resizing, 1 means we only expect a single thread to
     * access the map so make only a single shard
     */
    private final Set<Call> mCalls = Collections.newSetFromMap(
            new ConcurrentHashMap<Call, Boolean>(8, 0.9f, 1));

    private final ConnectionServiceRepository mConnectionServiceRepository =
            new ConnectionServiceRepository();
    private final DtmfLocalTonePlayer mDtmfLocalTonePlayer = new DtmfLocalTonePlayer();
    private final InCallController mInCallController = new InCallController();
    private final CallAudioManager mCallAudioManager;
    private final Ringer mRinger;
    // For this set initial table size to 16 because we add 13 listeners in
    // the CallsManager constructor.
    private final Set<CallsManagerListener> mListeners = Collections.newSetFromMap(
            new ConcurrentHashMap<CallsManagerListener, Boolean>(16, 0.9f, 1));
    private final HeadsetMediaButton mHeadsetMediaButton;
    private final WiredHeadsetManager mWiredHeadsetManager;
    private final TtyManager mTtyManager;
    private final ProximitySensorManager mProximitySensorManager;

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

        StatusBarNotifier statusBarNotifier = new StatusBarNotifier(app, this);
        mWiredHeadsetManager = new WiredHeadsetManager(app);
        mCallAudioManager = new CallAudioManager(app, statusBarNotifier, mWiredHeadsetManager);
        InCallTonePlayer.Factory playerFactory = new InCallTonePlayer.Factory(mCallAudioManager);
        mRinger = new Ringer(mCallAudioManager, this, playerFactory, app);
        mHeadsetMediaButton = new HeadsetMediaButton(app, this);
        mTtyManager = new TtyManager(app, mWiredHeadsetManager);
        mProximitySensorManager = new ProximitySensorManager(app);

        mListeners.add(statusBarNotifier);
        mListeners.add(new CallLogManager(app));
        mListeners.add(new PhoneStateBroadcaster());
        mListeners.add(mInCallController);
        mListeners.add(mRinger);
        mListeners.add(new RingbackPlayer(this, playerFactory));
        mListeners.add(new InCallToneMonitor(playerFactory, this));
        mListeners.add(mCallAudioManager);
        mListeners.add(app.getMissedCallNotifier());
        mListeners.add(mDtmfLocalTonePlayer);
        mListeners.add(mHeadsetMediaButton);
        mListeners.add(RespondViaSmsManager.getInstance());
        mListeners.add(mProximitySensorManager);
    }

    @Override
    public void onSuccessfulOutgoingCall(Call call, int callState) {
        Log.v(this, "onSuccessfulOutgoingCall, %s", call);

        setCallState(call, callState);
        if (!mCalls.contains(call)) {
            // Call was not added previously in startOutgoingCall due to it being a potential MMI
            // code, so add it now.
            addCall(call);
        }

        // The call's ConnectionService has been updated.
        for (CallsManagerListener listener : mListeners) {
            listener.onConnectionServiceChanged(call, null, call.getConnectionService());
        }

        markCallAsDialing(call);
    }

    @Override
    public void onFailedOutgoingCall(Call call, int errorCode, String errorMsg) {
        Log.v(this, "onFailedOutgoingCall, call: %s", call);

        // TODO: Replace disconnect cause with more specific disconnect causes.
        markCallAsDisconnected(call, errorCode, errorMsg);
    }

    @Override
    public void onSuccessfulIncomingCall(Call call) {
        Log.d(this, "onSuccessfulIncomingCall");
        setCallState(call, CallState.RINGING);
        addCall(call);
    }

    @Override
    public void onFailedIncomingCall(Call call) {
        setCallState(call, CallState.DISCONNECTED);
        call.removeListener(this);
    }

    @Override
    public void onRequestingRingback(Call call, boolean ringback) {
        for (CallsManagerListener listener : mListeners) {
            listener.onRequestingRingback(call, ringback);
        }
    }

    @Override
    public void onPostDialWait(Call call, String remaining) {
        mInCallController.onPostDialWait(call, remaining);
    }

    @Override
    public void onParentChanged(Call call) {
        for (CallsManagerListener listener : mListeners) {
            listener.onIsConferencedChanged(call);
        }
    }

    @Override
    public void onChildrenChanged(Call call) {
        for (CallsManagerListener listener : mListeners) {
            listener.onIsConferencedChanged(call);
        }
    }

    @Override
    public void onAudioModeIsVoipChanged(Call call) {
        for (CallsManagerListener listener : mListeners) {
            listener.onAudioModeIsVoipChanged(call);
        }
    }

    @Override
    public void onVideoStateChanged(Call call) {
        for (CallsManagerListener listener : mListeners) {
            listener.onVideoStateChanged(call);
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

    AudioState getAudioState() {
        return mCallAudioManager.getAudioState();
    }

    boolean isTtySupported() {
        return mTtyManager.isTtySupported();
    }

    int getCurrentTtyMode() {
        return mTtyManager.getCurrentTtyMode();
    }

    /**
     * Starts the process to attach the call to a connection service.
     *
     * @param phoneAccountHandle The phone account which contains the component name of the
     *        connection service to use for this call.
     * @param extras The optional extras Bundle passed with the intent used for the incoming call.
     */
    void processIncomingCallIntent(PhoneAccountHandle phoneAccountHandle, Bundle extras) {
        Log.d(this, "processIncomingCallIntent");
        // Create a call with no handle. The handle is eventually set when the call is attached
        // to a connection service.
        Call call = new Call(
                mConnectionServiceRepository,
                null /* handle */,
                null /* gatewayInfo */,
                null /* connectionManagerPhoneAccount */,
                phoneAccountHandle,
                true /* isIncoming */,
                false /* isConference */);

        call.setExtras(extras);
        // TODO: Move this to be a part of addCall()
        call.addListener(this);
        call.startCreateConnection();
    }

    /**
     * Kicks off the first steps to creating an outgoing call so that InCallUI can launch.
     *
     * @param handle Handle to connect the call with.
     * @param phoneAccountHandle The phone account which contains the component name of the
     *        connection service to use for this call.
     * @param extras The optional extras Bundle passed with the intent used for the incoming call.
     */
    Call startOutgoingCall(Uri handle, PhoneAccountHandle phoneAccountHandle, Bundle extras) {
        // We only allow a single outgoing call at any given time. Before placing a call, make sure
        // there doesn't already exist another outgoing call.
        Call call = getFirstCallWithState(CallState.NEW, CallState.DIALING,
                CallState.CONNECTING, CallState.PRE_DIAL_WAIT);

        if (call != null) {
            Log.i(this, "Canceling simultaneous outgoing call.");
            return null;
        }

        TelecommApp app = TelecommApp.getInstance();

        // Only dial with the requested phoneAccount if it is still valid. Otherwise treat this call
        // as if a phoneAccount was not specified (does the default behavior instead).
        // Note: We will not attempt to dial with a requested phoneAccount if it is disabled.
        if (phoneAccountHandle != null) {
            List<PhoneAccountHandle> enabledAccounts =
                    app.getPhoneAccountRegistrar().getEnabledPhoneAccounts(handle.getScheme());
            if (!enabledAccounts.contains(phoneAccountHandle)) {
                phoneAccountHandle = null;
            }
        }

        if (phoneAccountHandle == null) {
            // No preset account, check if default exists that supports the URI scheme for the
            // handle.
            PhoneAccountHandle defaultAccountHandle =
                    app.getPhoneAccountRegistrar().getDefaultOutgoingPhoneAccount(
                            handle.getScheme());
            if (defaultAccountHandle != null) {
                phoneAccountHandle = defaultAccountHandle;
            }
        }

        // Create a call with original handle. The handle may be changed when the call is attached
        // to a connection service, but in most cases will remain the same.
        call = new Call(
                mConnectionServiceRepository,
                handle,
                null /* gatewayInfo */,
                null /* connectionManagerPhoneAccount */,
                phoneAccountHandle,
                false /* isIncoming */,
                false /* isConference */);
        call.setExtras(extras);

        final boolean emergencyCall = TelephonyUtil.shouldProcessAsEmergency(app, call.getHandle());
        if (phoneAccountHandle == null && !emergencyCall) {
            // This is the state where the user is expected to select an account
            call.setState(CallState.PRE_DIAL_WAIT);
        } else {
            call.setState(CallState.CONNECTING);
        }

        if (!isPotentialMMICode(handle)) {
            addCall(call);
        } else {
            call.addListener(this);
        }

        return call;
    }

    /**
     * Attempts to issue/connect the specified call.
     *
     * @param handle Handle to connect the call with.
     * @param gatewayInfo Optional gateway information that can be used to route the call to the
     *        actual dialed handle via a gateway provider. May be null.
     * @param speakerphoneOn Whether or not to turn the speakerphone on once the call connects.
     * @param videoState The desired video state for the outgoing call.
     */
    void placeOutgoingCall(Call call, Uri handle, GatewayInfo gatewayInfo, boolean speakerphoneOn,
            int videoState) {
        if (call == null) {
            // don't do anything if the call no longer exists
            Log.i(this, "Canceling unknown call.");
            return;
        }

        final Uri uriHandle = (gatewayInfo == null) ? handle : gatewayInfo.getGatewayAddress();

        if (gatewayInfo == null) {
            Log.i(this, "Creating a new outgoing call with handle: %s", Log.piiHandle(uriHandle));
        } else {
            Log.i(this, "Creating a new outgoing call with gateway handle: %s, original handle: %s",
                    Log.pii(uriHandle), Log.pii(handle));
        }

        call.setHandle(uriHandle);
        call.setGatewayInfo(gatewayInfo);
        call.setStartWithSpeakerphoneOn(speakerphoneOn);
        call.setVideoState(videoState);

        TelecommApp app = TelecommApp.getInstance();
        final boolean emergencyCall = TelephonyUtil.shouldProcessAsEmergency(app, call.getHandle());
        if (emergencyCall) {
            // Emergency -- CreateConnectionProcessor will choose accounts automatically
            call.setTargetPhoneAccount(null);
        }

        if (call.getTargetPhoneAccount() != null || emergencyCall) {
            // If the account has been set, proceed to place the outgoing call.
            // Otherwise the connection will be initiated when the account is set by the user.
            call.startCreateConnection();
        }
    }

    /**
     * Attempts to start a conference call for the specified call.
     *
     * @param call The call to conference.
     * @param otherCall The other call to conference with.
     */
    void conference(Call call, Call otherCall) {
        call.conferenceWith(otherCall);
    }

    /**
     * Instructs Telecomm to answer the specified call. Intended to be invoked by the in-call
     * app through {@link InCallAdapter} after Telecomm notifies it of an incoming call followed by
     * the user opting to answer said call.
     *
     * @param call The call to answer.
     * @param videoState The video state in which to answer the call.
     */
    void answerCall(Call call, int videoState) {
        if (!mCalls.contains(call)) {
            Log.i(this, "Request to answer a non-existent call %s", call);
        } else {
            // If the foreground call is not the ringing call and it is currently isActive() or
            // STATE_DIALING, put it on hold before answering the call.
            if (mForegroundCall != null && mForegroundCall != call &&
                    (mForegroundCall.isActive() ||
                     mForegroundCall.getState() == CallState.DIALING)) {
                Log.v(this, "Holding active/dialing call %s before answering incoming call %s.",
                        mForegroundCall, call);
                mForegroundCall.hold();
                // TODO: Wait until we get confirmation of the active call being
                // on-hold before answering the new call.
                // TODO: Import logic from CallManager.acceptCall()
            }

            for (CallsManagerListener listener : mListeners) {
                listener.onIncomingCallAnswered(call);
            }

            // We do not update the UI until we get confirmation of the answer() through
            // {@link #markCallAsActive}.
            call.answer(videoState);
        }
    }

    /**
     * Instructs Telecomm to reject the specified call. Intended to be invoked by the in-call
     * app through {@link InCallAdapter} after Telecomm notifies it of an incoming call followed by
     * the user opting to reject said call.
     */
    void rejectCall(Call call, boolean rejectWithMessage, String textMessage) {
        if (!mCalls.contains(call)) {
            Log.i(this, "Request to reject a non-existent call %s", call);
        } else {
            for (CallsManagerListener listener : mListeners) {
                listener.onIncomingCallRejected(call, rejectWithMessage, textMessage);
            }
            call.reject(rejectWithMessage, textMessage);
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
     * Instructs Telecomm to continue (or not) the current post-dial DTMF string, if any.
     */
    void postDialContinue(Call call, boolean proceed) {
        if (!mCalls.contains(call)) {
            Log.i(this, "Request to continue post-dial string in a non-existent call %s", call);
        } else {
            call.postDialContinue(proceed);
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
     * Instructs Telecomm to disconnect all calls.
     */
    void disconnectAllCalls() {
        Log.v(this, "disconnectAllCalls");

        for (Call call : mCalls) {
            disconnectCall(call);
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
            for (Call c : mCalls) {
                if (c != null && c.isAlive() && c != call) {
                    c.hold();
                }
            }
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

    /** Called by the in-call UI to turn the proximity sensor on. */
    void turnOnProximitySensor() {
        mProximitySensorManager.turnOn();
    }

    /**
     * Called by the in-call UI to turn the proximity sensor off.
     * @param screenOnImmediately If true, the screen will be turned on immediately. Otherwise,
     *        the screen will be kept off until the proximity sensor goes negative.
     */
    void turnOffProximitySensor(boolean screenOnImmediately) {
        mProximitySensorManager.turnOff(screenOnImmediately);
    }

    void phoneAccountSelected(Call call, PhoneAccountHandle account) {
        if (!mCalls.contains(call)) {
            Log.i(this, "Attemped to add account to unknown call %s", call);
        } else {
            call.setTargetPhoneAccount(account);
            call.startCreateConnection();
        }
    }

    /** Called when the audio state changes. */
    void onAudioStateChanged(AudioState oldAudioState, AudioState newAudioState) {
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

        if (call.getStartWithSpeakerphoneOn()) {
            setAudioRoute(AudioState.ROUTE_SPEAKER);
        }
    }

    void markCallAsOnHold(Call call) {
        setCallState(call, CallState.ON_HOLD);
    }

    /**
     * Marks the specified call as STATE_DISCONNECTED and notifies the in-call app. If this was the last
     * live call, then also disconnect from the in-call controller.
     *
     * @param disconnectCause The disconnect reason, see {@link android.telephony.DisconnectCause}.
     * @param disconnectMessage Optional message about the disconnect.
     */
    void markCallAsDisconnected(Call call, int disconnectCause, String disconnectMessage) {
        call.setDisconnectCause(disconnectCause, disconnectMessage);
        setCallState(call, CallState.DISCONNECTED);
        removeCall(call);
    }

    /**
     * Removes an existing disconnected call, and notifies the in-call app.
     */
    void markCallAsRemoved(Call call) {
        removeCall(call);
    }

    /**
     * Cleans up any calls currently associated with the specified connection service when the
     * service binder disconnects unexpectedly.
     *
     * @param service The connection service that disconnected.
     */
    void handleConnectionServiceDeath(ConnectionServiceWrapper service) {
        if (service != null) {
            for (Call call : mCalls) {
                if (call.getConnectionService() == service) {
                    markCallAsDisconnected(call, DisconnectCause.ERROR_UNSPECIFIED, null);
                }
            }
        }
    }

    boolean hasAnyCalls() {
        return !mCalls.isEmpty();
    }

    boolean hasActiveOrHoldingCall() {
        return getFirstCallWithState(CallState.ACTIVE, CallState.ON_HOLD) != null;
    }

    boolean hasRingingCall() {
        return getFirstCallWithState(CallState.RINGING) != null;
    }

    boolean onMediaButton(int type) {
        if (hasAnyCalls()) {
            if (HeadsetMediaButton.SHORT_PRESS == type) {
                Call ringingCall = getFirstCallWithState(CallState.RINGING);
                if (ringingCall == null) {
                    mCallAudioManager.toggleMute();
                    return true;
                } else {
                    ringingCall.answer(ringingCall.getVideoState());
                    return true;
                }
            } else if (HeadsetMediaButton.LONG_PRESS == type) {
                Log.d(this, "handleHeadsetHook: longpress -> hangup");
                Call callToHangup = getFirstCallWithState(
                        CallState.RINGING, CallState.DIALING, CallState.ACTIVE, CallState.ON_HOLD);
                if (callToHangup != null) {
                    callToHangup.disconnect();
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Checks to see if the specified call is the only high-level call and if so, enable the
     * "Add-call" button. We allow you to add a second call but not a third or beyond.
     *
     * @param call The call to test for add-call.
     * @return Whether the add-call feature should be enabled for the call.
     */
    protected boolean isAddCallCapable(Call call) {
        if (call.getParentCall() != null) {
            // Never true for child calls.
            return false;
        }

        // Loop through all the other calls and there exists a top level (has no parent) call
        // that is not the specified call, return false.
        for (Call otherCall : mCalls) {
            if (call != otherCall && otherCall.getParentCall() == null) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the first call that it finds with the given states. The states are treated as having
     * priority order so that any call with the first state will be returned before any call with
     * states listed later in the parameter list.
     */
    Call getFirstCallWithState(int... states) {
        for (int currentState : states) {
            // check the foreground first
            if (mForegroundCall != null && mForegroundCall.getState() == currentState) {
                return mForegroundCall;
            }

            for (Call call : mCalls) {
                if (currentState == call.getState()) {
                    return call;
                }
            }
        }
        return null;
    }

    Call createConferenceCall(
            PhoneAccountHandle phoneAccount,
            ParcelableConference parcelableConference) {
        Call call = new Call(
                mConnectionServiceRepository,
                null /* handle */,
                null /* gatewayInfo */,
                null /* connectionManagerPhoneAccount */,
                phoneAccount,
                false /* isIncoming */,
                true /* isConference */);

        setCallState(call, Call.getStateFromConnectionState(parcelableConference.getState()));
        call.setCallCapabilities(parcelableConference.getCapabilities());

        // TODO: Move this to be a part of addCall()
        call.addListener(this);
        addCall(call);
        return call;
    }


    /**
     * Adds the specified call to the main list of live calls.
     *
     * @param call The call to add.
     */
    private void addCall(Call call) {
        Log.v(this, "addCall(%s)", call);

        call.addListener(this);
        mCalls.add(call);

        // TODO: Update mForegroundCall prior to invoking
        // onCallAdded for calls which immediately take the foreground (like the first call).
        for (CallsManagerListener listener : mListeners) {
            listener.onCallAdded(call);
        }
        updateForegroundCall();
    }

    private void removeCall(Call call) {
        Log.v(this, "removeCall(%s)", call);

        call.setParentCall(null);  // need to clean up parent relationship before destroying.
        call.removeListener(this);
        call.clearConnectionService();

        boolean shouldNotify = false;
        if (mCalls.contains(call)) {
            mCalls.remove(call);
            shouldNotify = true;
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
    private void setCallState(Call call, int newState) {
        if (call == null) {
            return;
        }
        int oldState = call.getState();
        Log.i(this, "setCallState %s -> %s, call: %s", CallState.toString(oldState),
                CallState.toString(newState), call);
        if (newState != oldState) {
            // Unfortunately, in the telephony world the radio is king. So if the call notifies
            // us that the call is in a particular state, we allow it even if it doesn't make
            // sense (e.g., STATE_ACTIVE -> STATE_RINGING).
            // TODO: Consider putting a stop to the above and turning CallState
            // into a well-defined state machine.
            // TODO: Define expected state transitions here, and log when an
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
            // TODO: Foreground-ness needs to be explicitly set. No call, regardless
            // of its state will be foreground by default and instead the connection service should
            // be notified when its calls enter and exit foreground state. Foreground will mean that
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

    private boolean isPotentialMMICode(Uri handle) {
        return (handle != null && handle.getSchemeSpecificPart() != null
                && handle.getSchemeSpecificPart().contains("#"));
    }
}
