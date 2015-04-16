/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.telecom.testapps;

import android.telecom.Call;
import android.telecom.CameraCapabilities;
import android.telecom.InCallService;
import android.telecom.VideoProfile;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import java.util.Map;
import java.util.Set;

/**
 * Maintains a list of calls received via the {@link TestInCallServiceImpl}.
 */
public class TestCallList extends Call.Callback {
    private static final TestCallList INSTANCE = new TestCallList();
    private static final String TAG = "TestCallList";

    private class TestVideoCallCallback extends InCallService.VideoCall.Callback {
        private Call mCall;

        public TestVideoCallCallback(Call call) {
            mCall = call;
        }

        @Override
        public void onSessionModifyRequestReceived(VideoProfile videoProfile) {
            Log.v(TAG,
                    "onSessionModifyRequestReceived: videoState = " + videoProfile.getVideoState()
                            + " call = " + mCall);
        }

        @Override
        public void onSessionModifyResponseReceived(int status, VideoProfile requestedProfile,
                VideoProfile responseProfile) {
            Log.v(TAG,
                    "onSessionModifyResponseReceived: status = " + status + " videoState = "
                            + responseProfile.getVideoState()
                            + " call = " + mCall);
        }

        @Override
        public void onCallSessionEvent(int event) {

        }

        @Override
        public void onPeerDimensionsChanged(int width, int height) {

        }

        @Override
        public void onVideoQualityChanged(int videoQuality) {
            Log.v(TAG,
                    "onVideoQualityChanged: videoQuality = " + videoQuality + " call = " + mCall);
        }

        @Override
        public void onCallDataUsageChanged(long dataUsage) {

        }

        @Override
        public void onCameraCapabilitiesChanged(CameraCapabilities cameraCapabilities) {

        }
    }

    // The calls the call list knows about.
    private Set<Call> mCalls = new ArraySet<Call>();
    private Map<Call, TestVideoCallCallback> mVideoCallCallbacks =
            new ArrayMap<Call, TestVideoCallCallback>();

    /**
     * Singleton accessor.
     */
    public static TestCallList getInstance() {
        return INSTANCE;
    }

    public void addCall(Call call) {
        if (mCalls.contains(call)) {
            Log.e(TAG, "addCall: Call already added.");
            return;
        }
        Log.v(TAG, "addCall: " + call + " " + System.identityHashCode(this));
        mCalls.add(call);
        call.registerCallback(this);
    }

    public void removeCall(Call call) {
        if (!mCalls.contains(call)) {
            Log.e(TAG, "removeCall: Call cannot be removed -- doesn't exist.");
            return;
        }
        Log.v(TAG, "removeCall: " + call);
        mCalls.remove(call);
        call.unregisterCallback(this);
    }

    public void clearCalls() {
        mCalls.clear();
        for (Call call : mVideoCallCallbacks.keySet()) {
            if (call.getVideoCall() != null) {
                call.getVideoCall().unregisterCallback();
            }
        }
        mVideoCallCallbacks.clear();
    }

    /**
     * For any video calls tracked, sends an upgrade to video request.
     */
    public void sendUpgradeToVideoRequest() {
        Log.v(TAG, "sendUpgradeToVideoRequest "+mCalls.size()+ " " + System.identityHashCode(this));

        for (Call call : mCalls) {
            InCallService.VideoCall videoCall = call.getVideoCall();
            Log.v(TAG, "sendUpgradeToVideoRequest: checkCall "+call);
            if (videoCall == null) {
                continue;
            }

            Log.v(TAG, "send upgrade to video request for call: " + call);
            videoCall.sendSessionModifyRequest(new VideoProfile(
                    VideoProfile.VideoState.BIDIRECTIONAL));
        }
    }

    @Override
    public void onVideoCallChanged(Call call, InCallService.VideoCall videoCall) {
        Log.v(TAG, "onVideoCallChanged: call = " + call + " " + System.identityHashCode(this));
        if (videoCall != null) {
            if (!mVideoCallCallbacks.containsKey(call)) {
                TestVideoCallCallback callback = new TestVideoCallCallback(call);
                videoCall.registerCallback(callback);
                mVideoCallCallbacks.put(call, callback);
                Log.v(TAG, "onVideoCallChanged: added new callback");
            }
        }
    }
}
