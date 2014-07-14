/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.telecomm.testapps;

import android.content.Context;
import android.os.RemoteException;
import android.telecomm.CallCameraCapabilities;
import android.telecomm.CallVideoClient;
import android.telecomm.CallVideoProvider;
import android.telecomm.RemoteCallVideoClient;
import android.telecomm.VideoCallProfile;

import android.util.Log;
import android.view.Surface;

import java.util.Random;

/**
 * Implements the CallVideoProvider.
 */
public class TestCallVideoProvider extends CallVideoProvider {
    private RemoteCallVideoClient mCallVideoClient;
    private CallCameraCapabilities mCapabilities;
    private Random random;


    public TestCallVideoProvider(Context context) {
        mCapabilities = new CallCameraCapabilities(false /* zoomSupported */, 0 /* maxZoom */);
        random = new Random();
    }

    /**
     * Save the reference to the CallVideoClient so callback can be invoked.
     */
    @Override
    public void onSetCallVideoClient(RemoteCallVideoClient callVideoClient) {
        mCallVideoClient = callVideoClient;
    }

    @Override
    public void onSetCamera(String cameraId) {
        log("Set camera to " + cameraId);
    }

    @Override
    public void onSetPreviewSurface(Surface surface) {
        log("Set preview surface");
    }

    @Override
    public void onSetDisplaySurface(Surface surface) {
        log("Set display surface");
    }

    @Override
    public void onSetDeviceOrientation(int rotation) {
        log("Set device orientation");
    }

    /**
     * Sets the zoom value, creating a new CallCameraCapabalities object. If the zoom value is
     * non-positive, assume that zoom is not supported.
     */
    @Override
    public void onSetZoom(float value) {
        log("Set zoom to " + value);

        if (value <= 0) {
            mCapabilities = new CallCameraCapabilities(false /* zoomSupported */, 0 /* maxZoom */);
        } else {
            mCapabilities = new CallCameraCapabilities(true /* zoomSupported */, value);
        }

        try {
            mCallVideoClient.handleCameraCapabilitiesChange(mCapabilities);
        } catch (RemoteException ignored) {
        }
    }

    /**
     * "Sends" a request with a video call profile. Assumes that this response succeeds and sends
     * the response back via the CallVideoClient.
     */
    @Override
    public void onSendSessionModifyRequest(VideoCallProfile requestProfile) {
        log("Sent session modify request");

        VideoCallProfile responseProfile = new VideoCallProfile(
                requestProfile.getVideoState(), requestProfile.getQuality());
        try {
            mCallVideoClient.receiveSessionModifyResponse(
                    CallVideoClient.SESSION_MODIFY_REQUEST_SUCCESS,
                    requestProfile,
                    responseProfile);
        } catch (RemoteException ignored) {
        }
    }

    @Override
    public void onSendSessionModifyResponse(VideoCallProfile responseProfile) {

    }

    /**
     * Returns a CallCameraCapabilities object without supporting zoom.
     */
    @Override
    public void onRequestCameraCapabilities() {
        log("Requested camera capabilities");
        try {
            mCallVideoClient.handleCameraCapabilitiesChange(mCapabilities);
        } catch (RemoteException ignored) {
        }
    }

    /**
     * Randomly reports data usage of value ranging from 10MB to 60MB.
     */
    @Override
    public void onRequestCallDataUsage() {
        log("Requested call data usage");
        int dataUsageKb = (10 *1024) + random.nextInt(50 * 1024);
        try {
            mCallVideoClient.updateCallDataUsage(dataUsageKb);
        } catch (RemoteException ignored) {
        }
    }

    /**
     * We do not have a need to set a paused image.
     */
    @Override
    public void onSetPauseImage(String uri) {
        // Not implemented.
    }

    private static void log(String msg) {
        Log.w("TestCallVideoProvider", "[TestCallServiceProvider] " + msg);
    }
}
