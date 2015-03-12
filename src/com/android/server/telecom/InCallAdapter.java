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

package com.android.server.telecom;

import android.os.Handler;
import android.os.Message;
import android.telecom.PhoneAccountHandle;

import com.android.internal.os.SomeArgs;
import com.android.internal.telecom.IInCallAdapter;

/**
 * Receives call commands and updates from in-call app and passes them through to CallsManager.
 * {@link InCallController} creates an instance of this class and passes it to the in-call app after
 * binding to it. This adapter can receive commands and updates until the in-call app is unbound.
 */
class InCallAdapter extends IInCallAdapter.Stub {
    private final CallsManager mCallsManager;
    private final CallIdMapper mCallIdMapper;
    private final TelecomSystem.SyncRoot mLock;

    /** Persists the specified parameters. */
    public InCallAdapter(CallsManager callsManager, CallIdMapper callIdMapper, TelecomSystem.SyncRoot lock) {
        mCallsManager = callsManager;
        mCallIdMapper = callIdMapper;
        mLock = lock;
    }

    @Override
    public void answerCall(String callId, int videoState) {
        synchronized (mLock) {
            Log.d(this, "answerCall(%s,%d)", callId, videoState);
            if (mCallIdMapper.isValidCallId(callId)) {
                Call call = mCallIdMapper.getCall(callId);
                if (call != null) {
                    mCallsManager.answerCall(call, videoState);
                } else {
                    Log.w(this, "answerCall, unknown call id: %s", callId);
                }
            }
        }
    }

    @Override
    public void rejectCall(String callId, boolean rejectWithMessage, String textMessage) {
        synchronized (this) {
            Log.d(this, "rejectCall(%s,%b,%s)", callId, rejectWithMessage, textMessage);
            if (mCallIdMapper.isValidCallId(callId)) {
                Call call = mCallIdMapper.getCall(callId);
                if (call != null) {
                    mCallsManager.rejectCall(call, rejectWithMessage, textMessage);
                } else {
                    Log.w(this, "setRingback, unknown call id: %s", callId);
                }
            }
        }
    }

    @Override
    public void playDtmfTone(String callId, char digit) {
        synchronized (mLock) {
            Log.d(this, "playDtmfTone(%s,%c)", callId, digit);
            if (mCallIdMapper.isValidCallId(callId)) {
                Call call = mCallIdMapper.getCall(callId);
                if (call != null) {
                    mCallsManager.playDtmfTone(call, digit);
                } else {
                    Log.w(this, "playDtmfTone, unknown call id: %s", callId);
                }
            }
        }
    }

    @Override
    public void stopDtmfTone(String callId) {
        synchronized (mLock) {
            Log.d(this, "stopDtmfTone(%s)", callId);
            if (mCallIdMapper.isValidCallId(callId)) {
                Call call = mCallIdMapper.getCall(callId);
                if (call != null) {
                    mCallsManager.stopDtmfTone(call);
                } else {
                    Log.w(this, "stopDtmfTone, unknown call id: %s", callId);
                }
            }
        }
    }

    @Override
    public void postDialContinue(String callId, boolean proceed) {
        synchronized (mLock) {
            Log.d(this, "postDialContinue(%s)", callId);
            if (mCallIdMapper.isValidCallId(callId)) {
                Call call = mCallIdMapper.getCall(callId);
                if (call != null) {
                    mCallsManager.postDialContinue(call, proceed);
                } else {
                    Log.w(this, "postDialContinue, unknown call id: %s", callId);
                }
            }
        }
    }

    @Override
    public void disconnectCall(String callId) {
        synchronized (mLock) {
            Log.v(this, "disconnectCall: %s", callId);
            if (mCallIdMapper.isValidCallId(callId)) {
                Call call = mCallIdMapper.getCall(callId);
                if (call != null) {
                    mCallsManager.disconnectCall(call);
                } else {
                    Log.w(this, "disconnectCall, unknown call id: %s", callId);
                }
            }
        }
    }

    @Override
    public void holdCall(String callId) {
        synchronized (mLock) {
            if (mCallIdMapper.isValidCallId(callId)) {
                Call call = mCallIdMapper.getCall(callId);
                if (call != null) {
                    mCallsManager.holdCall(call);
                } else {
                    Log.w(this, "holdCall, unknown call id: %s", callId);
                }
            }
        }
    }

    @Override
    public void unholdCall(String callId) {
        synchronized (mLock) {
            if (mCallIdMapper.isValidCallId(callId)) {
                Call call = mCallIdMapper.getCall(callId);
                if (call != null) {
                    mCallsManager.unholdCall(call);
                } else {
                    Log.w(this, "unholdCall, unknown call id: %s", callId);
                }
            }
        }
    }

    @Override
    public void phoneAccountSelected(String callId, PhoneAccountHandle accountHandle,
            boolean setDefault) {
        synchronized (mLock) {
            if (mCallIdMapper.isValidCallId(callId)) {
                Call call = mCallIdMapper.getCall(callId);
                if (call != null) {
                    mCallsManager.phoneAccountSelected(call, accountHandle, setDefault);
                } else {
                    Log.w(this, "phoneAccountSelected, unknown call id: %s", callId);
                }
            }
        }
    }

    @Override
    public void mute(boolean shouldMute) {
        synchronized (mLock) {
            mCallsManager.mute(shouldMute);
        }
    }

    @Override
    public void setAudioRoute(int route) {
        synchronized (mLock) {
            mCallsManager.setAudioRoute(route);
        }
    }

    @Override
    public void conference(String callId, String otherCallId) {
        synchronized (mLock) {
            if (mCallIdMapper.isValidCallId(callId) &&
                    mCallIdMapper.isValidCallId(otherCallId)) {
                Call call = mCallIdMapper.getCall(callId);
                Call otherCall = mCallIdMapper.getCall(otherCallId);
                if (call != null && otherCall != null) {
                    mCallsManager.conference(call, otherCall);
                } else {
                    Log.w(this, "conference, unknown call id: %s or %s", callId, otherCallId);
                }

            }
        }
    }

    @Override
    public void splitFromConference(String callId) {
        synchronized (mLock) {
            if (mCallIdMapper.isValidCallId(callId)) {
                Call call = mCallIdMapper.getCall(callId);
                if (call != null) {
                    call.splitFromConference();
                } else {
                    Log.w(this, "splitFromConference, unknown call id: %s", callId);
                }
            }
        }
    }

    @Override
    public void mergeConference(String callId) {
        synchronized (mLock) {
            if (mCallIdMapper.isValidCallId(callId)) {
                Call call = mCallIdMapper.getCall(callId);
                if (call != null) {
                    call.mergeConference();
                } else {
                    Log.w(this, "mergeConference, unknown call id: %s", callId);
                }
            }
        }
    }

    @Override
    public void swapConference(String callId) {
        synchronized (mLock) {
            if (mCallIdMapper.isValidCallId(callId)) {
                Call call = mCallIdMapper.getCall(callId);
                if (call != null) {
                    call.swapConference();
                } else {
                    Log.w(this, "swapConference, unknown call id: %s", callId);
                }
            }
        }
    }

    @Override
    public void turnOnProximitySensor() {
        synchronized (mLock) {
            mCallsManager.turnOnProximitySensor();
        }
    }

    @Override
    public void turnOffProximitySensor(boolean screenOnImmediately) {
        synchronized (mLock) {
            mCallsManager.turnOffProximitySensor(screenOnImmediately);
        }
    }
}
