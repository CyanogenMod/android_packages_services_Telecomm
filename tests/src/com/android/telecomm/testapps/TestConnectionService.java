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
 Ca* See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.telecomm.testapps;

import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.telecomm.CallAudioState;
import android.telecomm.Connection;
import android.telecomm.ConnectionRequest;
import android.telecomm.ConnectionService;
import android.telecomm.PhoneAccount;
import android.telecomm.RemoteConnection;
import android.telecomm.Response;
import android.telecomm.SimpleResponse;
import android.telecomm.StatusHints;
import android.telephony.DisconnectCause;
import android.text.TextUtils;
import android.util.Log;

import com.android.telecomm.tests.R;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Service which provides fake calls to test the ConnectionService interface.
 * TODO(santoscordon): Rename all classes in the directory to Dummy* (e.g., DummyConnectionService).
 */
public class TestConnectionService extends ConnectionService {
    public static final String EXTRA_GATEWAY_PROVIDER_PACKAGE =
            "com.android.phone.extra.GATEWAY_PROVIDER_PACKAGE";
    public static final String EXTRA_GATEWAY_ORIGINAL_URI =
            "com.android.phone.extra.GATEWAY_ORIGINAL_URI";

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
            public void onPostDialWait(RemoteConnection connection, String remainingDigits) {
                // TODO(santoscordon): Method needs to be exposed on Connection.java
            }

            @Override
            public void onFeaturesChanged(RemoteConnection connection, int features) {
                setFeatures(features);
            }

            @Override
            public void onSetAudioModeIsVoip(RemoteConnection connection, boolean isVoip) {
                setAudioModeIsVoip(isVoip);
            }

            @Override
            public void onSetStatusHints(RemoteConnection connection, StatusHints statusHints) {
                setStatusHints(statusHints);
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
        protected void onAnswer() {
            if (mRemoteConnection != null) {
                mRemoteConnection.answer();
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

    private class CallAttempter implements OutgoingCallResponse<RemoteConnection> {
        private final Iterator<PhoneAccount> mAccountIterator;
        private final OutgoingCallResponse<Connection> mCallback;
        private final ConnectionRequest mOriginalRequest;

        CallAttempter(
                Iterator<PhoneAccount> iterator,
                OutgoingCallResponse<Connection> callback,
                ConnectionRequest originalRequest) {
            mAccountIterator = iterator;
            mCallback = callback;
            mOriginalRequest = originalRequest;
        }

        @Override
        public void onSuccess(ConnectionRequest request, RemoteConnection remoteConnection) {
            if (remoteConnection != null) {
                TestConnection connection = new TestConnection(
                        remoteConnection, Connection.State.DIALING);
                mCalls.add(connection);
                mCallback.onSuccess(mOriginalRequest, connection);
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
        }

        public void tryNextAccount() {
            if (mAccountIterator.hasNext()) {
                ConnectionRequest connectionRequest = new ConnectionRequest(
                        mAccountIterator.next(),
                        mOriginalRequest.getCallId(),
                        mOriginalRequest.getHandle(),
                        null,
                        mOriginalRequest.getVideoState());
                createRemoteOutgoingConnection(connectionRequest, this);
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
        if (mCalls.isEmpty() && mMediaPlayer.isPlaying()) {
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
    public void onCreateConnections(
            final ConnectionRequest originalRequest,
            final OutgoingCallResponse<Connection> callback) {

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

        // Normally we would use the original request as is, but for testing purposes, we are adding
        // ".." to the end of the number to follow its path more easily through the logs.
        final ConnectionRequest request = new ConnectionRequest(
                originalRequest.getCallId(),
                Uri.fromParts(handle.getScheme(), handle.getSchemeSpecificPart() + "..", ""),
                originalRequest.getExtras(), originalRequest.getVideoState());

        // If the number starts with 555, then we handle it ourselves. If not, then we
        // use a remote connection service.
        // TODO(santoscordon): Have a special phone number to test the account-picker dialog flow.
        if (number.startsWith("555")) {
            TestConnection connection = new TestConnection(null, Connection.State.DIALING);
            mCalls.add(connection);
            callback.onSuccess(request, connection);
            connection.startOutgoing();
        } else {
            log("looking up accounts");
            lookupRemoteAccounts(handle, new SimpleResponse<Uri, List<PhoneAccount>>() {
                @Override
                public void onResult(Uri handle, final List<PhoneAccount> accounts) {
                    log("starting the call attempter with accounts: " + accounts);
                    new CallAttempter(accounts.iterator(), callback, request)
                            .tryNextAccount();
                }

                @Override
                public void onError(Uri handle) {
                    log("remote account lookup failed.");
                    callback.onFailure(request, 0, null);
                }
            });
        }
    }

    /** ${inheritDoc} */
    @Override
    public void onCreateIncomingConnection(
            ConnectionRequest request, Response<ConnectionRequest, Connection> callback) {

        // Use dummy number for testing incoming calls.
        Uri handle = Uri.fromParts(SCHEME_TEL, "5551234", null);

        TestConnection connection = new TestConnection(null, Connection.State.DIALING);
        mCalls.add(connection);
        callback.onResult(
                new ConnectionRequest(request.getCallId(), handle, request.getExtras(),
                        request.getVideoState()),
                connection);
    }
}
