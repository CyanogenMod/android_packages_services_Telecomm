package com.android.server.telecom;

import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyProperties;
import com.android.server.telecom.components.ErrorDialogActivity;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Trace;
import android.os.UserHandle;
import android.telecom.Connection;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telecom.VideoProfile;
import android.telephony.DisconnectCause;
import android.telephony.PhoneNumberUtils;
import android.widget.Toast;

import org.codeaurora.QtiVideoCallConstants;

/**
 * Single point of entry for all outgoing and incoming calls.
 * {@link com.android.server.telecom.components.UserCallIntentProcessor} serves as a trampoline that
 * captures call intents for individual users and forwards it to the {@link CallIntentProcessor}
 * which interacts with the rest of Telecom, both of which run only as the primary user.
 */
public class CallIntentProcessor {

    public static final String KEY_IS_UNKNOWN_CALL = "is_unknown_call";
    public static final String KEY_IS_INCOMING_CALL = "is_incoming_call";
    /*
     *  Whether or not the dialer initiating this outgoing call is the default dialer, or system
     *  dialer and thus allowed to make emergency calls.
     */
    public static final String KEY_IS_PRIVILEGED_DIALER = "is_privileged_dialer";

    private final Context mContext;
    private final CallsManager mCallsManager;

    public CallIntentProcessor(Context context, CallsManager callsManager) {
        this.mContext = context;
        this.mCallsManager = callsManager;
    }

    public void processIntent(Intent intent) {
        final boolean isUnknownCall = intent.getBooleanExtra(KEY_IS_UNKNOWN_CALL, false);
        Log.i(this, "onReceive - isUnknownCall: %s", isUnknownCall);

        Trace.beginSection("processNewCallCallIntent");
        if (isUnknownCall) {
            processUnknownCallIntent(mCallsManager, intent);
        } else {
            processOutgoingCallIntent(mContext, mCallsManager, intent);
        }
        Trace.endSection();
    }


    /**
     * Processes CALL, CALL_PRIVILEGED, and CALL_EMERGENCY intents.
     *
     * @param intent Call intent containing data about the handle to call.
     */
    static void processOutgoingCallIntent(
            Context context,
            CallsManager callsManager,
            Intent intent) {
        Uri handle = intent.getData();
        String scheme = handle.getScheme();
        String uriString = handle.getSchemeSpecificPart();
        Bundle clientExtras = null;
        String origin = null;

        if (clientExtras == null) {
            clientExtras = new Bundle();
        }

        boolean isSkipSchemaParsing = intent.getBooleanExtra(
                TelephonyProperties.EXTRA_SKIP_SCHEMA_PARSING, false);
        Log.d(CallIntentProcessor.class, "isSkipSchemaParsing = " + isSkipSchemaParsing);
        if (isSkipSchemaParsing) {
            clientExtras.putBoolean(TelephonyProperties.EXTRA_SKIP_SCHEMA_PARSING,
                    isSkipSchemaParsing);
            handle = Uri.fromParts(PhoneAccount.SCHEME_TEL, handle.toString(), null);
        }

        if (!PhoneAccount.SCHEME_VOICEMAIL.equals(scheme) && !isSkipSchemaParsing) {
            handle = Uri.fromParts(PhoneNumberUtils.isUriNumber(uriString) ?
                    PhoneAccount.SCHEME_SIP : PhoneAccount.SCHEME_TEL, uriString, null);
        }

        PhoneAccountHandle phoneAccountHandle = intent.getParcelableExtra(
                TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE);

        if (intent.hasExtra(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS)) {
            clientExtras = intent.getBundleExtra(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS);
        }
        if (intent.hasExtra(PhoneConstants.EXTRA_CALL_ORIGIN)) {
            origin = intent.getStringExtra(PhoneConstants.EXTRA_CALL_ORIGIN);
        }
        boolean isConferenceUri = intent.getBooleanExtra(
                TelephonyProperties.EXTRA_DIAL_CONFERENCE_URI, false);
        Log.d(CallIntentProcessor.class, "isConferenceUri = "+isConferenceUri);
        if (isConferenceUri) {
            clientExtras.putBoolean(TelephonyProperties.EXTRA_DIAL_CONFERENCE_URI, isConferenceUri);
        }
        boolean isAddParticipant = intent.getBooleanExtra(
                TelephonyProperties.ADD_PARTICIPANT_KEY, false);
        Log.d(CallIntentProcessor.class, "isAddparticipant = "+isAddParticipant);
        if (isAddParticipant) {
            clientExtras.putBoolean(TelephonyProperties.ADD_PARTICIPANT_KEY, isAddParticipant);
        }

        final int callDomain = intent.getIntExtra(
                QtiVideoCallConstants.EXTRA_CALL_DOMAIN, QtiVideoCallConstants.DOMAIN_AUTOMATIC);
        Log.d(CallIntentProcessor.class, "callDomain = " + callDomain);
        clientExtras.putInt(QtiVideoCallConstants.EXTRA_CALL_DOMAIN, callDomain);

        final int videoState = intent.getIntExtra(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE,
                VideoProfile.STATE_AUDIO_ONLY);
        clientExtras.putInt(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, videoState);

        boolean isCallPull = intent.getBooleanExtra(TelephonyProperties.EXTRA_IS_CALL_PULL, false);
        Log.d(CallIntentProcessor.class, "processOutgoingCallIntent callPull = " + isCallPull);
        if (isCallPull) {
            clientExtras.putBoolean(TelephonyProperties.EXTRA_IS_CALL_PULL, isCallPull);
        }

        Log.i(CallIntentProcessor.class, " processOutgoingCallIntent handle = " + handle
                + ",scheme = " + scheme + ", uriString = " + uriString
                + ", isSkipSchemaParsing = " + isSkipSchemaParsing
                + ", isAddParticipant = " + isAddParticipant
                + ", isCallPull = " + isCallPull);

        // Ensure call subject is passed on to the connection service.
        if (intent.hasExtra(TelecomManager.EXTRA_CALL_SUBJECT)) {
            String callsubject = intent.getStringExtra(TelecomManager.EXTRA_CALL_SUBJECT);
            clientExtras.putString(TelecomManager.EXTRA_CALL_SUBJECT, callsubject);
        }

        final boolean isPrivilegedDialer = intent.getBooleanExtra(KEY_IS_PRIVILEGED_DIALER, false);

        // Send to CallsManager to ensure the InCallUI gets kicked off before the broadcast returns
        Call call = callsManager.startOutgoingCall(handle, phoneAccountHandle, clientExtras,
                origin);

        if (call != null) {
            // Asynchronous calls should not usually be made inside a BroadcastReceiver because once
            // onReceive is complete, the BroadcastReceiver's process runs the risk of getting
            // killed if memory is scarce. However, this is OK here because the entire Telecom
            // process will be running throughout the duration of the phone call and should never
            // be killed.
            NewOutgoingCallIntentBroadcaster broadcaster = new NewOutgoingCallIntentBroadcaster(
                    context, callsManager, call, intent, isPrivilegedDialer);
            final int result = broadcaster.processIntent();
            final boolean success = result == DisconnectCause.NOT_DISCONNECTED;

            if (!success && call != null) {
                disconnectCallAndShowErrorDialog(context, call, result);
            }
        }
    }

    static void processIncomingCallIntent(CallsManager callsManager, Intent intent) {
        PhoneAccountHandle phoneAccountHandle = intent.getParcelableExtra(
                TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE);

        if (phoneAccountHandle == null) {
            Log.w(CallIntentProcessor.class,
                    "Rejecting incoming call due to null phone account");
            return;
        }
        if (phoneAccountHandle.getComponentName() == null) {
            Log.w(CallIntentProcessor.class,
                    "Rejecting incoming call due to null component name");
            return;
        }

        Bundle clientExtras = null;
        if (intent.hasExtra(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS)) {
            clientExtras = intent.getBundleExtra(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS);
        }
        if (clientExtras == null) {
            clientExtras = new Bundle();
        }

        Log.d(CallIntentProcessor.class,
                "Processing incoming call from connection service [%s]",
                phoneAccountHandle.getComponentName());
        callsManager.processIncomingCallIntent(phoneAccountHandle, clientExtras);
    }

    static void processUnknownCallIntent(CallsManager callsManager, Intent intent) {
        PhoneAccountHandle phoneAccountHandle = intent.getParcelableExtra(
                TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE);

        if (phoneAccountHandle == null) {
            Log.w(CallIntentProcessor.class, "Rejecting unknown call due to null phone account");
            return;
        }
        if (phoneAccountHandle.getComponentName() == null) {
            Log.w(CallIntentProcessor.class, "Rejecting unknown call due to null component name");
            return;
        }

        callsManager.addNewUnknownCall(phoneAccountHandle, intent.getExtras());
    }

    private static void disconnectCallAndShowErrorDialog(
            Context context, Call call, int errorCode) {
        call.disconnect();
        final Intent errorIntent = new Intent(context, ErrorDialogActivity.class);
        int errorMessageId = -1;
        switch (errorCode) {
            case DisconnectCause.INVALID_NUMBER:
            case DisconnectCause.NO_PHONE_NUMBER_SUPPLIED:
                errorMessageId = R.string.outgoing_call_error_no_phone_number_supplied;
                break;
        }
        if (errorMessageId != -1) {
            errorIntent.putExtra(ErrorDialogActivity.ERROR_MESSAGE_ID_EXTRA, errorMessageId);
            errorIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivityAsUser(errorIntent, UserHandle.CURRENT);
        }
    }

    /**
     * Whether an outgoing video call should be prevented from going out. Namely, don't allow an
     * outgoing video call if there is already an ongoing video call. Notify the user if their call
     * is not sent.
     *
     * @return {@code true} if the outgoing call is a video call and should be prevented from going
     *     out, {@code false} otherwise.
     */
    private static boolean shouldPreventDuplicateVideoCall(
            Context context,
            CallsManager callsManager,
            Intent intent) {
        int intentVideoState = intent.getIntExtra(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE,
                VideoProfile.STATE_AUDIO_ONLY);
        if (VideoProfile.isAudioOnly(intentVideoState)
                || !callsManager.hasVideoCall()) {
            return false;
        } else {
            // Display an error toast to the user.
            Toast.makeText(
                    context,
                    context.getResources().getString(R.string.duplicate_video_call_not_allowed),
                    Toast.LENGTH_LONG).show();
            return true;
        }
    }
}
