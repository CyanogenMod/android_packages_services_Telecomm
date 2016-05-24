/*
 * Copyright (c) 2014-2015 The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *     * Neither the name of The Linux FOundation, Inc. nor the names of its
 *       contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.server.telecom.ui;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Binder;
import android.os.IBinder;
import android.os.Handler;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.telecom.TelecomManager;
import android.telecom.VideoProfile;
import android.telephony.TelephonyManager;
import android.text.TextUtils;

import com.android.ims.ImsManager;
import com.android.internal.telephony.TelephonyProperties;
import com.android.server.telecom.Call;
import com.android.server.telecom.CallState;
import com.android.server.telecom.CallsManager;
import com.android.server.telecom.CallsManagerListenerBase;
import com.android.server.telecom.R;
import com.android.server.telecom.Log;
import com.android.server.telecom.components.TelecomBroadcastReceiver;
import com.android.server.telecom.TelecomBroadcastIntentProcessor;

import org.codeaurora.ims.qtiims.IQtiImsInterface;
import org.codeaurora.ims.qtiims.IQtiImsInterfaceListener;
import org.codeaurora.ims.qtiims.QtiImsInterfaceListenerBaseImpl;
import org.codeaurora.ims.qtiims.QtiImsInterfaceUtils;
import org.codeaurora.ims.qtiims.QtiViceInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Handles the VICE notifications on the statusbar
 * And when statusbar is expanded
 */
public class ViceNotificationImpl extends CallsManagerListenerBase {
    private final Context mContext;
    private final NotificationManager mNotificationManager;
    private IQtiImsInterface mQtiImsInterface = null;
    private boolean mImsServiceBound = false;
    private Notification.Builder mBuilder = null;
    private Notification.Builder mPublicNotificationBuilder = null;

    private MyHandler mHandler = new MyHandler();

    /**
     * Holds Pullable and NonPullable calls passed from IMS Service
     * Each String[] contains call info in following order
     * Number, Pullable/NonPullable, CallType, Direction
     * Ex: For callInfo[] -
     * callInfo[QtiViceInfo.INDEX_DIALOG_ID] - Holds Unique DialogId
     * callInfo[QtiViceInfo.INDEX_NUMBER] - Holds number/uri
     * callInfo[QtiViceInfo.INDEX_ISPULLABLE] - Pullable/NonPullable (true, false)
     * callInfo[QtiViceInfo.INDEX_CALLTYPE] - CallType
     *     CallType - volteactive, volteheld, vttxrx, vttx, vtrx, vtheld
     * callInfo[QtiViceInfo.INDEX_DIRECTION] - Direction of the call (Originator/recipent)
     */
    private List<String[]> mQtiViceInfo = null;

    // Holds the callInfo which needs to be displayed after the active call ends
    private  List<String[]> mBackedUpCallList = null;

    private boolean mWasInCall = false;

    // HashMap that holds Dialog Number & Notification Id used to display on the statusbar
    private Map<String,Integer> mNotification = new HashMap<String,Integer>();

    private ImsIntentReceiver mImsIntentReceiver = null;

    private static final String IMS_SERVICE_PKG_NAME = "org.codeaurora.ims";

    public ViceNotificationImpl(Context context, CallsManager callsManager) {
        mContext = context;
        mNotificationManager =
                (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        Log.i(this,"ViceNotificationImpl");
        if (!bindImsService()) {
            //Register for IMS ready intent to re try bind
            registerImsReceiver();
        }
    }

    private class MyHandler extends Handler {
        static final int MESSAGE_VICE_NOTIFY = 1;
        static final int MESSAGE_CALL_ENDED = 2;

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_VICE_NOTIFY:
                    Log.i(this,"MESSAGE_VICE_NOTIFY");
                    resetBeforeProcess();
                    processNotification();
                    break;

                case MESSAGE_CALL_ENDED:
                    if (mWasInCall) {
                        Log.i(this,"MESSAGE_CALL_ENDED");
                        resetBeforeProcess();
                        mQtiViceInfo = mBackedUpCallList;
                        processNotification();
                    }
                    break;

                default:
                    Log.i(this,"VICE default");
            }
        }
    }

    private class ImsIntentReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i("ViceNotificationImpl", "mImsIntentReceiver: action " + intent.getAction());
            if (ImsManager.ACTION_IMS_SERVICE_UP.equals(intent.getAction())) {
                if (bindImsService()) {
                    unregisterImsReceiver();
                }
            }
        }
    }

    // Clear the existing notifications on statusbar and
    // Hashmap whenever new Vice Notification is received
    private void resetBeforeProcess() {
        mBuilder = null;
        mPublicNotificationBuilder = null;
        mWasInCall = false;
        checkAndUpdateNotification();
    }

    /* Service connection bound to IQtiImsInterface */
    private ServiceConnection mConnection = new ServiceConnection() {

        /* Below API gets invoked when connection to ImsService is established */
        public void onServiceConnected(ComponentName className, IBinder service) {
            Log.i("ViceNotificationImpl", "onServiceConnected");
            /* Retrieve the IQtiImsInterface */
            mQtiImsInterface = IQtiImsInterface.Stub.asInterface(service);

            /**
             * If interface is available register for Vice notifications
             */
            if (mQtiImsInterface != null) {
                registerForViceRefreshInfo();
            } else {
                /* Request or interface is unavailable, unbind the service */
                unbindImsService();
            }
        }

        /* Below API gets invoked when connection to ImsService is disconnected */
        public void onServiceDisconnected(ComponentName className) {
            Log.i("ViceNotificationImpl", "onServiceDisconnected");
        }
    };

    /* QtiImsInterfaceListenerBaseImpl instance to handle call backs */
    private IQtiImsInterfaceListener imsInterfaceListener =
        new QtiImsInterfaceListenerBaseImpl() {

        @Override
        public void notifyRefreshViceInfo(QtiViceInfo qtiViceInfo) {
            mQtiViceInfo = null;
            processViceCallInfo(qtiViceInfo);
        }
    };

    public void registerForViceRefreshInfo() {
        try {
            Log.d(this, "registerForViceRefreshInfo");
            mQtiImsInterface.registerForViceRefreshInfo(imsInterfaceListener);
        } catch (RemoteException e) {
            Log.d(this, "registerForViceRefreshInfo exception " + e);
        }
    }

    /**
     * Informs if call deflection interafce is available or not.
     * Returns true if allowed, false otherwise.
     */
    public boolean isQtiImsInterfaceAvailable() {
        return (mImsServiceBound && (mQtiImsInterface != null));
    }

    /**
     * Checks if ims service is bound or not
     * Returns true when bound, false otherwise.
     */
    public boolean isImsServiceAvailable() {
        return mImsServiceBound;
    }

    /**
     * Bind to the ims service
     * Returns true if bound sucessfully, false otherwise.
     */
    public boolean bindImsService() {
        if (!ImsManager.getInstance(mContext, 0).isServiceAvailable()) {
            Log.d(this, "bindImsService: IMS service is not available!");
            return false;
        }
        Intent intent = new Intent(IQtiImsInterface.class.getName());
        intent.setPackage(IMS_SERVICE_PKG_NAME);
        mImsServiceBound = mContext.bindService(intent,
                                   mConnection,
                                   0);
        Log.d(this, "Getting IQtiImsInterface : " + (mImsServiceBound?"yes":"failed"));
        return mImsServiceBound;
    }

    /* Unbind the ims service if was already bound */
    public void unbindImsService() {
        if (mImsServiceBound) {
            Log.d(this, "UnBinding IQtiImsInterface");

            /* When disconnecting, reset the globals variables */
            mImsServiceBound = false;
            mContext.unbindService(mConnection);
        }
    }

    private void processViceCallInfo(QtiViceInfo qtiViceInfo) {
        mQtiViceInfo = new ArrayList<String[]>();
        mQtiViceInfo = qtiViceInfo.callInfo;
        // Post an event to self handler
        mHandler.sendEmptyMessage(MyHandler.MESSAGE_VICE_NOTIFY);
    }

    private void resetBuilders() {
        mBuilder = null;
        mPublicNotificationBuilder = null;
        mBuilder = new Notification.Builder(mContext);
        mPublicNotificationBuilder = new Notification.Builder(mContext);
    }

    /**
     * This function does the following -
     * - Iterate through the callList
     * - Update the hashmap for notification
     * - AddAction for pullable calls to update the button "TAP TO PULL"
     * - Build notification and show it
     *
     * "Tap to Pull" Button should be seen only if -
     * - Phone is in idle state AND
     * - CallState received is ACTIVE for Voice calls or ACTIVE/SENDONLY/RECVONLY for VT AND
     * - If Volte/VT is supported on device AND
     * - IsPullable is true
     */
    private void processNotification() {
        Random random = new Random();
        int notifId = 0;
        boolean isVt = false;

        if ((mQtiViceInfo != null) && !mQtiViceInfo.isEmpty()) {
            Log.i(this, "processNotification : Number of Calls = "
                    + mQtiViceInfo.size() + ", notif = " + notifId);

            for (int i = 0; i < mQtiViceInfo.size(); i++) {
                notifId = random.nextInt(500);
                String[] callInfo = new String[QtiViceInfo.INDEX_MAX];
                callInfo = mQtiViceInfo.get(i);
                Log.i(this, "processNotification callInfo[" + i + "] = "
                        + ", DialogId = " + callInfo[QtiViceInfo.INDEX_DIALOG_ID]
                        + ", Number = " + callInfo[QtiViceInfo.INDEX_NUMBER]
                        + ", Pullable = " + callInfo[QtiViceInfo.INDEX_ISPULLABLE]
                        + ", CallType = " + callInfo[QtiViceInfo.INDEX_CALLTYPE]
                        + ", Direction = " + callInfo[QtiViceInfo.INDEX_DIRECTION]
                        + ", notifId = " + notifId);

                resetBuilders();
                Log.i(this, "processNotification isInCall = " + getTelecomManager().isInCall());
                isVt = isVtCall(callInfo[QtiViceInfo.INDEX_CALLTYPE]);

                // Once the active call ends, we need to display "Tap to pull" option
                // for the pullable calls. Hence backup the callinfo
                if (getTelecomManager().isInCall()) {
                    backupInfoToProcessLater();
                }
                // Refer comments in API description
                if (!(getTelecomManager().isInCall()) &&
                        callInfo[QtiViceInfo.INDEX_ISPULLABLE].equalsIgnoreCase("true") &&
                        isDeviceCapableOfPull(callInfo[QtiViceInfo.INDEX_CALLTYPE])) {
                    addAction(callInfo[QtiViceInfo.INDEX_NUMBER], isVt,
                            callInfo[QtiViceInfo.INDEX_CALLTYPE],
                            callInfo[QtiViceInfo.INDEX_DIALOG_ID]);
                } else {
                    addAction(null, false, null, null);
                }
                updateLargeIconforCallType(isVt);

                showNotification(callInfo[QtiViceInfo.INDEX_NUMBER],
                        callInfo[QtiViceInfo.INDEX_DIALOG_ID], notifId);
            }
        } else {
            Log.i(this, "processNotification DEP null");
        }
    }

    private boolean isDeviceCapableOfPull(String callType) {
        return ((isVtCall(callType) && isVTPullAllowed()) ||
                ((callType != null) && callType.equalsIgnoreCase(
                QtiViceInfo.CALL_TYPE_VOICE_ACTIVE) && isVoltePullAllowed()));
    }

    private boolean isVTPullAllowed() {
        return TelephonyManager.getDefault().isVideoCallingEnabled();
    }

    private boolean isVoltePullAllowed() {
        return TelephonyManager.getDefault().isVolteAvailable()
                || TelephonyManager.getDefault().isWifiCallingAvailable();
    }

    private boolean isVtCall(String callType) {
        if ((callType != null) && (callType.equalsIgnoreCase(QtiViceInfo.CALL_TYPE_VIDEO_TX_RX) ||
                callType.equalsIgnoreCase(QtiViceInfo.CALL_TYPE_VIDEO_TX) ||
                callType.equalsIgnoreCase(QtiViceInfo.CALL_TYPE_VIDEO_RX) ||
                callType.equalsIgnoreCase(QtiViceInfo.CALL_TYPE_VIDEO_HELD))) {
            return true;
        }
        return false;
    }

    /**
     * After the active call disconnects, need to refresh the notification
     * to show pullable calls. Hence save the call information
     *
     */
    private void backupInfoToProcessLater() {
        mBackedUpCallList = new ArrayList<String[]>();
        mBackedUpCallList = mQtiViceInfo;
        // Call can be ended either through "Tap To Pull" or existing call ending
        // Reset the variables only for later case
        // Use this boolean to track the END reason
        mWasInCall = true;
    }

    /**
     * Retrieve all notifications from the map.
     * Cancel and remove all notifications from the map.
     * CancelAll not used as it is an asynchronous call and can cause issue with
     * back to back notifications.
     */
    private void checkAndUpdateNotification() {
        Set<Map.Entry<String, Integer>> call = mNotification.entrySet();
        if ((call == null) || (mNotification.isEmpty())) {
            return;
        }

        Iterator<Map.Entry<String, Integer>> iterator = call.iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Integer> entry = iterator.next();
            String dialog = entry.getKey();
            Integer notifId = entry.getValue();
            mNotificationManager.cancel(notifId);
            iterator.remove();
        }
    }

    /**
     * This API should be invoked only for pullable calls
     * Responsible to add "TAP to Pull" button, using which call
     * can be pulled
     */
    private void addAction(String uri, boolean isVt, String callType, String dialogId) {
        Log.i(this, "addAction dialogId = " + dialogId + ", isVt = " + isVt
                + ", callType = " + callType);
        if (uri != null) {
            mBuilder.addAction(R.drawable.ic_phone_24dp,
                    mContext.getString(R.string.pull_to_call_back),
                    createCallBackPendingIntent(Uri.parse(uri), isVt, callType, dialogId));
            mBuilder.setPriority(Notification.PRIORITY_HIGH);
        }
    }

    /*
     * When the notification bar is pulled,
     * Update - Video icon for VT Calls
     *        - Phone icon for Volte calls
     */
    private void updateLargeIconforCallType(boolean isVt) {
       Log.i(this, "updateLargeIconforCallType isVt  = " + isVt);
       Bitmap bitmap;
       if (isVt) {
            bitmap = BitmapFactory.decodeResource(mContext.getResources(),
                    R.drawable.video_icon);
       } else {
            bitmap = BitmapFactory.decodeResource(mContext.getResources(),
                    R.drawable.volte_icon);
       }
       mBuilder.setLargeIcon(bitmap);
    }

    /*
     * When the phone is pattern locked, display only restricted information
     * for this notification
     */
    private void buildPublicNotification() {
        // Update the text in the public version as well
        mPublicNotificationBuilder
                .setContentTitle(mContext.getString(R.string.notification_pullcall))
                .setAutoCancel(true)
                .setColor(com.android.internal.R.color.system_notification_accent_color);
    }

    // Builds & displays the notification on statusbar
    private void showNotification(String uri, String dialog, int notifId) {
        PendingIntent contentIntent = PendingIntent.getActivity( mContext,
                0, new Intent(), PendingIntent.FLAG_UPDATE_CURRENT);

        buildPublicNotification();

        mBuilder.setContentText(uri)
                .setColor(Color.BLUE)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(mPublicNotificationBuilder.build())
                .setDeleteIntent(contentIntent);

        // For 1st notification, display the icon on the statusbar
        // For subsequent notification, dont display the icon
        Log.i(this," showNotification size = " + mNotification.size() + ", notifid = " + notifId);
        if (mNotification.size() == 0) {
            mBuilder.setSmallIcon(R.drawable.ic_phone_24dp);
        } else {
            mBuilder.setSmallIcon(android.R.color.transparent);
        }

        mNotificationManager.notify(notifId, mBuilder.build());
        // Add the Dialog+Notification ID to hashmap
        mNotification.put(dialog, notifId);
    }

    private PendingIntent createCallBackPendingIntent(Uri handle, boolean isVt,
        String callType, String dialogId) {
        return createTelecomPendingIntent(
                TelecomBroadcastIntentProcessor.ACTION_CALL_PULL, handle, isVt, callType, dialogId);
    }

    /**
     * Creates generic pending intent from the specified parameters to be received by
     * {@link TelecomBroadcastIntentProcessor}.
     *
     * @param action The intent action.
     * @param data The intent data.
     */
    private PendingIntent createTelecomPendingIntent(String action, Uri data,
            boolean isVt, String callType, String dialogId) {
        Intent intent = new Intent(action, data, mContext, TelecomBroadcastReceiver.class);
        // Add following extras so that Dial request is placed for CallPull
        // And parsing is avoided for dialstring
        intent.putExtra("org.codeaurora.ims.VICE_CLEAR", dialogId);
        if (isVt) {
            // Extra to start Dial with VT enabled
            intent.putExtra(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE,
                    covertStringToIntVtType(callType));
        } else {
            intent.putExtra(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE,
                    VideoProfile.STATE_AUDIO_ONLY);
        }
        return PendingIntent.getBroadcast(mContext, 0, intent, PendingIntent.FLAG_CANCEL_CURRENT);
    }

    private int covertStringToIntVtType(String vtType) {
        if (vtType != null) {
            if (vtType.equalsIgnoreCase(QtiViceInfo.CALL_TYPE_VIDEO_TX_RX)) {
                return VideoProfile.STATE_BIDIRECTIONAL;
            } else if (vtType.equalsIgnoreCase(QtiViceInfo.CALL_TYPE_VIDEO_TX)) {
                return VideoProfile.STATE_TX_ENABLED;
            } else if (vtType.equalsIgnoreCase(QtiViceInfo.CALL_TYPE_VIDEO_RX)) {
                return VideoProfile.STATE_RX_ENABLED;
            } else {
                return VideoProfile.STATE_AUDIO_ONLY;
            }
        } else {
            Log.i(this, "covertStringToIntVtType vttype null!!");
            return VideoProfile.STATE_AUDIO_ONLY;
        }
    }

    private TelecomManager getTelecomManager() {
        return (TelecomManager) mContext.getSystemService(Context.TELECOM_SERVICE);
    }

    /**
     * During any ongoing calls, if DEP is received with pullable calls,
     * need to change them to non-pullable. But after calls ends, need to
     * make them as pullable again.
     */
    @Override
    public void onCallStateChanged(Call call, int oldState, int newState) {
        if ((newState == CallState.ACTIVE) || (newState == CallState.DISCONNECTED)) {
            Log.i(this, "onCallStateChanged newState = " + newState);
            backupInfoToProcessLater();
            mHandler.sendEmptyMessage(MyHandler.MESSAGE_CALL_ENDED);
        }
    }

    private void registerImsReceiver() {
        mImsIntentReceiver = new ImsIntentReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(ImsManager.ACTION_IMS_SERVICE_UP);
        mContext.registerReceiver(mImsIntentReceiver, filter);
    }

    private void unregisterImsReceiver() {
        if (mImsIntentReceiver != null) {
            mContext.unregisterReceiver(mImsIntentReceiver);
            mImsIntentReceiver = null;
        }
    }
}
