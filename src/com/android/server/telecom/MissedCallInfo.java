package com.android.server.telecom;

import com.android.internal.telephony.CallerInfo;

import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;

public class MissedCallInfo {
    private String mNumber;
    private String mName;
    private Drawable mPhoto;
    private String mSummary;
    private Bitmap mPhotoIcon;
    private long mCreationTimeMillis;
    private Uri mHandle;
    private CallerInfo mCallerInfo;
    private boolean mPhotoVisible;
    private boolean mFetchRequested;
    private boolean mProviderHasInformation;

    MissedCallInfo(Call call) {
        setNumber(call.getNumber());
        setName(call.getName());
        setPhoto(call.getPhoto());
        setPhotoIcon(call.getPhotoIcon());
        mCreationTimeMillis = call.getCreationTimeMillis();
        mHandle = call.getHandle();
        mCallerInfo = call.getCallerInfo();
    }

    public CallerInfo getCallerInfo() {
        return mCallerInfo;
    }

    public String getName() {
        return mName;
    }

    public void setName(String name) {
        mName = name;
    }

    public Drawable getPhoto() {
        return mPhoto;
    }

    public void setPhoto(Drawable photo) {
        mPhoto = photo;
    }

    public String getSummaryText() {
        return mSummary;
    }

    public void setSummaryText(String summary) {
        mSummary = summary;
    }

    private void setNumber(String number) {
        mNumber = number;
    }

    public String getNumber() {
        return mNumber;
    }

    public Uri getHandle() {
        return mHandle;
    }

    void setPhotoIcon(Bitmap photoIcon) {
        mPhotoIcon = photoIcon;
    }

    public Bitmap getPhotoIcon() {
        return mPhotoIcon;
    }

    public long getCreationTimeMillis() {
        return mCreationTimeMillis;
    }

    public boolean isFetchRequested() {
        return mFetchRequested;
    }

    public void setFetchRequested(boolean fetchRequested) {
        this.mFetchRequested = fetchRequested;
    }

    public boolean willPhotoBeVisible() {
        return mPhotoVisible;
    }

    public void setPhotoVisible(boolean willPhotoBeVisible) {
        this.mPhotoVisible = willPhotoBeVisible;
    }

    public boolean providerHasInformation() {
        return mProviderHasInformation;
    }

    public void setProviderHasInformation(boolean providerHasInformation) {
        mProviderHasInformation = providerHasInformation;
    }
}
