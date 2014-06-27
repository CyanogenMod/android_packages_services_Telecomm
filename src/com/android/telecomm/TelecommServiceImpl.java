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

import com.google.android.collect.Lists;

import com.android.internal.telecomm.ITelecommService;

import android.content.ComponentName;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ServiceManager;
import android.telecomm.CallState;
import android.telecomm.Subscription;
import android.text.TextUtils;

import java.util.List;

/**
 * Implementation of the ITelecomm interface.
 */
public class TelecommServiceImpl extends ITelecommService.Stub {
    /**
     * A request object for use with {@link MainThreadHandler}. Requesters should wait() on the
     * request after sending. The main thread will notify the request when it is complete.
     */
    private static final class MainThreadRequest {
        /** The result of the request that is run on the main thread */
        public Object result;
    }

    /**
     * A handler that processes messages on the main thread in the phone process. Since many
     * of the Phone calls are not thread safe this is needed to shuttle the requests from the
     * inbound binder threads to the main thread in the phone process.
     */
    private final class MainThreadHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            if (msg.obj instanceof MainThreadRequest) {
                MainThreadRequest request = (MainThreadRequest) msg.obj;
                Object result = null;
                switch (msg.what) {
                    case MSG_SILENCE_RINGER:
                        mCallsManager.getRinger().silence();
                        break;
                    case MSG_SHOW_CALL_SCREEN:
                        mCallsManager.getInCallController().bringToForeground(msg.arg1 == 1);
                        break;
                    case MSG_IS_IN_A_PHONE_CALL:
                        result = mCallsManager.hasAnyCalls();
                        break;
                    case MSG_IS_RINGING:
                        result = mCallsManager.hasRingingCall();
                        break;
                    case MSG_END_CALL:
                        result = endCallInternal();
                        break;
                    case MSG_ACCEPT_RINGING_CALL:
                        acceptRingingCallInternal();
                        break;
                }

                if (result != null) {
                    request.result = result;
                    synchronized(request) {
                        request.notifyAll();
                    }
                }
            }
        }
    }

    /** Private constructor; @see init() */
    private static final String TAG = TelecommServiceImpl.class.getSimpleName();

    private static final String SERVICE_NAME = "telecomm";

    private static final int MSG_SILENCE_RINGER = 1;
    private static final int MSG_SHOW_CALL_SCREEN = 2;
    private static final int MSG_IS_IN_A_PHONE_CALL = 3;
    private static final int MSG_IS_RINGING = 4;
    private static final int MSG_END_CALL = 5;
    private static final int MSG_ACCEPT_RINGING_CALL = 6;

    /** The singleton instance. */
    private static TelecommServiceImpl sInstance;

    private final CallsManager mCallsManager = CallsManager.getInstance();

    private final MainThreadHandler mMainThreadHandler = new MainThreadHandler();

    private TelecommServiceImpl() {
        publish();
    }

    /**
     * Initialize the singleton TelecommServiceImpl instance.
     * This is only done once, at startup, from TelecommApp.onCreate().
     */
    static TelecommServiceImpl init() {
        synchronized (TelecommServiceImpl.class) {
            if (sInstance == null) {
                sInstance = new TelecommServiceImpl();
            } else {
                Log.wtf(TAG, "init() called multiple times!  sInstance %s", sInstance);
            }
            return sInstance;
        }
    }

    //
    // Implementation of the ITelecommService interface.
    //

    @Override
    public List<Subscription> getSubscriptions() {
        return sSubscriptions;
    }

    @Override
    public void setEnabled(Subscription subscription, boolean enabled) {
        // Enforce MODIFY_PHONE_STATE ?
        // TODO
    }

    @Override
    public void setSystemDefault(Subscription subscription) {
        // Enforce MODIFY_PHONE_STATE ?
        // TODO
    }

    /**
     * @see TelecommManager#silenceringer
     */
    @Override
    public void silenceRinger() {
        Log.d(this, "silenceRinger");
        enforceModifyPermission();
        sendRequestAsync(MSG_SILENCE_RINGER, 0);
    }

    /**
     * @see TelecommManager#getDefaultPhoneApp
     */
    @Override
    public ComponentName getDefaultPhoneApp() {
        Resources resources = TelecommApp.getInstance().getResources();
        return new ComponentName(
                resources.getString(R.string.ui_default_package),
                resources.getString(R.string.dialer_default_class));
    }

    /**
     * @see TelecommManager#isInAPhoneCall
     */
    @Override
    public boolean isInAPhoneCall() {
        enforceReadPermission();
        return (boolean) sendRequest(MSG_IS_IN_A_PHONE_CALL);
    }

    /**
     * @see TelecommManager#isRinging
     */
    @Override
    public boolean isRinging() {
        enforceReadPermission();
        return (boolean) sendRequest(MSG_IS_RINGING);
    }

    /**
     * @see TelecommManager#endCall
     */
    @Override
    public boolean endCall() {
        enforceModifyPermission();
        return (boolean) sendRequest(MSG_END_CALL);
    }

    /**
     * @see TelecommManager#acceptRingingCall
     */
    @Override
    public void acceptRingingCall() {
        enforceModifyPermission();
        sendRequestAsync(MSG_ACCEPT_RINGING_CALL, 0);
    }

    @Override
    public void showCallScreen(boolean showDialpad) {
        mMainThreadHandler.obtainMessage(MSG_SHOW_CALL_SCREEN, showDialpad ? 1 : 0, 0)
                .sendToTarget();
    }

    //
    // Supporting methods for the ITelephony interface implementation.
    //

    private void acceptRingingCallInternal() {
        Call call = mCallsManager.getFirstCallWithState(CallState.RINGING);
        if (call != null) {
            call.answer();
        }
    }

    private boolean endCallInternal() {
        // Always operate on the foreground call if one exists, otherwise get the first call in
        // priority order by call-state.
        Call call = mCallsManager.getForegroundCall();
        if (call == null) {
            call = mCallsManager.getFirstCallWithState(
                    CallState.ACTIVE,
                    CallState.DIALING,
                    CallState.RINGING,
                    CallState.ON_HOLD);
        }

        if (call != null) {
            if (call.getState() == CallState.RINGING) {
                call.reject(false /* rejectWithMessage */, null);
            } else {
                call.disconnect();
            }
            return true;
        }

        return false;
    }

    /**
     * Make sure the caller has the MODIFY_PHONE_STATE permission.
     *
     * @throws SecurityException if the caller does not have the required permission
     */
    private void enforceModifyPermission() {
        TelecommApp.getInstance().enforceCallingOrSelfPermission(
                android.Manifest.permission.MODIFY_PHONE_STATE, null);
    }

    private void enforceReadPermission() {
        TelecommApp.getInstance().enforceCallingOrSelfPermission(
                android.Manifest.permission.READ_PHONE_STATE, null);
    }

    // TODO (STOPSHIP): Static list of Subscriptions for testing and UX work only.

    private  static final ComponentName sComponentName = new ComponentName(
            "com.android.telecomm",
            TelecommServiceImpl.class.getName());  // This field is a no-op

    private static final List<Subscription> sSubscriptions = Lists.newArrayList(
            new Subscription(
                    sComponentName,
                    "subscription0",
                    Uri.parse("tel:999-555-1212"),
                    R.string.test_subscription_0_label,
                    R.string.test_subscription_0_short_description,
                    R.drawable.q_mobile,
                    true,
                    true),
            new Subscription(
                    sComponentName,
                    "subscription1",
                    Uri.parse("tel:333-111-2222"),
                    R.string.test_subscription_1_label,
                    R.string.test_subscription_1_short_description,
                    R.drawable.market_wireless,
                    true,
                    false),
            new Subscription(
                    sComponentName,
                    "subscription2",
                    Uri.parse("mailto:two@example.com"),
                    R.string.test_subscription_2_label,
                    R.string.test_subscription_2_short_description,
                    R.drawable.talk_to_your_circles,
                    true,
                    false),
            new Subscription(
                    sComponentName,
                    "subscription3",
                    Uri.parse("mailto:three@example.com"),
                    R.string.test_subscription_3_label,
                    R.string.test_subscription_3_short_description,
                    R.drawable.chat_with_others,
                    true,
                    false)
    );

    private void publish() {
        Log.d(this, "publish: %s", this);
        ServiceManager.addService(SERVICE_NAME, this);
    }

    private MainThreadRequest sendRequestAsync(int command, int arg1) {
        MainThreadRequest request = new MainThreadRequest();
        mMainThreadHandler.obtainMessage(command, arg1, 0, request).sendToTarget();
        return request;
    }

    /**
     * Posts the specified command to be executed on the main thread, waits for the request to
     * complete, and returns the result.
     */
    private Object sendRequest(int command) {
        if (Looper.myLooper() == mMainThreadHandler.getLooper()) {
            throw new RuntimeException("This method will deadlock if called from the main thread.");
        }

        MainThreadRequest request = sendRequestAsync(command, 0);

        // Wait for the request to complete
        synchronized (request) {
            while (request.result == null) {
                try {
                    request.wait();
                } catch (InterruptedException e) {
                    // Do nothing, go back and wait until the request is complete
                }
            }
        }
        return request.result;
    }
}
