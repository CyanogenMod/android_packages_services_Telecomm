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
import android.telecomm.Conference;
import android.telecomm.Connection;
import android.telecomm.ConnectionRequest;
import android.telecomm.ConnectionService;
import android.telecomm.PhoneAccountHandle;
import android.telecomm.RemoteConference;
import android.telecomm.RemoteConnection;
import android.telecomm.StatusHints;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service which acts as a fake ConnectionManager if so configured.
 * TODO(santoscordon): Rename all classes in the directory to Dummy* (e.g., DummyConnectionService).
 */
public class TestConnectionManager extends ConnectionService {
    public final class TestManagedConnection extends Connection {
        private final RemoteConnection.Listener mRemoteListener = new RemoteConnection.Listener() {
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
            public void onDestroyed(RemoteConnection connection) {
                destroy();
                mManagedConnectionByRemote.remove(mRemote);
            }

            @Override
            public void onConferenceableConnectionsChanged(
                    RemoteConnection connect,
                    List<RemoteConnection> conferenceable) {
                List<Connection> c = new ArrayList<>();
                for (RemoteConnection remote : conferenceable) {
                    if (mManagedConnectionByRemote.containsKey(remote)) {
                        c.add(mManagedConnectionByRemote.get(remote));
                    }
                }
                setConferenceableConnections(c);
            }
        };

        private final RemoteConnection mRemote;
        private final boolean mIsIncoming;

        TestManagedConnection(RemoteConnection remote, boolean isIncoming) {
            mRemote = remote;
            mIsIncoming = isIncoming;
            mRemote.addListener(mRemoteListener);
            setState(mRemote.getState());
        }

        @Override
        public void onAbort() {
            mRemote.abort();
        }

        /** ${inheritDoc} */
        @Override
        public void onAnswer(int videoState) {
            mRemote.answer(videoState);
        }

        /** ${inheritDoc} */
        @Override
        public void onDisconnect() {
            mRemote.disconnect();
        }

        @Override
        public void onPlayDtmfTone(char c) {
            mRemote.playDtmfTone(c);
        }

        /** ${inheritDoc} */
        @Override
        public void onHold() {
            mRemote.hold();
        }

        /** ${inheritDoc} */
        @Override
        public void onReject() {
            mRemote.reject();
        }

        /** ${inheritDoc} */
        @Override
        public void onUnhold() {
            mRemote.unhold();
        }

        @Override
        public void onSetAudioState(AudioState state) {
            mRemote.setAudioState(state);
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

    public final class TestManagedConference extends Conference {
        private final RemoteConference.Callback mRemoteCallback = new RemoteConference.Callback() {
            @Override
            public void onStateChanged(RemoteConference conference, int oldState, int newState) {
                switch (newState) {
                    case Connection.STATE_DISCONNECTED:
                        // See onDisconnected below
                        break;
                    case Connection.STATE_HOLDING:
                        setOnHold();
                        break;
                    case Connection.STATE_ACTIVE:
                        setActive();
                        break;
                    default:
                        log("unrecognized state for Conference: " + newState);
                        break;
                }
            }

            @Override
            public void onDisconnected(RemoteConference conference, int cause, String message) {
                setDisconnected(cause, message);
            }

            @Override
            public void onConnectionAdded(
                    RemoteConference conference,
                    RemoteConnection connection) {
                TestManagedConnection c = mManagedConnectionByRemote.get(connection);
                if (c == null) {
                    log("onConnectionAdded cannot find remote connection: " + connection);
                } else {
                    addConnection(c);
                }
            }

            @Override
            public void onConnectionRemoved(
                    RemoteConference conference,
                    RemoteConnection connection) {
                TestManagedConnection c = mManagedConnectionByRemote.get(connection);
                if (c == null) {
                    log("onConnectionRemoved cannot find remote connection: " + connection);
                } else {
                    removeConnection(c);
                }
            }

            @Override
            public void onCapabilitiesChanged(RemoteConference conference, int capabilities) {
                setCapabilities(capabilities);
            }

            @Override
            public void onDestroyed(RemoteConference conference) {
                destroy();
                mRemote.removeCallback(mRemoteCallback);
                mManagedConferenceByRemote.remove(mRemote);
            }
        };

        private final RemoteConference mRemote;

        public TestManagedConference(RemoteConference remote) {
            super(null);
            mRemote = remote;
            remote.addCallback(mRemoteCallback);
            setActive();
            for (RemoteConnection r : remote.getConnections()) {
                TestManagedConnection c = mManagedConnectionByRemote.get(r);
                if (c != null) {
                    addConnection(c);
                }
            }
        }
    }

    private static void log(String msg) {
        Log.w("telecomtestcs", "[TestConnectionManager] " + msg);
    }

    private final Map<RemoteConference, TestManagedConference> mManagedConferenceByRemote
            = new HashMap<>();
    private final Map<RemoteConnection, TestManagedConnection> mManagedConnectionByRemote
            = new HashMap<>();

    @Override
    public Connection onCreateOutgoingConnection(
            PhoneAccountHandle connectionManagerAccount,
            final ConnectionRequest request) {
        return makeConnection(request, false);
    }

    @Override
    public Connection onCreateIncomingConnection(
            PhoneAccountHandle connectionManagerAccount,
            final ConnectionRequest request) {
        return makeConnection(request, true);
    }

    @Override
    public void onConference(Connection a, Connection b) {
        conferenceRemoteConnections(
                ((TestManagedConnection) a).mRemote,
                ((TestManagedConnection) b).mRemote);
    }

    @Override
    public void onRemoteConferenceAdded(RemoteConference remoteConference) {
        addConference(new TestManagedConference(remoteConference));
    }

    private Connection makeConnection(ConnectionRequest request, boolean incoming) {
        RemoteConnection remote = incoming
                ? createRemoteIncomingConnection(request.getAccountHandle(), request)
                : createRemoteOutgoingConnection(request.getAccountHandle(), request);
        TestManagedConnection local = new TestManagedConnection(remote, false);
        mManagedConnectionByRemote.put(remote, local);
        return local;
    }
}
