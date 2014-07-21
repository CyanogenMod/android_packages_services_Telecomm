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
import android.telecomm.PhoneAccountHandle;

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
    private static final int MSG_PHONE_ACCOUNT_CLICKED = 8;
    private static final int MSG_MUTE = 9;
    private static final int MSG_SET_AUDIO_ROUTE = 10;
    private static final int MSG_CONFERENCE = 11;
    private static final int MSG_SPLIT_FROM_CONFERENCE = 12;
    private static final int MSG_SWAP_WITH_BACKGROUND_CALL = 13;
    private static final int MSG_PHONE_ACCOUNT_SELECTED = 14;

    private final class InCallAdapterHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            Call call;
            switch (msg.what) {
                case MSG_ANSWER_CALL: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        call = mCallIdMapper.getCall(args.arg1);
                        int videoState = (int) args.arg2;
                        if (call != null) {
                            mCallsManager.answerCall(call, videoState);
                        } else {
                            Log.w(this, "answerCall, unknown call id: %s", msg.obj);
                        }
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_REJECT_CALL: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        call = mCallIdMapper.getCall(args.arg1);
                        boolean rejectWithMessage = args.argi1 == 1;
                        String textMessage = (String) args.arg2;
                        if (call != null) {
                            mCallsManager.rejectCall(call, rejectWithMessage, textMessage);
                        } else {
                            Log.w(this, "setRingback, unknown call id: %s", args.arg1);
                        }
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_PLAY_DTMF_TONE:
                    call = mCallIdMapper.getCall(msg.obj);
                    if (call != null) {
                        mCallsManager.playDtmfTone(call, (char) msg.arg1);
                    } else {
                        Log.w(this, "playDtmfTone, unknown call id: %s", msg.obj);
                    }
                    break;
                case MSG_STOP_DTMF_TONE:
                    call = mCallIdMapper.getCall(msg.obj);
                    if (call != null) {
                        mCallsManager.stopDtmfTone(call);
                    } else {
                        Log.w(this, "stopDtmfTone, unknown call id: %s", msg.obj);
                    }
                    break;
                case MSG_POST_DIAL_CONTINUE:
                    call = mCallIdMapper.getCall(msg.obj);
                    mCallsManager.postDialContinue(call, msg.arg1 == 1);
                    call = mCallIdMapper.getCall(msg.obj);
                    if (call != null) {
                        mCallsManager.postDialContinue(call, msg.arg1 == 1);
                    } else {
                        Log.w(this, "postDialContinue, unknown call id: %s", msg.obj);
                    }
                    break;
                case MSG_DISCONNECT_CALL:
                    call = mCallIdMapper.getCall(msg.obj);
                    if (call != null) {
                        mCallsManager.disconnectCall(call);
                    } else {
                        Log.w(this, "disconnectCall, unknown call id: %s", msg.obj);
                    }
                    break;
                case MSG_HOLD_CALL:
                    call = mCallIdMapper.getCall(msg.obj);
                    if (call != null) {
                        mCallsManager.holdCall(call);
                    } else {
                        Log.w(this, "holdCall, unknown call id: %s", msg.obj);
                    }
                    break;
                case MSG_UNHOLD_CALL:
                    call = mCallIdMapper.getCall(msg.obj);
                    if (call != null) {
                        mCallsManager.unholdCall(call);
                    } else {
                        Log.w(this, "unholdCall, unknown call id: %s", msg.obj);
                    }
                    break;
                case MSG_PHONE_ACCOUNT_CLICKED:
                    call = mCallIdMapper.getCall(msg.obj);
                    if (call != null) {
                        mCallsManager.phoneAccountClicked(call);
                    } else {
                        Log.w(this, "phoneAccountClicked, unknown call id: %s", msg.obj);
                    }
                    break;
                case MSG_PHONE_ACCOUNT_SELECTED: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        call = mCallIdMapper.getCall(args.arg1);
                        if (call != null) {
                            mCallsManager.phoneAccountSelected(call, (PhoneAccountHandle) args.arg2);
                        } else {
                            Log.w(this, "phoneAccountSelected, unknown call id: %s", args.arg1);
                        }
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_MUTE:
                    mCallsManager.mute(msg.arg1 == 1);
                    break;
                case MSG_SET_AUDIO_ROUTE:
                    mCallsManager.setAudioRoute(msg.arg1);
                    break;
                case MSG_CONFERENCE:
                    call = mCallIdMapper.getCall(msg.obj);
                    if (call != null) {
                        mCallsManager.conference(call);
                    } else {
                        Log.w(this, "conference, unknown call id: %s", msg.obj);
                    }
                    break;
                case MSG_SPLIT_FROM_CONFERENCE:
                    call = mCallIdMapper.getCall(msg.obj);
                    if (call != null) {
                        call.splitFromConference();
                    } else {
                        Log.w(this, "splitFromConference, unknown call id: %s", msg.obj);
                    }
                    break;
                case MSG_SWAP_WITH_BACKGROUND_CALL:
                    call = mCallIdMapper.getCall(msg.obj);
                    if (call != null) {
                        call.swapWithBackgroundCall();
                    } else {
                        Log.w(this, "swapWithBackgroundCall, unknown call id: %s", msg.obj);
                    }
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

    @Override
    public void answerCall(String callId, int videoState) {
        Log.d(this, "answerCall(%s,%d)", callId, videoState);
        mCallIdMapper.checkValidCallId(callId);
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = callId;
        args.arg2 = videoState;
        mHandler.obtainMessage(MSG_ANSWER_CALL, args).sendToTarget();
    }

    @Override
    public void rejectCall(String callId, boolean rejectWithMessage, String textMessage) {
        Log.d(this, "rejectCall(%s,%b,%s)", callId, rejectWithMessage, textMessage);
        mCallIdMapper.checkValidCallId(callId);
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = callId;
        args.argi1 = rejectWithMessage ? 1 : 0;
        args.arg2 = textMessage;
        mHandler.obtainMessage(MSG_REJECT_CALL, args).sendToTarget();
    }

    @Override
    public void playDtmfTone(String callId, char digit) {
        Log.d(this, "playDtmfTone(%s,%c)", callId, digit);
        mCallIdMapper.checkValidCallId(callId);
        mHandler.obtainMessage(MSG_PLAY_DTMF_TONE, (int) digit, 0, callId).sendToTarget();
    }

    @Override
    public void stopDtmfTone(String callId) {
        Log.d(this, "stopDtmfTone(%s)", callId);
        mCallIdMapper.checkValidCallId(callId);
        mHandler.obtainMessage(MSG_STOP_DTMF_TONE, callId).sendToTarget();
    }

    @Override
    public void postDialContinue(String callId, boolean proceed) {
        Log.d(this, "postDialContinue(%s)", callId);
        mCallIdMapper.checkValidCallId(callId);
        mHandler.obtainMessage(MSG_POST_DIAL_CONTINUE, proceed ? 1 : 0, 0, callId).sendToTarget();
    }

    @Override
    public void disconnectCall(String callId) {
        Log.v(this, "disconnectCall: %s", callId);
        mCallIdMapper.checkValidCallId(callId);
        mHandler.obtainMessage(MSG_DISCONNECT_CALL, callId).sendToTarget();
    }

    @Override
    public void holdCall(String callId) {
        mCallIdMapper.checkValidCallId(callId);
        mHandler.obtainMessage(MSG_HOLD_CALL, callId).sendToTarget();
    }

    @Override
    public void unholdCall(String callId) {
        mCallIdMapper.checkValidCallId(callId);
        mHandler.obtainMessage(MSG_UNHOLD_CALL, callId).sendToTarget();
    }

    @Override
    public void phoneAccountClicked(String callId) {
        mCallIdMapper.checkValidCallId(callId);
        mHandler.obtainMessage(MSG_PHONE_ACCOUNT_CLICKED, callId).sendToTarget();
    }

    @Override
    public void phoneAccountSelected(String callId, PhoneAccountHandle accountHandle) {
        mCallIdMapper.checkValidCallId(callId);
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = callId;
        args.arg2 = accountHandle;
        mHandler.obtainMessage(MSG_PHONE_ACCOUNT_SELECTED, args).sendToTarget();
    }

    @Override
    public void mute(boolean shouldMute) {
        mHandler.obtainMessage(MSG_MUTE, shouldMute ? 1 : 0, 0).sendToTarget();
    }

    @Override
    public void setAudioRoute(int route) {
        mHandler.obtainMessage(MSG_SET_AUDIO_ROUTE, route, 0).sendToTarget();
    }

    @Override
    public void conference(String callId) {
        mHandler.obtainMessage(MSG_CONFERENCE, callId).sendToTarget();
    }

    @Override
    public void splitFromConference(String callId) {
        mHandler.obtainMessage(MSG_SPLIT_FROM_CONFERENCE, callId).sendToTarget();
    }

    @Override
    public void swapWithBackgroundCall(String callId) {
        mHandler.obtainMessage(MSG_SWAP_WITH_BACKGROUND_CALL, callId).sendToTarget();
    }
}
