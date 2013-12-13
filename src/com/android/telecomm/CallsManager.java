package com.android.telecomm;

import com.android.telecomm.exceptions.CallServiceUnavailableException;
import com.android.telecomm.exceptions.RestrictedCallException;

import java.util.ArrayList;
import java.util.List;

/** Singleton */
public class CallsManager {

  private static final CallsManager INSTANCE = new CallsManager();

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

  /** Package private */
  static CallsManager getInstance() {
    return INSTANCE;
  }

  /** Package private */
  // TODO(gilad): Circle back to how we'd want to do this.
  void addCallService(CallService callService) {
    if (callService != null) {
      switchboard.addCallService(callService);
      callService.setCallServiceAdapter(new CallServiceAdapter(this));
    }
  }

  /** Package private */
  void connectTo(String userInput, ContactInfo contactInfo)
      throws RestrictedCallException, CallServiceUnavailableException {

    for (OutgoingCallFilter policy : outgoingCallFilters) {
      policy.validate(userInput, contactInfo);
    }

    // No objection to issue the call, proceed with trying to put it through.
    switchboard.placeOutgoingCall(userInput, contactInfo);
  }
}
