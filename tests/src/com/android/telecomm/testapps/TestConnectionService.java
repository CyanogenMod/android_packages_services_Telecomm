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
import android.telecomm.AudioState;
import android.telecomm.Connection;
import android.telecomm.PhoneCapabilities;
import android.telecomm.PropertyPresentation;
import android.telecomm.ConnectionRequest;
import android.telecomm.ConnectionService;
import android.telecomm.PhoneAccountHandle;
import android.telecomm.RemoteConnection;
import android.telecomm.StatusHints;
import android.telecomm.VideoProfile;
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
 * TODO: Rename all classes in the directory to Dummy* (e.g., DummyConnectionService).
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

        private RemoteConnection mRemoteConnection;
        private final boolean mIsIncoming;

        /** Used to cleanup camera and media when done with connection. */
        private TestVideoProvider mTestVideoCallProvider;

        TestConnection(boolean isIncoming) {
            mIsIncoming = isIncoming;
        }

        void setRemoteConnection(RemoteConnection remoteConnection) {
            mRemoteConnection = remoteConnection;
            if (mRemoteConnection != null) {
                mRemoteConnection.addListener(mProxyListener);
                setState(mIsIncoming ? STATE_RINGING : STATE_DIALING);
            }
        }

        void startOutgoing() {
            setDialing();
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    setActive();
                    activateCall(TestConnection.this);
                }
            }, 4000);
        }

        boolean isProxy() {
            return mRemoteConnection != null;
        }

        /** ${inheritDoc} */
        @Override
        public void onAbort() {
            if (mRemoteConnection != null) {
                mRemoteConnection.disconnect();
                mRemoteConnection.removeListener(mProxyListener);
            } else {
                destroyCall(this);
                destroy();
            }
        }

        /** ${inheritDoc} */
        @Override
        public void onAnswer(int videoState) {
            if (mRemoteConnection != null) {
                mRemoteConnection.answer(videoState);
            } else {
                setVideoState(videoState);
                activateCall(this);
                setActive();
            }
        }

        /** ${inheritDoc} */
        @Override
        public void onPlayDtmfTone(char c) {
             if (mRemoteConnection != null) {
                mRemoteConnection.playDtmfTone(c);
            } else {
                if (c == '1') {
                    setDialing();
                }
            }
        }

        /** ${inheritDoc} */
        @Override
        public void onStopDtmfTone() {
             if (mRemoteConnection != null) {
                mRemoteConnection.stopDtmfTone();
            } else {
            }
        }

        /** ${inheritDoc} */
        @Override
        public void onDisconnect() {
            if (mRemoteConnection != null) {
                mRemoteConnection.disconnect();
            } else {
                setDisconnected(DisconnectCause.LOCAL, null);
                destroyCall(this);
                destroy();
            }
        }

        /** ${inheritDoc} */
        @Override
        public void onHold() {
            if (mRemoteConnection != null) {
                mRemoteConnection.hold();
            } else {
                setOnHold();
            }
        }

        /** ${inheritDoc} */
        @Override
        public void onReject() {
            if (mRemoteConnection != null) {
                mRemoteConnection.reject();
            } else {
                setDisconnected(DisconnectCause.INCOMING_REJECTED, null);
                destroyCall(this);
                destroy();
            }
        }

        /** ${inheritDoc} */
        @Override
        public void onUnhold() {
            if (mRemoteConnection != null) {
                mRemoteConnection.hold();
            } else {
                setActive();
            }
        }

        @Override
        public void onSetAudioState(AudioState state) {
            if (mRemoteConnection != null) {
                mRemoteConnection.setAudioState(state);
            }
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

        public void setTestVideoCallProvider(TestVideoProvider testVideoCallProvider) {
            mTestVideoCallProvider = testVideoCallProvider;
        }

        /**
         * Stops playback of test videos.
         */
        private void stopAndCleanupMedia() {
            if (mTestVideoCallProvider != null) {
                mTestVideoCallProvider.stopAndCleanupMedia();
                mTestVideoCallProvider.stopCamera();
            }
        }
    }

    private class CallAttempter {
        private final Iterator<PhoneAccountHandle> mAccountIterator;
        private final ConnectionRequest mOriginalRequest;
        private final boolean mIsIncoming;
        private final TestConnection mTestConnection;

        private final RemoteConnection.Listener mRemoteListener = new RemoteConnection.Listener() {
            @Override
            public void onStateChanged(RemoteConnection conn, int newState) {
                if (newState != Connection.STATE_INITIALIZING) {
                    // Set the underlying connection
                    mTestConnection.setRemoteConnection(conn);
                } else {
                    tryNextAccount();
                }
                conn.removeListener(this);
            }
        };

        CallAttempter(
                TestConnection testConnection,
                Iterator<PhoneAccountHandle> iterator,
                ConnectionRequest originalRequest,
                boolean isIncoming) {
            mTestConnection = testConnection;
            mAccountIterator = iterator;
            mOriginalRequest = originalRequest;
            mIsIncoming = isIncoming;
        }


        public void tryNextAccount() {
            if (mAccountIterator.hasNext()) {
                ConnectionRequest connectionRequest = new ConnectionRequest(
                        mAccountIterator.next(),
                        mOriginalRequest.getHandle(),
                        mOriginalRequest.getHandlePresentation(),
                        null,
                        mOriginalRequest.getVideoState());
                RemoteConnection remoteConnection;
                if (mIsIncoming) {
                    remoteConnection = createRemoteIncomingConnection(null, connectionRequest);
                } else {
                    remoteConnection = createRemoteOutgoingConnection(null, connectionRequest);
                }
                if (remoteConnection != null) {
                    remoteConnection.addListener(mRemoteListener);
                }
            } else {
                log("Unable to get a remote connection");
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

        // Ensure any playing media and camera resources are released.
        connection.stopAndCleanupMedia();

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
    public Connection onCreateOutgoingConnection(
            PhoneAccountHandle connectionManagerAccount,
            final ConnectionRequest originalRequest) {

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

        final TestConnection connection = new TestConnection(false /* isIncoming */);

        // If the number starts with 555, then we handle it ourselves. If not, then we
        // use a remote connection service.
        // TODO: Have a special phone number to test the account-picker dialog flow.
        if (number != null && number.startsWith("555")) {
            // Normally we would use the original request as is, but for testing purposes, we are
            // adding ".." to the end of the number to follow its path more easily through the logs.
            final ConnectionRequest request = new ConnectionRequest(
                    originalRequest.getAccountHandle(),
                    Uri.fromParts(handle.getScheme(),
                    handle.getSchemeSpecificPart() + "..", ""),
                    originalRequest.getHandlePresentation(),
                    originalRequest.getExtras(),
                    originalRequest.getVideoState());

            mCalls.add(connection);
            connection.startOutgoing();
        } else {
            log("Not a test number");
        }
        return connection;
    }

    /** ${inheritDoc} */
    @Override
    public Connection onCreateIncomingConnection(
            PhoneAccountHandle connectionManagerAccount,
            final ConnectionRequest request) {
        PhoneAccountHandle accountHandle = request.getAccountHandle();
        ComponentName componentName = new ComponentName(this, TestConnectionService.class);

        final TestConnection connection = new TestConnection(true);

        if (accountHandle != null && componentName.equals(accountHandle.getComponentName())) {
            // Get the stashed intent extra that determines if this is a video call or audio call.
            Bundle extras = request.getExtras();
            boolean isVideoCall = extras.getBoolean(IS_VIDEO_CALL);

            // Use dummy number for testing incoming calls.
            Uri handle = Uri.fromParts(SCHEME_TEL, getDummyNumber(isVideoCall), null);
            if (isVideoCall) {
                TestVideoProvider testVideoCallProvider =
                        new TestVideoProvider(getApplicationContext());
                connection.setVideoProvider(testVideoCallProvider);

                // Keep reference to original so we can clean up the media players later.
                connection.setTestVideoCallProvider(testVideoCallProvider);
            }

            // Assume all calls are video capable.
            int capabilities = connection.getCallCapabilities();
            capabilities |= PhoneCapabilities.SUPPORTS_VT_LOCAL;
            capabilities |= PhoneCapabilities.ALL;
            connection.setCallCapabilities(capabilities);

            int videoState = isVideoCall ?
                    VideoProfile.VideoState.BIDIRECTIONAL :
                    VideoProfile.VideoState.AUDIO_ONLY;
            connection.setVideoState(videoState);
            connection.setHandle(handle, PropertyPresentation.ALLOWED);

            mCalls.add(connection);

            ConnectionRequest newRequest = new ConnectionRequest(
                    request.getAccountHandle(),
                    handle,
                    request.getHandlePresentation(),
                    request.getExtras(),
                    videoState);
            connection.setVideoState(videoState);
        } else {
            /*
            SimpleResponse<Uri, List<PhoneAccountHandle>> accountResponse =
                    new SimpleResponse<Uri, List<PhoneAccountHandle>>() {
                        @Override
                        public void onResult(
                                Uri handle, List<PhoneAccountHandle> accountHandles) {
                            log("attaching to incoming call with accountHandles: "
                                    + accountHandles);
                            new CallAttempter(connection, accountHandles.iterator(), request, true)
                                .tryNextAccount();
                        }

                        @Override
                        public void onError(Uri handle) {
                            log("remote accountHandle lookup failed.");
                            connection.setDisconnected(0, null);
                            connection.setFailed(0, null);
                        }
                    };
            connection.setInitializing();
            */
        }
        return connection;
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
