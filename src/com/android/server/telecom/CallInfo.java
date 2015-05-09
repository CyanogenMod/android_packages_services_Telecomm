package com.android.server.telecom;

import android.graphics.Bitmap;

public class CallInfo {
    private String mName;
    private Bitmap mPhoto;
    private String mSummary;

    public String getName() {
        return mName;
    }

    public void setName(String name) {
        mName = name;
    }

    public Bitmap getPhoto() {
        return mPhoto;
    }

    public void setPhoto(Bitmap photo) {
        mPhoto = photo;
    }

    public String getSummaryText() {
        return mSummary;
    }

    public void setSummaryText(String summary) {
        mSummary = summary;
    }
}
