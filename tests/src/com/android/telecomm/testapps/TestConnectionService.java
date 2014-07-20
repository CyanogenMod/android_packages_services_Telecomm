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

package com.android.telecomm.testapps;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.telecomm.CallAudioState;
import android.telecomm.Connection;
import android.telecomm.ConnectionRequest;
import android.telecomm.ConnectionService;
import android.telecomm.PhoneAccountHandle;
import android.telecomm.RemoteConnection;
import android.telecomm.SimpleResponse;
import android.telecomm.StatusHints;
import android.telecomm.VideoCallProfile;
import android.telephony.DisconnectCause;
import android.util.Log;

import com.android.telecomm.tests.R;

import java.lang.String;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Service which provides fake calls to test the ConnectionService interface.
 * TODO(santoscordon): Rename all classes in the directory to Dummy* (e.g., DummyConnectionService).
 */
public class TestConnectionService extends ConnectionService {
    public static final String EXTRA_GATEWAY_PROVIDER_PACKAGE =
            "com.android.phone.extra.GATEWAY_PROVIDER_PACKAGE";
    public static final String EXTRA_GATEWAY_ORIGINAL_URI =
            "com.android.phone.extra.GATEWAY_ORIGINAL_URI";

    /**
     * Intent extra used to pass along whether a call is video or audio based on the user's choice
     * in the notification.
     */
    public static final String IS_VIDEO_CALL = "IsVideoCall";

    /**
     * Random number generator used to generate phone numbers.
     */
    private Random mRandom = new Random();

    private final class TestConnection extends Connection {
        private final RemoteConnection.Listener mProxyListener = new RemoteConnection.Listener() {
            @Override
            public void onStateChanged(RemoteConnection connection, int state) {
                setState(state);
            }

            @Override
            public void onDisconnected(RemoteConnection connection, int cause, String message) {
                setDisconnected(cause, message);
                destroyCall(TestConnection.this);
                setDestroyed();
            }

            @Override
            public void onRequestingRingback(RemoteConnection connection, boolean ringback) {
                setRequestingRingback(ringback);
            }

            @Override
            public void onCallCapabilitiesChanged(RemoteConnection connection,
                    int callCapabilities) {
                setCallCapabilities(callCapabilities);
            }

            @Override
            public void onPostDialWait(RemoteConnection connection, String remainingDigits) {
                setPostDialWait(remainingDigits);
            }

            @Override
            public void onAudioModeIsVoipChanged(RemoteConnection connection, boolean isVoip) {
                setAudioModeIsVoip(isVoip);
            }

            @Override
            public void onStatusHintsChanged(RemoteConnection connection, StatusHints statusHints) {
                setStatusHints(statusHints);
            }

            @Override
            public void onVideoStateChanged(RemoteConnection connection, int videoState) {
                setVideoState(videoState);
            }

            @Override
            public void onHandleChanged(RemoteConnection connection, Uri handle, int presentation) {
                setHandle(handle, presentation);
            }

            @Override
            public void onCallerDisplayNameChanged(
                    RemoteConnection connection, String callerDisplayName, int presentation) {
                setCallerDisplayName(callerDisplayName, presentation);
            }

            @Override
            public void onStartActivityFromInCall(
                    RemoteConnection connection, PendingIntent intent) {
                startActivityFromInCall(intent);
            }

            @Override
            public void onDestroyed(RemoteConnection connection) {
                setDestroyed();
            }
        };

        private final RemoteConnection mRemoteConnection;

        TestConnection(RemoteConnection remoteConnection, int initialState) {
            mRemoteConnection = remoteConnection;
            if (mRemoteConnection != null) {
                mRemoteConnection.addListener(mProxyListener);
            } else {
                setState(initialState);
            }
        }

        void startOutgoing() {
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    TestConnection.this.setActive();
                    activateCall(TestConnection.this);
                }
            }, 4000);
        }

        boolean isProxy() {
            return mRemoteConnection != null;
        }

        /** ${inheritDoc} */
        @Override
        protected void onAbort() {
            if (mRemoteConnection != null) {
                mRemoteConnection.disconnect();
                mRemoteConnection.removeListener(mProxyListener);
            } else {
                destroyCall(this);
                setDestroyed();
            }
        }

        /** ${inheritDoc} */
        @Override
        protected void onAnswer(int videoState) {
            if (mRemoteConnection != null) {
                mRemoteConnection.answer(videoState);
            } else {
                activateCall(this);
                setActive();
            }
        }

        /** ${inheritDoc} */
        @Override
        protected void onDisconnect() {
            if (mRemoteConnection != null) {
                mRemoteConnection.disconnect();
            } else {
                setDisconnected(DisconnectCause.LOCAL, null);
                destroyCall(this);
                setDestroyed();
            }
        }

        /** ${inheritDoc} */
        @Override
        protected void onHold() {
            if (mRemoteConnection != null) {
                mRemoteConnection.hold();
            } else {
                setOnHold();
            }
        }

        /** ${inheritDoc} */
        @Override
        protected void onReject() {
            if (mRemoteConnection != null) {
                mRemoteConnection.reject();
            } else {
                setDisconnected(DisconnectCause.INCOMING_REJECTED, null);
                destroyCall(this);
                setDestroyed();
            }
        }

        /** ${inheritDoc} */
        @Override
        protected void onUnhold() {
            if (mRemoteConnection != null) {
                mRemoteConnection.hold();
            } else {
                setActive();
            }
        }

        @Override
        protected void onSetAudioState(CallAudioState state) {
            if (mRemoteConnection != null) {
                mRemoteConnection.setAudioState(state);
            }
        }

        private void setState(int state) {
            log("setState: " + state);
            switch (state) {
                case Connection.State.ACTIVE:
                    setActive();
                    break;
                case Connection.State.HOLDING:
                    setOnHold();
                    break;
                case Connection.State.DIALING:
                    setDialing();
                    break;
                case Connection.State.RINGING:
                    setRinging();
                    break;
            }
        }
    }

    private class CallAttempter implements CreateConnectionResponse<RemoteConnection> {
        private final Iterator<PhoneAccountHandle> mAccountIterator;
        private final CreateConnectionResponse<Connection> mCallback;
        private final ConnectionRequest mOriginalRequest;
        private final boolean mIsIncoming;

        CallAttempter(
                Iterator<PhoneAccountHandle> iterator,
                CreateConnectionResponse<Connection> callback,
                ConnectionRequest originalRequest,
                boolean isIncoming) {
            mAccountIterator = iterator;
            mCallback = callback;
            mOriginalRequest = originalRequest;
            mIsIncoming = isIncoming;
        }

        @Override
        public void onSuccess(ConnectionRequest request, RemoteConnection remoteConnection) {
            if (remoteConnection != null) {
                TestConnection connection = new TestConnection(remoteConnection,
                        mIsIncoming ? Connection.State.RINGING: Connection.State.DIALING);

                mCalls.add(connection);

                ConnectionRequest remoteRequest = new ConnectionRequest(
                        request.getAccountHandle(),
                        mOriginalRequest.getCallId(),
                        request.getHandle(),
                        request.getHandlePresentation(),
                        request.getExtras(),
                        request.getVideoState());
                mCallback.onSuccess(remoteRequest, connection);
            } else {
                tryNextAccount();
            }
        }

        @Override
        public void onFailure(ConnectionRequest request, int code, String msg) {
            tryNextAccount();
        }

        @Override
        public void onCancel(ConnectionRequest request) {
            mCallback.onCancel(mOriginalRequest);
        }

        public void tryNextAccount() {
            if (mAccountIterator.hasNext()) {
                ConnectionRequest connectionRequest = new ConnectionRequest(
                        mAccountIterator.next(),
                        mOriginalRequest.getCallId(),
                        mOriginalRequest.getHandle(),
                        mOriginalRequest.getHandlePresentation(),
                        null,
                        mOriginalRequest.getVideoState());
                if (mIsIncoming) {
                    createRemoteIncomingConnection(connectionRequest, this);
                } else {
                    createRemoteOutgoingConnection(connectionRequest, this);
                }
            } else {
                mCallback.onFailure(mOriginalRequest, 0, null);
            }
        }
    }

    private static final String SCHEME_TEL = "tel";

    private final List<TestConnection> mCalls = new ArrayList<>();
    private final Handler mHandler = new Handler();

    /** Used to play an audio tone during a call. */
    private MediaPlayer mMediaPlayer;

    /** {@inheritDoc} */
    @Override
    public boolean onUnbind(Intent intent) {
        log("onUnbind");
        mMediaPlayer = null;
        return super.onUnbind(intent);
    }

    private void activateCall(TestConnection connection) {
        if (!connection.isProxy()) {
            if (mMediaPlayer == null) {
                mMediaPlayer = createMediaPlayer();
            }
            if (!mMediaPlayer.isPlaying()) {
                mMediaPlayer.start();
            }
        }
    }

    private void destroyCall(TestConnection connection) {
        mCalls.remove(connection);

        // Stops audio if there are no more calls.
        if (mCalls.isEmpty() && mMediaPlayer != null && mMediaPlayer.isPlaying()) {
            mMediaPlayer.stop();
            mMediaPlayer.release();
            mMediaPlayer = createMediaPlayer();
        }
    }

    private MediaPlayer createMediaPlayer() {
        // Prepare the media player to play a tone when there is a call.
        MediaPlayer mediaPlayer = MediaPlayer.create(getApplicationContext(), R.raw.beep_boop);
        mediaPlayer.setLooping(true);
        return mediaPlayer;
    }

    private static void log(String msg) {
        Log.w("telecomtestcs", "[TestConnectionService] " + msg);
    }

    /** ${inheritDoc} */
    @Override
    public void onCreateOutgoingConnection(
            final ConnectionRequest originalRequest,
            final CreateConnectionResponse<Connection> callback) {

        final Uri handle = originalRequest.getHandle();
        String number = originalRequest.getHandle().getSchemeSpecificPart();
        log("call, number: " + number);

        // Crash on 555-DEAD to test call service crashing.
        if ("5550340".equals(number)) {
            throw new RuntimeException("Goodbye, cruel world.");
        }

        Bundle extras = originalRequest.getExtras();
        String gatewayPackage = extras.getString(EXTRA_GATEWAY_PROVIDER_PACKAGE);
        Uri originalHandle = extras.getParcelable(EXTRA_GATEWAY_ORIGINAL_URI);

        log("gateway package [" + gatewayPackage + "], original handle [" +
                originalHandle + "]");

        // If the number starts with 555, then we handle it ourselves. If not, then we
        // use a remote connection service.
        // TODO(santoscordon): Have a special phone number to test the account-picker dialog flow.
        if (number.startsWith("555")) {
            // Normally we would use the original request as is, but for testing purposes, we are
            // adding ".." to the end of the number to follow its path more easily through the logs.
            final ConnectionRequest request = new ConnectionRequest(
                    originalRequest.getAccountHandle(),
                    originalRequest.getCallId(),
                    Uri.fromParts(handle.getScheme(),
                    handle.getSchemeSpecificPart() + "..", ""),
                    originalRequest.getHandlePresentation(),
                    originalRequest.getExtras(),
                    originalRequest.getVideoState());

            TestConnection connection = new TestConnection(null, Connection.State.DIALING);
            mCalls.add(connection);

            callback.onSuccess(request, connection);
            connection.startOutgoing();
        } else {
            log("looking up accounts");
            lookupRemoteAccounts(handle, new SimpleResponse<Uri, List<PhoneAccountHandle>>() {
                @Override
                public void onResult(Uri handle, List<PhoneAccountHandle> accountHandles) {
                    log("starting the call attempter with accountHandles: " + accountHandles);
                    new CallAttempter(
                            accountHandles.iterator(), callback, originalRequest, false)
                            .tryNextAccount();
                }

                @Override
                public void onError(Uri handle) {
                    log("remote account lookup failed.");
                    callback.onFailure(originalRequest, 0, null);
                }
            });
        }
    }

    /** ${inheritDoc} */
    @Override
    public void onCreateIncomingConnection(
            final ConnectionRequest request, final CreateConnectionResponse<Connection> response) {
        PhoneAccountHandle accountHandle = request.getAccountHandle();
        ComponentName componentName = new ComponentName(this, TestConnectionService.class);
        if (accountHandle != null && componentName.equals(accountHandle.getComponentName())) {
            // Get the stashed intent extra that determines if this is a video call or audio call.
            Bundle extras = request.getExtras();
            boolean isVideoCall = extras.getBoolean(IS_VIDEO_CALL);

            // Use dummy number for testing incoming calls.
            Uri handle = Uri.fromParts(SCHEME_TEL, getDummyNumber(isVideoCall), null);

            TestConnection connection = new TestConnection(null, Connection.State.RINGING);
            if (isVideoCall) {
                connection.setCallVideoProvider(new TestCallVideoProvider(getApplicationContext()));
            }
            int videoState = isVideoCall ?
                    VideoCallProfile.VIDEO_STATE_BIDIRECTIONAL :
                    VideoCallProfile.VIDEO_STATE_AUDIO_ONLY;
            mCalls.add(connection);

            ConnectionRequest newRequest = new ConnectionRequest(
                    request.getAccountHandle(),
                    request.getCallId(),
                    handle,
                    request.getHandlePresentation(),
                    request.getExtras(),
                    videoState);
            response.onSuccess(newRequest, connection);
            connection.setVideoState(videoState);
        } else {
            SimpleResponse<Uri, List<PhoneAccountHandle>> accountResponse =
                    new SimpleResponse<Uri, List<PhoneAccountHandle>>() {
                            @Override
                            public void onResult(
                                    Uri handle, List<PhoneAccountHandle> accountHandles) {
                                log("attaching to incoming call with accountHandles: "
                                        + accountHandles);
                                new CallAttempter(accountHandles.iterator(), response, request,
                                        true /* isIncoming */)
                                        .tryNextAccount();
                            }

                            @Override
                            public void onError(Uri handle) {
                                log("remote accountHandle lookup failed.");
                                response.onFailure(request, 0, null);
                            }
            };
            lookupRemoteAccounts(request.getHandle(), accountResponse);
        }
    }

    /**
     * Generates a random phone number of format 555YXXX.  Where Y will be {@code 1} if the
     * phone number is for a video call and {@code 0} for an audio call.  XXX is a randomly
     * generated phone number.
     *
     * @param isVideo {@code True} if the call is a video call.
     * @return The phone number.
     */
    private String getDummyNumber(boolean isVideo) {
        int videoDigit = isVideo ? 1 : 0;
        int number = mRandom.nextInt(999);
        return String.format("555%s%03d", videoDigit, number);
    }
}
