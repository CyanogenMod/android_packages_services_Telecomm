package com.android.telecomm;

/**
 * Only exposes the CallsManager APIs that the Dialer should have access to.
 *
 * NOTE(gilad): may be unnecessary, see the comment in CallsManager.
 */

public class DialerAdapter {
  private CallsManager callsManager;

  DialerAdapter(CallsManager callsManager) {
    this.callsManager = callsManager;
  }
}
