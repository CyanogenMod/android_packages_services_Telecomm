package com.android.telecomm;

/** Only exposes the CallsManager APIs that In-Call should have access to. */
public class InCallAdapter {
  private CallsManager callsManager;

  /** Package private */
  InCallAdapter(CallsManager callsManager) {
    this.callsManager = callsManager;
  }
}
