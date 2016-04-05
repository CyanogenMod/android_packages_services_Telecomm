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

package com.android.server.telecom;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.SystemProperties;
import android.os.Trace;
import android.provider.CallLog.Calls;
import android.provider.Settings;
import android.telecom.AudioState;
import android.telecom.CallState;
import android.telecom.Conference;
import android.telecom.Connection;
import android.telecom.DisconnectCause;
import android.telecom.GatewayInfo;
import android.telecom.ParcelableConference;
import android.telecom.ParcelableConnection;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telecom.VideoProfile;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;

import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.telephony.util.BlacklistUtils;
import com.android.internal.util.IndentingPrintWriter;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Singleton.
 *
 * NOTE: by design most APIs are package private, use the relevant adapter/s to allow
 * access from other packages specifically refraining from passing the CallsManager instance
 * beyond the com.android.server.telecom package boundary.
 */
public final class CallsManager extends Call.ListenerBase {

    // TODO: Consider renaming this CallsManagerPlugin.
    interface CallsManagerListener {
        void onCallAdded(Call call);
        void onCallRemoved(Call call);
        void onCallStateChanged(Call call, int oldState, int newState);
        void onCallExtrasUpdated(Call call);
        void onConnectionServiceChanged(
                Call call,
                ConnectionServiceWrapper oldService,
                ConnectionServiceWrapper newService);
        void onIncomingCallAnswered(Call call);
        void onIncomingCallRejected(Call call, boolean rejectWithMessage, String textMessage);
        void onForegroundCallChanged(Call oldForegroundCall, Call newForegroundCall);
        void onAudioStateChanged(AudioState oldAudioState, AudioState newAudioState);
        void onRingbackRequested(Call call, boolean ringback);
        void onIsConferencedChanged(Call call);
        void onIsVoipAudioModeChanged(Call call);
        void onVideoStateChanged(Call call);
        void onCallSubstateChanged(Call call);
        void onCanAddCallChanged(boolean canAddCall);
        void onProcessIncomingCall(Call call);
    }

    /**
     * Singleton instance of the {@link CallsManager}, initialized from {@link TelecomService}.
     */
    private static CallsManager sInstance = null;

    private static final String TAG = "CallsManager";

    private static final int MAXIMUM_LIVE_CALLS = 1;
    private static final int MAXIMUM_HOLD_CALLS = 1;
    private static final int MAXIMUM_RINGING_CALLS = 1;
    private static final int MAXIMUM_OUTGOING_CALLS = 1;
    private static final int MAXIMUM_DSDA_LIVE_CALLS = 2;
    private static final int MAXIMUM_DSDA_HOLD_CALLS = 2;
    private static final int MAXIMUM_TOP_LEVEL_CALLS = 2;

    private static final int[] OUTGOING_CALL_STATES =
            {CallState.CONNECTING, CallState.DIALING};

    private static final int[] LIVE_CALL_STATES =
            {CallState.CONNECTING, CallState.PRE_DIAL_WAIT, CallState.DIALING, CallState.ACTIVE};

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

    private final ConnectionServiceRepository mConnectionServiceRepository;
    private final DtmfLocalTonePlayer mDtmfLocalTonePlayer;
    private final InCallController mInCallController;
    private final CallAudioManager mCallAudioManager;
    private final Ringer mRinger;
    private final InCallWakeLockController mInCallWakeLockController;
    // For this set initial table size to 16 because we add 13 listeners in
    // the CallsManager constructor.
    private final Set<CallsManagerListener> mListeners = Collections.newSetFromMap(
            new ConcurrentHashMap<CallsManagerListener, Boolean>(16, 0.9f, 1));
    private final HeadsetMediaButton mHeadsetMediaButton;
    private final WiredHeadsetManager mWiredHeadsetManager;
    private final TtyManager mTtyManager;
    private final ProximitySensorManager mProximitySensorManager;
    private final PhoneStateBroadcaster mPhoneStateBroadcaster;
    private final CallLogManager mCallLogManager;
    private final Context mContext;
    private final PhoneAccountRegistrar mPhoneAccountRegistrar;
    private final MissedCallNotifier mMissedCallNotifier;
    private final BlacklistCallNotifier mBlacklistCallNotifier;
    private final CallInfoProvider mCallInfoProvider;
    private final Set<Call> mLocallyDisconnectingCalls = new HashSet<>();
    private final Set<Call> mPendingCallsToDisconnect = new HashSet<>();

    private boolean mCanAddCall = true;

    /**
     * The call the user is currently interacting with. This is the call that should have audio
     * focus and be visible in the in-call UI.
     */
    private Call mForegroundCall;
    private InCallTonePlayer.Factory mPlayerFactory;

    private static final int LCH_PLAY_DTMF = 56;
    private static final int LCH_STOP_DTMF = 57;
    private static final int LCH_DTMF_PERIODICITY = 3000;
    private static final int LCH_DTMF_PERIOD = 500;
    private static final int PHONE_START_MSIM_INCALL_TONE = 55;
    private static final String sSupervisoryCallHoldToneConfig =
            SystemProperties.get("persist.radio.sch_tone", "none");

    private String mLchSub = null;

    private InCallTonePlayer mLocalCallReminderTonePlayer = null;
    private InCallTonePlayer mSupervisoryCallHoldTonePlayer = null;
    private String mSubInConversation = null;

    private Runnable mStopTone;

    /** Singleton accessor. */
    static CallsManager getInstance() {
        return sInstance;
    }

    /**
     * Sets the static singleton instance.
     *
     * @param instance The instance to set.
     */
    static void initialize(CallsManager instance) {
        sInstance = instance;
    }

    /**
     * Initializes the required Telecom components.
     */
     CallsManager(Context context, MissedCallNotifier missedCallNotifier,
                  BlacklistCallNotifier blacklistCallNotifier,
                  PhoneAccountRegistrar phoneAccountRegistrar,
                  CallInfoProvider callInfoProvider) {
        mContext = context;
        mPhoneAccountRegistrar = phoneAccountRegistrar;
        mMissedCallNotifier = missedCallNotifier;
        mBlacklistCallNotifier = blacklistCallNotifier;
        StatusBarNotifier statusBarNotifier = new StatusBarNotifier(context, this);
        mWiredHeadsetManager = new WiredHeadsetManager(context);
        mCallAudioManager = new CallAudioManager(context, statusBarNotifier, mWiredHeadsetManager);
        InCallTonePlayer.Factory playerFactory = new InCallTonePlayer.Factory(mCallAudioManager);
        mPlayerFactory = playerFactory;
        mRinger = new Ringer(mCallAudioManager, this, playerFactory, context);
        mHeadsetMediaButton = new HeadsetMediaButton(context, this);
        mTtyManager = new TtyManager(context, mWiredHeadsetManager);
        mProximitySensorManager = new ProximitySensorManager(context);
        mPhoneStateBroadcaster = new PhoneStateBroadcaster();
        mCallLogManager = new CallLogManager(context);
        mInCallController = new InCallController(context);
        mDtmfLocalTonePlayer = new DtmfLocalTonePlayer(context);
        mConnectionServiceRepository = new ConnectionServiceRepository(mPhoneAccountRegistrar,
                context);
        mInCallWakeLockController = new InCallWakeLockController(context, this);
        mCallInfoProvider = callInfoProvider;

        mListeners.add(statusBarNotifier);
        mListeners.add(mCallLogManager);
        mListeners.add(mPhoneStateBroadcaster);
        mListeners.add(mInCallController);
        mListeners.add(mRinger);
        mListeners.add(new RingbackPlayer(this, playerFactory));
        mListeners.add(new InCallToneMonitor(playerFactory, this));
        mListeners.add(mCallAudioManager);
        mListeners.add(missedCallNotifier);
        mListeners.add(mDtmfLocalTonePlayer);
        mListeners.add(mHeadsetMediaButton);
        mListeners.add(RespondViaSmsManager.getInstance());
        mListeners.add(mProximitySensorManager);
        mListeners.add(mCallInfoProvider);
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
    public void onFailedOutgoingCall(Call call, DisconnectCause disconnectCause) {
        Log.v(this, "onFailedOutgoingCall, call: %s", call);

        markCallAsRemoved(call);
    }

    @Override
    public void onSuccessfulIncomingCall(Call incomingCall) {
        Log.d(this, "onSuccessfulIncomingCall");

        if (isCallBlacklisted(incomingCall)) {
            mCallLogManager.logCall(incomingCall, Calls.BLACKLIST_TYPE);
            incomingCall.setDisconnectCause(
                    new DisconnectCause(android.telephony.DisconnectCause.CALL_BLACKLISTED));
        } else if (mCallInfoProvider.shouldBlock(incomingCall.getNumber())) {
            // TODO: show notification for blocked spam calls
            // TODO: add unique call type for spam
            mCallLogManager.logCall(incomingCall, Calls.BLACKLIST_TYPE);
            incomingCall.setDisconnectCause(
                    new DisconnectCause(android.telephony.DisconnectCause.CALL_BLACKLISTED));
        } else {
            setCallState(incomingCall, CallState.RINGING);
            if (hasMaximumRingingCalls(incomingCall.getTargetPhoneAccount().getId())) {
                incomingCall.reject(false, null);
                // since the call was not added to the list of calls, we have to call the missed
                // call notifier and the call logger manually.
                mMissedCallNotifier.showMissedCallNotification(incomingCall);
                mCallLogManager.logCall(incomingCall, Calls.MISSED_TYPE);
            } else {
                incomingCall.mIsActiveSub = true;
                addCall(incomingCall);
                setActiveSubscription(incomingCall.getTargetPhoneAccount().getId());
            }
        }
    }

    @Override
    public void onFailedIncomingCall(Call call) {
        setCallState(call, CallState.DISCONNECTED);
        call.removeListener(this);
    }

    @Override
    public void onSuccessfulUnknownCall(Call call, int callState) {
        setCallState(call, callState);
        Log.i(this, "onSuccessfulUnknownCall for call %s", call);
        addCall(call);
    }

    @Override
    public void onFailedUnknownCall(Call call) {
        Log.i(this, "onFailedUnknownCall for call %s", call);
        setCallState(call, CallState.DISCONNECTED);
        call.removeListener(this);
    }

    @Override
    public void onRingbackRequested(Call call, boolean ringback) {
        for (CallsManagerListener listener : mListeners) {
            listener.onRingbackRequested(call, ringback);
        }
    }

    @Override
    public void onPostDialWait(Call call, String remaining) {
        mInCallController.onPostDialWait(call, remaining);
    }

    @Override
    public void onPostDialChar(final Call call, char nextChar) {
        if (PhoneNumberUtils.is12Key(nextChar)) {
            // Play tone if it is one of the dialpad digits, canceling out the previously queued
            // up stopTone runnable since playing a new tone automatically stops the previous tone.
            if (mStopTone != null) {
                mHandler.removeCallbacks(mStopTone);
            }

            mDtmfLocalTonePlayer.playTone(call, nextChar);

            mStopTone = new Runnable() {
                @Override
                public void run() {
                    // Set a timeout to stop the tone in case there isn't another tone to follow.
                    mDtmfLocalTonePlayer.stopTone(call);
                }
            };
            mHandler.postDelayed(
                    mStopTone,
                    Timeouts.getDelayBetweenDtmfTonesMillis(mContext.getContentResolver()));
        } else if (nextChar == 0 || nextChar == TelecomManager.DTMF_CHARACTER_WAIT ||
                nextChar == TelecomManager.DTMF_CHARACTER_PAUSE) {
            // Stop the tone if a tone is playing, removing any other stopTone callbacks since
            // the previous tone is being stopped anyway.
            if (mStopTone != null) {
                mHandler.removeCallbacks(mStopTone);
            }
            mDtmfLocalTonePlayer.stopTone(call);
        } else {
            Log.w(this, "onPostDialChar: invalid value %d", nextChar);
        }
    }

    @Override
    public void onParentChanged(Call call) {
        // parent-child relationship affects which call should be foreground, so do an update.
        updateCallsManagerState();
        for (CallsManagerListener listener : mListeners) {
            listener.onIsConferencedChanged(call);
        }
    }

    @Override
    public void onChildrenChanged(Call call) {
        // parent-child relationship affects which call should be foreground, so do an update.
        updateCallsManagerState();
        for (CallsManagerListener listener : mListeners) {
            listener.onIsConferencedChanged(call);
        }
    }

    @Override
    public void onIsVoipAudioModeChanged(Call call) {
        for (CallsManagerListener listener : mListeners) {
            listener.onIsVoipAudioModeChanged(call);
        }
    }

    @Override
    public void onVideoStateChanged(Call call) {
        for (CallsManagerListener listener : mListeners) {
            listener.onVideoStateChanged(call);
        }
    }

    @Override
    public void onCallSubstateChanged(Call call) {
        for (CallsManagerListener listener : mListeners) {
            listener.onCallSubstateChanged(call);
        }
    }

    @Override
    public boolean onCanceledViaNewOutgoingCallBroadcast(final Call call) {
        mPendingCallsToDisconnect.add(call);
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (mPendingCallsToDisconnect.remove(call)) {
                    Log.i(this, "Delayed disconnection of call: %s", call);
                    call.disconnect();
                }
            }
        }, Timeouts.getNewOutgoingCallCancelMillis(mContext.getContentResolver()));

        return true;
    }

    Collection<Call> getCalls() {
        return Collections.unmodifiableCollection(mCalls);
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

    boolean hasVideoCall() {
        for (Call call : mCalls) {
            if (call.getVideoState() != VideoProfile.VideoState.AUDIO_ONLY) {
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

    void addListener(CallsManagerListener listener) {
        mListeners.add(listener);
    }

    void removeListener(CallsManagerListener listener) {
        mListeners.remove(listener);
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
        Uri handle = extras.getParcelable(TelephonyManager.EXTRA_INCOMING_NUMBER);
        Call call = new Call(
                mContext,
                mConnectionServiceRepository,
                handle,
                null /* gatewayInfo */,
                null /* connectionManagerPhoneAccount */,
                phoneAccountHandle,
                true /* isIncoming */,
                false /* isConference */);

        call.setExtras(extras);
        // TODO: Move this to be a part of addCall()
        call.addListener(this);
        for (CallsManagerListener listener : mListeners) {
            listener.onProcessIncomingCall(call);
        }
        call.startCreateConnection(mPhoneAccountRegistrar);
    }

    void addNewUnknownCall(PhoneAccountHandle phoneAccountHandle, Bundle extras) {
        Uri handle = extras.getParcelable(TelecomManager.EXTRA_UNKNOWN_CALL_HANDLE);
        String state = extras.getString(TelecomManager.EXTRA_UNKNOWN_CALL_STATE);

        Log.i(this, "addNewUnknownCall with handle: %s", Log.pii(handle));
        Call call = new Call(
                mContext,
                mConnectionServiceRepository,
                handle,
                null /* gatewayInfo */,
                null /* connectionManagerPhoneAccount */,
                phoneAccountHandle,
                // Use onCreateIncomingConnection in TelephonyConnectionService, so that we attach
                // to the existing connection instead of trying to create a new one.
                true /* isIncoming */,
                false /* isConference */);
        call.setIsUnknown(true);
        call.setState(convertState(state));
        call.setExtras(extras);
        call.addListener(this);
        call.startCreateConnection(mPhoneAccountRegistrar);
    }

    private int convertState(String state) {
        if (state == null) {
            return CallState.RINGING;
        } else if (state.compareTo("ACTIVE") == 0) {
            return CallState.ACTIVE;
        } else if (state.compareTo("HOLDING") == 0) {
            return CallState.ON_HOLD;
        } else if (state.compareTo("DIALING") == 0) {
            return CallState.DIALING;
        }  else if (state.compareTo("ALERTING") == 0) {
            return CallState.RINGING;
        }  else if (state.compareTo("INCOMING") == 0) {
            return CallState.RINGING;
        }  else if (state.compareTo("DISCONNECTED") == 0) {
            return CallState.DISCONNECTED;
        }  else if (state.compareTo("DISCONNECTING") == 0) {
            return CallState.DISCONNECTING;
        } else {
            return CallState.RINGING;
        }
    }

    private Call getNewOutgoingCall(Uri handle) {
        // First check to see if we can reuse any of the calls that are waiting to disconnect.
        // See {@link Call#abort} and {@link #onCanceledViaNewOutgoingCall} for more information.
        Call reusedCall = null;
        for (Call pendingCall : mPendingCallsToDisconnect) {
            if (reusedCall == null && Objects.equals(pendingCall.getHandle(), handle)) {
                mPendingCallsToDisconnect.remove(pendingCall);
                Log.i(this, "Reusing disconnected call %s", pendingCall);
                reusedCall = pendingCall;
            } else {
                pendingCall.disconnect();
            }
        }
        if (reusedCall != null) {
            return reusedCall;
        }

        // Create a call with original handle. The handle may be changed when the call is attached
        // to a connection service, but in most cases will remain the same.
        return new Call(
                mContext,
                mConnectionServiceRepository,
                handle,
                null /* gatewayInfo */,
                null /* connectionManagerPhoneAccount */,
                null /* phoneAccountHandle */,
                false /* isIncoming */,
                false /* isConference */);
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
        Call call = getNewOutgoingCall(handle);

        boolean isSkipSchemaOrConfUri = ((extras != null) && (extras.getBoolean(
                TelephonyProperties.EXTRA_SKIP_SCHEMA_PARSING, false) ||
                extras.getBoolean(TelephonyProperties.EXTRA_DIAL_CONFERENCE_URI, false)));

        // Force tel scheme for ims conf uri/skip schema calls to avoid selection of sip accounts
        String scheme = (isSkipSchemaOrConfUri? PhoneAccount.SCHEME_TEL: handle.getScheme());

        List<PhoneAccountHandle> accounts =
                mPhoneAccountRegistrar.getCallCapablePhoneAccounts(scheme);

        Log.v(this, "startOutgoingCall found accounts = " + accounts);

        if (mForegroundCall != null && mForegroundCall.getTargetPhoneAccount() != null) {
            // If there is an ongoing call, use the same phone account to place this new call.
            phoneAccountHandle = mForegroundCall.getTargetPhoneAccount();
        }

        // Only dial with the requested phoneAccount if it is still valid. Otherwise treat this call
        // as if a phoneAccount was not specified (does the default behavior instead).
        // Note: We will not attempt to dial with a requested phoneAccount if it is disabled.
        if (phoneAccountHandle != null) {
            if (!accounts.contains(phoneAccountHandle)) {
                phoneAccountHandle = null;
            }
        }

        if (phoneAccountHandle == null) {
            // No preset account, check if default exists that supports the URI scheme for the
            // handle.
            PhoneAccountHandle defaultAccountHandle =
                    mPhoneAccountRegistrar.getDefaultOutgoingPhoneAccount(
                            scheme);
            TelephonyManager.MultiSimVariants msimConfig =
                    TelephonyManager.getDefault().getMultiSimConfiguration();
            if (((msimConfig == TelephonyManager.MultiSimVariants.DSDS) ||
                    (msimConfig == TelephonyManager.MultiSimVariants.TSTS)) &&
                    (mForegroundCall != null) && (mForegroundCall.isAlive())) {
                defaultAccountHandle = mForegroundCall.getTargetPhoneAccount();
            }
            if (defaultAccountHandle != null) {
                phoneAccountHandle = defaultAccountHandle;
            }
        }

        call.setTargetPhoneAccount(phoneAccountHandle);

        boolean isEmergencyCall = TelephonyUtil.shouldProcessAsEmergency(mContext,
                call.getHandle());
        boolean isPotentialInCallMMICode = isPotentialInCallMMICode(handle);

        // Do not support any more live calls.  Our options are to move a call to hold, disconnect
        // a call, or cancel this call altogether.
        if (!isPotentialInCallMMICode && !makeRoomForOutgoingCall(call, isEmergencyCall)) {
            // just cancel at this point.
            Log.i(this, "No remaining room for outgoing call: %s", call);
            if (mCalls.contains(call)) {
                // This call can already exist if it is a reused call,
                // See {@link #getNewOutgoingCall}.
                call.disconnect();
            }
            return null;
        }

        boolean needsAccountSelection = phoneAccountHandle == null && accounts.size() > 1 &&
                !isEmergencyCall;

        if (needsAccountSelection) {
            // This is the state where the user is expected to select an account
            call.setState(CallState.PRE_DIAL_WAIT);
            // Create our own instance to modify (since extras may be Bundle.EMPTY)
            extras = new Bundle(extras);
            extras.putParcelableList(android.telecom.Call.AVAILABLE_PHONE_ACCOUNTS, accounts);
        } else {
            call.setState(CallState.CONNECTING);
        }

        call.setExtras(extras);

        // Do not add the call if it is a potential MMI code.
        if ((isPotentialMMICode(handle) || isPotentialInCallMMICode) && !needsAccountSelection) {
            call.addListener(this);
        } else if (!mCalls.contains(call)) {
            // We check if mCalls already contains the call because we could potentially be reusing
            // a call which was previously added (See {@link #getNewOutgoingCall}).
            addCall(call);
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

        boolean isEmergencyCall = TelephonyUtil.shouldProcessAsEmergency(mContext,
                call.getHandle());
        if (isEmergencyCall) {
            // Emergency -- CreateConnectionProcessor will choose accounts automatically
            call.setTargetPhoneAccount(null);
        }

        if (call.getTargetPhoneAccount() != null || isEmergencyCall) {
            if (!isEmergencyCall) {
                updateLchStatus(call.getTargetPhoneAccount().getId());
            }
            // If the account has been set, proceed to place the outgoing call.
            // Otherwise the connection will be initiated when the account is set by the user.
            call.startCreateConnection(mPhoneAccountRegistrar);
        }
    }

    /**
     * Attempts to add participant in a call.
     *
     * @param number number to connect the call with.
     */
    void addParticipant(String number) {
        Log.i(this, "addParticipant number ="+number);
        if (getForegroundCall() == null) {
            // don't do anything if the call no longer exists
            Log.i(this, "Canceling unknown call.");
            return;
        } else {
            getForegroundCall().addParticipantWithConference(number);
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
     * Instructs Telecom to answer the specified call. Intended to be invoked by the in-call
     * app through {@link InCallAdapter} after Telecom notifies it of an incoming call followed by
     * the user opting to answer said call.
     *
     * @param call The call to answer.
     * @param videoState The video state in which to answer the call.
     */
    void answerCall(Call call, int videoState) {
        if (!mCalls.contains(call)) {
            Log.i(this, "Request to answer a non-existent call %s", call);
        } else {
            Call activeCall = getFirstCallWithStateUsingSubId(call.getTargetPhoneAccount()
                    .getId(), CallState.ACTIVE, CallState.DIALING);
            //Check for existence of active call & held
            if ((activeCall != null) && (getFirstCallWithStateUsingSubId(call
                    .getTargetPhoneAccount().getId(), CallState.ON_HOLD) != null)) {
                Log.i(this, "Disconnect active call");
                // Both active call & Held call are present, hence disconnect active call
                // before sending answer request on waiting call.
                activeCall.disconnect();
                activeCall = null;
            }

            // If the foreground call is not the ringing call and it is currently isActive() or
            // STATE_DIALING, put it on hold before answering the call.
            if (activeCall != null && activeCall != call &&
                    (activeCall.isActive() ||
                     activeCall.getState() == CallState.DIALING)) {
                if (0 == (activeCall.getConnectionCapabilities() & Connection.CAPABILITY_HOLD)) {
                    // This call does not support hold.  If it is from a different connection
                    // service, then disconnect it, otherwise allow the connection service to
                    // figure out the right states.
                    if (activeCall.getConnectionService() != call.getConnectionService()) {
                        activeCall.disconnect();
                    }
                } else {
                    Call heldCall = getHeldCall();
                    if (heldCall != null) {
                        Log.v(this, "Disconnecting held call %s before holding active call.",
                                heldCall);
                        heldCall.disconnect();
                    }

                    Log.v(this, "Holding active/dialing call %s before answering incoming call %s.",
                            mForegroundCall, call);
                    activeCall.hold();
                }
                // TODO: Wait until we get confirmation of the active call being
                // on-hold before answering the new call.
                // TODO: Import logic from CallManager.acceptCall()
            }

            for (CallsManagerListener listener : mListeners) {
                listener.onIncomingCallAnswered(call);
            }
            updateLchStatus(call.getTargetPhoneAccount().getId());
            // We do not update the UI until we get confirmation of the answer() through
            // {@link #markCallAsActive}.
            call.answer(videoState);
            if (VideoProfile.VideoState.isVideo(videoState) &&
                !mWiredHeadsetManager.isPluggedIn() &&
                !mCallAudioManager.isBluetoothDeviceAvailable() &&
                isSpeakerEnabledForVideoCalls()) {
                call.setStartWithSpeakerphoneOn(true);
            }
        }
    }

    private static boolean isSpeakerEnabledForVideoCalls() {
        return (SystemProperties.getInt(TelephonyProperties.PROPERTY_IMS_AUDIO_OUTPUT,
                PhoneConstants.IMS_AUDIO_OUTPUT_DEFAULT) ==
                PhoneConstants.IMS_AUDIO_OUTPUT_ENABLE_SPEAKER);
    }

    /**
     * Instructs Telecomm to deflect the specified call. Intended to be invoked by the in-call
     * app through {@link InCallAdapter} after Telecomm notifies it of an incoming call followed by
     * the user opting to deflect said call.
     */
    void deflectCall(Call call, String number) {
        if (!mCalls.contains(call)) {
            Log.i(this, "Request to deflect a non-existent call %s", call);
        } else {
            call.deflect(number);
        }
    }

    /**
     * Instructs Telecom to reject the specified call. Intended to be invoked by the in-call
     * app through {@link InCallAdapter} after Telecom notifies it of an incoming call followed by
     * the user opting to reject said call.
     */
    void rejectCall(Call call, boolean rejectWithMessage, String textMessage) {
        if (!mCalls.contains(call)) {
            Log.i(this, "Request to reject a non-existent call %s", call);
        } else {
            for (CallsManagerListener listener : mListeners) {
                listener.onIncomingCallRejected(call, rejectWithMessage, textMessage);
            }
            setActiveSubscription(getConversationSub());
            call.reject(rejectWithMessage, textMessage);
        }
    }

    /**
     * Instructs Telecom to play the specified DTMF tone within the specified call.
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
     * Instructs Telecom to stop the currently playing DTMF tone, if any.
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
     * Instructs Telecom to continue (or not) the current post-dial DTMF string, if any.
     */
    void postDialContinue(Call call, boolean proceed) {
        if (!mCalls.contains(call)) {
            Log.i(this, "Request to continue post-dial string in a non-existent call %s", call);
        } else {
            call.postDialContinue(proceed);
        }
    }

    /**
     * Instructs Telecom to disconnect the specified call. Intended to be invoked by the
     * in-call app through {@link InCallAdapter} for an ongoing call. This is usually triggered by
     * the user hitting the end-call button.
     */
    void disconnectCall(Call call) {
        Log.v(this, "disconnectCall %s", call);

        if (!mCalls.contains(call)) {
            Log.w(this, "Unknown call (%s) asked to disconnect", call);
        } else {
            mLocallyDisconnectingCalls.add(call);
            call.disconnect();
        }
    }

    /**
     * Instructs Telecom to disconnect all calls.
     */
    void disconnectAllCalls() {
        Log.v(this, "disconnectAllCalls");

        for (Call call : mCalls) {
            disconnectCall(call);
        }
    }


    /**
     * Instructs Telecom to put the specified call on hold. Intended to be invoked by the
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
     * Instructs Telecom to release the specified call from hold. Intended to be invoked by
     * the in-call app through {@link InCallAdapter} for an ongoing call. This is usually triggered
     * by the user hitting the hold button during a held call.
     */
    void unholdCall(Call call) {
        if (!mCalls.contains(call)) {
            Log.w(this, "Unknown call (%s) asked to be removed from hold", call);
        } else {
            Log.d(this, "unholding call: (%s)", call);
            PhoneAccountHandle ph = call.getTargetPhoneAccount();
            if ((ph == null) && (call.getChildCalls().size() > 1)) {
                Call child = call.getChildCalls().get(0);
                ph = child.getTargetPhoneAccount();
            }
            for (Call c : mCalls) {
                // Only operate on top-level calls
                if (c.getParentCall() != null) {
                    continue;
                }

                PhoneAccountHandle ph1 = c.getTargetPhoneAccount();
                if ((ph1 == null) && (c.getChildCalls().size() > 1)) {
                    Call child = c.getChildCalls().get(0);
                    ph1 = child.getTargetPhoneAccount();
                }

                // if 'c' is not for same subscription as call, then don't disturb 'c'
                if (c != null && c.isAlive() && c != call && (ph != null
                        && ph1 != null && isSameIdOrSipId(ph.getId(), ph1.getId()))) {
                    c.hold();
                }
            }
            call.unhold();
        }
    }

    /**
     *  Returns true if the ids are same or one of the ids is sip id.
     */
    private boolean isSameIdOrSipId(String id1, String id2) {
        boolean ret = ((id1 != null && id2 != null) &&
                (id1.equals(id2) || id1.contains("sip") || id2.contains("sip")));
        Log.d(this, "isSameIdOrSipId: id1 = " + id1 + " id2 = " + id2 + " ret = " + ret);
        return ret;
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

    void phoneAccountSelected(Call call, PhoneAccountHandle account, boolean setDefault) {
        if (!mCalls.contains(call)) {
            Log.i(this, "Attempted to add account to unknown call %s", call);
        } else {
            // TODO: There is an odd race condition here. Since NewOutgoingCallIntentBroadcaster and
            // the PRE_DIAL_WAIT sequence run in parallel, if the user selects an account before the
            // NEW_OUTGOING_CALL sequence finishes, we'll start the call immediately without
            // respecting a rewritten number or a canceled number. This is unlikely since
            // NEW_OUTGOING_CALL sequence, in practice, runs a lot faster than the user selecting
            // a phone account from the in-call UI.
            Log.i(this, "phoneAccountSelected , id = %s", account.getId());
            updateLchStatus(account.getId());
            call.setTargetPhoneAccount(account);

            // Note: emergency calls never go through account selection dialog so they never
            // arrive here.
            if (makeRoomForOutgoingCall(call, false /* isEmergencyCall */)) {
                call.startCreateConnection(mPhoneAccountRegistrar);
            } else {
                call.disconnect();
            }

            if (setDefault) {
                mPhoneAccountRegistrar.setUserSelectedOutgoingPhoneAccount(account);
            }
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
        setActiveSubscription(call.getTargetPhoneAccount().getId());
    }

    void markCallAsActive(Call call) {
        setCallState(call, CallState.ACTIVE);

        if (call.getStartWithSpeakerphoneOn()) {
            setAudioRoute(AudioState.ROUTE_SPEAKER);
        }
    }

    void markCallAsOnHold(Call call) {
        setCallState(call, CallState.ON_HOLD);
    }

    /**
     * Marks the specified call as STATE_DISCONNECTED and notifies the in-call app. If this was the
     * last live call, then also disconnect from the in-call controller.
     *
     * @param disconnectCause The disconnect cause, see {@link android.telecomm.DisconnectCause}.
     */
    void markCallAsDisconnected(Call call, DisconnectCause disconnectCause) {
        call.setDisconnectCause(disconnectCause);
        int prevState = call.getState();
        setCallState(call, CallState.DISCONNECTED);
        String activeSub = getActiveSubscription();
        String conversationSub = getConversationSub();
        String lchSub = IsAnySubInLch();

        PhoneAccount phAcc =
                 getPhoneAccountRegistrar().getPhoneAccount(call.getTargetPhoneAccount());
        if ((call.getTargetPhoneAccount() != null &&
                    call.getTargetPhoneAccount().getId().equals(activeSub)) &&
                    (phAcc != null) && (phAcc.isSet(PhoneAccount.LCH)) &&
                    (conversationSub != null) &&
                    (!conversationSub.equals(activeSub))) {
            Log.d(this,"Set active sub to conversation sub");
            setActiveSubscription(conversationSub);
        } else if ((conversationSub == null) && (lchSub != null) &&
                ((prevState == CallState.CONNECTING) || (prevState == CallState.PRE_DIAL_WAIT)) &&
                (call.getState() == CallState.DISCONNECTED)) {
            Log.d(this,"remove sub with call from LCH");
            updateLchStatus(lchSub);
            setActiveSubscription(lchSub);
            manageMSimInCallTones(false);
        }

        if ((call.getTargetPhoneAccount() != null) && (phAcc != null) &&
                (phAcc.isSet(PhoneAccount.LCH))) {
            Call activecall = getFirstCallWithStateUsingSubId(call.getTargetPhoneAccount().getId(),
                    CallState.RINGING, CallState.DIALING, CallState.ACTIVE, CallState.ON_HOLD);
            Log.d(this,"activecall: " + activecall);
            if (activecall == null) {
                phAcc.unSetBit(PhoneAccount.LCH);
                manageMSimInCallTones(false);
            }
        }
    }

    private String IsAnySubInLch() {
        for (PhoneAccountHandle ph : getPhoneAccountRegistrar().getCallCapablePhoneAccounts()) {
            if (getPhoneAccountRegistrar().getPhoneAccount(ph).isSet(PhoneAccount.LCH)) {
                Log.d(this, "Sub in LCH: " + ph.getId());
                return ph.getId();
            }
        }
        return null;
    }

    /**
     * Removes an existing disconnected call, and notifies the in-call app.
     */
    void markCallAsRemoved(Call call) {
        removeCall(call);
        if (!hasAnyCalls()) {
            updateLchStatus(null);
            setActiveSubscription(null);
            manageMSimInCallTones(false);
        }
        if (mLocallyDisconnectingCalls.contains(call)) {
            mLocallyDisconnectingCalls.remove(call);
            if (mForegroundCall != null && mForegroundCall.getState() == CallState.ON_HOLD) {
                mForegroundCall.unhold();
            }
        }
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
                    if (call.getState() != CallState.DISCONNECTED) {
                        markCallAsDisconnected(call, new DisconnectCause(DisconnectCause.ERROR));
                    }
                    markCallAsRemoved(call);
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

    boolean hasActiveOrHoldingCall(String sub) {
        return (getFirstCallWithStateUsingSubId(sub, CallState.ACTIVE, CallState.ON_HOLD) != null);
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
     * Returns true if telecom supports adding another top-level call.
     */
    boolean canAddCall() {
        boolean isDeviceProvisioned = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.DEVICE_PROVISIONED, 0) != 0;
        if (!isDeviceProvisioned) {
            Log.d(TAG, "Device not provisioned, canAddCall is false.");
            return false;
        }

        if (getFirstCallWithState(OUTGOING_CALL_STATES) != null) {
            return false;
        }

        int count = 0;
        for (Call call : mCalls) {
            if (call.isEmergencyCall()) {
                // We never support add call if one of the calls is an emergency call.
                return false;
            } else if (call.getParentCall() == null) {
                count++;
            }

            // We do not check states for canAddCall. We treat disconnected calls the same
            // and wait until they are removed instead. If we didn't count disconnected calls,
            // we could put InCallServices into a state where they are showing two calls but
            // also support add-call. Technically it's right, but overall looks better (UI-wise)
            // and acts better if we wait until the call is removed.
            if (count >= MAXIMUM_TOP_LEVEL_CALLS) {
                return false;
            }

            // Commented below block as 'Add Call' button was still missing in single
            // sim VoLTE conference cases even after increasing MAXIMUM_TOP_LEVEL_CALLS
            // TODO: Check if the below block needs changes to cover MSIM cases
            /*
            PhoneAccountHandle ph = call.getTargetPhoneAccount();
            // Loop through all the other calls and there exists a top level (has no parent) call
            // that is not the specified call, return false.
            for (Call otherCall : mCalls) {
                PhoneAccountHandle otherCallPh = otherCall.getTargetPhoneAccount();
                // if 'otherCall' is not for same subscription as 'call', then don't consider it
                if (call != otherCall && otherCall.getParentCall() == null && ph != null
                        && otherCallPh != null && isSameIdOrSipId(ph.getId(), otherCallPh.getId())) {
                    return false;
                }
            }

            if ((call.getState() != CallState.ACTIVE) && (call.getState() != CallState.ON_HOLD)) {
                return false;
            } */
        }
        return true;
    }

    Call getRingingCall() {
        return getFirstCallWithState(CallState.RINGING);
    }

    Call getActiveCall() {
        return getFirstCallWithState(CallState.ACTIVE);
    }

    Call getDialingCall() {
        return getFirstCallWithState(CallState.DIALING);
    }

    Call getHeldCall() {
        return getFirstCallWithState(CallState.ON_HOLD);
    }

    int getNumHeldCalls() {
        int count = 0;
        for (Call call : mCalls) {
            if (call.getParentCall() == null && call.getState() == CallState.ON_HOLD) {
                count++;
            }
        }
        return count;
    }

    Call getFirstCallWithState(int... states) {
        return getFirstCallWithState(null, states);
    }

    /**
     * Returns the first call that it finds with the given states. The states are treated as having
     * priority order so that any call with the first state will be returned before any call with
     * states listed later in the parameter list.
     *
     * @param callToSkip Call that this method should skip while searching
     */
    Call getFirstCallWithState(Call callToSkip, int... states) {
        for (int currentState : states) {
            // check the foreground first
            if (mForegroundCall != null && mForegroundCall.getState() == currentState) {
                return mForegroundCall;
            }

            for (Call call : mCalls) {
                if (Objects.equals(callToSkip, call)) {
                    continue;
                }

                // Only operate on top-level calls
                if (call.getParentCall() != null) {
                    continue;
                }

                if (currentState == call.getState()) {
                    return call;
                }
            }
        }
        return null;
    }

    /**
     * Returns the first call that it finds with the given states for given subscription.
     * the states are treated as having priority order so that any call with the first
     * state will be returned before any call with states listed later in the parameter list.
     *
     * @param subId check calls only on this subscription
     * @param callToSkip Call that this method should skip while searching
     */
    Call getFirstCallWithStateUsingSubId(String subId, Call callToSkip, int... states) {
        for (int currentState : states) {
            // check the foreground first
            if (mForegroundCall != null && mForegroundCall.getState() == currentState
                    && mForegroundCall.getTargetPhoneAccount() != null
                    && isSameIdOrSipId(mForegroundCall.getTargetPhoneAccount().getId(), subId)) {
                return mForegroundCall;
            }

            for (Call call : mCalls) {
                if (Objects.equals(callToSkip, call)) {
                    continue;
                }

                // Only operate on top-level calls
                if (call.getParentCall() != null) {
                    continue;
                }

                if ((call.getTargetPhoneAccount() == null) && (call.getChildCalls().size() > 1)) {
                    Call child = call.getChildCalls().get(0);
                    PhoneAccountHandle childph = child.getTargetPhoneAccount();
                    if (childph != null && isSameIdOrSipId(childph.getId(), subId)) {
                        return call;
                    }
                }

                if (currentState == call.getState() && call.getTargetPhoneAccount() != null
                        && isSameIdOrSipId(call.getTargetPhoneAccount().getId(), subId)) {
                    return call;
                }
            }
        }
        return null;
    }

    Call createConferenceCall(
            PhoneAccountHandle phoneAccount,
            ParcelableConference parcelableConference) {

        // If the parceled conference specifies a connect time, use it; otherwise default to 0,
        // which is the default value for new Calls.
        long connectTime =
                parcelableConference.getConnectTimeMillis() ==
                        Conference.CONNECT_TIME_NOT_SPECIFIED ? 0 :
                        parcelableConference.getConnectTimeMillis();

        Call call = new Call(
                mContext,
                mConnectionServiceRepository,
                null /* handle */,
                null /* gatewayInfo */,
                null /* connectionManagerPhoneAccount */,
                phoneAccount,
                false /* isIncoming */,
                true /* isConference */,
                connectTime);

        setCallState(call, Call.getStateFromConnectionState(parcelableConference.getState()));
        call.setConnectionCapabilities(parcelableConference.getConnectionCapabilities());
        call.setVideoState(parcelableConference.getVideoState());
        call.setVideoProvider(parcelableConference.getVideoProvider());

        // TODO: Move this to be a part of addCall()
        call.addListener(this);
        addCall(call);
        return call;
    }

    /**
     * @return the call state currently tracked by {@link PhoneStateBroadcaster}
     */
    int getCallState() {
        return mPhoneStateBroadcaster.getCallState();
    }

    /**
     * Retrieves the {@link PhoneAccountRegistrar}.
     *
     * @return The {@link PhoneAccountRegistrar}.
     */
    PhoneAccountRegistrar getPhoneAccountRegistrar() {
        return mPhoneAccountRegistrar;
    }

    /**
     * Retrieves the {@link MissedCallNotifier}
     * @return The {@link MissedCallNotifier}.
     */
    MissedCallNotifier getMissedCallNotifier() {
        return mMissedCallNotifier;
    }

    /**
     * Retrieves the {@link MissedCallNotifier}
     * @return The {@link MissedCallNotifier}.
     */
    BlacklistCallNotifier getBlacklistCallNotifier() {
        return mBlacklistCallNotifier;
    }

    /**
     * Adds the specified call to the main list of live calls.
     *
     * @param call The call to add.
     */
    private void addCall(Call call) {
        Trace.beginSection("addCall");
        Log.v(this, "addCall(%s)", call);
        call.addListener(this);
        mCalls.add(call);

        // TODO: Update mForegroundCall prior to invoking
        // onCallAdded for calls which immediately take the foreground (like the first call).
        for (CallsManagerListener listener : mListeners) {
            if (Log.SYSTRACE_DEBUG) {
                Trace.beginSection(listener.getClass().toString() + " addCall");
            }
            listener.onCallAdded(call);
            if (Log.SYSTRACE_DEBUG) {
                Trace.endSection();
            }
        }
        updateCallsManagerState();
        Trace.endSection();
    }

    private void removeCall(Call call) {
        Trace.beginSection("removeCall");
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
                if (Log.SYSTRACE_DEBUG) {
                    Trace.beginSection(listener.getClass().toString() + " onCallRemoved");
                }
                listener.onCallRemoved(call);
                if (Log.SYSTRACE_DEBUG) {
                    Trace.endSection();
                }
            }
            updateCallsManagerState();
        }
        Trace.endSection();
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

            Trace.beginSection("onCallStateChanged");
            // Only broadcast state change for calls that are being tracked.
            if (mCalls.contains(call)) {
                for (CallsManagerListener listener : mListeners) {
                    if (Log.SYSTRACE_DEBUG) {
                        Trace.beginSection(listener.getClass().toString() + " onCallStateChanged");
                    }
                    listener.onCallStateChanged(call, oldState, newState);
                    if (Log.SYSTRACE_DEBUG) {
                        Trace.endSection();
                    }
                }
                updateCallsManagerState();
            }
            Trace.endSection();
        }
        manageMSimInCallTones(false);
    }

    /**
     * Adds OEM extras from lower layers into Call's extras.
     *
     * @param call The call.
     * @param extras OEM call extras.
     */
    void setCallExtras(Call call, Bundle extras) {
        if (extras == null) {
            Log.d(this, "setCallExtras Null extras Bundle");
            return;
        }
        Bundle callExtras = call.getExtras();

        // NOTE: OEM extras are packed "as is" within the Call
        // object's mExtras Bundle so as to preserve the
        // original contents of the mExtras Bundle. We don't
        // want to overwrite mExtras with the OEM extras.
        callExtras.putBundle(Call.KEY_OEM_EXTRAS, extras);

        for (CallsManagerListener listener : mListeners) {
            listener.onCallExtrasUpdated(call);
        }
    }

    /**
     * Checks which call should be visible to the user and have audio focus.
     */
    private void updateForegroundCall() {
        Trace.beginSection("updateForegroundCall");
        Call newForegroundCall = null;
            // TODO: Foreground-ness needs to be explicitly set. No call, regardless
            // of its state will be foreground by default and instead the connection service should
            // be notified when its calls enter and exit foreground state. Foreground will mean that
            // the call should play audio and listen to microphone if it wants.

        if (TelephonyManager.getDefault().getMultiSimConfiguration()
                == TelephonyManager.MultiSimVariants.DSDA) {
            String lchSub = getLchSub();
            for (Call call : mCalls) {
                // Only top-level calls can be in foreground
                if (call.getParentCall() != null) {
                    continue;
                }

                PhoneAccountHandle ph = call.getTargetPhoneAccount();
                if (ph != null && ph.getId().equals(lchSub)) continue;
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
            // if active sub doesn't have any calls, then consider calls on all subs,
            // which ever call is active set that as foreground call. give more priority
            // to ringing call on LCH sub over active call.
            if (newForegroundCall == null) {
                newForegroundCall = getFirstCallWithState(CallState.RINGING);
                if (newForegroundCall == null) {
                    newForegroundCall = getFirstCallWithState(CallState.ACTIVE);
                }
            }
        } else {
            for (Call call : mCalls) {
                // Only top-level calls can be in foreground
                if (call.getParentCall() != null) {
                    continue;
                }

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
        }

        if (newForegroundCall != mForegroundCall) {
            Log.v(this, "Updating foreground call, %s -> %s.", mForegroundCall, newForegroundCall);
            Call oldForegroundCall = mForegroundCall;
            mForegroundCall = newForegroundCall;

            for (CallsManagerListener listener : mListeners) {
                if (Log.SYSTRACE_DEBUG) {
                    Trace.beginSection(listener.getClass().toString() + " updateForegroundCall");
                }
                listener.onForegroundCallChanged(oldForegroundCall, mForegroundCall);
                if (Log.SYSTRACE_DEBUG) {
                    Trace.endSection();
                }
            }
        }
        Trace.endSection();
    }

    private void updateCanAddCall() {
        boolean newCanAddCall = canAddCall();
        if (newCanAddCall != mCanAddCall) {
            mCanAddCall = newCanAddCall;
            for (CallsManagerListener listener : mListeners) {
                if (Log.SYSTRACE_DEBUG) {
                    Trace.beginSection(listener.getClass().toString() + " updateCanAddCall");
                }
                listener.onCanAddCallChanged(mCanAddCall);
                if (Log.SYSTRACE_DEBUG) {
                    Trace.endSection();
                }
            }
        }
    }

    private void updateCallsManagerState() {
        updateForegroundCall();
        updateCanAddCall();
    }

    private boolean isPotentialMMICode(Uri handle) {
        return (handle != null && handle.getSchemeSpecificPart() != null
                && handle.getSchemeSpecificPart().contains("#"));
    }

    /**
     * Determines if a dialed number is potentially an In-Call MMI code.  In-Call MMI codes are
     * MMI codes which can be dialed when one or more calls are in progress.
     * <P>
     * Checks for numbers formatted similar to the MMI codes defined in:
     * {@link com.android.internal.telephony.gsm.GSMPhone#handleInCallMmiCommands(String)}
     * and
     * {@link com.android.internal.telephony.imsphone.ImsPhone#handleInCallMmiCommands(String)}
     *
     * @param handle The URI to call.
     * @return {@code True} if the URI represents a number which could be an in-call MMI code.
     */
    private boolean isPotentialInCallMMICode(Uri handle) {
        if (handle != null && handle.getSchemeSpecificPart() != null &&
                handle.getScheme().equals(PhoneAccount.SCHEME_TEL)) {

            String dialedNumber = handle.getSchemeSpecificPart();
            return (dialedNumber.equals("0") ||
                    (dialedNumber.startsWith("1") && dialedNumber.length() <= 2) ||
                    (dialedNumber.startsWith("2") && dialedNumber.length() <= 2) ||
                    dialedNumber.equals("3") ||
                    dialedNumber.equals("4") ||
                    dialedNumber.equals("5"));
        }
        return false;
    }

    private int getNumCallsWithState(int... states) {
        int count = 0;
        for (int state : states) {
            for (Call call : mCalls) {
                if (call.getParentCall() == null && call.getState() == state) {
                    count++;
                }
            }
        }
        return count;
    }  

    private int getNumCallsWithState(String subId, int... states) {
        int count = 0;
        for (int state : states) {
            for (Call call : mCalls) {
                if (call.getState() == state && call.getTargetPhoneAccount() != null
                        && isSameIdOrSipId(call.getTargetPhoneAccount().getId(), subId)) {
                    count++;
                }
            }
        }
        return count;
    }

    private boolean hasMaximumLiveCalls() {
        return MAXIMUM_LIVE_CALLS <= getNumCallsWithState(LIVE_CALL_STATES);
    }

    private boolean hasMaximumLiveCalls(String subId) {
        return MAXIMUM_LIVE_CALLS <= getNumCallsWithState(subId, LIVE_CALL_STATES);
    }

    private boolean hasMaximumHoldingCalls() {
        return MAXIMUM_HOLD_CALLS <= getNumCallsWithState(CallState.ON_HOLD);
    }

    private boolean hasMaximumHoldingCalls(String subId) {
        return MAXIMUM_HOLD_CALLS <= getNumCallsWithState(subId, CallState.ON_HOLD);
    }

    private boolean hasMaximumRingingCalls() {
        return MAXIMUM_RINGING_CALLS <= getNumCallsWithState(CallState.RINGING);
    }

    private boolean hasMaximumRingingCalls(String subId) {
        return MAXIMUM_RINGING_CALLS <= getNumCallsWithState(subId, CallState.RINGING);
    }

    private boolean hasMaximumOutgoingCalls() {
        return MAXIMUM_OUTGOING_CALLS <= getNumCallsWithState(OUTGOING_CALL_STATES);
    }

    private boolean makeRoomForOutgoingCall(Call call, boolean isEmergency) {
        if (TelephonyManager.getDefault().getMultiSimConfiguration()
                == TelephonyManager.MultiSimVariants.DSDA) {
            return makeRoomForOutgoingCallForDsda(call, isEmergency);
        }
        if (hasMaximumLiveCalls()) {
            // NOTE: If the amount of live calls changes beyond 1, this logic will probably
            // have to change.
            Call liveCall = getFirstCallWithState(call, LIVE_CALL_STATES);
            Log.i(this, "makeRoomForOutgoingCall call = " + call + " livecall = " +
                   liveCall);

            if (call == liveCall) {
                // If the call is already the foreground call, then we are golden.
                // This can happen after the user selects an account in the PRE_DIAL_WAIT
                // state since the call was already populated into the list.
                return true;
            }

            if (hasMaximumOutgoingCalls()) {
                // Disconnect the current outgoing call if it's not an emergency call. If the user
                // tries to make two outgoing calls to different emergency call numbers, we will try
                // to connect the first outgoing call.
                if (isEmergency) {
                    Call outgoingCall = getFirstCallWithState(OUTGOING_CALL_STATES);
                    if (!outgoingCall.isEmergencyCall()) {
                        outgoingCall.disconnect();
                        return true;
                    }
                }
                return false;
            }

            if (hasMaximumHoldingCalls()) {
                // There is no more room for any more calls, unless it's an emergency.
                if (isEmergency) {
                    // Kill the current active call, this is easier then trying to disconnect a
                    // holding call and hold an active call.
                    liveCall.disconnect();
                    return true;
                }
                return false;  // No more room!
            }

            // We have room for at least one more holding call at this point.

            // First thing, if we are trying to make a call with the same phone account as the live
            // call, then allow it so that the connection service can make its own decision about
            // how to handle the new call relative to the current one.
            if (Objects.equals(liveCall.getTargetPhoneAccount(), call.getTargetPhoneAccount())) {
                return true;
            } else if (call.getTargetPhoneAccount() == null) {
                // Without a phone account, we can't say reliably that the call will fail.
                // If the user chooses the same phone account as the live call, then it's
                // still possible that the call can be made (like with CDMA calls not supporting
                // hold but they still support adding a call by going immediately into conference
                // mode). Return true here and we'll run this code again after user chooses an
                // account.
                return true;
            }

            // Try to hold the live call before attempting the new outgoing call.
            if (liveCall.can(Connection.CAPABILITY_HOLD)) {
                liveCall.hold();
                return true;
            }

            // The live call cannot be held so we're out of luck here.  There's no room.
            return false;
        }
        return true;
    }

    private boolean makeRoomForOutgoingCallForDsda(Call call, boolean isEmergency) {
        if (isEmergency) {
            return true;
        }

        PhoneAccountHandle phAcc = call.getTargetPhoneAccount();
        if (phAcc == null) {
            if (getNumCallsWithState(LIVE_CALL_STATES) == MAXIMUM_DSDA_LIVE_CALLS
                    && getNumCallsWithState(CallState.ON_HOLD) == MAXIMUM_DSDA_HOLD_CALLS) {
                return false;
            } else {
                return true;
            }
        }
        if (hasMaximumLiveCalls(phAcc.getId())) {
            // NOTE: If the amount of live calls changes beyond 1, this logic will probably
            // have to change.
            Call liveCall = getFirstCallWithStateUsingSubId(phAcc.getId(), call, LIVE_CALL_STATES);

            if (hasMaximumHoldingCalls(phAcc.getId())) {
                // There is no more room for any more calls, unless it's an emergency.
                return false;  // No more room!
            }
            if (Objects.equals(liveCall.getTargetPhoneAccount(), call.getTargetPhoneAccount())) {
                return true;
            }
            // Try to hold the live call before attempting the new outgoing call.
            if (liveCall.can(Connection.CAPABILITY_HOLD)) {
                liveCall.hold();
                return true;
            }

            // The live call cannot be held so we're out of luck here.  There's no room.
            return false;
        }
        return true;
    }

    /**
     * Creates a new call for an existing connection.
     *
     * @param callId The id of the new call.
     * @param connection The connection information.
     * @return The new call.
     */
    Call createCallForExistingConnection(String callId, ParcelableConnection connection) {
        Call call = new Call(
                mContext,
                mConnectionServiceRepository,
                connection.getHandle() /* handle */,
                null /* gatewayInfo */,
                null /* connectionManagerPhoneAccount */,
                connection.getPhoneAccount(), /* targetPhoneAccountHandle */
                false /* isIncoming */,
                false /* isConference */);

        int callState = Call.getStateFromConnectionState(connection.getState());
        Call existingCall = getFirstCallWithStateUsingSubId(
                connection.getPhoneAccount().getId(), callState);
        setCallState(call, callState);
        Log.i(this, "createCallForExistingConnection existingCall = "
                + existingCall + " new call = " + call);
        call.setConnectionCapabilities(connection.getConnectionCapabilities());
        call.setCallerDisplayName(connection.getCallerDisplayName(),
                connection.getCallerDisplayNamePresentation());

        call.addListener(this);
        addCall(call);

        return call;
    }

    /**
     * Dumps the state of the {@link CallsManager}.
     *
     * @param pw The {@code IndentingPrintWriter} to write the state to.
     */
    public void dump(IndentingPrintWriter pw) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DUMP, TAG);
        if (mCalls != null) {
            pw.println("mCalls: ");
            pw.increaseIndent();
            for (Call call : mCalls) {
                pw.println(call);
            }
            pw.decreaseIndent();
        }
        pw.println("mForegroundCall: " + (mForegroundCall == null ? "none" : mForegroundCall));

        if (mCallAudioManager != null) {
            pw.println("mCallAudioManager:");
            pw.increaseIndent();
            mCallAudioManager.dump(pw);
            pw.decreaseIndent();
        }

        if (mTtyManager != null) {
            pw.println("mTtyManager:");
            pw.increaseIndent();
            mTtyManager.dump(pw);
            pw.decreaseIndent();
        }

        if (mInCallController != null) {
            pw.println("mInCallController:");
            pw.increaseIndent();
            mInCallController.dump(pw);
            pw.decreaseIndent();
        }

        if (mConnectionServiceRepository != null) {
            pw.println("mConnectionServiceRepository:");
            pw.increaseIndent();
            mConnectionServiceRepository.dump(pw);
            pw.decreaseIndent();
        }
    }

    private final Handler mHandler = new LchHandler();
    private final class LchHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case PHONE_START_MSIM_INCALL_TONE:
                    Log.d(this, "PHONE_START_MSIM_INCALL_TONE...");
                    startMSimInCallTones();
                    break;
                case LCH_PLAY_DTMF:
                    playLchDtmf();
                    break;
                case LCH_STOP_DTMF:
                    stopLchDtmf();
                    break;
            }
        }
    }

    public void switchToOtherActiveSub(String subId, boolean retainLch) {
        if (TelephonyManager.getDefault().getMultiSimConfiguration()
                != TelephonyManager.MultiSimVariants.DSDA) {
            return;
        }
        Log.i(this, "switchToOtherActiveSub sub:" + subId
                + " retainLch:" + retainLch);
        setActiveSubscription(subId);
        updateLchStatus(subId);
        manageMSimInCallTones(true);
        updateForegroundCall();
    }

    public void setActiveSubscription(String subId) {
        Log.i(this, "setActiveSubscription = " + subId);
        if (TelephonyManager.getDefault().getMultiSimConfiguration()
                != TelephonyManager.MultiSimVariants.DSDA) {
            return;
        }
        boolean changed = false;
        for (PhoneAccountHandle ph : getPhoneAccountRegistrar().getCallCapablePhoneAccounts()) {
            PhoneAccount phAcc = getPhoneAccountRegistrar().getPhoneAccount(ph);
            if (subId != null && subId.equals(ph.getId())
                    && !phAcc.isSet(PhoneAccount.ACTIVE)) {
                changed = true;
                phAcc.setBit(PhoneAccount.ACTIVE);
            } else if (subId != null && !subId.equals(ph.getId())
                        && phAcc.isSet(PhoneAccount.ACTIVE)) {
                changed = true;
                phAcc.unSetBit(PhoneAccount.ACTIVE);
            } else if (subId == null && phAcc.isSet(PhoneAccount.ACTIVE)) {
                phAcc.unSetBit(PhoneAccount.ACTIVE);
            }
        }
        if (!changed) {
            Log.i(this, "setActiveSubscription not changed ");
            return;
        } else {
            Log.i(this, "setActiveSubscription changed " );
            for (Call call : mCalls) {
                PhoneAccountHandle ph = call.getTargetPhoneAccount();
                if (ph != null) {
                    call.mIsActiveSub = ph.getId().equals(subId) ? true : false;
                }
                for (CallsManagerListener listener : mListeners) {
                    listener.onCallStateChanged(call, call.getState(), call.getState());
                }
            }
            Call call = getFirstCallWithStateUsingSubId(subId, CallState.RINGING,
                    CallState.DIALING, CallState.ACTIVE, CallState.ON_HOLD);
            if (call != null) {
                call.setActiveSubscription();
            }
        }
    }

    public String getActiveSubscription() {
        for (PhoneAccountHandle ph : getPhoneAccountRegistrar().getCallCapablePhoneAccounts()) {
            if (getPhoneAccountRegistrar()
                    .getPhoneAccount(ph).isSet(PhoneAccount.ACTIVE)) {
                Log.d(this, "getActiveSubscription: " + ph.getId());
                return ph.getId();
            }
        }
        return null;
    }

    private String getConversationSub() {
        for (PhoneAccountHandle ph : getPhoneAccountRegistrar().getCallCapablePhoneAccounts()) {
            if (!getPhoneAccountRegistrar().getPhoneAccount(ph).isSet(PhoneAccount.LCH) &&
                    (getFirstCallWithStateUsingSubId(ph.getId(), CallState.ACTIVE, CallState.DIALING,
                        CallState.ON_HOLD) != null)) {
                Log.d(this, "getConversationSub: " + ph.getId());
                return ph.getId();
            }
        }
        return null;
    }

    void manageMSimInCallTones(boolean isSubSwitch) {
        Log.i(this, " entered manageMSimInCallTones ");

        // If there is no background active subscription available, stop playing the tones.
        // Do not start/stop LCH/SCH tones when phone is in RINGING state.
        if (getLchSub() != null && !hasRingingCall()) {
            //If sub switch happens re-start the tones with a delay of 100msec.
            if (isSubSwitch) {
                Log.i(this, " manageMSimInCallTones: re-start playing tones, lch sub = "
                        + getLchSub());
                reStartMSimInCallTones();
            } else {
                Log.i(this, " entered manageMSimInCallTones ");
                startMSimInCallTones();
            }
        } else if (getLchSub() == null) {
            // if there is no sub in Lch state, then stop playing the tones
            stopMSimInCallTones();
        }
    }

    private void reStartMSimInCallTones() {
        Log.i(this, " reStartMSimInCallTones");
        stopMSimInCallTones();
        /* Remove any pending PHONE_START_MSIM_INCALL_TONE messages from queue */
        mHandler.removeMessages(PHONE_START_MSIM_INCALL_TONE);
        Message message = Message.obtain(mHandler, PHONE_START_MSIM_INCALL_TONE);
        mHandler.sendMessageDelayed(message, 100);
    }

    /**
     * Returns the first call that it finds with the given states for given subscription.
     * The states are treated as having priority order so that any call with the first
     * state will be returned before any call with states listed later in the parameter list.
     */
    Call getFirstCallWithStateUsingSubId(String sub, int... states) {
        for (int currentState : states) {
            // check the foreground first
            if (mForegroundCall != null && mForegroundCall.getState() == currentState
                    && (mForegroundCall.getTargetPhoneAccount() != null)
                    && isSameIdOrSipId(mForegroundCall.getTargetPhoneAccount().getId(),
                    sub)) {
                return mForegroundCall;
            }

            for (Call call : mCalls) {
                if ((currentState == call.getState()) &&
                        (call.getTargetPhoneAccount() != null) &&
                        (isSameIdOrSipId(call.getTargetPhoneAccount().getId(), sub))) {
                    return call;
                }
            }
        }
        return null;
    }

    private String getLchSub() {
        for (PhoneAccountHandle ph : getPhoneAccountRegistrar().getCallCapablePhoneAccounts()) {
            if (getPhoneAccountRegistrar().getPhoneAccount(ph).isSet(PhoneAccount.LCH)) {
                return ph.getId();
            }
        }
        return null;
    }

    private void playLchDtmf() {
        if (mLchSub != null || mHandler.hasMessages(LCH_PLAY_DTMF)) {
            // Ignore any redundant requests to start playing tones
            return;
        }
        mLchSub = getLchSub();
        Log.i(this, " playLchDtmf... lch sub " + mLchSub);
        if (mLchSub == null) return;
        removeAnyPendingDtmfMsgs();
        char c = mContext.getResources()
                .getString(R.string.Lch_dtmf_key).charAt(0);
        Call call = getNonRingingLiveCall(mLchSub);
        if (call == null) {
            mLchSub = null;
            return;
        }
        call.playDtmfTone(c);
        // Keep playing LCH DTMF tone to remote party on LCH call, with periodicity
        // "LCH_DTMF_PERIODICITY" until call moves out of LCH.
        mHandler.sendMessageDelayed(Message.obtain(mHandler, LCH_PLAY_DTMF), LCH_DTMF_PERIODICITY);
        mHandler.sendMessageDelayed(Message.obtain(mHandler, LCH_STOP_DTMF), LCH_DTMF_PERIOD);
    }

    private Call getNonRingingLiveCall(String subId) {
        return getFirstCallWithStateUsingSubId(subId, CallState.DIALING,
                CallState.ACTIVE, CallState.ON_HOLD);
    }

    private void stopLchDtmf() {
        if (mLchSub != null) {
            // Ignore any redundant requests to stop playing tones
            Call call = getNonRingingLiveCall(mLchSub);
            Log.i(this, " stopLchDtmf... call: " + call + " mLchSub:" + mLchSub);
            if (call == null) {
                mLchSub = null;
                return;
            }
            call.stopDtmfTone();
        }
        mLchSub = null;
    }

    private void startMSimInCallTones() {
        if (mLocalCallReminderTonePlayer == null) {
            Log.i(this, " Play local call hold reminder tone ");
            mLocalCallReminderTonePlayer =
                    mPlayerFactory.createPlayer(InCallTonePlayer.TONE_HOLD_RECALL);
            mLocalCallReminderTonePlayer.start();
        }
        if (sSupervisoryCallHoldToneConfig.equals("inband")) {
            // if "persist.radio.sch_tone" is set to "inband", play inband supervisory
            // call hold tone. if set to "dtmf", play the SCH tones
            // over DTMF, don't play SCH tones for anyother value.
            if (mSupervisoryCallHoldTonePlayer == null) {
                Log.i(this, " startMSimInCallTones: Supervisory call hold tone ");
                mSupervisoryCallHoldTonePlayer =
                        mPlayerFactory.createPlayer(InCallTonePlayer.TONE_SUPERVISORY_CH);
                mSupervisoryCallHoldTonePlayer.start();
            }
        } else if (sSupervisoryCallHoldToneConfig.equals("dtmf")) {
            Log.i(this, " startMSimInCallTones: Supervisory call hold tone over dtmf ");
            playLchDtmf();
        }
    }

    private void removeAnyPendingDtmfMsgs() {
        mHandler.removeMessages(LCH_PLAY_DTMF);
        mHandler.removeMessages(LCH_STOP_DTMF);
    }

    protected void stopMSimInCallTones() {
        if (mLocalCallReminderTonePlayer != null) {
            Log.i(this, " stopMSimInCallTones: local call hold reminder tone ");
            mLocalCallReminderTonePlayer.stopTone();
            mLocalCallReminderTonePlayer = null;
        }
        if (mSupervisoryCallHoldTonePlayer != null) {
            Log.i(this, " stopMSimInCallTones: Supervisory call hold tone ");
            mSupervisoryCallHoldTonePlayer.stopTone();
            mSupervisoryCallHoldTonePlayer = null;
        }
        if (sSupervisoryCallHoldToneConfig.equals("dtmf")) {
            Log.i(this, " stopMSimInCallTones: stop SCH Dtmf call hold tone ");
            stopLchDtmf();
            /* Remove any previous dtmf nssages from queue */
            removeAnyPendingDtmfMsgs();
        }
    }

    /**
     * Update the local call hold state for all subscriptions
     * 1 -- if call on local hold, 0 -- if call is not on local hold
     *
     * @param subInConversation is the sub user is currently active in subsription.
     * so if this sub is in LCH, then bring that sub out of LCH.
     */
    private void updateLchStatus(String subInConversation) {
        Log.i(this, "updateLchStatus subInConversation: " + subInConversation);
        if (subInConversation != null && subInConversation.contains("sip")) {
            return;
        }
        for (PhoneAccountHandle ph : getPhoneAccountRegistrar().getCallCapablePhoneAccounts()) {
            String sub = ph.getId();
            if (sub != null && sub.contains("sip")) {
                Log.d(this, "update lch. Skipping account: " + sub);
                continue;
            }
            PhoneAccount phAcc = getPhoneAccountRegistrar().getPhoneAccount(ph);
            boolean lchState = false;
            if (subInConversation != null && hasActiveOrHoldingCall(sub) &&
                    !sub.equals(subInConversation)) {
                // if sub is not conversation  sub and if it has an active
                // voice call then update lchStatus as Active
                lchState = true;
            }
            // Update state only if the new state is different
            if (lchState != phAcc.isSet(PhoneAccount.LCH)) {
                Call call = getNonRingingLiveCall(sub);
                Log.i(this, " setLocal Call Hold to  = " + lchState + " sub:" + sub);

                if (call != null) {
                    if (call.getChildCalls().size() > 1) {
                        Call child = call.getChildCalls().get(0);
                        child.setLocalCallHold(lchState ? 1 : 0);
                    } else {
                        call.setLocalCallHold(lchState ? 1 : 0);
                    }
                }
                if (lchState) {
                    phAcc.setBit(PhoneAccount.LCH);
                } else {
                    phAcc.unSetBit(PhoneAccount.LCH);
                }
            }
        }
    }

    protected boolean isCallBlacklisted(Call c) {
        final String number = c.getNumber();
        // See if the number is in the blacklist
        // Result is one of: MATCH_NONE, MATCH_LIST or MATCH_REGEX
        int listType = BlacklistUtils.isListed(mContext, number, BlacklistUtils.BLOCK_CALLS);
        if (listType != BlacklistUtils.MATCH_NONE) {
            // We have a match, set the user and hang up the call and notify
            Log.d(this, "Incoming call from " + number + " blocked.");
            mBlacklistCallNotifier.notifyBlacklistedCall(number,
                    c.getCreationTimeMillis(), listType);
            return true;
        }
        return false;
    }
}
