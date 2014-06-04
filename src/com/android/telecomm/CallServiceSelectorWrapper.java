/*
 * Copyright 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.telecomm;

import android.content.ComponentName;

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.telecomm.CallInfo;
import android.telecomm.CallServiceDescriptor;
import android.telecomm.TelecommConstants;
import android.telephony.DisconnectCause;

import com.android.internal.os.SomeArgs;
import com.android.internal.telecomm.ICallServiceSelector;
import com.android.internal.telecomm.ICallServiceSelectorAdapter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Wrapper for {@link ICallServiceSelector}s, handles binding and keeps track of when the object can
 * safely be unbound.
 */
final class CallServiceSelectorWrapper extends ServiceBinder<ICallServiceSelector> {

    private final class Adapter extends ICallServiceSelectorAdapter.Stub {
        private static final int MSG_SET_SELECTED_CALL_SERVICES = 0;
        private static final int MSG_CANCEL_OUTGOING_CALL = 1;
        private static final int MSG_SET_HANDOFF_INFO = 2;

        private final Handler mHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case MSG_SET_SELECTED_CALL_SERVICES: {
                        SomeArgs args = (SomeArgs) msg.obj;
                        try {
                            String callId = (String) args.arg1;
                            if (mPendingSelects.containsKey(callId)) {
                                @SuppressWarnings("unchecked")
                                List<CallServiceDescriptor> descriptors =
                                        (List<CallServiceDescriptor>) args.arg2;

                                mCallIdMapper.removeCall(callId);
                                mPendingSelects.remove(callId).onResult(descriptors, 0, null);
                            } else {
                                Log.w(this, "setSelectedCallServices: unknown call: %s, id: %s",
                                        callId, args.arg1);
                            }
                        } finally {
                            args.recycle();
                        }
                        break;
                    }
                    case MSG_CANCEL_OUTGOING_CALL: {
                        Call call = mCallIdMapper.getCall(msg.obj);
                        if (call != null) {
                            call.abort();
                        } else {
                            Log.w(this, "cancelOutgoingCall: unknown call: %s, id: %s", call,
                                    msg.obj);
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
                                Log.w(this, "setHandoffInfo: unknown call: %s, id: %s",
                                        call, args.arg1);
                            }
                        } finally {
                            args.recycle();
                        }
                        break;
                    }
                }
            }
        };

        @Override
        public void setSelectedCallServices(String callId,
                List<CallServiceDescriptor> descriptors) {
            mCallIdMapper.checkValidCallId(callId);
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = callId;
            args.arg2 = descriptors;
            mHandler.obtainMessage(MSG_SET_SELECTED_CALL_SERVICES, args).sendToTarget();
        }

        @Override
        public void cancelOutgoingCall(String callId) {
            mCallIdMapper.checkValidCallId(callId);
            mHandler.obtainMessage(MSG_CANCEL_OUTGOING_CALL, callId).sendToTarget();
        }

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

    private final Binder mBinder = new Binder();
    private final CallIdMapper mCallIdMapper = new CallIdMapper("CallServiceSelector");
    private final Adapter mAdapter = new Adapter();
    private final CallsManager mCallsManager;
    private final Map<String, AsyncResultCallback<List<CallServiceDescriptor>>> mPendingSelects =
            new HashMap<>();

    private ICallServiceSelector mSelectorInterface;

    /**
     * Creates a call-service selector for the specified component using the specified action to
     * bind to it.
     *
     * @param action The action used to bind to the selector.
     * @param componentName The component name of the service.
     * @param callsManager The calls manager.
     */
    CallServiceSelectorWrapper(
            String action, ComponentName componentName, CallsManager callsManager) {

        super(action, componentName);
        mCallsManager = callsManager;
    }

    /**
     * Creates a call-service selector for specified component and uses
     * {@link TelecommConstants#ACTION_CALL_SERVICE_SELECTOR} as the action to bind.
     *
     * @param componentName The component name of the service.
     * @param callsManager The calls manager.
     */
    CallServiceSelectorWrapper(ComponentName componentName, CallsManager callsManager) {
        this(TelecommConstants.ACTION_CALL_SERVICE_SELECTOR, componentName, callsManager);
    }

    /**
     * Retrieves the sorted set of call services that are preferred by this selector. Upon failure,
     * the error callback is invoked. Can be invoked even when the call service is unbound.
     *
     * @param call The call being placed using the {@link CallService}s.
     * @param descriptors The descriptors of the available {@link CallService}s with which to place
     *            the call.
     * @param resultCallback The callback on which to return the result.
     */
    void select(
            final Call call,
            final List<CallServiceDescriptor> descriptors,
            final AsyncResultCallback<List<CallServiceDescriptor>> resultCallback) {

        BindCallback callback = new BindCallback() {
            @Override
            public void onSuccess() {
                String callId = mCallIdMapper.getCallId(call);
                mPendingSelects.put(callId, resultCallback);

                try {
                    CallInfo callInfo = call.toCallInfo(mCallIdMapper.getCallId(call));
                    mSelectorInterface.select(callInfo, descriptors);
                } catch (RemoteException e) {
                    mCallIdMapper.removeCall(call);
                    mPendingSelects.get(callId).onResult(
                            null, DisconnectCause.ERROR_UNSPECIFIED, e.toString());
                }
            }

            @Override
            public void onFailure() {
                resultCallback.onResult(null, DisconnectCause.ERROR_UNSPECIFIED, null);
            }
        };

        mBinder.bind(callback);
    }

    void addCall(Call call) {
        mCallIdMapper.addCall(call);
        onCallUpdated(call.toCallInfo(mCallIdMapper.getCallId(call)));
    }

    void removeCall(Call call) {
        String callId = mCallIdMapper.getCallId(call);
        mCallIdMapper.removeCall(call);
        onCallRemoved(callId);
    }

    /** {@inheritDoc} */
    @Override
    protected void setServiceInterface(IBinder binder) {
        if (binder == null) {
            mSelectorInterface = null;
        } else {
            mSelectorInterface = ICallServiceSelector.Stub.asInterface(binder);
            setCallServiceSelectorAdapter(mAdapter);
        }
    }

    private void setCallServiceSelectorAdapter(ICallServiceSelectorAdapter adapter) {
        if (isServiceValid("setCallServiceSelectorAdapter")) {
            try {
                mSelectorInterface.setCallServiceSelectorAdapter(adapter);
            } catch (RemoteException e) {
            }
        }
    }

    private void onCallUpdated(final CallInfo callInfo) {
        BindCallback callback = new BindCallback() {
            @Override
            public void onSuccess() {
                if (isServiceValid("onCallUpdated")) {
                    try {
                        mSelectorInterface.onCallUpdated(callInfo);
                    } catch (RemoteException e) {
                    }
                }
            }

            @Override
            public void onFailure() {
            }
        };
        mBinder.bind(callback);
    }

    private void onCallRemoved(final String callId) {
        BindCallback callback = new BindCallback() {
            @Override
            public void onSuccess() {
                if (isServiceValid("onCallRemoved")) {
                    try {
                        mSelectorInterface.onCallRemoved(callId);
                    } catch (RemoteException e) {
                    }
                }
            }

            @Override
            public void onFailure() {
            }
        };
        mBinder.bind(callback);
    }
}
