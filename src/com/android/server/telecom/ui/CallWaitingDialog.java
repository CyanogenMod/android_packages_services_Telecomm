/*
 * Copyright (C) 2016 The CyanogenMod Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.server.telecom.ui;

import android.annotation.NonNull;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.text.TextUtils;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ListView;
import com.android.server.telecom.Call;
import com.android.server.telecom.R;

/**
 * <pre>
 *     This dialog shows up when you answer another call while currently in a call
 * </pre>
 *
 * @see {@link Dialog}
 */
public class CallWaitingDialog extends Dialog implements AdapterView.OnItemClickListener{

    // Members
    DialogInterface.OnClickListener mHoldListener;
    DialogInterface.OnClickListener mEndListener;

    // Views
    private ListView mButtonListView;
    private CallWaitingButtonAdapter mButtonAdapter;
    private Integer buttons[] = new Integer[] {
            CallWaitingDialogButton.BUTTON_HOLD,
            CallWaitingDialogButton.BUTTON_END
    };

    private CallWaitingDialog(@NonNull Context context,
            DialogInterface.OnClickListener holdListener,
            DialogInterface.OnClickListener endListener) {
        super(context, android.R.style.Theme_Material_Light_Dialog);
        setContentView(R.layout.call_waiting_dialog);
        mButtonListView = (ListView) findViewById(R.id.lv_buttons);
        mButtonAdapter = new CallWaitingButtonAdapter(context,
                R.layout.call_waiting_dialog_button, buttons);
        mButtonListView.setAdapter(mButtonAdapter);
        mHoldListener = holdListener;
        mEndListener = endListener;
        mButtonListView.setOnItemClickListener(this);
    }

    /**
     * Create a new call waiting choice dialog.  This should be the entrypoint
     *
     * @param context {@link Context}
     * @param call {@link Call}
     * @param holdListener {@link android.content.DialogInterface.OnClickListener}
     * @param endListener {@link android.content.DialogInterface.OnClickListener}
     * @return {@link Dialog}
     */
    public static Dialog createCallWaitingDialog(Context context, final Call call,
            DialogInterface.OnClickListener holdListener,
            DialogInterface.OnClickListener endListener) {
        CallWaitingDialog dialog = new CallWaitingDialog(context, holdListener, endListener);
        String template = context.getResources().getString(R.string.call_waiting_dialog_title);
        String name = (TextUtils.isEmpty(call.getName()) ? call.getNumber() : call.getName());
        String title = String.format(template, name);
        dialog.setTitle(title);
        dialog.setCancelable(false);
        dialog.getWindow()
                .setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        return dialog;
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        switch (position) {
            case CallWaitingDialogButton.BUTTON_HOLD:
                if (mHoldListener != null) {
                    mHoldListener.onClick(this, DialogInterface.BUTTON_POSITIVE);
                }
                break;
            case CallWaitingDialogButton.BUTTON_END:
            default:
                if (mEndListener != null) {
                    mEndListener.onClick(this, DialogInterface.BUTTON_NEGATIVE);
                }
                break;
        }
        this.dismiss();
    }

}
