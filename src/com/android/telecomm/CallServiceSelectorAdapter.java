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

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.telecomm.CallServiceDescriptor;

import com.android.internal.telecomm.ICallServiceSelectorAdapter;
import com.android.internal.os.SomeArgs;

import java.util.List;

/**
 * Used by call service selector to communicate with Telecomm.
 */
public final class CallServiceSelectorAdapter extends ICallServiceSelectorAdapter.Stub {
    private static final int MSG_SET_SELECTED_CALL_SERVICES = 0;
    private static final int MSG_SET_HANDOFF_INFO = 1;

    private final class CallServiceSelectorAdapaterHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_SET_SELECTED_CALL_SERVICES: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        Call call = mCallIdMapper.getCall(args.arg1);
                        List<CallServiceDescriptor> descriptors =
                                (List<CallServiceDescriptor>) args.arg2;
                        if (call != null) {
                            mOutgoingCallsManager.processSelectedCallServices(call, descriptors);
                        } else {
                            Log.w(this, "Unknown call: %s, id: %s", call, args.arg1);
                        }
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_SET_HANDOFF_INFO: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        Call call = mCallIdMapper.getCall(args.arg1);
                        Uri handle = (Uri) args.arg2;
                        Bundle extras = (Bundle) args.arg3;
                        if (call != null) {
                            mCallsManager.setHandoffInfo(call, handle, extras);
                        } else {
                            Log.w(this, "Unknown call: %s, id: %s", call, args.arg1);
                        }
                    } finally {
                        args.recycle();
                    }
                    break;
                }
            }
        }
    }

    private final Handler mHandler = new CallServiceSelectorAdapaterHandler();
    private final CallsManager mCallsManager;
    private final OutgoingCallsManager mOutgoingCallsManager;
    private final CallIdMapper mCallIdMapper;

    CallServiceSelectorAdapter(
            CallsManager callsManager,
            OutgoingCallsManager outgoingCallsManager,
            CallIdMapper callIdMapper) {
        ThreadUtil.checkOnMainThread();
        mCallsManager = callsManager;
        mOutgoingCallsManager = outgoingCallsManager;
        mCallIdMapper = callIdMapper;
    }

    /**
     * @see CallServiceSelectorAdapater#setSelectedCallServices(String,List<CallServiceDescriptor>)
     */
    @Override
    public void setSelectedCallServices(String callId, List<CallServiceDescriptor> descriptors) {
        mCallIdMapper.checkValidCallId(callId);
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = callId;
        args.arg2 = descriptors;
        mHandler.obtainMessage(MSG_SET_SELECTED_CALL_SERVICES, args).sendToTarget();
    }

    /** @see CallServiceSelectorAdapater#setHandoffInfo(String,Uri,Bundle) */
    @Override
    public void setHandoffInfo(String callId, Uri handle, Bundle extras) {
        mCallIdMapper.checkValidCallId(callId);
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = callId;
        args.arg2 = handle;
        args.arg3 = extras;
        mHandler.obtainMessage(MSG_SET_HANDOFF_INFO, args).sendToTarget();
    }
}
