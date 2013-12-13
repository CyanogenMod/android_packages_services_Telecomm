package com.android.telecomm;

/**
 * Only exposes the CallsManager APIs that CallService implementations should
 * have access to.
 */
public class CallServiceAdapter {
  private CallsManager callsManager;

  private CallService callService;

  /** Package private */
  CallServiceAdapter(CallsManager callsManager) {
    this.callsManager = callsManager;
  }
}
