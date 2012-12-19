/**
 * Copyright (C) 2011-2012 yvolk (Yuri Volkov), http://yurivolkov.com
 * Copyright (C) 2008 Torgny Bjers
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
package org.andstatus.app;

import org.andstatus.app.MyService.CommandData;
import org.andstatus.app.MyService.CommandEnum;
import org.andstatus.app.account.MyAccount;
import org.andstatus.app.data.MyDatabase;
import org.andstatus.app.data.MyPreferences;
import org.andstatus.app.data.MyProvider;

import java.util.Locale;

import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

/**
 * "Enter your tweet here" box 
 */
class TweetEditor {
    private TimelineActivity mActivity;
    private android.view.ViewGroup mEditor;

    private Button mSendButton;
    /**
     * Text to be sent
     */
    private EditText mEditText;
    private TextView mCharsLeftText;
    /**
     * Information about the message we are editing
     */
    private TextView mDetails;

    /**
     * Id of the Message to which we are replying
     * -1 - is non-existent id
     */
    private long mReplyToId = -1;
    /**
     * Recipient Id. If =0 we are editing Public message
     */
    private long mRecipientId = 0;

    /**
     * {@link MyAccount} to use with this message (send/reply As ...)
     */
    private MyAccount mAccount = null;
    private boolean mShowAccount = false;

    /**
     * Do we hold loaded but not restored state
     */
    private boolean mIsStateLoaded = false;
    private String mStatus_restored = "";
    private long mReplyToId_restored = 0;
    private long mRecipientId_restored = 0;
    private String mAccountGuid_restored = "";
    private boolean mShowAccount_restored = false;
    
    public TweetEditor(TimelineActivity activity) {
        mActivity = activity;
        mEditor = (android.view.ViewGroup) activity.findViewById(R.id.tweetlist_editor);

        mSendButton = (Button) activity.findViewById(R.id.messageEditSendButton);
        mEditText = (EditText) activity.findViewById(R.id.edtTweetInput);
        mCharsLeftText = (TextView) activity.findViewById(R.id.messageEditCharsLeftTextView);
        mDetails = (TextView) activity.findViewById(R.id.messageEditDetails);
        
        mSendButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                updateStatus();
            }
        });

        mEditText.addTextChangedListener(new TextWatcher() {
            public void afterTextChanged(Editable s) {
                if (mAccount != null) {
                    mCharsLeftText.setText(String.valueOf(mAccount.messageCharactersLeft(s.toString())));
                }
            }

            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }
        });

        mEditText.setOnKeyListener(new View.OnKeyListener() {
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    switch (keyCode) {
                        case KeyEvent.KEYCODE_DPAD_CENTER:
                            updateStatus();
                            return true;
                        case KeyEvent.KEYCODE_ENTER:
                            if (event.isAltPressed()) {
                                mEditText.append("\n");
                                return true;
                            }
                        default:
                            mCharsLeftText.setText(String.valueOf(mAccount
                                    .messageCharactersLeft(mEditText.getText().toString())));
                            break;
                    }
                }
                return false;
            }
        });

        mEditText.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (event != null) {
                    if (event.isAltPressed()) {
                        return false;
                    }
                }
                updateStatus();
                return true;
            }
        });
    }
    
    /**
     * Continue message editing
     * @return new state of visibility
     */
    public boolean toggleVisibility() {
        boolean isVisibleNew = !isVisible();
        if (isVisibleNew) {
            show();
        } else {
            hide();
        }
        return isVisibleNew;
    }

    public void show() {
        mCharsLeftText.setText(String.valueOf(mAccount
                .messageCharactersLeft(mEditText.getText().toString())));

        mEditor.setVisibility(View.VISIBLE);
        
        mEditText.requestFocus();
        /* do we need this instead?
        if (mActivity.hasHardwareKeyboard()) {
            mEditText.requestFocus();
        }
        */
        
    }
    
    public void hide() {
        mEditor.setVisibility(View.GONE);
    }
    
    public boolean isVisible() {
        return (mEditor.getVisibility() == View.VISIBLE);
    }
    
    /**
     * Start editing "Status update" (public message) OR "Direct message".
     * If both replyId and recipientId parameters are the same, we continue editing 
     * (i.e. previous not sent message is preserved). This behavior is close to how 
     * the application worked before.
     * @param textInitial not null String
     * @param replyToId =0 if not replying
     * @param recipientId =0 if this is Public message
     */
    public void startEditingMessage(String textInitial, long replyToId, long recipientId, String accountGuid, boolean showAccount) {
        String accountGuid_prev = "";
        if (mAccount != null) {
            accountGuid_prev = mAccount.getAccountGuid();
        }
        if (mReplyToId != replyToId || mRecipientId != recipientId 
                || accountGuid_prev.compareTo(accountGuid) != 0 || !mAccount.isPersistent()
                || mShowAccount != showAccount) {
            mReplyToId = replyToId;
            mRecipientId = recipientId;
            mAccount = MyAccount.getMyAccount(accountGuid);
            mShowAccount = showAccount;
            String messageDetails = (showAccount ? mAccount.getAccountGuid() : "");
            if (recipientId == 0) {
                if (replyToId != 0) {
                    String replyToName = MyProvider.msgIdToUsername(MyDatabase.Msg.AUTHOR_ID, replyToId);
                    if (!TextUtils.isEmpty(textInitial)) {
                        textInitial += " ";
                    }
                    textInitial = "@" + replyToName + " ";
                    messageDetails += " " + String.format(Locale.getDefault(), MyPreferences.getContext().getText(R.string.message_source_in_reply_to).toString(), replyToName);
                }
            } else {
                String recipientName = MyProvider.userIdToName(recipientId);
                if (!TextUtils.isEmpty(recipientName)) {
                    messageDetails += " " + String.format(Locale.getDefault(), MyPreferences.getContext().getText(R.string.message_source_to).toString(), recipientName);
                }
            }
            mEditText.setText(textInitial);
            // mEditText.append(textInitial, 0, textInitial.length());
            mDetails.setText(messageDetails);
            if (TextUtils.isEmpty(messageDetails)) {
                mDetails.setVisibility(View.GONE);
            } else {
                mDetails.setVisibility(View.VISIBLE);
            }
        }
        
        // Start asynchronous task that will show Rate limit status
        mActivity.serviceConnector.sendCommand(new CommandData(CommandEnum.RATE_LIMIT_STATUS, mAccount.getAccountGuid()));
        
        show();
    }
    

    /**
     * Handles threaded sending of the message, typed in the mEditText text box.
     * Queued message sending is supported (if initial sending failed for some
     * reason).
     */
    private void updateStatus() {
        String status = mEditText.getText().toString();
        if (TextUtils.isEmpty(status.trim())) {
            Toast.makeText(mActivity, R.string.cannot_send_empty_message,
                    Toast.LENGTH_SHORT).show();
        } else if (mAccount.messageCharactersLeft(status) < 0) {
            Toast.makeText(mActivity, R.string.message_is_too_long,
                    Toast.LENGTH_SHORT).show();
        } else {
            CommandData commandData = new CommandData(
                    CommandEnum.UPDATE_STATUS,
                    mAccount.getAccountGuid());
            commandData.bundle.putString(MyService.EXTRA_STATUS, status);
            if (mReplyToId != 0) {
                commandData.bundle.putLong(MyService.EXTRA_INREPLYTOID, mReplyToId);
            }
            if (mRecipientId != 0) {
                commandData.bundle.putLong(MyService.EXTRA_RECIPIENTID, mRecipientId);
            }
            mActivity.serviceConnector.sendCommand(commandData);
            closeSoftKeyboard();

            // Let's assume that everything will be Ok
            // so we may clear the text box with the sent message text...
            mReplyToId = 0;
            mRecipientId = 0;
            mEditText.setText("");
            mAccount = null;
            mShowAccount = false;

            hide();
        }
    }

    /**
     * Close the on-screen keyboard.
     */
    private void closeSoftKeyboard() {
        InputMethodManager inputMethodManager = (InputMethodManager) mActivity.getSystemService(Context.INPUT_METHOD_SERVICE);
        inputMethodManager.hideSoftInputFromWindow(mEditText.getWindowToken(), 0);
    }
    
    public void saveState(Bundle outState) {
        mIsStateLoaded = false;
        if (outState != null) {
            if (mEditText != null && mAccount != null) {
                String status = mEditText.getText().toString();
                if (!TextUtils.isEmpty(status)) {
                    outState.putString(MyService.EXTRA_STATUS, status);
                    outState.putLong(MyService.EXTRA_INREPLYTOID, mReplyToId);
                    outState.putLong(MyService.EXTRA_RECIPIENTID, mRecipientId);
                    outState.putString(MyService.EXTRA_ACCOUNT_NAME, mAccount.getAccountGuid());
                    outState.putBoolean(MyService.EXTRA_SHOW_ACCOUNT, mShowAccount);
                }
            }
        }
    }
    
    public void loadState(Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            if (savedInstanceState.containsKey(MyService.EXTRA_INREPLYTOID)) {
                if (savedInstanceState.containsKey(MyService.EXTRA_STATUS)) {
                    String status = savedInstanceState.getString(MyService.EXTRA_STATUS);
                    if (!TextUtils.isEmpty(status)) {
                        mStatus_restored = status;
                        mReplyToId_restored = savedInstanceState.getLong(MyService.EXTRA_INREPLYTOID);
                        mRecipientId_restored = savedInstanceState.getLong(MyService.EXTRA_RECIPIENTID);
                        mAccountGuid_restored = savedInstanceState.getString(MyService.EXTRA_ACCOUNT_NAME);
                        mShowAccount_restored = savedInstanceState.getBoolean(MyService.EXTRA_SHOW_ACCOUNT);
                        mIsStateLoaded = true;
                    }
                }
            }
        }
    }
    
    /**
     * Do we hold loaded but not restored state?
     */
    public boolean isStateLoaded() {
        return mIsStateLoaded;
    }
    
    public void continueEditingLoadedState() {
        if (isStateLoaded()) {
            mIsStateLoaded = false;
            startEditingMessage(mStatus_restored, mReplyToId_restored, mRecipientId_restored, mAccountGuid_restored, mShowAccount_restored);
        }
    }
}
