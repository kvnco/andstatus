/* 
 * Copyright (c) 2011-2012 yvolk (Yuri Volkov), http://yurivolkov.com
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

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ListActivity;
import android.app.NotificationManager;
import android.app.ProgressDialog;
import android.app.SearchManager;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.res.Configuration;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.provider.SearchRecentSuggestions;
import android.text.TextUtils;
import android.util.Log;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.ContextMenu.ContextMenuInfo;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SimpleCursorAdapter;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.AbsListView.OnScrollListener;
import android.widget.ToggleButton;

import org.andstatus.app.MyService.CommandData;
import org.andstatus.app.MyService.CommandEnum;
import org.andstatus.app.account.AccountSelector;
import org.andstatus.app.account.MyAccount;
import org.andstatus.app.account.MyAccount.CredentialsVerified;
import org.andstatus.app.data.MyDatabase;
import org.andstatus.app.data.MyProvider;
import org.andstatus.app.data.PagedCursorAdapter;
import org.andstatus.app.data.TimelineSearchSuggestionProvider;
import org.andstatus.app.data.TweetBinder;
import org.andstatus.app.data.MyDatabase.MsgOfUser;
import org.andstatus.app.data.MyDatabase.TimelineTypeEnum;
import org.andstatus.app.data.MyDatabase.User;
import org.andstatus.app.data.MyPreferences;
import org.andstatus.app.util.MyLog;
import org.andstatus.app.util.SelectionAndArgs;
import org.json.JSONObject;

/**
 * @author torgny.bjers
 */
public class TimelineActivity extends ListActivity implements ITimelineActivity {

    private static final String TAG = TimelineActivity.class.getSimpleName();

    // Handler message codes
    /**
     * My tweet ("What's happening?"...) is being sending
     */
    private static final int MSG_UPDATE_STATUS = 3;

    private static final int MSG_AUTHENTICATION_ERROR = 5;

    private static final int MSG_LOAD_ITEMS = 6;

    private static final int MSG_SERVICE_UNAVAILABLE_ERROR = 8;

    private static final int MSG_CONNECTION_TIMEOUT_EXCEPTION = 11;

    private static final int MSG_STATUS_DESTROY = 12;

    private static final int MSG_FAVORITE_CREATE = 13;

    private static final int MSG_FAVORITE_DESTROY = 14;

    private static final int MSG_CONNECTION_EXCEPTION = 15;

    // Handler message status codes
    public static final int STATUS_LOAD_ITEMS_FAILURE = 0;

    public static final int STATUS_LOAD_ITEMS_SUCCESS = 1;

    // Dialog identifier codes
    public static final int DIALOG_AUTHENTICATION_FAILED = 1;

    public static final int DIALOG_SERVICE_UNAVAILABLE = 3;

    public static final int DIALOG_CONNECTION_TIMEOUT = 7;

    public static final int DIALOG_EXECUTING_COMMAND = 8;

    public static final int DIALOG_TIMELINE_TYPE = 9;
    
    // Context menu items -----------------------------------------
    public static final int CONTEXT_MENU_ITEM_REPLY = Menu.FIRST + 2;

    public static final int CONTEXT_MENU_ITEM_FAVORITE = Menu.FIRST + 3;

    public static final int CONTEXT_MENU_ITEM_DIRECT_MESSAGE = Menu.FIRST + 4;

    public static final int CONTEXT_MENU_ITEM_UNFOLLOW = Menu.FIRST + 5;

    public static final int CONTEXT_MENU_ITEM_BLOCK = Menu.FIRST + 6;

    public static final int CONTEXT_MENU_ITEM_REBLOG = Menu.FIRST + 7;

    public static final int CONTEXT_MENU_ITEM_DESTROY_REBLOG = Menu.FIRST + 8;

    public static final int CONTEXT_MENU_ITEM_PROFILE = Menu.FIRST + 9;

    public static final int CONTEXT_MENU_ITEM_DESTROY_FAVORITE = Menu.FIRST + 10;

    public static final int CONTEXT_MENU_ITEM_DESTROY_STATUS = Menu.FIRST + 11;

    public static final int CONTEXT_MENU_ITEM_SHARE = Menu.FIRST + 12;
    
    // Intent bundle result keys
    public static final String INTENT_RESULT_KEY_AUTHENTICATION = "authentication";

    // Bundle identifier keys
    public static final String BUNDLE_KEY_CURRENT_PAGE = "currentPage";

    public static final String BUNDLE_KEY_IS_LOADING = "isLoading";

    // Request codes for called activities
    protected static final int REQUEST_SELECT_ACCOUNT = RESULT_FIRST_USER;
    
    /**
     * Key prefix for the stored list position
     */
    protected static final String LAST_POS_KEY = "last_position_";

    public static final int MILLISECONDS = 1000;

    /**
     * List footer, appears at the bottom of the list of messages 
     * when new items are being loaded into the list 
     */
    protected LinearLayout mListFooter;

    protected Cursor mCursor;

    protected NotificationManager mNM;

    protected ProgressDialog mProgressDialog;
    
    /**
     * Msg are being loaded into the list starting from one page. More Msg
     * are being loaded in a case User scrolls down to the end of list.
     */
    protected final static int PAGE_SIZE = 20;

    /**
     * Is saved position restored (or some default positions set)?
     */
    protected boolean positionRestored = false;

    /**
     * Number of items (Msg) in the list. It is used to find out when we need
     * to load more items.
     */
    protected int mTotalItemCount = 0;

    /**
     * Items are being loaded into the list (asynchronously...)
     */
    private boolean mIsLoading = false;
    
    /**
     * For testing purposes
     */
    protected long instanceId = 0;
    protected MyHandler mHandler = new MyHandler();
    MyServiceConnector serviceConnector;

    /**
     * We are going to finish/restart this Activity (e.g. onResume or even onCreate)
     */
    protected boolean mIsFinishing = false;

    /**
     * Timeline type
     */
    protected TimelineTypeEnum mTimelineType = TimelineTypeEnum.UNKNOWN;

    /**
     * Is the timeline combined? (Timeline shows messages from all accounts)
     */
    protected boolean mIsTimelineCombined = false;
    
    /**
     * True if this timeline is filtered using query string ("Mentions" are not
     * counted here because they have separate TimelineType)
     */
    protected boolean mIsSearchMode = false;

    /**
     * The string is not empty if this timeline is filtered using query string
     * ("Mentions" are not counted here because they have separate TimelineType)
     */
    protected String mQueryString = "";

    /**
     * Time when shared preferences where changed
     */
    protected long preferencesChangeTime = 0;

    /**
     * Id of the Message that was selected (clicked, or whose context menu item
     * was selected) TODO: clicked, restore position...
     */
    protected long mCurrentId = 0;

    /** 
     * Controls of the TweetEditor
     */
    protected TweetEditor mTweetEditor;
 
    /** 
     * Table columns to use for the messages content
     */
    private static final String[] PROJECTION = new String[] {
            MyDatabase.Msg._ID, MyDatabase.User.AUTHOR_NAME, MyDatabase.Msg.BODY, User.IN_REPLY_TO_NAME,
            User.RECIPIENT_NAME,
            MyDatabase.MsgOfUser.FAVORITED, MyDatabase.Msg.CREATED_DATE,
            MyDatabase.MsgOfUser.USER_ID
    };

    /**
     * Message handler for messages from threads and from the remote {@link MyService}.
     * TODO: Remove codes (of msg.what ) which are not used
     */
    private class MyHandler extends Handler {
        @Override
        public void handleMessage(android.os.Message msg) {
            MyLog.v(TAG, "handleMessage, what=" + msg.what + ", instance " + instanceId);
            JSONObject result = null;
            switch (msg.what) {
                case MyServiceConnector.MSG_TWEETS_CHANGED:
                    int numTweets = msg.arg1;
                    if (numTweets > 0) {
                        mNM.cancelAll();
                    }
                    break;

                case MyServiceConnector.MSG_DATA_LOADING:
                    boolean isLoadingNew = (msg.arg2 == 1) ? true : false;
                    if (!isLoadingNew) {
                        MyLog.v(TAG, "Timeline has been loaded " + (TimelineActivity.this.isLoading() ? " (loading) " : " (not loading) ") + ", visibility=" + mListFooter.getVisibility());
                        mListFooter.setVisibility(View.INVISIBLE);
                        if (isLoading()) {
                            Toast.makeText(TimelineActivity.this, R.string.timeline_reloaded,
                                    Toast.LENGTH_SHORT).show();
                            setIsLoading(false);
                        }
                    }
                    break;

                case MSG_UPDATE_STATUS:
                    result = (JSONObject) msg.obj;
                    if (result == null) {
                        Toast.makeText(TimelineActivity.this, R.string.error_connection_error,
                                Toast.LENGTH_LONG).show();
                    } else if (result.optString("error").length() > 0) {
                        Toast.makeText(TimelineActivity.this,
                                (CharSequence) result.optString("error"), Toast.LENGTH_LONG).show();
                    } else {
                        // The tweet was sent successfully
                        Toast.makeText(TimelineActivity.this, R.string.message_sent,
                                Toast.LENGTH_SHORT).show();
                    }
                    break;

                case MSG_AUTHENTICATION_ERROR:
                    mListFooter.setVisibility(View.INVISIBLE);
                    showDialog(DIALOG_AUTHENTICATION_FAILED);
                    break;

                case MSG_SERVICE_UNAVAILABLE_ERROR:
                    mListFooter.setVisibility(View.INVISIBLE);
                    showDialog(DIALOG_SERVICE_UNAVAILABLE);
                    break;

                case MSG_LOAD_ITEMS:
                    mListFooter.setVisibility(View.INVISIBLE);
                    switch (msg.arg1) {
                        case STATUS_LOAD_ITEMS_SUCCESS:
                            updateTitle();
                            mListFooter.setVisibility(View.INVISIBLE);
                            if (positionRestored) {
                                // This will prevent continuous loading...
                                if (mCursor.getCount() > getListAdapter().getCount()) {
                                    ((SimpleCursorAdapter) getListAdapter()).changeCursor(mCursor);
                                }
                            }
                            setIsLoading(false);
                            // setProgressBarIndeterminateVisibility(false);
                            break;
                        case STATUS_LOAD_ITEMS_FAILURE:
                            break;
                    }
                    break;

                case MyServiceConnector.MSG_UPDATED_TITLE:
                    if (msg.arg1 > 0) {
                        updateTitle(msg.arg1 + "/" + msg.arg2);
                    }
                    break;

                case MSG_CONNECTION_TIMEOUT_EXCEPTION:
                    mListFooter.setVisibility(View.INVISIBLE);
                    showDialog(DIALOG_CONNECTION_TIMEOUT);
                    break;

                case MSG_STATUS_DESTROY:
                    result = (JSONObject) msg.obj;
                    if (result == null) {
                        Toast.makeText(TimelineActivity.this, R.string.error_connection_error,
                                Toast.LENGTH_LONG).show();
                    } else if (result.optString("error").length() > 0) {
                        Toast.makeText(TimelineActivity.this,
                                (CharSequence) result.optString("error"), Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(TimelineActivity.this, R.string.status_destroyed,
                                Toast.LENGTH_SHORT).show();
                        mCurrentId = 0;
                    }
                    break;

                case MSG_FAVORITE_CREATE:
                    result = (JSONObject) msg.obj;
                    if (result == null) {
                        Toast.makeText(TimelineActivity.this, R.string.error_connection_error,
                                Toast.LENGTH_LONG).show();
                    } else if (result.optString("error").length() > 0) {
                        Toast.makeText(TimelineActivity.this,
                                (CharSequence) result.optString("error"), Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(TimelineActivity.this, R.string.favorite_created,
                                Toast.LENGTH_SHORT).show();
                        mCurrentId = 0;
                    }
                    break;

                case MSG_FAVORITE_DESTROY:
                    result = (JSONObject) msg.obj;
                    if (result == null) {
                        Toast.makeText(TimelineActivity.this, R.string.error_connection_error,
                                Toast.LENGTH_LONG).show();
                    } else if (result.optString("error").length() > 0) {
                        Toast.makeText(TimelineActivity.this,
                                (CharSequence) result.optString("error"), Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(TimelineActivity.this, R.string.favorite_destroyed,
                                Toast.LENGTH_SHORT).show();
                        mCurrentId = 0;
                    }
                    break;

                case MSG_CONNECTION_EXCEPTION:
                    switch (msg.arg1) {
                        case MSG_FAVORITE_CREATE:
                        case MSG_FAVORITE_DESTROY:
                        case MSG_STATUS_DESTROY:
                            try {
                                dismissDialog(DIALOG_EXECUTING_COMMAND);
                            } catch (IllegalArgumentException e) {
                                MyLog.d(TAG, "", e);
                            }
                            break;
                    }
                    Toast.makeText(TimelineActivity.this, R.string.error_connection_error,
                            Toast.LENGTH_SHORT).show();
                    break;

                default:
                    super.handleMessage(msg);
            }
        }
    }

    /**
     * @return the mIsLoading
     */
    boolean isLoading() {
        //MyLog.v(TAG, "isLoading checked " + mIsLoading + ", instance " + instanceId);
        return mIsLoading;
    }

    /**
     * @param isLoading Is loading now?
     */
    void setIsLoading(boolean isLoading) {
        MyLog.v(TAG, "isLoading set from " + mIsLoading + " to " + isLoading + ", instance " + instanceId );
        mIsLoading = isLoading;
    }
    
    /**
     * This method is the first of the whole application to be called 
     * when the application starts for the very first time.
     * So we may put some Application initialization code here. 
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (instanceId == 0) {
            instanceId = MyPreferences.nextInstanceId();
        } else {
            MyLog.d(TAG, "onCreate reuse the same instance " + instanceId);
        }

        MyPreferences.initialize(this, this);
        preferencesChangeTime = MyPreferences.getDefaultSharedPreferences().getLong(MyPreferences.KEY_PREFERENCES_CHANGE_TIME, 0);
        
        if (MyLog.isLoggable(TAG, Log.DEBUG)) {
            MyLog.d(TAG, "onCreate instance " + instanceId + " , preferencesChangeTime=" + preferencesChangeTime);
        }

        if (!mIsFinishing) {
            if (!MyPreferences.getSharedPreferences(PreferenceManager.KEY_HAS_SET_DEFAULT_VALUES, MODE_PRIVATE).getBoolean(PreferenceManager.KEY_HAS_SET_DEFAULT_VALUES, false)) {
                Log.i(TAG, "We are running the Application for the very first time?");
                startActivity(new Intent(this, SplashActivity.class));
                finish();
            }
        }
        if (!mIsFinishing) {
            if (MyAccount.getCurrentMyAccount() == null) {
                Log.i(TAG, "No current MyAccount");
                startActivity(new Intent(this, SplashActivity.class));
                finish();
            }
        }
        if (mIsFinishing) {
            return;
        }

        serviceConnector = new MyServiceConnector(instanceId);
        
        MyPreferences.loadTheme(TAG, this);

        // Request window features before loading the content view
        requestWindowFeature(Window.FEATURE_CUSTOM_TITLE);

        setContentView(R.layout.tweetlist);
        getWindow().setFeatureInt(Window.FEATURE_CUSTOM_TITLE, R.layout.timeline_title);

        mTweetEditor = new TweetEditor(this);
        // TODO: Maybe this should be a parameter
        mTweetEditor.hide();

        boolean isInstanceStateRestored = false;
        if (savedInstanceState != null) {
            TimelineTypeEnum timelineType_new = TimelineTypeEnum.load(savedInstanceState
                    .getString(MyService.EXTRA_TIMELINE_TYPE));
            if (timelineType_new != TimelineTypeEnum.UNKNOWN) {
                isInstanceStateRestored = true;
                mTimelineType = timelineType_new;
                mTweetEditor.loadState(savedInstanceState);
                if (savedInstanceState.containsKey(BUNDLE_KEY_IS_LOADING)) {
                    setIsLoading(savedInstanceState.getBoolean(BUNDLE_KEY_IS_LOADING));
                }
                if (savedInstanceState.containsKey(MyService.EXTRA_TWEETID)) {
                    mCurrentId = savedInstanceState.getLong(MyService.EXTRA_TWEETID);
                }
                if (savedInstanceState.containsKey(MyService.EXTRA_TIMELINE_IS_COMBINED)) {
                    mIsTimelineCombined = savedInstanceState.getBoolean(MyService.EXTRA_TIMELINE_IS_COMBINED);
                }
                if (savedInstanceState.containsKey(SearchManager.QUERY)) {
                    mQueryString = savedInstanceState.getString(SearchManager.QUERY);
                }
            }
        }

        // Set up notification manager
        mNM = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        
        // Create list footer to show the progress of message loading
        // We use "this" as a context, otherwise custom styles are not recognized...
        
        LayoutInflater inflater = LayoutInflater.from(this);

        mListFooter = (LinearLayout) inflater.inflate(R.layout.item_loading, null);;
        getListView().addFooterView(mListFooter);
        mListFooter.setVisibility(View.INVISIBLE);

        /* This also works (after we've added R.layout.item_loading to the view)
           but is not needed here:
        mListFooter = (LinearLayout) findViewById(R.id.item_loading);
        */
        
        // Attach listeners to the message list
        getListView().setOnScrollListener(this);
        getListView().setOnCreateContextMenuListener(this);
        getListView().setOnItemClickListener(this);

        Button accountButton = (Button) findViewById(R.id.selectAccountButton);
        accountButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent i = new Intent(TimelineActivity.this, AccountSelector.class);
                startActivityForResult(i, REQUEST_SELECT_ACCOUNT);
            }
        });
       
        Button createMessageButton = (Button) findViewById(R.id.createMessageButton);
        createMessageButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (mTweetEditor.isVisible()) {
                    mTweetEditor.hide();
                } else {
                    mTweetEditor.startEditingMessage("", 0, 0, MyAccount.getCurrentMyAccount().getAccountGuid(), mIsTimelineCombined);
                }
            }
        });

        if (!isInstanceStateRestored) {
            mIsTimelineCombined = MyPreferences.getDefaultSharedPreferences().getBoolean(MyPreferences.KEY_TIMELINE_IS_COMBINED, false);
            processNewIntent(getIntent());
        }
        updateThisOnChangedParameters();
    }

    /**
     * Switch combined timeline on/off
     * @param view combinedTimelineToggle
     */
    public void onCombinedTimelineToggle(View view) {
        boolean on = ((android.widget.ToggleButton) view).isChecked();
        MyPreferences.getDefaultSharedPreferences().edit().putBoolean(MyPreferences.KEY_TIMELINE_IS_COMBINED, on).commit();
        switchTimelineActivity(mTimelineType, on);
    }
    
    public void onTimelineTypeButtonClick(View view) {
        showDialog(DIALOG_TIMELINE_TYPE);
    }
    
    /**
     * See <a href="http://developer.android.com/guide/topics/search/search-dialog.html">Creating 
     * a Search Interface</a>
     */
    @Override
    public boolean onSearchRequested() {
        Bundle appSearchData = new Bundle();
        appSearchData.putString(MyService.EXTRA_TIMELINE_TYPE, mTimelineType.save());
        appSearchData.putBoolean(MyService.EXTRA_TIMELINE_IS_COMBINED, mIsTimelineCombined);
        startSearch(null, false, appSearchData, false);
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        MyLog.v(TAG, "onResume, instance " + instanceId);
        if (!mIsFinishing) {
            if (MyAccount.getCurrentMyAccount() != null) {
                if (MyPreferences.getDefaultSharedPreferences().getLong(MyPreferences.KEY_PREFERENCES_CHANGE_TIME, 0) > preferencesChangeTime) {
                    MyLog.v(TAG, "Restarting this Activity to apply any new changes");
                    finish();
                    switchTimelineActivity(mTimelineType, mIsTimelineCombined);
                }
            } else { 
                MyLog.v(TAG, "Finishing this Activity because there is no Account selected");
                finish();
            }
        }
        if (!mIsFinishing) {
            serviceConnector.bindToService(this, mHandler);
            updateTitle();
            restorePosition();

            if (MyAccount.getCurrentMyAccount().getCredentialsVerified() == CredentialsVerified.SUCCEEDED) {
                if (!MyAccount.getCurrentMyAccount().getMyAccountPreferences().getBoolean("loadedOnce", false)) {
                    MyAccount.getCurrentMyAccount().getMyAccountPreferences().edit()
                            .putBoolean("loadedOnce", true).commit();
                    // One-time "manually" load tweets from the Internet for the
                    // new MyAccount
                    manualReload(true);
                }
            }
        }
    }
    
    /**
     * Save Position per User and per TimeleneType Actually we save two Item
     * IDs: firstItemId - the first visible Tweet lastItemId - the last tweet we
     * should retrieve before restoring position
     */
    private void savePosition() {
        long firstItemId = 0;
        long lastItemId = 0;
        int firstScrollPos = getListView().getFirstVisiblePosition();
        int lastScrollPos = -1;
        android.widget.ListAdapter la = getListView().getAdapter();
        if (firstScrollPos >= la.getCount() - 1) {
            // Skip footer
            firstScrollPos = la.getCount() - 2;
        }
        if (firstScrollPos >= 0) {
            // for (int ind =0; ind < la.getCount(); ind++) {
            // Log.v(TAG, "itemId[" + ind + "]=" + la.getItemId(ind));
            // }
            firstItemId = la.getItemId(firstScrollPos);
            // We will load one more "page of tweets" below (older) current top
            // item
            lastScrollPos = firstScrollPos + PAGE_SIZE;
            if (lastScrollPos >= la.getCount() - 1) {
                // Skip footer
                lastScrollPos = la.getCount() - 2;
            }
            // Log.v(TAG, "lastScrollPos=" + lastScrollPos);
            if (lastScrollPos >= 0) {
                lastItemId = la.getItemId(lastScrollPos);
            } else {
                lastItemId = firstItemId;
            }
        }
        if (firstItemId > 0) {
            SharedPreferences sp = null;
            String accountGuid = "";
            if (mIsTimelineCombined) {
                sp = MyPreferences.getDefaultSharedPreferences();
            } else {
                MyAccount ma = MyAccount.getCurrentMyAccount();
                sp = ma.getMyAccountPreferences();
                accountGuid = ma.getAccountGuid();
                
            }
            
            // Variant 2 is overkill... but let's try...
            // I have a feeling that saving preferences while finishing activity sometimes doesn't work...
            //  - Maybe this was fixed introducing MyPreferences class?! 
            boolean saveSync = true;
            boolean saveAsync = false;
            if (saveSync) {
                // 1. Synchronous saving
                sp.edit().putLong(positionKey(false), firstItemId)
                .putLong(positionKey(true), lastItemId).commit();
                if (mIsSearchMode) {
                    // Remember query string for which the position was saved
                    sp.edit().putString(positionQueryStringKey(), mQueryString)
                            .commit();
                }
            }
            if (saveAsync) {
                // 2. Asynchronous saving of user's preferences
                serviceConnector.sendCommand(new CommandData(accountGuid, positionKey(false), firstItemId));
                serviceConnector.sendCommand(new CommandData(accountGuid, positionKey(true), lastItemId));
                if (mIsSearchMode) {
                    // Remember query string for which the position was saved
                    serviceConnector.sendCommand(new CommandData(positionQueryStringKey(), mQueryString, accountGuid));
                }
            } 
            
            if (MyLog.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "Saved position \"" + accountGuid + "\"; " + positionKey(false) + "="
                        + firstItemId + "; index=" + firstScrollPos + "; lastId="
                        + lastItemId + "; index=" + lastScrollPos);
            }
        }
    }

    /**
     * Restore (First visible item) position saved for this user and for this type of timeline
     */
    private void restorePosition() {
        SharedPreferences sp = null;
        String accountGuid = "";
        if (mIsTimelineCombined) {
            sp = MyPreferences.getDefaultSharedPreferences();
        } else {
            MyAccount ma = MyAccount.getCurrentMyAccount();
            sp = ma.getMyAccountPreferences();
            accountGuid = ma.getAccountGuid();
            
        }
        boolean loaded = false;
        long firstItemId = -3;
        try {
            int scrollPos = -1;
            firstItemId = getSavedPosition(false);
            if (firstItemId > 0) {
                scrollPos = listPosForId(firstItemId);
            }
            if (scrollPos >= 0) {
                getListView().setSelectionFromTop(scrollPos, 0);
                loaded = true;
                if (MyLog.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "Restored position \"" + accountGuid + "\"; " + positionKey(false) + "="
                            + firstItemId +"; list index=" + scrollPos);
                }
            } else {
                // There is no stored position
                if (mIsSearchMode) {
                    // In search mode start from the most recent tweet!
                    scrollPos = 0;
                } else {
                    scrollPos = getListView().getCount() - 2;
                }
                if (scrollPos >= 0) {
                    setSelectionAtBottom(scrollPos);
                }
            }
        } catch (Exception e) {
            Editor ed = sp.edit();
            ed.remove(positionKey(false));
            ed.commit();
            firstItemId = -2;
        }
        if (!loaded && MyLog.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "Didn't restore position \"" + accountGuid + "\"; " + positionKey(false) + "="
                    + firstItemId);
        }
        positionRestored = true;
    }

    /**
     * @param sp This may be global or {@link MyAccount}-specific Shared Preferences
     * @param lastRow Key for First visible row (false) or Last row that will be retrieved (true)
     * @return Saved Tweet id or < 0 if none was found.
     */
    protected long getSavedPosition(boolean lastRow) {
        SharedPreferences sp;
        if (mIsTimelineCombined) {
            sp = MyPreferences.getDefaultSharedPreferences();
        } else {
            MyAccount ma = MyAccount.getCurrentMyAccount();
            sp = ma.getMyAccountPreferences();
            
        }
        long savedItemId = -3;
        if (!mIsSearchMode
                || (mQueryString.compareTo(sp.getString(
                        positionQueryStringKey(), "")) == 0)) {
            // Load saved position in Search mode only if that position was
            // saved for the same query string
            savedItemId = sp.getLong(positionKey(lastRow), -1);
        }
        return savedItemId;
    }

    /**
     * Two rows are store for each position:
     * @param lastRow Key for First visible row (false) or Last row that will be retrieved (true)
     * @return Key to store position (tweet id of the first visible row)
     */
    private String positionKey(boolean lastRow) {
        return LAST_POS_KEY + mTimelineType.save() + (mIsSearchMode ? "_search" : "") + (lastRow ? "_last" : "");
    }

    /**
     * @return Key to store query string for this position
     */
    private String positionQueryStringKey() {
        return LAST_POS_KEY + mTimelineType.save() + "_querystring";
    }

    private void setSelectionAtBottom(int scrollPos) {
        if (MyLog.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "setSelectionAtBottom, 1");
        }
        int viewHeight = getListView().getHeight();
        int childHeight;
        childHeight = 30;
        int y = viewHeight - childHeight;
        if (MyLog.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "set position of last item to " + y + "px");
        }
        getListView().setSelectionFromTop(scrollPos, y);
    }

    /**
     * Returns the position of the item with the given ID.
     * 
     * @param searchedId the ID of the item whose position in the list is to be
     *            returned.
     * @return the position in the list or -1 if the item was not found
     */
    private int listPosForId(long searchedId) {
        int listPos;
        boolean itemFound = false;
        ListView lv = getListView();
        int itemCount = lv.getCount();
        if (MyLog.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "item count: " + itemCount);
        }
        for (listPos = 0; (!itemFound && (listPos < itemCount)); listPos++) {
            long itemId = lv.getItemIdAtPosition(listPos);
            if (itemId == searchedId) {
                itemFound = true;
                break;
            }
        }

        if (!itemFound) {
            listPos = -1;
        }
        return listPos;
    }

    @Override
    public void onContentChanged() {
        super.onContentChanged();
        if (MyLog.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Content changed");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (MyLog.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "onPause, instance " + instanceId);
        }
        // The activity just lost its focus,
        // so we have to start notifying the User about new events after his
        // moment.

        if (positionRestored) {
            // Get rid of the "fast scroll thumb"
            ((ListView) findViewById(android.R.id.list)).setFastScrollEnabled(false);
            clearNotifications();
            savePosition();
        }        
        positionRestored = false;
        serviceConnector.disconnectService();
    }
   
    /**
     *  Cancel notifications of loading timeline
     *  They were set in 
     *  @see org.andstatus.app.MyService.CommandExecutor#notifyNewTweets(int, CommandEnum)
     */
    private void clearNotifications() {
        try {
            // TODO: Check if there are any notifications
            // and if none than don't waist time for this:

            mNM.cancel(MyService.CommandEnum.NOTIFY_HOME_TIMELINE.ordinal());
            mNM.cancel(MyService.CommandEnum.NOTIFY_MENTIONS.ordinal());
            mNM.cancel(MyService.CommandEnum.NOTIFY_DIRECT_MESSAGE.ordinal());

            // Reset notifications on AppWidget(s)
            Intent intent = new Intent(MyService.ACTION_APPWIDGET_UPDATE);
            intent.putExtra(MyService.EXTRA_MSGTYPE, MyService.CommandEnum.NOTIFY_CLEAR.save());
            sendBroadcast(intent);
        } finally {
            // Nothing yet...
        }
    }

    @Override
    public void onDestroy() {
        MyLog.v(TAG,"onDestroy, instance " + instanceId);
        super.onDestroy();
        if (mCursor != null && !mCursor.isClosed()) {
            mCursor.close();
        }
        if (serviceConnector != null) {
            serviceConnector.disconnectService();
        }
    }

    @Override
    public void finish() {
        MyLog.v(TAG,"Finish requested" + (mIsFinishing ? ", already finishing" : "") + ", instance " + instanceId);
        if (!mIsFinishing) {
            mIsFinishing = true;
            if (mHandler == null) {
                Log.e(TAG,"Finishing. mHandler is already null, instance " + instanceId);
            }
            mHandler = null;
        }
        // TODO Auto-generated method stub
        super.finish();
    }

    @Override
    protected Dialog onCreateDialog(int id) {
        switch (id) {
            case DIALOG_AUTHENTICATION_FAILED:
                return new AlertDialog.Builder(this).setIcon(android.R.drawable.ic_dialog_alert)
                        .setTitle(R.string.dialog_title_authentication_failed).setMessage(
                                R.string.dialog_summary_authentication_failed).setPositiveButton(
                                android.R.string.ok, new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface Dialog, int whichButton) {
                                        startActivity(new Intent(TimelineActivity.this,
                                                PreferencesActivity.class));
                                    }
                                }).create();

            case DIALOG_SERVICE_UNAVAILABLE:
                return new AlertDialog.Builder(this).setIcon(android.R.drawable.ic_dialog_alert)
                        .setTitle(R.string.dialog_title_service_unavailable).setMessage(
                                R.string.dialog_summary_service_unavailable).setPositiveButton(
                                android.R.string.ok, new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface Dialog, int whichButton) {
                                    }
                                }).create();

            case DIALOG_CONNECTION_TIMEOUT:
                return new AlertDialog.Builder(this).setIcon(android.R.drawable.ic_dialog_alert)
                        .setTitle(R.string.dialog_title_connection_timeout).setMessage(
                                R.string.dialog_summary_connection_timeout).setPositiveButton(
                                android.R.string.ok, new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface Dialog, int whichButton) {
                                    }
                                }).create();

            case DIALOG_EXECUTING_COMMAND:
                mProgressDialog = new ProgressDialog(this);
                mProgressDialog.setIcon(android.R.drawable.ic_dialog_info);
                mProgressDialog.setTitle(R.string.dialog_title_executing_command);
                mProgressDialog.setMessage(getText(R.string.dialog_summary_executing_command));
                return mProgressDialog;

            case DIALOG_TIMELINE_TYPE:
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle(R.string.dialog_title_select_timeline);
                String[] timelines = {
                        getString(R.string.activity_title_home),
                        getString(R.string.activity_title_favorites),
                        getString(R.string.activity_title_mentions),
                        getString(R.string.activity_title_direct_messages)
                };
                builder.setItems(timelines, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        // The 'which' argument contains the index position of the selected item
                        switch (which) {
                            case 0:
                                switchTimelineActivity(MyDatabase.TimelineTypeEnum.HOME, mIsTimelineCombined);
                                break;

                            case 1:
                                switchTimelineActivity(MyDatabase.TimelineTypeEnum.FAVORITES, mIsTimelineCombined);
                                break;

                            case 2:
                                switchTimelineActivity(MyDatabase.TimelineTypeEnum.MENTIONS, mIsTimelineCombined);
                                break;

                            case 3:
                                switchTimelineActivity(MyDatabase.TimelineTypeEnum.DIRECT, mIsTimelineCombined);
                                break;
                        }
                    }
                });
                return builder.create();                
            default:
                return super.onCreateDialog(id);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);

        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.timeline, menu);

        Intent intent = new Intent(null, getIntent().getData());
        intent.addCategory(Intent.CATEGORY_ALTERNATIVE);
        menu.addIntentOptions(Menu.CATEGORY_ALTERNATIVE, 0, 0, new ComponentName(this,
                TimelineActivity.class), null, intent, 0, null);

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        switch (item.getItemId()) {
            case R.id.preferences_menu_id:
                startPreferencesActivity();
                break;
/**
            case R.id.favorites_timeline_menu_id:
                switchTimelineActivity(MyDatabase.TimelineTypeEnum.FAVORITES, mIsTimelineCombined);
                break;

            case R.id.home_timeline_menu_id:
                switchTimelineActivity(MyDatabase.TimelineTypeEnum.HOME, mIsTimelineCombined);
                break;

            case R.id.direct_messages_menu_id:
                switchTimelineActivity(MyDatabase.TimelineTypeEnum.DIRECT, mIsTimelineCombined);
                break;

            case R.id.mentions_menu_id:
                switchTimelineActivity(MyDatabase.TimelineTypeEnum.MENTIONS, mIsTimelineCombined);
                break;
**/
            case R.id.search_menu_id:
                onSearchRequested();
                break;
                
            case R.id.reload_menu_item:
                manualReload(false);
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * Listener that checks for clicks on the main list view.
     * 
     * @param adapterView
     * @param view
     * @param position
     * @param id
     */
    public void onItemClick(AdapterView<?> adapterView, View view, int position, long id) {
        if (id <= 0) {
            if (MyLog.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "onItemClick, id=" + id);
            }
            return;
        }
        long userId = getUserIdFromCursor(position);
        
        if (MyLog.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "onItemClick, id=" + id + "; userId=" + userId);
        }
        Uri uri = MyProvider.getTimelineMsgUri(userId, id);
        String action = getIntent().getAction();
        if (Intent.ACTION_PICK.equals(action) || Intent.ACTION_GET_CONTENT.equals(action)) {
            if (MyLog.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onItemClick, setData=" + uri);
            }
            setResult(RESULT_OK, new Intent().setData(uri));
        } else {
            if (MyLog.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onItemClick, startActivity=" + uri);
            }
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        }
    }

    /**
     * @param position Of current item in the {@link #mCursor}
     */
    private long getUserIdFromCursor(int position) {
        long userId = 0;
        try {
            mCursor.moveToPosition(position);
            int columnIndex = mCursor.getColumnIndex(MsgOfUser.USER_ID);
            if (columnIndex > -1) {
                try {
                    userId = mCursor.getLong(columnIndex);
                } catch (Exception e) {}
            }
        } catch (Exception e) {}
        return (userId);
    }
    
    public boolean onKey(View v, int keyCode, KeyEvent event) {
        return false;
    }

    public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount,
            int totalItemCount) {
        mTotalItemCount = totalItemCount;

        if (positionRestored && !isLoading()) {
            // Idea from
            // http://stackoverflow.com/questions/1080811/android-endless-list
            boolean loadMore = (visibleItemCount > 0) && (firstVisibleItem > 0)
                    && (firstVisibleItem + visibleItemCount >= totalItemCount);
            if (loadMore) {
                setIsLoading(true);
                MyLog.d(TAG, "Start Loading more items, total=" + totalItemCount);
                // setProgressBarIndeterminateVisibility(true);
                mListFooter.setVisibility(View.VISIBLE);
                Thread thread = new Thread(mLoadListItems);
                thread.start();
            }
        }
    }

    public void onScrollStateChanged(AbsListView view, int scrollState) {
        switch (scrollState) {
            case OnScrollListener.SCROLL_STATE_IDLE:
                break;
            case OnScrollListener.SCROLL_STATE_TOUCH_SCROLL:
                break;
            case OnScrollListener.SCROLL_STATE_FLING:
                // Turn the "fast scroll thumb" on
                view.setFastScrollEnabled(true);
                break;
        }
    }

    /**
     * Updates the activity title.
     * Sets the title with a left and right title.
     * 
     * @param rightText Right title part
     */
    public void updateTitle(String rightText) {
        String timelinename = "??";
        switch (mTimelineType) {
            case FAVORITES:
                timelinename = getString(R.string.activity_title_favorites);
                break;
            case HOME:
                timelinename = getString(R.string.activity_title_home);
                break;
            case MENTIONS:
                timelinename = getString(R.string.activity_title_mentions);
                break;
            case DIRECT:
                timelinename = getString(R.string.activity_title_direct_messages);
                break;
        }
        Button timelineTypeButton = (Button) findViewById(R.id.timelineTypeButton);
        timelineTypeButton.setText(timelinename + (mIsSearchMode ? " *" : ""));
        
        // Show current account info on the left button
        String username = MyAccount.getCurrentMyAccount().getUsername();
        Button leftTitle = (Button) findViewById(R.id.selectAccountButton);
        leftTitle.setText(username);
        
        TextView rightTitle = (TextView) findViewById(R.id.custom_title_right_text);
        rightTitle.setText(rightText);

        Button createMessageButton = (Button) findViewById(R.id.createMessageButton);
        if (mTimelineType != TimelineTypeEnum.DIRECT) {
            createMessageButton.setText(getString(MyAccount.getCurrentMyAccount().alternativeTermResourceId(R.string.button_create_message)));
            createMessageButton.setVisibility(View.VISIBLE);
        } else {
            createMessageButton.setVisibility(View.GONE);
        }
    }

    /**
     * Updates the activity title.
     */
   public void updateTitle() {
        // First set less detailed title
        updateTitle("");
    }

    /**
     * Retrieve the text that is currently in the editor.
     * 
     * @return Text currently in the editor
     */
    protected CharSequence getSavedText() {
        return ((EditText) findViewById(R.id.edtTweetInput)).getText();
    }

    /**
     * Set the text in the text editor.
     * 
     * @param text
     */
    protected void setSavedText(CharSequence text) {
        ((EditText) findViewById(R.id.edtTweetInput)).setText(text);
    }

    /**
     * Check to see if the system has a hardware keyboard.
     * 
     * @return
     */
    protected boolean hasHardwareKeyboard() {
        Configuration c = getResources().getConfiguration();
        switch (c.keyboard) {
            case Configuration.KEYBOARD_12KEY:
            case Configuration.KEYBOARD_QWERTY:
                return true;
            default:
                return false;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (MyLog.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "onNewIntent, instance " + instanceId);
        }
        processNewIntent(intent);
        updateThisOnChangedParameters();
    }

    /**
     * Change the Activity according to the new intent. This procedure is done
     * both {@link #onCreate(Bundle)} and {@link #onNewIntent(Intent)}
     * 
     * @param intentNew
     */
    private void processNewIntent(Intent intentNew) {
        TimelineTypeEnum timelineType_new = TimelineTypeEnum.load(intentNew
                .getStringExtra(MyService.EXTRA_TIMELINE_TYPE));
        if (timelineType_new != TimelineTypeEnum.UNKNOWN) {
            mTimelineType = timelineType_new;
            mIsTimelineCombined = intentNew.getBooleanExtra(MyService.EXTRA_TIMELINE_IS_COMBINED, mIsTimelineCombined);
            mQueryString = intentNew.getStringExtra(SearchManager.QUERY);
        } else {
            Bundle appSearchData = intentNew.getBundleExtra(SearchManager.APP_DATA);
            if (appSearchData != null) {
                // We use other packaging of the same parameters in onSearchRequested
                timelineType_new = TimelineTypeEnum.load(appSearchData
                        .getString(MyService.EXTRA_TIMELINE_TYPE));
                if (timelineType_new != TimelineTypeEnum.UNKNOWN) {
                    mTimelineType = timelineType_new;
                    mIsTimelineCombined = appSearchData.getBoolean(MyService.EXTRA_TIMELINE_IS_COMBINED, mIsTimelineCombined);
                    mQueryString = intentNew.getStringExtra(SearchManager.QUERY);
                }
            }
        }
        if (mTimelineType == TimelineTypeEnum.UNKNOWN) {
            mTimelineType = TimelineTypeEnum.HOME;
        }

        // Are we supposed to send a tweet?
        if (Intent.ACTION_SEND.equals(intentNew.getAction())) {
            String textInitial = "";
            // This is Title of the page is Sharing Web page
            String subject = intentNew.getStringExtra(Intent.EXTRA_SUBJECT);
            // This is URL of the page if sharing web page
            String text = intentNew.getStringExtra(Intent.EXTRA_TEXT);
            if (!TextUtils.isEmpty(subject)) {
                textInitial += subject;
            }
            if (!TextUtils.isEmpty(text)) {
                if (!TextUtils.isEmpty(textInitial)) {
                    textInitial += " ";
                }
                textInitial += text;
            }
            MyLog.v(TAG, "Intent.ACTION_SEND '" + textInitial +"'");
            mTweetEditor.startEditingMessage(textInitial, 0, 0, MyAccount.getCurrentMyAccount().getAccountGuid(), mIsTimelineCombined);
        }

        if (MyLog.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "processNewIntent; type=\"" + mTimelineType.save() + "\"");
        }
    }

    private void updateThisOnChangedParameters() {
        mIsSearchMode = !TextUtils.isEmpty(mQueryString);
        if (mIsSearchMode) {
            // Let's check if last time we saved position for the same query
            // string

        } else {
            mQueryString = "";
        }

        ToggleButton combinedTimelineToggle = (ToggleButton) findViewById(R.id.combinedTimelineToggle);
        combinedTimelineToggle.setChecked(mIsTimelineCombined);
        
        queryListData(false, false);

        if (mTweetEditor.isStateLoaded()) {
            mTweetEditor.continueEditingLoadedState();
        } else if (mTweetEditor.isVisible()) {
            // This is done to request focus (if we need this...)
            mTweetEditor.show();
        }
    }
    
    /**
     * Prepare query to the ContentProvider (to the database) and load List of Tweets with data
     * @param otherThread This method is being accessed from other thread
     * @param loadOneMorePage load one more page of tweets
     */
    protected void queryListData(boolean otherThread, boolean loadOneMorePage) {
        Intent intent = getIntent();

        if (MyLog.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "queryListData; queryString=\"" + mQueryString + "\"; TimelineType="
                    + mTimelineType.save()
                    + "; isCombined=" + (mIsTimelineCombined ? "yes" : "no"));
        }

        Uri contentUri = MyProvider.getTimelineUri(MyAccount.getCurrentMyAccountUserId(), mIsTimelineCombined);

        SelectionAndArgs sa = new SelectionAndArgs();
        String sortOrder = MyDatabase.Msg.DEFAULT_SORT_ORDER;
        // Id of the last (oldest) tweet to retrieve 
        long lastItemId = -1;
        
        if (!TextUtils.isEmpty(mQueryString)) {
            // Record the query string in the recent queries of the Suggestion Provider
            SearchRecentSuggestions suggestions = new SearchRecentSuggestions(this,
                    TimelineSearchSuggestionProvider.AUTHORITY,
                    TimelineSearchSuggestionProvider.MODE);
            suggestions.saveRecentQuery(mQueryString, null);

            contentUri = MyProvider.getTimelineSearchUri(MyAccount.getCurrentMyAccountUserId(), mIsTimelineCombined, mQueryString);
        }

        if (!contentUri.equals(intent.getData())) {
            intent.setData(contentUri);
        }

        if (sa.nArgs == 0) {
            // In fact this is needed every time you want to load next page of tweets.
            // So we have to duplicate here everything we set in
            // org.andstatus.app.TimelineActivity.onOptionsItemSelected()
            
            /* TODO: Other conditions... */
            sa.clear();
            
            switch (mTimelineType) {
                case HOME:
                    // In the Home of the combined timeline we see ALL loaded messages,
                    // even those that we downloaded not as Home timeline of any Account
                    if (!mIsTimelineCombined) {
                        sa.addSelection(MyDatabase.MsgOfUser.SUBSCRIBED + " = ?", new String[] {
                                "1"
                            });
                    }
                    break;
                case MENTIONS:
                    sa.addSelection(MyDatabase.MsgOfUser.MENTIONED + " = ?", new String[] {
                            "1"
                        });
                    /* We already figured it out!
                    sa.addSelection(MyDatabase.Msg.BODY + " LIKE ?", new String[] {
                            "%@" + MyAccount.getTwitterUser().getUsername() + "%"
                        });
                    */
                    break;
                case FAVORITES:
                    sa.addSelection(MyDatabase.MsgOfUser.FAVORITED + " = ?", new String[] {
                            "1"
                        });
                    break;
                case DIRECT:
                    sa.addSelection(MyDatabase.MsgOfUser.DIRECTED + " = ?", new String[] {
                            "1"
                        });
                    break;
            }
        }

        if (!positionRestored) {
            // We have to ensure that saved position will be
            // loaded from database into the list
            lastItemId = getSavedPosition(true);
        }

        int nTweets = 0;
        if (mCursor != null && !mCursor.isClosed()) {
            if (positionRestored) {
                // If position is NOT loaded - this cursor is from other
                // timeline/search
                // and we shouldn't care how much rows are there.
                nTweets = mCursor.getCount();
            }
            if (!otherThread) {
                mCursor.close();
            }
        }

        if (lastItemId > 0) {
            sa.addSelection(MyDatabase.MSG_TABLE_NAME + "." + MyDatabase.Msg.SENT_DATE + " >= ?", new String[] {
                String.valueOf(MyProvider.msgSentDate(lastItemId))
            });
        } else {
            if (loadOneMorePage) {
                nTweets += PAGE_SIZE;
            } else if (nTweets < PAGE_SIZE) {
                nTweets = PAGE_SIZE;
            }
            sortOrder += " LIMIT 0," + nTweets;
        }

        // This is for testing pruneOldRecords
//        try {
//            TimelineDownloader fl = new TimelineDownloader(TimelineActivity.this,
//                    TimelineActivity.TIMELINE_TYPE_HOME);
//            fl.pruneOldRecords();
//
//        } catch (Exception e) {
//            e.printStackTrace();
//        }

        mCursor = getContentResolver().query(contentUri, PROJECTION, sa.selection,
                sa.selectionArgs, sortOrder);
        if (!otherThread) {
            createAdapters();
        }

    }
    
    /**
     * Only newer tweets (newer that last loaded) are being loaded from the
     * Internet, old ones are not being reloaded.
     */
    protected void manualReload(boolean allTimelineTypes) {

        // Show something to the user...
        setIsLoading(true);
        mListFooter.setVisibility(View.VISIBLE);
        //TimelineActivity.this.findViewById(R.id.item_loading).setVisibility(View.VISIBLE);

        // Ask service to load data for this mTimelineType
        MyService.CommandEnum command;
        switch (mTimelineType) {
            case DIRECT:
                command = CommandEnum.FETCH_DIRECT_MESSAGES;
                break;
            case MENTIONS:
                command = CommandEnum.FETCH_MENTIONS;
                break;
            default:
                command = CommandEnum.FETCH_HOME;
        }
        serviceConnector.sendCommand(new CommandData(command,
               mIsTimelineCombined ? "" : MyAccount.getCurrentMyAccount().getAccountGuid()));

        if (allTimelineTypes) {
            serviceConnector.sendCommand(new CommandData(CommandEnum.FETCH_ALL_TIMELINES, MyAccount.getCurrentMyAccount().getAccountGuid()));
        }
    }
    
    protected void startPreferencesActivity() {
        // We need to restart this Activity after exiting PreferencesActivity
        // So let's set the flag:
        //MyPreferences.getDefaultSharedPreferences().edit()
        //        .putBoolean(PreferencesActivity.KEY_PREFERENCES_CHANGE_TIME, true).commit();
        startActivity(new Intent(this, PreferencesActivity.class));
    }

    /**
     * Switch type of presented timeline
     */
    protected void switchTimelineActivity(TimelineTypeEnum timelineType, boolean isTimelineCombined) {
        Intent intent;
        if (MyLog.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "switchTimelineActivity; type=\"" + timelineType.save() + "\"; isCombined=" + (isTimelineCombined ? "yes" : "no"));
        }
        switch (timelineType) {
            default:
                timelineType = MyDatabase.TimelineTypeEnum.HOME;
                // Actually we use one Activity for all timelines...
            case MENTIONS:
            case FAVORITES:
            case HOME:
            case DIRECT:
                intent = new Intent(this, TimelineActivity.class);
                break;

        }

        intent.removeExtra(SearchManager.QUERY);
        intent.putExtra(MyService.EXTRA_TIMELINE_TYPE, timelineType.save());
        intent.putExtra(MyService.EXTRA_TIMELINE_IS_COMBINED, isTimelineCombined);
        // We don't use the Action anywhere, so there is no need it setting it.
        // - we're analyzing query instead!
        // intent.setAction(Intent.ACTION_SEARCH);
        startActivity(intent);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(BUNDLE_KEY_IS_LOADING, isLoading());

        mTweetEditor.saveState(outState);
        outState.putString(MyService.EXTRA_TIMELINE_TYPE, mTimelineType.save());
        outState.putLong(MyService.EXTRA_TWEETID, mCurrentId);
        outState.putBoolean(MyService.EXTRA_TIMELINE_IS_COMBINED, mIsTimelineCombined);
        outState.putString(SearchManager.QUERY, mQueryString);

        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQUEST_SELECT_ACCOUNT:
                if (resultCode == RESULT_OK) {
                    MyLog.v(TAG, "Restarting the activity for the selected account");
                    finish();
                    switchTimelineActivity(mTimelineType, mIsTimelineCombined);
                }
                break;
            default:
                super.onActivityResult(requestCode, resultCode, data);
                break;
        }
        
    }
    

    @Override
    public void onCreateContextMenu(ContextMenu menu, View view, ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, view, menuInfo);

        // Get the adapter context menu information
        AdapterView.AdapterContextMenuInfo info;
        try {
            info = (AdapterView.AdapterContextMenuInfo) menuInfo;
        } catch (ClassCastException e) {
            Log.e(TAG, "bad menuInfo", e);
            return;
        }

        int m = 0;
        mCurrentId = info.id;
        MyAccount ma = MyAccount.getMyAccountForTheMessage(mCurrentId, getUserIdFromCursor(info.position));
        if (ma == null) {
            return;
        }

        // Add menu items
        menu.add(0, CONTEXT_MENU_ITEM_REPLY, m++, R.string.menu_item_reply);
        menu.add(0, CONTEXT_MENU_ITEM_SHARE, m++, R.string.menu_item_share);
        menu.add(0, CONTEXT_MENU_ITEM_DIRECT_MESSAGE, m++, R.string.menu_item_direct_message);
        // menu.add(0, CONTEXT_MENU_ITEM_UNFOLLOW, m++,
        // R.string.menu_item_unfollow);
        // menu.add(0, CONTEXT_MENU_ITEM_BLOCK, m++, R.string.menu_item_block);
        // menu.add(0, CONTEXT_MENU_ITEM_PROFILE, m++,
        // R.string.menu_item_view_profile);

        // Get the record for the currently selected item
        Uri uri = MyProvider.getTimelineMsgUri(ma.getUserId(), mCurrentId);
        Cursor c = getContentResolver().query(uri, new String[] {
                MyDatabase.Msg._ID, MyDatabase.Msg.BODY, MyDatabase.Msg.SENDER_ID, 
                MyDatabase.Msg.AUTHOR_ID, MyDatabase.MsgOfUser.FAVORITED, 
                MyDatabase.MsgOfUser.REBLOGGED
        }, null, null, null);
        try {
            if (c != null && c.getCount() > 0) {
                c.moveToFirst();
                menu.setHeaderTitle( (mIsTimelineCombined ? ma.getAccountGuid() + ": " : "" )
                         + c.getString(c.getColumnIndex(MyDatabase.Msg.BODY)));
                if (c.getInt(c.getColumnIndex(MyDatabase.MsgOfUser.FAVORITED)) == 1) {
                    menu.add(0, CONTEXT_MENU_ITEM_DESTROY_FAVORITE, m++,
                            R.string.menu_item_destroy_favorite);
                } else {
                    menu.add(0, CONTEXT_MENU_ITEM_FAVORITE, m++, R.string.menu_item_favorite);
                }
                if (c.getInt(c.getColumnIndex(MyDatabase.MsgOfUser.REBLOGGED)) == 1) {
                    // TODO:
                    //menu.add(0, CONTEXT_MENU_ITEM_DESTROY_REBLOG, m++,
                    //        R.string.menu_item_destroy_reblog);
                } else {
                    // Don't allow a User to reblog himself 
                    if (ma.getUserId() != c.getLong(c.getColumnIndex(MyDatabase.Msg.SENDER_ID))) {
                        menu.add(0, CONTEXT_MENU_ITEM_REBLOG, m++, ma.alternativeTermResourceId(R.string.menu_item_reblog));
                    }
                }
                if (ma.getUserId() == c.getLong(c.getColumnIndex(MyDatabase.Msg.SENDER_ID))
                        && ma.getUserId() == c.getLong(c.getColumnIndex(MyDatabase.Msg.AUTHOR_ID))
                        ) {
                    menu.add(0, CONTEXT_MENU_ITEM_DESTROY_STATUS, m++,
                            R.string.menu_item_destroy_status);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "onCreateContextMenu: " + e.toString());
        } finally {
            if (c != null && !c.isClosed())
                c.close();
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        super.onContextItemSelected(item);
        AdapterView.AdapterContextMenuInfo info;
        try {
            info = (AdapterView.AdapterContextMenuInfo) item.getMenuInfo();
        } catch (ClassCastException e) {
            Log.e(TAG, "bad menuInfo", e);
            return false;
        }

        mCurrentId = info.id;
        MyAccount ma = MyAccount.getMyAccountForTheMessage(mCurrentId, getUserIdFromCursor(info.position));
        if (ma != null) {
            Uri uri;
            String userName;
            Cursor c;

            switch (item.getItemId()) {
                case CONTEXT_MENU_ITEM_REPLY:
                    mTweetEditor.startEditingMessage("", mCurrentId, 0, ma.getAccountGuid(), mIsTimelineCombined);
                    return true;

                case CONTEXT_MENU_ITEM_DIRECT_MESSAGE:
                    long authorId = MyProvider.msgIdToUserId(MyDatabase.Msg.AUTHOR_ID, mCurrentId);
                    if (authorId != 0) {
                        mTweetEditor.startEditingMessage("", mCurrentId, authorId, ma.getAccountGuid(), mIsTimelineCombined);
                        return true;
                    }
                    break;

                case CONTEXT_MENU_ITEM_REBLOG:
                    serviceConnector.sendCommand( new CommandData(CommandEnum.REBLOG, ma.getAccountGuid(), mCurrentId));
                    return true;

                case CONTEXT_MENU_ITEM_DESTROY_STATUS:
                    serviceConnector.sendCommand( new CommandData(CommandEnum.DESTROY_STATUS, ma.getAccountGuid(), mCurrentId));
                    return true;

                case CONTEXT_MENU_ITEM_FAVORITE:
                    serviceConnector.sendCommand( new CommandData(CommandEnum.CREATE_FAVORITE, ma.getAccountGuid(), mCurrentId));
                    return true;

                case CONTEXT_MENU_ITEM_DESTROY_FAVORITE:
                    serviceConnector.sendCommand( new CommandData(CommandEnum.DESTROY_FAVORITE, ma.getAccountGuid(), mCurrentId));
                    return true;

                case CONTEXT_MENU_ITEM_SHARE:
                    userName = MyProvider.msgIdToUsername(MyDatabase.Msg.AUTHOR_ID, mCurrentId);
                    uri = MyProvider.getTimelineMsgUri(ma.getUserId(), info.id);
                    c = getContentResolver().query(uri, new String[] {
                            MyDatabase.Msg.MSG_OID, MyDatabase.Msg.BODY
                    }, null, null, null);
                    try {
                        if (c != null && c.getCount() > 0) {
                            c.moveToFirst();
        
                            StringBuilder subject = new StringBuilder();
                            StringBuilder text = new StringBuilder();
                            String msgBody = c.getString(c.getColumnIndex(MyDatabase.Msg.BODY));
        
                            subject.append(getText(ma.alternativeTermResourceId(R.string.message)));
                            subject.append(" - " + msgBody);
                            int maxlength = 80;
                            if (subject.length() > maxlength) {
                                subject.setLength(maxlength);
                                // Truncate at the last space
                                subject.setLength(subject.lastIndexOf(" "));
                                subject.append("...");
                            }
        
                            text.append(msgBody);
                            text.append("\n-- \n" + userName);
                            text.append("\n URL: " + ma.messagePermalink(userName, 
                                    c.getString(c.getColumnIndex(MyDatabase.Msg.MSG_OID))));
                            
                            Intent share = new Intent(android.content.Intent.ACTION_SEND); 
                            share.setType("text/plain"); 
                            share.putExtra(Intent.EXTRA_SUBJECT, subject.toString()); 
                            share.putExtra(Intent.EXTRA_TEXT, text.toString()); 
                            startActivity(Intent.createChooser(share, getText(R.string.menu_item_share)));
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "onContextItemSelected: " + e.toString());
                        return false;
                    } finally {
                        if (c != null && !c.isClosed())
                            c.close();
                    }
                    return true;
                    
                case CONTEXT_MENU_ITEM_UNFOLLOW:
                case CONTEXT_MENU_ITEM_BLOCK:
                case CONTEXT_MENU_ITEM_PROFILE:
                    Toast.makeText(this, R.string.unimplemented, Toast.LENGTH_SHORT).show();
                    return true;
            }
        }

        return false;
    }

    /**
     * Create adapters
     */
    private void createAdapters() {
        int listItemId = R.layout.tweetlist_item;
        if (MyPreferences.getDefaultSharedPreferences().getBoolean("appearance_use_avatars", false)) {
            listItemId = R.layout.tweetlist_item_avatar;
        }
        PagedCursorAdapter tweetsAdapter = new PagedCursorAdapter(TimelineActivity.this,
                listItemId, mCursor, new String[] {
                MyDatabase.User.AUTHOR_NAME, MyDatabase.Msg.BODY, MyDatabase.Msg.CREATED_DATE, MyDatabase.MsgOfUser.FAVORITED
                }, new int[] {
                        R.id.message_author, R.id.message_body, R.id.message_details,
                        R.id.tweet_favorite
                }, getIntent().getData(), PROJECTION, MyDatabase.Msg.DEFAULT_SORT_ORDER);
        tweetsAdapter.setViewBinder(new TweetBinder());

        setListAdapter(tweetsAdapter);
    }

    /**
     * Load more items from the database into the list. This procedure doesn't
     * download any new tweets from the Internet
     */
    protected Runnable mLoadListItems = new Runnable() {
        public void run() {
            if (MyLog.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "mLoadListItems run");
            }
            if (!mIsFinishing) {
                queryListData(true, true);
            }
            if (!mIsFinishing) {
                mHandler.sendMessageDelayed(
                        mHandler.obtainMessage(MSG_LOAD_ITEMS, STATUS_LOAD_ITEMS_SUCCESS, 0), 400);
            }
        }
    };
    
}
