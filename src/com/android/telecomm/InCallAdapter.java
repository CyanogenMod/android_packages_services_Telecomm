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
 * limitations under the License.
 */

package com.android.telecomm;

import android.os.Handler;
import android.os.Message;

import com.android.internal.os.SomeArgs;
import com.android.internal.telecomm.IInCallAdapter;

/**
 * Receives call commands and updates from in-call app and passes them through to CallsManager.
 * {@link InCallController} creates an instance of this class and passes it to the in-call app after
 * binding to it. This adapter can receive commands and updates until the in-call app is unbound.
 */
class InCallAdapter extends IInCallAdapter.Stub {
    private static final int MSG_ANSWER_CALL = 0;
    private static final int MSG_REJECT_CALL = 1;
    private static final int MSG_PLAY_DTMF_TONE = 2;
    private static final int MSG_STOP_DTMF_TONE = 3;
    private static final int MSG_POST_DIAL_CONTINUE = 4;
    private static final int MSG_DISCONNECT_CALL = 5;
    private static final int MSG_HOLD_CALL = 6;
    private static final int MSG_UNHOLD_CALL = 7;
    private static final int MSG_HANDOFF_CALL = 8;
    private static final int MSG_MUTE = 9;
    private static final int MSG_SET_AUDIO_ROUTE = 10;

    private final class InCallAdapterHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            Call call = null;
            if (msg.obj != null) {
                call = mCallIdMapper.getCall(msg.obj);
                if (call == null) {
                    Log.w(this, "Unknown call id: %s, msg: %d", msg.obj, msg.what);
                    return;
                }
            }

            switch (msg.what) {
                case MSG_ANSWER_CALL:
                    mCallsManager.answerCall(call);
                    break;
                case MSG_REJECT_CALL:
                    mCallsManager.rejectCall(call);
                    break;
                case MSG_PLAY_DTMF_TONE:
                    mCallsManager.playDtmfTone(call, (char) msg.arg1);
                    break;
                case MSG_STOP_DTMF_TONE:
                    mCallsManager.stopDtmfTone(call);
                    break;
                case MSG_POST_DIAL_CONTINUE:
                    mCallsManager.postDialContinue(call, msg.arg1 == 1);
                    break;
                case MSG_DISCONNECT_CALL:
                    mCallsManager.disconnectCall(call);
                    break;
                case MSG_HOLD_CALL:
                    mCallsManager.holdCall(call);
                    break;
                case MSG_UNHOLD_CALL:
                    mCallsManager.unholdCall(call);
                    break;
                case MSG_HANDOFF_CALL:
                    mCallsManager.startHandoffForCall(call);
                    break;
                case MSG_MUTE:
                    mCallsManager.mute(msg.arg1 == 1 ? true : false);
                    break;
                case MSG_SET_AUDIO_ROUTE:
                    mCallsManager.setAudioRoute(msg.arg1);
                    break;
            }
        }
    }

    private final CallsManager mCallsManager;
    private final Handler mHandler = new InCallAdapterHandler();
    private final CallIdMapper mCallIdMapper;

    /** Persists the specified parameters. */
    public InCallAdapter(CallsManager callsManager, CallIdMapper callIdMapper) {
        ThreadUtil.checkOnMainThread();
        mCallsManager = callsManager;
        mCallIdMapper = callIdMapper;
    }

    /** {@inheritDoc} */
    @Override
    public void answerCall(String callId) {
        Log.d(this, "answerCall(%s)", callId);
        mCallIdMapper.checkValidCallId(callId);
        mHandler.obtainMessage(MSG_ANSWER_CALL, callId).sendToTarget();
    }

    /** {@inheritDoc} */
    @Override
    public void rejectCall(String callId) {
        Log.d(this, "rejectCall(%s)", callId);
        mCallIdMapper.checkValidCallId(callId);
        mHandler.obtainMessage(MSG_REJECT_CALL, callId).sendToTarget();
    }

    /** {@inheritDoc} */
    @Override
    public void playDtmfTone(String callId, char digit) {
        Log.d(this, "playDtmfTone(%s,%c)", callId, digit);
        mCallIdMapper.checkValidCallId(callId);
        mHandler.obtainMessage(MSG_PLAY_DTMF_TONE, (int) digit, 0, callId).sendToTarget();
    }

    /** {@inheritDoc} */
    @Override
    public void stopDtmfTone(String callId) {
        Log.d(this, "stopDtmfTone(%s)", callId);
        mCallIdMapper.checkValidCallId(callId);
        mHandler.obtainMessage(MSG_STOP_DTMF_TONE, callId).sendToTarget();
    }

    /** {@inheritDoc} */
    @Override
    public void postDialContinue(String callId, boolean proceed) {
        Log.d(this, "postDialContinue(%s)", callId);
        mCallIdMapper.checkValidCallId(callId);
        mHandler.obtainMessage(MSG_POST_DIAL_CONTINUE, proceed ? 1 : 0, 0, callId).sendToTarget();
    }

    /** {@inheritDoc} */
    @Override
    public void disconnectCall(String callId) {
        Log.v(this, "disconnectCall: %s", callId);
        mCallIdMapper.checkValidCallId(callId);
        mHandler.obtainMessage(MSG_DISCONNECT_CALL, callId).sendToTarget();
    }

    /** {@inheritDoc} */
    @Override
    public void holdCall(String callId) {
        mCallIdMapper.checkValidCallId(callId);
        mHandler.obtainMessage(MSG_HOLD_CALL, callId).sendToTarget();
    }

    /** {@inheritDoc} */
    @Override
    public void unholdCall(String callId) {
        mCallIdMapper.checkValidCallId(callId);
        mHandler.obtainMessage(MSG_UNHOLD_CALL, callId).sendToTarget();
    }

    /** {@inheritDoc} */
    @Override
    public void handoffCall(String callId) {
        mCallIdMapper.checkValidCallId(callId);
        mHandler.obtainMessage(MSG_HANDOFF_CALL, callId).sendToTarget();
    }

    /** {@inheritDoc} */
    @Override
    public void mute(boolean shouldMute) {
        mHandler.obtainMessage(MSG_MUTE, shouldMute ? 1 : 0, 0).sendToTarget();
    }

    /** {@inheritDoc} */
    @Override
    public void setAudioRoute(int route) {
        mHandler.obtainMessage(MSG_SET_AUDIO_ROUTE, route, 0).sendToTarget();
    }

    /** ${inheritDoc} */
    @Override
    public void conferenceWith(String arg0, String arg1) {
    }

    /** ${inheritDoc} */
    @Override
    public void splitFromConference(String arg0) {
    }
}
