package com.android.telecomm;

import com.android.telecomm.exceptions.CallServiceUnavailableException;
import com.android.telecomm.exceptions.RestrictedCallException;

import java.util.ArrayList;
import java.util.List;

/**
 * Singleton.
 *
 * NOTE(gilad): by design most APIs are package private, use the relevant adapter/s to allow
 * access from other packages specifically refraining from passing the CallsManager instance
 * beyond the com.android.telecomm package boundary.
 */
public class CallsManager {

  private static final CallsManager INSTANCE = new CallsManager();

  /**
   * May be unnecessary per off-line discussions (between santoscordon and gilad) since the set
   * of CallsManager APIs that need to be exposed to the dialer (or any application firing call
   * intents) may be empty.
   */
  private DialerAdapter dialerAdapter;

  private InCallAdapter inCallAdapter;

  private Switchboard switchboard;

  private CallLogManager callLogManager;

  private VoicemailManager voicemailManager;

  private List<OutgoingCallFilter> outgoingCallFilters =
      new ArrayList<OutgoingCallFilter>();

  private List<IncomingCallFilter> incomingCallFilters =
      new ArrayList<IncomingCallFilter>();

  // Singleton, private constructor (see getInstance).
  private CallsManager() {
    switchboard = new Switchboard();
    callLogManager = new CallLogManager();
    voicemailManager = new VoicemailManager();  // As necessary etc.
  }

  static CallsManager getInstance() {
    return INSTANCE;
  }

  // TODO(gilad): Circle back to how we'd want to do this.
  void addCallService(CallService callService) {
    if (callService != null) {
      switchboard.addCallService(callService);
      callService.setCallServiceAdapter(new CallServiceAdapter(this));
    }
  }

  /**
   * Attempts to issue/connect the specified call.  From an (arbitrary) application standpoint,
   * all that is required to initiate this flow is to fire either of the CALL, CALL_PRIVILEGED,
   * and CALL_EMERGENCY intents. These are listened to by CallActivity.java which then invokes
   * this method.
   */
  void processOutgoingCallIntent(String handle, ContactInfo contactInfo)
      throws RestrictedCallException, CallServiceUnavailableException {

    for (OutgoingCallFilter policy : outgoingCallFilters) {
      policy.validate(handle, contactInfo);
    }

    // No objection to issue the call, proceed with trying to put it through.
    switchboard.placeOutgoingCall(handle, contactInfo);
  }
}
