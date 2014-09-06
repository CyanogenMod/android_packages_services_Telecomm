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
import android.telecomm.Conference;
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
    public static final String EXTRA_IS_VIDEO_CALL = "extra_is_video_call";

    public static final String EXTRA_HANDLE = "extra_handle";

    /**
     * Random number generator used to generate phone numbers.
     */
    private Random mRandom = new Random();

    private final class TestConference extends Conference {

        private final Connection.Listener mConnectionListener = new Connection.Listener() {
            @Override
            public void onDestroyed(Connection c) {
                removeConnection(c);
                if (getConnections().size() == 0) {
                    setDisconnected(DisconnectCause.NORMAL, null);
                    destroy();
                }
            }
        };

        public TestConference(Connection a, Connection b) {
            super(null);

            addConnection(a);
            addConnection(b);

            a.addConnectionListener(mConnectionListener);
            b.addConnectionListener(mConnectionListener);

            a.setConference(this);
            b.setConference(this);

            setActive();
        }

        @Override
        public void onDisconnect() {
            for (Connection c : getConnections()) {
                c.setDisconnected(DisconnectCause.NORMAL, null);
                c.destroy();
            }
        }

        @Override
        public void onSeparate(Connection connection) {
            if (getConnections().contains(connection)) {
                connection.setConference(null);
                removeConnection(connection);
                connection.removeConnectionListener(mConnectionListener);
            }
        }

        @Override
        public void onHold() {
            for (Connection c : getConnections()) {
                c.setOnHold();
            }
            setOnHold();
        }

        @Override
        public void onUnhold() {
            for (Connection c : getConnections()) {
                c.setActive();
            }
            setActive();
        }
    }

    private final class TestConnection extends Connection {
        private final boolean mIsIncoming;

        /** Used to cleanup camera and media when done with connection. */
        private TestVideoProvider mTestVideoCallProvider;

        TestConnection(boolean isIncoming) {
            mIsIncoming = isIncoming;
            // Assume all calls are video capable.
            int capabilities = getCallCapabilities();
            capabilities |= PhoneCapabilities.SUPPORTS_VT_LOCAL;
            capabilities |= PhoneCapabilities.ADD_CALL;
            capabilities |= PhoneCapabilities.MUTE;
            capabilities |= PhoneCapabilities.SUPPORT_HOLD;
            capabilities |= PhoneCapabilities.HOLD;
            setCallCapabilities(capabilities);
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

        /** ${inheritDoc} */
        @Override
        public void onAbort() {
            destroyCall(this);
            destroy();
        }

        /** ${inheritDoc} */
        @Override
        public void onAnswer(int videoState) {
            setVideoState(videoState);
            activateCall(this);
            setActive();
            updateConferenceable();
        }

        /** ${inheritDoc} */
        @Override
        public void onPlayDtmfTone(char c) {
            if (c == '1') {
                setDialing();
            }
        }

        /** ${inheritDoc} */
        @Override
        public void onStopDtmfTone() { }

        /** ${inheritDoc} */
        @Override
        public void onDisconnect() {
            setDisconnected(DisconnectCause.LOCAL, null);
            destroyCall(this);
            destroy();
        }

        /** ${inheritDoc} */
        @Override
        public void onHold() {
            setOnHold();
        }

        /** ${inheritDoc} */
        @Override
        public void onReject() {
            setDisconnected(DisconnectCause.INCOMING_REJECTED, null);
            destroyCall(this);
            destroy();
        }

        /** ${inheritDoc} */
        @Override
        public void onUnhold() {
            setActive();
        }

        @Override
        public void onSetAudioState(AudioState state) { }

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

    private static final String SCHEME_TEL = "tel";

    private final List<TestConnection> mCalls = new ArrayList<>();
    private final Handler mHandler = new Handler();

    /** Used to play an audio tone during a call. */
    private MediaPlayer mMediaPlayer;

    @Override
    public boolean onUnbind(Intent intent) {
        log("onUnbind");
        mMediaPlayer = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onConference(Connection a, Connection b) {
        addConference(new TestConference(a, b));
    }

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

            addCall(connection);
            connection.startOutgoing();

            for (Connection c : getAllConnections()) {
                c.setOnHold();
            }
        } else {
            log("Not a test number");
        }
        return connection;
    }

    @Override
    public Connection onCreateIncomingConnection(
            PhoneAccountHandle connectionManagerAccount,
            final ConnectionRequest request) {
        PhoneAccountHandle accountHandle = request.getAccountHandle();
        ComponentName componentName = new ComponentName(this, TestConnectionService.class);

        if (accountHandle != null && componentName.equals(accountHandle.getComponentName())) {
            final TestConnection connection = new TestConnection(true);
            // Get the stashed intent extra that determines if this is a video call or audio call.
            Bundle extras = request.getExtras();
            boolean isVideoCall = extras.getBoolean(EXTRA_IS_VIDEO_CALL);
            Uri providedHandle = extras.getParcelable(EXTRA_HANDLE);

            // Use dummy number for testing incoming calls.
            Uri handle = providedHandle == null ?
                    Uri.fromParts(SCHEME_TEL, getDummyNumber(isVideoCall), null) : providedHandle;
            if (isVideoCall) {
                TestVideoProvider testVideoCallProvider =
                        new TestVideoProvider(getApplicationContext());
                connection.setVideoProvider(testVideoCallProvider);

                // Keep reference to original so we can clean up the media players later.
                connection.setTestVideoCallProvider(testVideoCallProvider);
            }

            int videoState = isVideoCall ?
                    VideoProfile.VideoState.BIDIRECTIONAL :
                    VideoProfile.VideoState.AUDIO_ONLY;
            connection.setVideoState(videoState);
            connection.setHandle(handle, PropertyPresentation.ALLOWED);

            addCall(connection);

            ConnectionRequest newRequest = new ConnectionRequest(
                    request.getAccountHandle(),
                    handle,
                    request.getHandlePresentation(),
                    request.getExtras(),
                    videoState);
            connection.setVideoState(videoState);
            return connection;
        } else {
            return Connection.createFailedConnection(
                    DisconnectCause.NOT_VALID,
                    "Invalid inputs: " + accountHandle + " " + componentName);
        }
    }

    private void activateCall(TestConnection connection) {
        if (mMediaPlayer == null) {
            mMediaPlayer = createMediaPlayer();
        }
        if (!mMediaPlayer.isPlaying()) {
            mMediaPlayer.start();
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

        updateConferenceable();
    }

    private void addCall(TestConnection connection) {
        mCalls.add(connection);
        updateConferenceable();
    }

    private void updateConferenceable() {
        List<Connection> freeConnections = new ArrayList<>();
        freeConnections.addAll(mCalls);
        for (int i = 0; i < freeConnections.size(); i++) {
            if (freeConnections.get(i).getConference() != null) {
                freeConnections.remove(i);
            }
        }
        for (int i = 0; i < freeConnections.size(); i++) {
            Connection c = freeConnections.remove(i);
            c.setConferenceableConnections(freeConnections);
            freeConnections.add(i, c);
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

