package com.android.telecomm;

import com.android.telecomm.exceptions.RestrictedCallException;

// Can be used to prevent outgoing calls based on arbitrary restrictions across
// call services (e.g. black listing a phone number regardless if it is
// attempted over PSTN or WiFi).  That being the case, FDN which is specific to
// GSM may need to be implemented separately since these policies are generally
// invoked before a particular call service is selected.
// See http://en.wikipedia.org/wiki/Fixed_Dialing_Number and IncomingCallFilter
// regarding incoming calls.
public interface OutgoingCallFilter {
  public boolean validate(String userInput, ContactInfo contactInfo)
      throws RestrictedCallException;
}
