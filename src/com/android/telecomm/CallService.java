package com.android.telecomm;

import com.android.telecomm.exceptions.OutgoingCallException;

// TODO(gilad): Move to use the AIDL-based implementation.
public interface CallService {

  public void setCallServiceAdapter(CallServiceAdapter adapter);

  public boolean isCompatibleWith(String userInput, ContactInfo contactInfo);

  public void placeOutgoingCall(String userInput, ContactInfo contactInfo)
    throws OutgoingCallException;
}
