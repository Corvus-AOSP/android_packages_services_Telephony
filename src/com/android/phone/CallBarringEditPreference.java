/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.phone;

import static com.android.phone.TimeConsumingPreferenceActivity.RESPONSE_ERROR;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.TypedArray;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.PersistableBundle;
import android.telephony.CarrierConfigManager;
import android.text.method.DigitsKeyListener;
import android.text.method.PasswordTransformationMethod;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.android.internal.telephony.CommandException;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.phone.settings.fdn.EditPinPreference;

import java.lang.ref.WeakReference;

/**
 * This preference represents the status of call barring options, enabling/disabling
 * the call barring option will prompt the user for the current password.
 */
public class CallBarringEditPreference extends EditPinPreference {
    private static final String LOG_TAG = "CallBarringEditPreference";
    private static final boolean DBG = (PhoneGlobals.DBG_LEVEL >= 2);

    private String mFacility;
    boolean mIsActivated = false;
    private CharSequence mEnableText;
    private CharSequence mDisableText;
    private CharSequence mSummaryOn;
    private CharSequence mSummaryOff;
    private int mButtonClicked;
    private final MyHandler mHandler = new MyHandler(this);
    private Phone mPhone;
    private TimeConsumingPreferenceListener mTcpListener;

    private static final int PW_LENGTH = 4;

    /**
     * CallBarringEditPreference constructor.
     *
     * @param context The context of view.
     * @param attrs The attributes of the XML tag that is inflating EditTextPreference.
     */
    public CallBarringEditPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        // Get the summary settings, use CheckBoxPreference as the standard.
        TypedArray typedArray = context.obtainStyledAttributes(attrs,
                android.R.styleable.CheckBoxPreference, 0, 0);
        mSummaryOn = typedArray.getString(android.R.styleable.CheckBoxPreference_summaryOn);
        mSummaryOff = typedArray.getString(android.R.styleable.CheckBoxPreference_summaryOff);
        mDisableText = context.getText(R.string.disable);
        mEnableText = context.getText(R.string.enable);
        typedArray.recycle();

        // Get default phone
        mPhone = PhoneFactory.getDefaultPhone();

        typedArray = context.obtainStyledAttributes(attrs,
                R.styleable.CallBarringEditPreference, 0, R.style.EditPhoneNumberPreference);
        mFacility = typedArray.getString(R.styleable.CallBarringEditPreference_facility);
        typedArray.recycle();
    }

    /**
     * CallBarringEditPreference constructor.
     *
     * @param context The context of view.
     */
    public CallBarringEditPreference(Context context) {
        this(context, null);
    }

    void init(TimeConsumingPreferenceListener listener, boolean skipReading, Phone phone) {
        Log.d(LOG_TAG, "init: phone id = " + phone.getPhoneId());

        mPhone = phone;

        mTcpListener = listener;
        if (!skipReading) {
            // Query call barring status
            mPhone.getCallBarring(mFacility, "", mHandler.obtainMessage(
                    MyHandler.MESSAGE_GET_CALL_BARRING), getServiceClassForCallBarring(mPhone));
            if (mTcpListener != null) {
                mTcpListener.onStarted(this, true);
            }
        }
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        super.onClick(dialog, which);
        mButtonClicked = which;
    }

    @Override
    protected void showDialog(Bundle state) {
        setDialogMessage(getContext().getString(R.string.messageCallBarring));
        super.showDialog(state);
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);

        // Sync the summary view
        TextView summaryView = (TextView) view.findViewById(android.R.id.summary);
        if (summaryView != null) {
            CharSequence sum;
            int vis;

            // Set summary depending upon mode
            if (mIsActivated) {
                sum = (mSummaryOn == null) ? getSummary() : mSummaryOn;
            } else {
                sum = (mSummaryOff == null) ? getSummary() : mSummaryOff;
            }

            if (sum != null) {
                summaryView.setText(sum);
                vis = View.VISIBLE;
            } else {
                vis = View.GONE;
            }

            if (vis != summaryView.getVisibility()) {
                summaryView.setVisibility(vis);
            }
        }
    }

    @Override
    protected void onPrepareDialogBuilder(AlertDialog.Builder builder) {
        builder.setPositiveButton(null, null);
        builder.setNeutralButton(mIsActivated ? mDisableText : mEnableText, this);
    }

    @Override
    protected void onBindDialogView(View view) {
        super.onBindDialogView(view);
        // Default the button clicked to be the cancel button.
        mButtonClicked = DialogInterface.BUTTON_NEGATIVE;

        final EditText editText = (EditText) view.findViewById(android.R.id.edit);
        if (editText != null) {
            editText.setSingleLine(true);
            editText.setTransformationMethod(PasswordTransformationMethod.getInstance());
            editText.setKeyListener(DigitsKeyListener.getInstance());

            editText.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onDialogClosed(boolean positiveResult) {
        super.onDialogClosed(positiveResult);
        Log.d(LOG_TAG, "onDialogClosed: mButtonClicked=" + mButtonClicked + ", positiveResult="
                + positiveResult);

        if (mButtonClicked != DialogInterface.BUTTON_NEGATIVE) {
            String password = getEditText().getText().toString();

            // Check if the password is valid.
            if (password == null || password.length() != PW_LENGTH) {
                Toast.makeText(getContext(),
                        getContext().getString(R.string.call_barring_right_pwd_number),
                        Toast.LENGTH_SHORT).show();
                return;
            }

            Log.d(LOG_TAG, "onDialogClosed");

            // Send set call barring message to RIL layer.
            mPhone.setCallBarring(mFacility, !mIsActivated, password,
                    mHandler.obtainMessage(MyHandler.MESSAGE_SET_CALL_BARRING),
                    getServiceClassForCallBarring(mPhone));
            if (mTcpListener != null) {
                mTcpListener.onStarted(this, false);
            }
        }
    }

    void handleCallBarringResult(boolean status) {
        mIsActivated = status;
        Log.i(LOG_TAG, "handleCallBarringResult: mIsActivated=" + mIsActivated);
    }

    private static int getServiceClassForCallBarring(Phone phone) {
        int serviceClass = CarrierConfigManager.SERVICE_CLASS_VOICE;
        PersistableBundle carrierConfig = PhoneGlobals.getInstance()
                .getCarrierConfigForSubId(phone.getSubId());
        if (carrierConfig != null) {
            serviceClass = carrierConfig.getInt(
                    CarrierConfigManager.KEY_CALL_BARRING_DEFAULT_SERVICE_CLASS_INT,
                    CarrierConfigManager.SERVICE_CLASS_VOICE);
        }
        return serviceClass;
    }

    void updateSummaryText() {
        notifyChanged();
        notifyDependencyChange(shouldDisableDependents());
    }

    @Override
    public boolean shouldDisableDependents() {
        return mIsActivated;
    }

    // Message protocol:
    // what: get vs. set
    // arg1: action -- register vs. disable
    // arg2: get vs. set for the preceding request
    private static class MyHandler extends Handler {
        private static final int MESSAGE_GET_CALL_BARRING = 0;
        private static final int MESSAGE_SET_CALL_BARRING = 1;

        private final WeakReference<CallBarringEditPreference> mCallBarringEditPreference;

        private MyHandler(CallBarringEditPreference callBarringEditPreference) {
            mCallBarringEditPreference =
                    new WeakReference<CallBarringEditPreference>(callBarringEditPreference);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_GET_CALL_BARRING:
                    handleGetCallBarringResponse(msg);
                    break;
                case MESSAGE_SET_CALL_BARRING:
                    handleSetCallBarringResponse(msg);
                    break;
                default:
                    break;
            }
        }

        // Handle the response message for query CB status.
        private void handleGetCallBarringResponse(Message msg) {
            final CallBarringEditPreference pref = mCallBarringEditPreference.get();
            if (pref == null) {
                return;
            }

            Log.i(LOG_TAG, "handleGetCallBarringResponse: done");

            AsyncResult ar = (AsyncResult) msg.obj;

            if (msg.arg2 == MESSAGE_SET_CALL_BARRING) {
                pref.mTcpListener.onFinished(pref, false);
            } else {
                pref.mTcpListener.onFinished(pref, true);
            }

            // Unsuccessful query for call barring.
            if (ar.exception != null) {
                Log.i(LOG_TAG, "handleGetCallBarringResponse: ar.exception=" + ar.exception);
                pref.mTcpListener.onException(pref, (CommandException) ar.exception);
            } else {
                if (ar.userObj instanceof Throwable) {
                    pref.mTcpListener.onError(pref, RESPONSE_ERROR);
                }
                int[] ints = (int[]) ar.result;
                if (ints.length == 0) {
                    Log.i(LOG_TAG, "handleGetCallBarringResponse: ar.result.length==0");
                    pref.setEnabled(false);
                    pref.mTcpListener.onError(pref, RESPONSE_ERROR);
                } else {
                    pref.handleCallBarringResult(ints[0] != 0);
                    Log.i(LOG_TAG,
                            "handleGetCallBarringResponse: CB state successfully queried: "
                                    + ints[0]);
                }
            }
            // Update call barring status.
            pref.updateSummaryText();
        }

        // Handle the response message for CB settings.
        private void handleSetCallBarringResponse(Message msg) {
            final CallBarringEditPreference pref = mCallBarringEditPreference.get();
            if (pref == null) {
                return;
            }

            AsyncResult ar = (AsyncResult) msg.obj;

            if (ar.exception != null || ar.userObj instanceof Throwable) {
                Log.i(LOG_TAG, "handleSetCallBarringResponse: ar.exception=" + ar.exception);
            }
            Log.i(LOG_TAG, "handleSetCallBarringResponse: re-get call barring option");
            pref.mPhone.getCallBarring(
                    pref.mFacility,
                    "",
                    obtainMessage(MESSAGE_GET_CALL_BARRING, 0, MESSAGE_SET_CALL_BARRING,
                            ar.exception), getServiceClassForCallBarring(pref.mPhone));
        }
    }
}
