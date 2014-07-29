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

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;

import android.content.res.Resources;
import android.net.Uri;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.telecomm.CallAudioState;
import android.telecomm.CallCapabilities;
import android.telecomm.CallPropertyPresentation;
import android.telecomm.CallState;
import android.telecomm.ParcelableCall;

import com.android.internal.telecomm.IInCallService;
import com.google.common.collect.ImmutableCollection;

import java.util.ArrayList;
import java.util.List;

/**
 * Binds to {@link IInCallService} and provides the service to {@link CallsManager} through which it
 * can send updates to the in-call app. This class is created and owned by CallsManager and retains
 * a binding to the {@link IInCallService} (implemented by the in-call app).
 */
public final class InCallController extends CallsManagerListenerBase {
    /**
     * Used to bind to the in-call app and triggers the start of communication between
     * this class and in-call app.
     */
    private class InCallServiceConnection implements ServiceConnection {
        /** {@inheritDoc} */
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            onConnected(service);
        }

        /** {@inheritDoc} */
        @Override public void onServiceDisconnected(ComponentName name) {
            onDisconnected();
        }
    }

    private final Call.Listener mCallListener = new Call.ListenerBase() {
        @Override
        public void onCallCapabilitiesChanged(Call call) {
            updateCall(call);
        }

        @Override
        public void onCannedSmsResponsesLoaded(Call call) {
            updateCall(call);
        }

        @Override
        public void onVideoCallProviderChanged(Call call) {
            updateCall(call);
        }

        @Override
        public void onStatusHintsChanged(Call call) {
            updateCall(call);
        }

        @Override
        public void onHandleChanged(Call call) {
            updateCall(call);
        }

        @Override
        public void onCallerDisplayNameChanged(Call call) {
            updateCall(call);
        }

        @Override
        public void onVideoStateChanged(Call call) {
            updateCall(call);
        }

        @Override
        public void onStartActivityFromInCall(Call call, PendingIntent intent) {
            if (mInCallService != null) {
                Log.i(this, "Calling startActivity, intent: %s", intent);
                try {
                    mInCallService.startActivity(mCallIdMapper.getCallId(call), intent);
                } catch (RemoteException ignored) {
                }
            }
        }

        @Override
        public void onTargetPhoneAccountChanged(Call call) {
            updateCall(call);
        }

        @Override
        public void onConferenceableCallsChanged(Call call) {
            updateCall(call);
        }
    };

    /** Maintains a binding connection to the in-call app. */
    private final InCallServiceConnection mConnection = new InCallServiceConnection();

    /** The in-call app implementation, see {@link IInCallService}. */
    private IInCallService mInCallService;

    private final CallIdMapper mCallIdMapper = new CallIdMapper("InCall");

    IInCallService getService() {
        return mInCallService;
    }

    @Override
    public void onCallAdded(Call call) {
        if (mInCallService == null) {
            bind();
        } else {
            Log.i(this, "Adding call: %s", call);
            if (mCallIdMapper.getCallId(call) == null) {
                mCallIdMapper.addCall(call);
                call.addListener(mCallListener);
                try {
                    mInCallService.addCall(toParcelableCall(call));
                } catch (RemoteException ignored) {
                }
            }
        }
    }

    @Override
    public void onCallRemoved(Call call) {
        if (CallsManager.getInstance().getCalls().isEmpty()) {
            // TODO(sail): Wait for all messages to be delivered to the service before unbinding.
            unbind();
        }
        call.removeListener(mCallListener);
        mCallIdMapper.removeCall(call);
    }

    @Override
    public void onCallStateChanged(Call call, CallState oldState, CallState newState) {
        updateCall(call);
    }

    @Override
    public void onConnectionServiceChanged(
            Call call,
            ConnectionServiceWrapper oldService,
            ConnectionServiceWrapper newService) {
        updateCall(call);
    }

    @Override
    public void onAudioStateChanged(CallAudioState oldAudioState, CallAudioState newAudioState) {
        if (mInCallService != null) {
            Log.i(this, "Calling onAudioStateChanged, audioState: %s -> %s", oldAudioState,
                    newAudioState);
            try {
                mInCallService.onAudioStateChanged(newAudioState);
            } catch (RemoteException ignored) {
            }
        }
    }

    void onPostDialWait(Call call, String remaining) {
        if (mInCallService != null) {
            Log.i(this, "Calling onPostDialWait, remaining = %s", remaining);
            try {
                mInCallService.setPostDialWait(mCallIdMapper.getCallId(call), remaining);
            } catch (RemoteException ignored) {
            }
        }
    }

    @Override
    public void onIsConferencedChanged(Call call) {
        Log.v(this, "onIsConferencedChanged %s", call);
        updateCall(call);
    }

    void bringToForeground(boolean showDialpad) {
        if (mInCallService != null) {
            try {
                mInCallService.bringToForeground(showDialpad);
            } catch (RemoteException ignored) {
            }
        } else {
            Log.w(this, "Asking to bring unbound in-call UI to foreground.");
        }
    }

    /**
     * Unbinds an existing bound connection to the in-call app.
     */
    private void unbind() {
        ThreadUtil.checkOnMainThread();
        if (mInCallService != null) {
            Log.i(this, "Unbinding from InCallService");
            TelecommApp.getInstance().unbindService(mConnection);
            mInCallService = null;
        }
    }

    /**
     * Binds to the in-call app if not already connected by binding directly to the saved
     * component name of the {@link IInCallService} implementation.
     */
    private void bind() {
        ThreadUtil.checkOnMainThread();
        if (mInCallService == null) {
            Context context = TelecommApp.getInstance();
            Resources resources = context.getResources();
            ComponentName component = new ComponentName(
                    resources.getString(R.string.ui_default_package),
                    resources.getString(R.string.incall_default_class));
            Log.i(this, "Attempting to bind to InCallService: %s", component);

            Intent serviceIntent = new Intent(IInCallService.class.getName());
            serviceIntent.setComponent(component);

            if (!context.bindServiceAsUser(serviceIntent, mConnection, Context.BIND_AUTO_CREATE,
                    UserHandle.CURRENT)) {
                Log.w(this, "Could not connect to the in-call app (%s)", component);

                // TODO(santoscordon): Implement retry or fall-back-to-default logic.
            }
        }
    }

    /**
     * Persists the {@link IInCallService} instance and starts the communication between
     * this class and in-call app by sending the first update to in-call app. This method is
     * called after a successful binding connection is established.
     *
     * @param service The {@link IInCallService} implementation.
     */
    private void onConnected(IBinder service) {
        ThreadUtil.checkOnMainThread();
        mInCallService = IInCallService.Stub.asInterface(service);

        try {
            mInCallService.setInCallAdapter(new InCallAdapter(CallsManager.getInstance(),
                    mCallIdMapper));
        } catch (RemoteException e) {
            Log.e(this, e, "Failed to set the in-call adapter.");
            mInCallService = null;
            return;
        }

        // Upon successful connection, send the state of the world to the in-call app.
        ImmutableCollection<Call> calls = CallsManager.getInstance().getCalls();
        if (!calls.isEmpty()) {
            for (Call call : calls) {
                onCallAdded(call);
            }
            onAudioStateChanged(null, CallsManager.getInstance().getAudioState());
        } else {
            unbind();
        }
    }

    /**
     * Cleans up the instance of in-call app after the service has been unbound.
     */
    private void onDisconnected() {
        ThreadUtil.checkOnMainThread();
        mInCallService = null;
    }

    private void updateCall(Call call) {
        if (mInCallService != null) {
            try {
                ParcelableCall parcelableCall = toParcelableCall(call);
                Log.v(this, "updateCall %s ==> %s", call, parcelableCall);
                mInCallService.updateCall(parcelableCall);
            } catch (RemoteException ignored) {
            }
        }
    }

    private ParcelableCall toParcelableCall(Call call) {
        String callId = mCallIdMapper.getCallId(call);

        int capabilities = call.getCallCapabilities();
        if (CallsManager.getInstance().isAddCallCapable(call)) {
            capabilities |= CallCapabilities.ADD_CALL;
        }
        if (!call.isEmergencyCall()) {
            capabilities |= CallCapabilities.MUTE;
        }

        CallState state = call.getState();
        if (state == CallState.ABORTED) {
            state = CallState.DISCONNECTED;
        }

        String parentCallId = null;
        Call parentCall = call.getParentCall();
        if (parentCall != null) {
            parentCallId = mCallIdMapper.getCallId(parentCall);
        }

        long connectTimeMillis = call.getConnectTimeMillis();
        List<Call> childCalls = call.getChildCalls();
        List<String> childCallIds = new ArrayList<>();
        if (!childCalls.isEmpty()) {
            connectTimeMillis = Long.MAX_VALUE;
            for (Call child : childCalls) {
                connectTimeMillis = Math.min(child.getConnectTimeMillis(), connectTimeMillis);
                childCallIds.add(mCallIdMapper.getCallId(child));
            }
        }

        if (call.isRespondViaSmsCapable()) {
            capabilities |= CallCapabilities.RESPOND_VIA_TEXT;
        }

        Uri handle = call.getHandlePresentation() == CallPropertyPresentation.ALLOWED ?
                call.getHandle() : null;
        String callerDisplayName = call.getCallerDisplayNamePresentation() ==
                CallPropertyPresentation.ALLOWED ?  call.getCallerDisplayName() : null;

        List<Call> conferenceableCalls = call.getConferenceableCalls();
        List<String> conferenceableCallIds = new ArrayList<String>(conferenceableCalls.size());
        for (Call otherCall : conferenceableCalls) {
            String otherId = mCallIdMapper.getCallId(otherCall);
            if (otherId != null) {
                conferenceableCallIds.add(otherId);
            }
        }

        return new ParcelableCall(
                callId,
                state,
                call.getDisconnectCause(),
                call.getDisconnectMessage(),
                call.getCannedSmsResponses(),
                capabilities,
                connectTimeMillis,
                handle,
                call.getHandlePresentation(),
                callerDisplayName,
                call.getCallerDisplayNamePresentation(),
                call.getGatewayInfo(),
                call.getTargetPhoneAccount(),
                call.getVideoCallProvider(),
                parentCallId,
                childCallIds,
                call.getStatusHints(),
                call.getVideoState(),
                conferenceableCallIds);
    }
}
