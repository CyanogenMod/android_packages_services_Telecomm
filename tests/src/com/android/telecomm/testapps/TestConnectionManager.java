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
import android.net.Uri;
import android.telecomm.AudioState;
import android.telecomm.Connection;
import android.telecomm.ConnectionRequest;
import android.telecomm.ConnectionService;
import android.telecomm.PhoneAccountHandle;
import android.telecomm.RemoteConnection;
import android.telecomm.StatusHints;
import android.util.Log;

import java.util.Random;

/**
 * Service which acts as a fake ConnectionManager if so configured.
 * TODO(santoscordon): Rename all classes in the directory to Dummy* (e.g., DummyConnectionService).
 */
public class TestConnectionManager extends ConnectionService {
    /**
     * Random number generator used to generate phone numbers.
     */
    private Random mRandom = new Random();

    private final class TestManagedConnection extends Connection {
        private final RemoteConnection.Listener mProxyListener = new RemoteConnection.Listener() {
            @Override
            public void onStateChanged(RemoteConnection connection, int state) {
                setState(state);
            }

            @Override
            public void onDisconnected(RemoteConnection connection, int cause, String message) {
                setDisconnected(cause, message);
                destroy();
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
                destroy();
            }
        };

        private final RemoteConnection mRemoteConnection;
        private final boolean mIsIncoming;

        TestManagedConnection(RemoteConnection remoteConnection, boolean isIncoming) {
            mRemoteConnection = remoteConnection;
            mIsIncoming = isIncoming;
            mRemoteConnection.addListener(mProxyListener);
            setState(mRemoteConnection.getState());
        }

        @Override
        public void onAbort() {
            mRemoteConnection.abort();
        }

        /** ${inheritDoc} */
        @Override
        public void onAnswer(int videoState) {
            mRemoteConnection.answer(videoState);
        }

        /** ${inheritDoc} */
        @Override
        public void onDisconnect() {
            mRemoteConnection.disconnect();
        }

        @Override
        public void onPlayDtmfTone(char c) {
            mRemoteConnection.playDtmfTone(c);
        }

        /** ${inheritDoc} */
        @Override
        public void onHold() {
            mRemoteConnection.hold();
        }

        /** ${inheritDoc} */
        @Override
        public void onReject() {
            mRemoteConnection.reject();
        }

        /** ${inheritDoc} */
        @Override
        public void onUnhold() {
            mRemoteConnection.unhold();
        }

        @Override
        public void onSetAudioState(AudioState state) {
            mRemoteConnection.setAudioState(state);
        }

        private void setState(int state) {
            log("setState: " + state);
            switch (state) {
                case STATE_ACTIVE:
                    setActive();
                    break;
                case STATE_HOLDING:
                    setOnHold();
                    break;
                case STATE_DIALING:
                    setDialing();
                    break;
                case STATE_RINGING:
                    setRinging();
                    break;
            }
        }
    }

    private static void log(String msg) {
        Log.w("telecomtestcs", "[TestConnectionManager] " + msg);
    }

    @Override
    public Connection onCreateOutgoingConnection(
            PhoneAccountHandle connectionManagerAccount,
            final ConnectionRequest request) {
        return new TestManagedConnection(
                createRemoteOutgoingConnection(
                        request.getAccountHandle(),
                        request),
                false);
    }

    @Override
    public Connection onCreateIncomingConnection(
            PhoneAccountHandle connectionManagerAccount,
            final ConnectionRequest request) {
        return new TestManagedConnection(
                createRemoteIncomingConnection(
                        request.getAccountHandle(),
                        request),
                true);
    }
}
