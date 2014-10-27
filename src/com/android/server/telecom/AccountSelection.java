/*
 * Copyright (c) 2014, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *     * Neither the name of The Linux Foundation nor the names of its
 *       contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 */
package com.android.server.telecom;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.DialogInterface.OnCancelListener;
import android.content.Intent;
import android.net.Uri;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ArrayAdapter;
import android.widget.ListAdapter;
import android.widget.ImageView;
import android.widget.TextView;


import java.util.List;
import android.os.Bundle;
import android.telecom.TelecomManager;
import android.view.LayoutInflater;

public class AccountSelection extends Activity {

    TelecomManager mTelecomManager;
    List<PhoneAccountHandle> mAccountHandles;
    Uri mHandle;
    boolean mIsSelected = false;

    @Override
    protected void onCreate(Bundle icicle) {
        Log.d(this, "onCreate()...  this = " + this);

        super.onCreate(icicle);
        Intent intent = getIntent();
        Bundle extras = intent.getExtras();
        mHandle = Uri.parse(extras.getString("Handle"));
        mAccountHandles = extras.getParcelableArrayList(android.telecom.
                Call.AVAILABLE_PHONE_ACCOUNTS);
        if (mAccountHandles == null ) {
            finish();
            return;
        }
        mTelecomManager = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
        final DialogInterface.OnClickListener selectionListener =
                new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                mIsSelected = true;
                CallsManager.getInstance().phoneAccountSelectedForMMI(mHandle,
                        mAccountHandles.get(which));
                finish();
            }
        };
        AlertDialog.Builder builder = new AlertDialog.Builder(AccountSelection.this);

        ListAdapter AccountListAdapter = new AccountListAdapter(
                builder.getContext(),
                R.layout.account_list_item,
                mAccountHandles);

        builder.setTitle(R.string.select_account_dialog_title)
                .setAdapter(AccountListAdapter, selectionListener)
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        @Override
                        public void onCancel(DialogInterface dialog) {
                            mIsSelected = false;
                            finish();
                        }
                 }).create().show();
    }

    private class AccountListAdapter extends ArrayAdapter<PhoneAccountHandle> {
        private Context mContext;
        private int mResId;

        public AccountListAdapter(
                Context context, int resource, List<PhoneAccountHandle> accountHandles) {
            super(context, resource, accountHandles);
            mContext = context;
            mResId = resource;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LayoutInflater inflater = (LayoutInflater)
                    mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

            View rowView = inflater.inflate(mResId, null);
            TextView textView = (TextView) rowView.findViewById(R.id.text);
            ImageView imageView = (ImageView) rowView.findViewById(R.id.icon);

            PhoneAccountHandle accountHandle = getItem(position);
            PhoneAccount account = mTelecomManager.getPhoneAccount(accountHandle);
            textView.setText(account.getLabel());
            imageView.setImageDrawable(account.getIcon(mContext));
            return rowView;
        }

    }


    @Override
    protected void onDestroy() {
        Log.d(this, "onDestroy()...  this = " + mIsSelected);
        if(!mIsSelected) {
            CallsManager.getInstance().phoneAccountSelectedForMMI(mHandle, null);
        }
        super.onDestroy();
    }
}
