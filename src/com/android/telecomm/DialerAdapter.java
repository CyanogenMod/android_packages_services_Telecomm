package com.android.telecomm;

/** Only exposes the CallsManager APIs that the Dialer should have access to. */
public class DialerAdapter {
  private CallsManager callsManager;

  /** Package private */
  DialerAdapter(CallsManager callsManager) {
    this.callsManager = callsManager;
  }
}
