/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.dialer.calllog;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.Loader;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.sqlite.SQLiteFullException;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.provider.CallLog.Calls;
import android.provider.ContactsContract;
import android.provider.ContactsContract.PhoneLookup;
import android.telecom.PhoneAccountHandle;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.AccessibilityDelegate;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.android.common.widget.GroupingListAdapter;
import com.android.contacts.common.CallUtil;
import com.android.contacts.common.ContactPhotoManager;
import com.android.contacts.common.ContactPhotoManager.DefaultImageRequest;
import com.android.contacts.common.util.PhoneNumberHelper;
import com.android.contacts.common.model.Contact;
import com.android.contacts.common.model.ContactLoader;
import com.android.contacts.common.util.UriUtils;
import com.android.dialer.DialtactsActivity;
import com.android.dialer.PhoneCallDetails;
import com.android.dialer.PhoneCallDetailsHelper;
import com.android.dialer.R;
import com.android.dialer.util.DialerUtils;
import com.android.dialer.util.ExpirableCache;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;

/**
 * Adapter class to fill in data for the Call Log.
 */
public class CallLogAdapter extends GroupingListAdapter
        implements ViewTreeObserver.OnPreDrawListener, CallLogGroupBuilder.GroupCreator {
    private static final String TAG = CallLogAdapter.class.getSimpleName();

    private static final int VOICEMAIL_TRANSCRIPTION_MAX_LINES = 10;

    /** The enumeration of {@link android.os.AsyncTask} objects used in this class. */
    public enum Tasks {
        REMOVE_CALL_LOG_ENTRIES,
    }

    /** Interface used to inform a parent UI element that a list item has been expanded. */
    public interface CallItemExpandedListener {
        /**
         * @param view The {@link View} that represents the item that was clicked
         *         on.
         */
        public void onItemExpanded(View view);

        /**
         * Retrieves the call log view for the specified call Id.  If the view is not currently
         * visible, returns null.
         *
         * @param callId The call Id.
         * @return The call log view.
         */
        public View getViewForCallId(long callId);
    }

    /** Interface used to initiate a refresh of the content. */
    public interface CallFetcher {
        public void fetchCalls();
    }

    /** Implements onClickListener for the report button. */
    public interface OnReportButtonClickListener {
        public void onReportButtonClick(String number);
    }

    /**
     * Stores a phone number of a call with the country code where it originally occurred.
     * <p>
     * Note the country does not necessarily specifies the country of the phone number itself, but
     * it is the country in which the user was in when the call was placed or received.
     */
    private static final class NumberWithCountryIso {
        public final String number;
        public final String countryIso;

        public NumberWithCountryIso(String number, String countryIso) {
            this.number = number;
            this.countryIso = countryIso;
        }

        @Override
        public boolean equals(Object o) {
            if (o == null) return false;
            if (!(o instanceof NumberWithCountryIso)) return false;
            NumberWithCountryIso other = (NumberWithCountryIso) o;
            return TextUtils.equals(number, other.number)
                    && TextUtils.equals(countryIso, other.countryIso);
        }

        @Override
        public int hashCode() {
            return (number == null ? 0 : number.hashCode())
                    ^ (countryIso == null ? 0 : countryIso.hashCode());
        }
    }

    /** The time in millis to delay starting the thread processing requests. */
    private static final int START_PROCESSING_REQUESTS_DELAY_MILLIS = 1000;

    /** The size of the cache of contact info. */
    private static final int CONTACT_INFO_CACHE_SIZE = 100;

    /** Constant used to indicate no row is expanded. */
    private static final long NONE_EXPANDED = -1;

    protected final Context mContext;
    private final ContactInfoHelper mContactInfoHelper;
    private final CallFetcher mCallFetcher;
    private final Toast mReportedToast;
    private final OnReportButtonClickListener mOnReportButtonClickListener;
    private ViewTreeObserver mViewTreeObserver = null;

    /**
     * A cache of the contact details for the phone numbers in the call log.
     * <p>
     * The content of the cache is expired (but not purged) whenever the application comes to
     * the foreground.
     * <p>
     * The key is number with the country in which the call was placed or received.
     */
    private ExpirableCache<NumberWithCountryIso, ContactInfo> mContactInfoCache;

    /**
     * Tracks the call log row which was previously expanded.  Used so that the closure of a
     * previously expanded call log entry can be animated on rebind.
     */
    private long mPreviouslyExpanded = NONE_EXPANDED;

    /**
     * Tracks the currently expanded call log row.
     */
    private long mCurrentlyExpanded = NONE_EXPANDED;

    /**
     *  Hashmap, keyed by call Id, used to track the day group for a call.  As call log entries are
     *  put into the primary call groups in {@link com.android.dialer.calllog.CallLogGroupBuilder},
     *  they are also assigned a secondary "day group".  This hashmap tracks the day group assigned
     *  to all calls in the call log.  This information is used to trigger the display of a day
     *  group header above the call log entry at the start of a day group.
     *  Note: Multiple calls are grouped into a single primary "call group" in the call log, and
     *  the cursor used to bind rows includes all of these calls.  When determining if a day group
     *  change has occurred it is necessary to look at the last entry in the call log to determine
     *  its day group.  This hashmap provides a means of determining the previous day group without
     *  having to reverse the cursor to the start of the previous day call log entry.
     */
    private HashMap<Long,Integer> mDayGroups = new HashMap<Long, Integer>();

    /**
     * A request for contact details for the given number.
     */
    private static final class ContactInfoRequest {
        /** The number to look-up. */
        public final String number;
        /** The country in which a call to or from this number was placed or received. */
        public final String countryIso;
        /** The cached contact information stored in the call log. */
        public final ContactInfo callLogInfo;

        public ContactInfoRequest(String number, String countryIso, ContactInfo callLogInfo) {
            this.number = number;
            this.countryIso = countryIso;
            this.callLogInfo = callLogInfo;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null) return false;
            if (!(obj instanceof ContactInfoRequest)) return false;

            ContactInfoRequest other = (ContactInfoRequest) obj;

            if (!TextUtils.equals(number, other.number)) return false;
            if (!TextUtils.equals(countryIso, other.countryIso)) return false;
            if (!Objects.equal(callLogInfo, other.callLogInfo)) return false;

            return true;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((callLogInfo == null) ? 0 : callLogInfo.hashCode());
            result = prime * result + ((countryIso == null) ? 0 : countryIso.hashCode());
            result = prime * result + ((number == null) ? 0 : number.hashCode());
            return result;
        }
    }

    /**
     * List of requests to update contact details.
     * <p>
     * Each request is made of a phone number to look up, and the contact info currently stored in
     * the call log for this number.
     * <p>
     * The requests are added when displaying the contacts and are processed by a background
     * thread.
     */
    private final LinkedList<ContactInfoRequest> mRequests;

    private boolean mLoading = true;
    private static final int REDRAW = 1;
    private static final int START_THREAD = 2;

    private QueryThread mCallerIdThread;

    /** Instance of helper class for managing views. */
    private final CallLogListItemHelper mCallLogViewsHelper;

    /** Helper to set up contact photos. */
    private final ContactPhotoManager mContactPhotoManager;
    /** Helper to parse and process phone numbers. */
    private PhoneNumberDisplayHelper mPhoneNumberHelper;
    /** Helper to access Telephony phone number utils class */
    protected final PhoneNumberUtilsWrapper mPhoneNumberUtilsWrapper;
    /** Helper to group call log entries. */
    private final CallLogGroupBuilder mCallLogGroupBuilder;

    private CallItemExpandedListener mCallItemExpandedListener;

    /** Can be set to true by tests to disable processing of requests. */
    private volatile boolean mRequestProcessingDisabled = false;

    private boolean mIsCallLog = true;

    private View mBadgeContainer;
    private ImageView mBadgeImageView;
    private TextView mBadgeText;

    private int mCallLogBackgroundColor;
    private int mExpandedBackgroundColor;
    private float mExpandedTranslationZ;
    private int mPhotoSize;

    /** Listener for the primary or secondary actions in the list.
     *  Primary opens the call details.
     *  Secondary calls or plays.
     **/
    private final View.OnClickListener mActionListener = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            startActivityForAction(view);
        }
    };

    /**
     * The onClickListener used to expand or collapse the action buttons section for a call log
     * entry.
     */
    private final View.OnClickListener mExpandCollapseListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            final View callLogItem = (View) v.getParent().getParent();
            handleRowExpanded(callLogItem, true /* animate */, false /* forceExpand */);
        }
    };

    private AccessibilityDelegate mAccessibilityDelegate = new AccessibilityDelegate() {
        @Override
        public boolean onRequestSendAccessibilityEvent(ViewGroup host, View child,
                AccessibilityEvent event) {
            if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) {
                handleRowExpanded(host, false /* animate */,
                        true /* forceExpand */);
            }
            return super.onRequestSendAccessibilityEvent(host, child, event);
        }
    };

    private void startActivityForAction(View view) {
        final IntentProvider intentProvider = (IntentProvider) view.getTag();
        if (intentProvider != null) {
            final Intent intent = intentProvider.getIntent(mContext);
            // See IntentProvider.getCallDetailIntentProvider() for why this may be null.
            if (intent != null) {
                DialerUtils.startActivityWithErrorToast(mContext, intent);
            }
        }
    }

    @Override
    public boolean onPreDraw() {
        // We only wanted to listen for the first draw (and this is it).
        unregisterPreDrawListener();

        // Only schedule a thread-creation message if the thread hasn't been
        // created yet. This is purely an optimization, to queue fewer messages.
        if (mCallerIdThread == null) {
            mHandler.sendEmptyMessageDelayed(START_THREAD, START_PROCESSING_REQUESTS_DELAY_MILLIS);
        }

        return true;
    }

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case REDRAW:
                    notifyDataSetChanged();
                    break;
                case START_THREAD:
                    startRequestProcessing();
                    break;
            }
        }
    };

    public CallLogAdapter(Context context, CallFetcher callFetcher,
            ContactInfoHelper contactInfoHelper, CallItemExpandedListener callItemExpandedListener,
            OnReportButtonClickListener onReportButtonClickListener, boolean isCallLog) {
        super(context);

        mContext = context;
        mCallFetcher = callFetcher;
        mContactInfoHelper = contactInfoHelper;
        mIsCallLog = isCallLog;
        mCallItemExpandedListener = callItemExpandedListener;

        mOnReportButtonClickListener = onReportButtonClickListener;
        mReportedToast = Toast.makeText(mContext, R.string.toast_caller_id_reported,
                Toast.LENGTH_SHORT);

        mContactInfoCache = ExpirableCache.create(CONTACT_INFO_CACHE_SIZE);
        mRequests = new LinkedList<ContactInfoRequest>();

        Resources resources = mContext.getResources();
        CallTypeHelper callTypeHelper = new CallTypeHelper(resources);
        mCallLogBackgroundColor = resources.getColor(R.color.background_dialer_list_items);
        mExpandedBackgroundColor = resources.getColor(R.color.call_log_expanded_background_color);
        mExpandedTranslationZ = resources.getDimension(R.dimen.call_log_expanded_translation_z);
        mPhotoSize = resources.getDimensionPixelSize(R.dimen.contact_photo_size);

        mContactPhotoManager = ContactPhotoManager.getInstance(mContext);
        mPhoneNumberHelper = new PhoneNumberDisplayHelper(mContext, resources);
        mPhoneNumberUtilsWrapper = new PhoneNumberUtilsWrapper(mContext);
        PhoneCallDetailsHelper phoneCallDetailsHelper =
                new PhoneCallDetailsHelper(mContext, resources, mPhoneNumberUtilsWrapper);
        mCallLogViewsHelper =
                new CallLogListItemHelper(
                        phoneCallDetailsHelper, mPhoneNumberHelper, resources);
        mCallLogGroupBuilder = new CallLogGroupBuilder(this);
    }

    /**
     * Requery on background thread when {@link Cursor} changes.
     */
    @Override
    protected void onContentChanged() {
        mCallFetcher.fetchCalls();
    }

    public void setLoading(boolean loading) {
        mLoading = loading;
    }

    @Override
    public boolean isEmpty() {
        if (mLoading) {
            // We don't want the empty state to show when loading.
            return false;
        } else {
            return super.isEmpty();
        }
    }

    /**
     * Starts a background thread to process contact-lookup requests, unless one
     * has already been started.
     */
    private synchronized void startRequestProcessing() {
        // For unit-testing.
        if (mRequestProcessingDisabled) return;

        // Idempotence... if a thread is already started, don't start another.
        if (mCallerIdThread != null) return;

        mCallerIdThread = new QueryThread();
        mCallerIdThread.setPriority(Thread.MIN_PRIORITY);
        mCallerIdThread.start();
    }

    /**
     * Stops the background thread that processes updates and cancels any
     * pending requests to start it.
     */
    public synchronized void stopRequestProcessing() {
        // Remove any pending requests to start the processing thread.
        mHandler.removeMessages(START_THREAD);
        if (mCallerIdThread != null) {
            // Stop the thread; we are finished with it.
            mCallerIdThread.stopProcessing();
            mCallerIdThread.interrupt();
            mCallerIdThread = null;
        }
    }

    /**
     * Stop receiving onPreDraw() notifications.
     */
    private void unregisterPreDrawListener() {
        if (mViewTreeObserver != null && mViewTreeObserver.isAlive()) {
            mViewTreeObserver.removeOnPreDrawListener(this);
        }
        mViewTreeObserver = null;
    }

    public void invalidateCache() {
        mContactInfoCache.expireAll();

        // Restart the request-processing thread after the next draw.
        stopRequestProcessing();
        unregisterPreDrawListener();
    }

    /**
     * Enqueues a request to look up the contact details for the given phone number.
     * <p>
     * It also provides the current contact info stored in the call log for this number.
     * <p>
     * If the {@code immediate} parameter is true, it will start immediately the thread that looks
     * up the contact information (if it has not been already started). Otherwise, it will be
     * started with a delay. See {@link #START_PROCESSING_REQUESTS_DELAY_MILLIS}.
     */
    protected void enqueueRequest(String number, String countryIso, ContactInfo callLogInfo,
            boolean immediate) {
        ContactInfoRequest request = new ContactInfoRequest(number, countryIso, callLogInfo);
        synchronized (mRequests) {
            if (!mRequests.contains(request)) {
                mRequests.add(request);
                mRequests.notifyAll();
            }
        }
        if (immediate) startRequestProcessing();
    }

    /**
     * Queries the appropriate content provider for the contact associated with the number.
     * <p>
     * Upon completion it also updates the cache in the call log, if it is different from
     * {@code callLogInfo}.
     * <p>
     * The number might be either a SIP address or a phone number.
     * <p>
     * It returns true if it updated the content of the cache and we should therefore tell the
     * view to update its content.
     */
    private boolean queryContactInfo(String number, String countryIso, ContactInfo callLogInfo) {
        final ContactInfo info = mContactInfoHelper.lookupNumber(number, countryIso);

        if (info == null) {
            // The lookup failed, just return without requesting to update the view.
            return false;
        }

        // Check the existing entry in the cache: only if it has changed we should update the
        // view.
        NumberWithCountryIso numberCountryIso = new NumberWithCountryIso(number, countryIso);
        ContactInfo existingInfo = mContactInfoCache.getPossiblyExpired(numberCountryIso);

        final boolean isRemoteSource = info.sourceType != 0;

        // Don't force redraw if existing info in the cache is equal to {@link ContactInfo#EMPTY}
        // to avoid updating the data set for every new row that is scrolled into view.
        // see (https://googleplex-android-review.git.corp.google.com/#/c/166680/)

        // Exception: Photo uris for contacts from remote sources are not cached in the call log
        // cache, so we have to force a redraw for these contacts regardless.
        boolean updated = (existingInfo != ContactInfo.EMPTY || isRemoteSource) &&
                !info.equals(existingInfo);

        // Store the data in the cache so that the UI thread can use to display it. Store it
        // even if it has not changed so that it is marked as not expired.
        mContactInfoCache.put(numberCountryIso, info);
        // Update the call log even if the cache it is up-to-date: it is possible that the cache
        // contains the value from a different call log entry.
        updateCallLogContactInfoCache(number, countryIso, info, callLogInfo);
        return updated;
    }

    /*
     * Handles requests for contact name and number type.
     */
    private class QueryThread extends Thread {
        private volatile boolean mDone = false;

        public QueryThread() {
            super("CallLogAdapter.QueryThread");
        }

        public void stopProcessing() {
            mDone = true;
        }

        @Override
        public void run() {
            boolean needRedraw = false;
            while (true) {
                // Check if thread is finished, and if so return immediately.
                if (mDone) return;

                // Obtain next request, if any is available.
                // Keep synchronized section small.
                ContactInfoRequest req = null;
                synchronized (mRequests) {
                    if (!mRequests.isEmpty()) {
                        req = mRequests.removeFirst();
                    }
                }

                if (req != null) {
                    // Process the request. If the lookup succeeds, schedule a
                    // redraw.
                    needRedraw |= queryContactInfo(req.number, req.countryIso, req.callLogInfo);
                } else {
                    // Throttle redraw rate by only sending them when there are
                    // more requests.
                    if (needRedraw) {
                        needRedraw = false;
                        mHandler.sendEmptyMessage(REDRAW);
                    }

                    // Wait until another request is available, or until this
                    // thread is no longer needed (as indicated by being
                    // interrupted).
                    try {
                        synchronized (mRequests) {
                            mRequests.wait(1000);
                        }
                    } catch (InterruptedException ie) {
                        // Ignore, and attempt to continue processing requests.
                    }
                }
            }
        }
    }

    @Override
    protected void addGroups(Cursor cursor) {
        mCallLogGroupBuilder.addGroups(cursor);
    }

    @Override
    protected View newStandAloneView(Context context, ViewGroup parent) {
        return newChildView(context, parent);
    }

    @Override
    protected View newGroupView(Context context, ViewGroup parent) {
        return newChildView(context, parent);
    }

    @Override
    protected View newChildView(Context context, ViewGroup parent) {
        LayoutInflater inflater = LayoutInflater.from(context);
        View view = inflater.inflate(R.layout.call_log_list_item, parent, false);

        // Get the views to bind to and cache them.
        CallLogListItemViews views = CallLogListItemViews.fromView(view);
        view.setTag(views);

        // Set text height to false on the TextViews so they don't have extra padding.
        views.phoneCallDetailsViews.nameView.setElegantTextHeight(false);
        views.phoneCallDetailsViews.callLocationAndDate.setElegantTextHeight(false);

        return view;
    }

    @Override
    protected void bindStandAloneView(View view, Context context, Cursor cursor) {
        bindView(view, cursor, 1);
    }

    @Override
    protected void bindChildView(View view, Context context, Cursor cursor) {
        bindView(view, cursor, 1);
    }

    @Override
    protected void bindGroupView(View view, Context context, Cursor cursor, int groupSize,
            boolean expanded) {
        bindView(view, cursor, groupSize);
    }

    private void findAndCacheViews(View view) {
    }

    /**
     * Binds the views in the entry to the data in the call log.
     *
     * @param callLogItemView the view corresponding to this entry
     * @param c the cursor pointing to the entry in the call log
     * @param count the number of entries in the current item, greater than 1 if it is a group
     */
    private void bindView(View callLogItemView, Cursor c, int count) {
        callLogItemView.setAccessibilityDelegate(mAccessibilityDelegate);
        final CallLogListItemViews views = (CallLogListItemViews) callLogItemView.getTag();

        // Default case: an item in the call log.
        views.primaryActionView.setVisibility(View.VISIBLE);

        final String number = c.getString(CallLogQuery.NUMBER);
        final int numberPresentation = c.getInt(CallLogQuery.NUMBER_PRESENTATION);
        final long date = c.getLong(CallLogQuery.DATE);
        final long duration = c.getLong(CallLogQuery.DURATION);
        final int callType = c.getInt(CallLogQuery.CALL_TYPE);
        final PhoneAccountHandle accountHandle = PhoneAccountUtils.getAccount(
                c.getString(CallLogQuery.ACCOUNT_COMPONENT_NAME),
                c.getString(CallLogQuery.ACCOUNT_ID));
        final String countryIso = c.getString(CallLogQuery.COUNTRY_ISO);

        final long rowId = c.getLong(CallLogQuery.ID);
        views.rowId = rowId;

        // For entries in the call log, check if the day group has changed and display a header
        // if necessary.
        if (mIsCallLog) {
            int currentGroup = getDayGroupForCall(rowId);
            int previousGroup = getPreviousDayGroup(c);
            if (currentGroup != previousGroup) {
                views.dayGroupHeader.setVisibility(View.VISIBLE);
                views.dayGroupHeader.setText(getGroupDescription(currentGroup));
            } else {
                views.dayGroupHeader.setVisibility(View.GONE);
            }
        } else {
            views.dayGroupHeader.setVisibility(View.GONE);
        }

        // Store some values used when the actions ViewStub is inflated on expansion of the actions
        // section.
        views.number = number;
        views.numberPresentation = numberPresentation;
        views.callType = callType;
        views.accountHandle = accountHandle;
        views.voicemailUri = c.getString(CallLogQuery.VOICEMAIL_URI);
        // Stash away the Ids of the calls so that we can support deleting a row in the call log.
        views.callIds = getCallIds(c, count);

        final ContactInfo cachedContactInfo = getContactInfoFromCallLog(c);

        final boolean isVoicemailNumber =
                mPhoneNumberUtilsWrapper.isVoicemailNumber(accountHandle, number);

        // Where binding and not in the call log, use default behaviour of invoking a call when
        // tapping the primary view.
        if (!mIsCallLog) {
            views.primaryActionView.setOnClickListener(this.mActionListener);

            // Set return call intent, otherwise null.
            if (PhoneNumberUtilsWrapper.canPlaceCallsTo(number, numberPresentation)) {
                // Sets the primary action to call the number.
                if (isVoicemailNumber) {
                    views.primaryActionView.setTag(
                            IntentProvider.getReturnVoicemailCallIntentProvider());
                } else {
                    views.primaryActionView.setTag(
                            IntentProvider.getReturnCallIntentProvider(number));
                }
            } else {
                // Number is not callable, so hide button.
                views.primaryActionView.setTag(null);
            }
        } else {
            // In the call log, expand/collapse an actions section for the call log entry when
            // the primary view is tapped.
            views.primaryActionView.setOnClickListener(this.mExpandCollapseListener);

            // Note: Binding of the action buttons is done as required in configureActionViews
            // when the user expands the actions ViewStub.
        }

        // Lookup contacts with this number
        NumberWithCountryIso numberCountryIso = new NumberWithCountryIso(number, countryIso);
        ExpirableCache.CachedValue<ContactInfo> cachedInfo =
                mContactInfoCache.getCachedValue(numberCountryIso);
        ContactInfo info = cachedInfo == null ? null : cachedInfo.getValue();
        if (!PhoneNumberUtilsWrapper.canPlaceCallsTo(number, numberPresentation)
                || isVoicemailNumber) {
            // If this is a number that cannot be dialed, there is no point in looking up a contact
            // for it.
            info = ContactInfo.EMPTY;
        } else if (cachedInfo == null) {
            mContactInfoCache.put(numberCountryIso, ContactInfo.EMPTY);
            // Use the cached contact info from the call log.
            info = cachedContactInfo;
            // The db request should happen on a non-UI thread.
            // Request the contact details immediately since they are currently missing.
            enqueueRequest(number, countryIso, cachedContactInfo, true);
            // We will format the phone number when we make the background request.
        } else {
            if (cachedInfo.isExpired()) {
                // The contact info is no longer up to date, we should request it. However, we
                // do not need to request them immediately.
                enqueueRequest(number, countryIso, cachedContactInfo, false);
            } else  if (!callLogInfoMatches(cachedContactInfo, info)) {
                // The call log information does not match the one we have, look it up again.
                // We could simply update the call log directly, but that needs to be done in a
                // background thread, so it is easier to simply request a new lookup, which will, as
                // a side-effect, update the call log.
                enqueueRequest(number, countryIso, cachedContactInfo, false);
            }

            if (info == ContactInfo.EMPTY) {
                // Use the cached contact info from the call log.
                info = cachedContactInfo;
            }
        }

        final Uri lookupUri = info.lookupUri;
        final String name = info.name;
        final int ntype = info.type;
        final String label = info.label;
        final long photoId = info.photoId;
        final Uri photoUri = info.photoUri;
        CharSequence formattedNumber = info.formattedNumber == null
                ? null : PhoneNumberUtils.ttsSpanAsPhoneNumber(info.formattedNumber);
        final int[] callTypes = getCallTypes(c, count);
        final String geocode = c.getString(CallLogQuery.GEOCODED_LOCATION);
        final int sourceType = info.sourceType;
        final int features = getCallFeatures(c, count);
        final String transcription = c.getString(CallLogQuery.TRANSCRIPTION);
        Long dataUsage = null;
        if (!c.isNull(CallLogQuery.DATA_USAGE)) {
            dataUsage = c.getLong(CallLogQuery.DATA_USAGE);
        }

        final PhoneCallDetails details;

        views.reported = info.isBadData;

        // The entry can only be reported as invalid if it has a valid ID and the source of the
        // entry supports marking entries as invalid.
        views.canBeReportedAsInvalid = mContactInfoHelper.canReportAsInvalid(info.sourceType,
                info.objectId);

        // Restore expansion state of the row on rebind.  Inflate the actions ViewStub if required,
        // and set its visibility state accordingly.
        expandOrCollapseActions(callLogItemView, isExpanded(rowId));

        if (TextUtils.isEmpty(name)) {
            details = new PhoneCallDetails(number, numberPresentation, formattedNumber, countryIso,
                    geocode, callTypes, date, duration, accountHandle, features, dataUsage,
                    transcription);
        } else {
            details = new PhoneCallDetails(number, numberPresentation, formattedNumber, countryIso,
                    geocode, callTypes, date, duration, name, ntype, label, lookupUri, photoUri,
                    sourceType, accountHandle, features, dataUsage, transcription);
        }

        mCallLogViewsHelper.setPhoneCallDetails(mContext, views, details);

        int contactType = ContactPhotoManager.TYPE_DEFAULT;

        if (isVoicemailNumber) {
            contactType = ContactPhotoManager.TYPE_VOICEMAIL;
        } else if (mContactInfoHelper.isBusiness(info.sourceType)) {
            contactType = ContactPhotoManager.TYPE_BUSINESS;
        }

        String lookupKey = lookupUri == null ? null
                : ContactInfoHelper.getLookupKeyFromUri(lookupUri);

        String nameForDefaultImage = null;
        if (TextUtils.isEmpty(name)) {
            nameForDefaultImage = mPhoneNumberHelper.getDisplayNumber(details.accountHandle,
                    details.number, details.numberPresentation, details.formattedNumber).toString();
        } else {
            nameForDefaultImage = name;
        }

        if (photoId == 0 && photoUri != null) {
            setPhoto(views, photoUri, lookupUri, nameForDefaultImage, lookupKey, contactType);
        } else {
            setPhoto(views, photoId, lookupUri, nameForDefaultImage, lookupKey, contactType);
        }

        // Listen for the first draw
        if (mViewTreeObserver == null) {
            mViewTreeObserver = callLogItemView.getViewTreeObserver();
            mViewTreeObserver.addOnPreDrawListener(this);
        }

        bindBadge(callLogItemView, info, details, callType);
    }

    /**
     * Retrieves the day group of the previous call in the call log.  Used to determine if the day
     * group has changed and to trigger display of the day group text.
     *
     * @param cursor The call log cursor.
     * @return The previous day group, or DAY_GROUP_NONE if this is the first call.
     */
    private int getPreviousDayGroup(Cursor cursor) {
        // We want to restore the position in the cursor at the end.
        int startingPosition = cursor.getPosition();
        int dayGroup = CallLogGroupBuilder.DAY_GROUP_NONE;
        if (cursor.moveToPrevious()) {
            long previousRowId = cursor.getLong(CallLogQuery.ID);
            dayGroup = getDayGroupForCall(previousRowId);
        }
        cursor.moveToPosition(startingPosition);
        return dayGroup;
    }

    /**
     * Given a call Id, look up the day group that the call belongs to.  The day group data is
     * populated in {@link com.android.dialer.calllog.CallLogGroupBuilder}.
     *
     * @param callId The call to retrieve the day group for.
     * @return The day group for the call.
     */
    private int getDayGroupForCall(long callId) {
        if (mDayGroups.containsKey(callId)) {
            return mDayGroups.get(callId);
        }
        return CallLogGroupBuilder.DAY_GROUP_NONE;
    }
    /**
     * Determines if a call log row with the given Id is expanded.
     * @param rowId The row Id of the call.
     * @return True if the row should be expanded.
     */
    private boolean isExpanded(long rowId) {
        return mCurrentlyExpanded == rowId;
    }

    /**
     * Toggles the expansion state tracked for the call log row identified by rowId and returns
     * the new expansion state.  Assumes that only a single call log row will be expanded at any
     * one point and tracks the current and previous expanded item.
     *
     * @param rowId The row Id associated with the call log row to expand/collapse.
     * @return True where the row is now expanded, false otherwise.
     */
    private boolean toggleExpansion(long rowId) {
        if (rowId == mCurrentlyExpanded) {
            // Collapsing currently expanded row.
            mPreviouslyExpanded = NONE_EXPANDED;
            mCurrentlyExpanded = NONE_EXPANDED;

            return false;
        } else {
            // Expanding a row (collapsing current expanded one).

            mPreviouslyExpanded = mCurrentlyExpanded;
            mCurrentlyExpanded = rowId;
            return true;
        }
    }

    /**
     * Expands or collapses the view containing the CALLBACK/REDIAL, VOICEMAIL and DETAILS action
     * buttons.
     *
     * @param callLogItem The call log entry parent view.
     * @param isExpanded The new expansion state of the view.
     */
    private void expandOrCollapseActions(View callLogItem, boolean isExpanded) {
        final CallLogListItemViews views = (CallLogListItemViews)callLogItem.getTag();

        expandVoicemailTranscriptionView(views, isExpanded);
        if (isExpanded) {
            // Inflate the view stub if necessary, and wire up the event handlers.
            inflateActionViewStub(callLogItem);

            views.actionsView.setVisibility(View.VISIBLE);
            views.actionsView.setAlpha(1.0f);
            views.callLogEntryView.setBackgroundColor(mExpandedBackgroundColor);
            views.callLogEntryView.setTranslationZ(mExpandedTranslationZ);
            callLogItem.setTranslationZ(mExpandedTranslationZ); // WAR
        } else {
            // When recycling a view, it is possible the actionsView ViewStub was previously
            // inflated so we should hide it in this case.
            if (views.actionsView != null) {
                views.actionsView.setVisibility(View.GONE);
            }

            views.callLogEntryView.setBackgroundColor(mCallLogBackgroundColor);
            views.callLogEntryView.setTranslationZ(0);
            callLogItem.setTranslationZ(0); // WAR
        }
    }

    public static void expandVoicemailTranscriptionView(CallLogListItemViews views,
            boolean isExpanded) {
        if (views.callType != Calls.VOICEMAIL_TYPE) {
            return;
        }

        final TextView view = views.phoneCallDetailsViews.voicemailTranscriptionView;
        if (TextUtils.isEmpty(view.getText())) {
            return;
        }
        view.setMaxLines(isExpanded ? VOICEMAIL_TRANSCRIPTION_MAX_LINES : 1);
        view.setSingleLine(!isExpanded);
    }

    /**
     * Configures the action buttons in the expandable actions ViewStub.  The ViewStub is not
     * inflated during initial binding, so click handlers, tags and accessibility text must be set
     * here, if necessary.
     *
     * @param callLogItem The call log list item view.
     */
    private void inflateActionViewStub(final View callLogItem) {
        final CallLogListItemViews views = (CallLogListItemViews)callLogItem.getTag();

        ViewStub stub = (ViewStub)callLogItem.findViewById(R.id.call_log_entry_actions_stub);
        if (stub != null) {
            views.actionsView = (ViewGroup) stub.inflate();
        }

        if (views.callBackButtonView == null) {
            views.callBackButtonView = (TextView)views.actionsView.findViewById(
                    R.id.call_back_action);
        }

        if (views.videoCallButtonView == null) {
            views.videoCallButtonView = (TextView)views.actionsView.findViewById(
                    R.id.video_call_action);
        }

        if (views.voicemailButtonView == null) {
            views.voicemailButtonView = (TextView)views.actionsView.findViewById(
                    R.id.voicemail_action);
        }

        if (views.detailsButtonView == null) {
            views.detailsButtonView = (TextView)views.actionsView.findViewById(R.id.details_action);
        }

        if (views.reportButtonView == null) {
            views.reportButtonView = (TextView)views.actionsView.findViewById(R.id.report_action);
            views.reportButtonView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mOnReportButtonClickListener != null) {
                        mOnReportButtonClickListener.onReportButtonClick(views.number);
                    }
                }
            });
        }

        bindActionButtons(views);
    }

    /***
     * Binds text titles, click handlers and intents to the voicemail, details and callback action
     * buttons.
     *
     * @param views  The call log item views.
     */
    private void bindActionButtons(CallLogListItemViews views) {
        boolean canPlaceCallToNumber =
                PhoneNumberUtilsWrapper.canPlaceCallsTo(views.number, views.numberPresentation);
        // Set return call intent, otherwise null.
        if (canPlaceCallToNumber) {
            boolean isVoicemailNumber =
                    mPhoneNumberUtilsWrapper.isVoicemailNumber(views.accountHandle, views.number);
            if (isVoicemailNumber) {
                // Make a general call to voicemail to ensure that if there are multiple accounts
                // it does not call the voicemail number of a specific phone account.
                views.callBackButtonView.setTag(
                        IntentProvider.getReturnVoicemailCallIntentProvider());
            } else {
                // Sets the primary action to call the number.
                views.callBackButtonView.setTag(
                        IntentProvider.getReturnCallIntentProvider(views.number));
            }
            views.callBackButtonView.setVisibility(View.VISIBLE);
            views.callBackButtonView.setOnClickListener(mActionListener);

            final int titleId;
            if (views.callType == Calls.VOICEMAIL_TYPE || views.callType == Calls.OUTGOING_TYPE) {
                titleId = R.string.call_log_action_redial;
            } else {
                titleId = R.string.call_log_action_call_back;
            }
            views.callBackButtonView.setText(mContext.getString(titleId));
        } else {
            // Number is not callable, so hide button.
            views.callBackButtonView.setTag(null);
            views.callBackButtonView.setVisibility(View.GONE);
        }

        // If one of the calls had video capabilities, show the video call button.
        if (CallUtil.isVideoEnabled(mContext) && canPlaceCallToNumber &&
                views.phoneCallDetailsViews.callTypeIcons.isVideoShown()) {
            views.videoCallButtonView.setTag(
                    IntentProvider.getReturnVideoCallIntentProvider(views.number));
            views.videoCallButtonView.setVisibility(View.VISIBLE);
            views.videoCallButtonView.setOnClickListener(mActionListener);
        } else {
            views.videoCallButtonView.setTag(null);
            views.videoCallButtonView.setVisibility(View.GONE);
        }

        // For voicemail calls, show the "VOICEMAIL" action button; hide otherwise.
        if (views.callType == Calls.VOICEMAIL_TYPE) {
            views.voicemailButtonView.setOnClickListener(mActionListener);
            views.voicemailButtonView.setTag(
                    IntentProvider.getPlayVoicemailIntentProvider(
                            views.rowId, views.voicemailUri));
            views.voicemailButtonView.setVisibility(View.VISIBLE);

            views.detailsButtonView.setVisibility(View.GONE);
        } else {
            views.voicemailButtonView.setTag(null);
            views.voicemailButtonView.setVisibility(View.GONE);

            views.detailsButtonView.setOnClickListener(mActionListener);
            views.detailsButtonView.setTag(
                    IntentProvider.getCallDetailIntentProvider(
                            views.rowId, views.callIds, null)
            );

            if (views.canBeReportedAsInvalid && !views.reported) {
                views.reportButtonView.setVisibility(View.VISIBLE);
            } else {
                views.reportButtonView.setVisibility(View.GONE);
            }
        }

        mCallLogViewsHelper.setActionContentDescriptions(views);
    }

    protected void bindBadge(
            View view, final ContactInfo info, final PhoneCallDetails details, int callType) {
        // Do not show badge in call log.
        if (!mIsCallLog) {
            final ViewStub stub = (ViewStub) view.findViewById(R.id.link_stub);
            if (UriUtils.isEncodedContactUri(info.lookupUri)) {
                if (stub != null) {
                    mBadgeContainer = stub.inflate();
                } else {
                    mBadgeContainer = view.findViewById(R.id.badge_container);
                }

                mBadgeContainer.setVisibility(View.VISIBLE);
                mBadgeImageView = (ImageView) mBadgeContainer.findViewById(R.id.badge_image);
                mBadgeText = (TextView) mBadgeContainer.findViewById(R.id.badge_text);

                final View clickableArea = mBadgeContainer.findViewById(R.id.badge_link_container);
                if (clickableArea != null) {
                    clickableArea.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            // If no lookup uri is provided, we need to rely on what information
                            // we have available; namely the phone number and name.
                            if (info.lookupUri == null) {
                                final Intent intent =
                                        DialtactsActivity.getAddToContactIntent(details.name,
                                                details.number,
                                                details.numberType);
                                DialerUtils.startActivityWithErrorToast(mContext, intent,
                                        R.string.add_contact_not_available);
                            } else {
                                addContactFromLookupUri(info.lookupUri);
                            }
                        }
                    });
                }
                mBadgeImageView.setImageResource(R.drawable.ic_person_add_24dp);
                mBadgeText.setText(R.string.recentCalls_addToContact);
            } else {
                // Hide badge if it was previously shown.
                mBadgeContainer = view.findViewById(R.id.badge_container);
                if (mBadgeContainer != null) {
                    mBadgeContainer.setVisibility(View.GONE);
                }
            }
        }
    }

    /** Checks whether the contact info from the call log matches the one from the contacts db. */
    private boolean callLogInfoMatches(ContactInfo callLogInfo, ContactInfo info) {
        // The call log only contains a subset of the fields in the contacts db.
        // Only check those.
        return TextUtils.equals(callLogInfo.name, info.name)
                && callLogInfo.type == info.type
                && TextUtils.equals(callLogInfo.label, info.label);
    }

    /** Stores the updated contact info in the call log if it is different from the current one. */
    private void updateCallLogContactInfoCache(String number, String countryIso,
            ContactInfo updatedInfo, ContactInfo callLogInfo) {
        final ContentValues values = new ContentValues();
        boolean needsUpdate = false;

        if (callLogInfo != null) {
            if (!TextUtils.equals(updatedInfo.name, callLogInfo.name)) {
                values.put(Calls.CACHED_NAME, updatedInfo.name);
                needsUpdate = true;
            }

            if (updatedInfo.type != callLogInfo.type) {
                values.put(Calls.CACHED_NUMBER_TYPE, updatedInfo.type);
                needsUpdate = true;
            }

            if (!TextUtils.equals(updatedInfo.label, callLogInfo.label)) {
                values.put(Calls.CACHED_NUMBER_LABEL, updatedInfo.label);
                needsUpdate = true;
            }
            if (!UriUtils.areEqual(updatedInfo.lookupUri, callLogInfo.lookupUri)) {
                values.put(Calls.CACHED_LOOKUP_URI, UriUtils.uriToString(updatedInfo.lookupUri));
                needsUpdate = true;
            }
            // Only replace the normalized number if the new updated normalized number isn't empty.
            if (!TextUtils.isEmpty(updatedInfo.normalizedNumber) &&
                    !TextUtils.equals(updatedInfo.normalizedNumber, callLogInfo.normalizedNumber)) {
                values.put(Calls.CACHED_NORMALIZED_NUMBER, updatedInfo.normalizedNumber);
                needsUpdate = true;
            }
            if (!TextUtils.equals(updatedInfo.number, callLogInfo.number)) {
                values.put(Calls.CACHED_MATCHED_NUMBER, updatedInfo.number);
                needsUpdate = true;
            }
            if (updatedInfo.photoId != callLogInfo.photoId) {
                values.put(Calls.CACHED_PHOTO_ID, updatedInfo.photoId);
                needsUpdate = true;
            }
            if (!TextUtils.equals(updatedInfo.formattedNumber, callLogInfo.formattedNumber)) {
                values.put(Calls.CACHED_FORMATTED_NUMBER, updatedInfo.formattedNumber);
                needsUpdate = true;
            }
        } else {
            // No previous values, store all of them.
            values.put(Calls.CACHED_NAME, updatedInfo.name);
            values.put(Calls.CACHED_NUMBER_TYPE, updatedInfo.type);
            values.put(Calls.CACHED_NUMBER_LABEL, updatedInfo.label);
            values.put(Calls.CACHED_LOOKUP_URI, UriUtils.uriToString(updatedInfo.lookupUri));
            values.put(Calls.CACHED_MATCHED_NUMBER, updatedInfo.number);
            values.put(Calls.CACHED_NORMALIZED_NUMBER, updatedInfo.normalizedNumber);
            values.put(Calls.CACHED_PHOTO_ID, updatedInfo.photoId);
            values.put(Calls.CACHED_FORMATTED_NUMBER, updatedInfo.formattedNumber);
            needsUpdate = true;
        }

        if (!needsUpdate) return;

        try {
            if (countryIso == null) {
                mContext.getContentResolver().update(Calls.CONTENT_URI_WITH_VOICEMAIL, values,
                        Calls.NUMBER + " = ? AND " + Calls.COUNTRY_ISO + " IS NULL",
                        new String[]{ number });
            } else {
                mContext.getContentResolver().update(Calls.CONTENT_URI_WITH_VOICEMAIL, values,
                        Calls.NUMBER + " = ? AND " + Calls.COUNTRY_ISO + " = ?",
                        new String[]{ number, countryIso });
            }
        } catch (SQLiteFullException e) {
            Log.e(TAG, "Unable to update contact info in call log db", e);
        }
    }

    /** Returns the contact information as stored in the call log. */
    private ContactInfo getContactInfoFromCallLog(Cursor c) {
        ContactInfo info = new ContactInfo();
        info.lookupUri = UriUtils.parseUriOrNull(c.getString(CallLogQuery.CACHED_LOOKUP_URI));
        info.name = c.getString(CallLogQuery.CACHED_NAME);
        info.type = c.getInt(CallLogQuery.CACHED_NUMBER_TYPE);
        info.label = c.getString(CallLogQuery.CACHED_NUMBER_LABEL);
        String matchedNumber = c.getString(CallLogQuery.CACHED_MATCHED_NUMBER);
        info.number = matchedNumber == null ? c.getString(CallLogQuery.NUMBER) : matchedNumber;
        info.normalizedNumber = c.getString(CallLogQuery.CACHED_NORMALIZED_NUMBER);
        info.photoId = c.getLong(CallLogQuery.CACHED_PHOTO_ID);
        info.photoUri = null;  // We do not cache the photo URI.
        info.formattedNumber = c.getString(CallLogQuery.CACHED_FORMATTED_NUMBER);
        return info;
    }

    /**
     * Returns the call types for the given number of items in the cursor.
     * <p>
     * It uses the next {@code count} rows in the cursor to extract the types.
     * <p>
     * It position in the cursor is unchanged by this function.
     */
    private int[] getCallTypes(Cursor cursor, int count) {
        int position = cursor.getPosition();
        int[] callTypes = new int[count];
        for (int index = 0; index < count; ++index) {
            callTypes[index] = cursor.getInt(CallLogQuery.CALL_TYPE);
            cursor.moveToNext();
        }
        cursor.moveToPosition(position);
        return callTypes;
    }

    /**
     * Determine the features which were enabled for any of the calls that make up a call log
     * entry.
     *
     * @param cursor The cursor.
     * @param count The number of calls for the current call log entry.
     * @return The features.
     */
    private int getCallFeatures(Cursor cursor, int count) {
        int features = 0;
        int position = cursor.getPosition();
        for (int index = 0; index < count; ++index) {
            features |= cursor.getInt(CallLogQuery.FEATURES);
            cursor.moveToNext();
        }
        cursor.moveToPosition(position);
        return features;
    }

    private void setPhoto(CallLogListItemViews views, long photoId, Uri contactUri,
            String displayName, String identifier, int contactType) {
        views.quickContactView.assignContactUri(contactUri);
        views.quickContactView.setOverlay(null);
        DefaultImageRequest request = new DefaultImageRequest(displayName, identifier,
                contactType, true /* isCircular */);
        mContactPhotoManager.loadThumbnail(views.quickContactView, photoId, false /* darkTheme */,
                true /* isCircular */, request);
    }

    private void setPhoto(CallLogListItemViews views, Uri photoUri, Uri contactUri,
            String displayName, String identifier, int contactType) {
        views.quickContactView.assignContactUri(contactUri);
        views.quickContactView.setOverlay(null);
        DefaultImageRequest request = new DefaultImageRequest(displayName, identifier,
                contactType, true /* isCircular */);
        mContactPhotoManager.loadPhoto(views.quickContactView, photoUri, mPhotoSize,
                false /* darkTheme */, true /* isCircular */, request);
    }

    /**
     * Bind a call log entry view for testing purposes.  Also inflates the action view stub so
     * unit tests can access the buttons contained within.
     *
     * @param view The current call log row.
     * @param context The current context.
     * @param cursor The cursor to bind from.
     */
    @VisibleForTesting
    void bindViewForTest(View view, Context context, Cursor cursor) {
        bindStandAloneView(view, context, cursor);
        inflateActionViewStub(view);
    }

    /**
     * Sets whether processing of requests for contact details should be enabled.
     * <p>
     * This method should be called in tests to disable such processing of requests when not
     * needed.
     */
    @VisibleForTesting
    void disableRequestProcessingForTest() {
        mRequestProcessingDisabled = true;
    }

    @VisibleForTesting
    void injectContactInfoForTest(String number, String countryIso, ContactInfo contactInfo) {
        NumberWithCountryIso numberCountryIso = new NumberWithCountryIso(number, countryIso);
        mContactInfoCache.put(numberCountryIso, contactInfo);
    }

    @Override
    public void addGroup(int cursorPosition, int size, boolean expanded) {
        super.addGroup(cursorPosition, size, expanded);
    }

    /**
     * Stores the day group associated with a call in the call log.
     *
     * @param rowId The row Id of the current call.
     * @param dayGroup The day group the call belongs in.
     */
    @Override
    public void setDayGroup(long rowId, int dayGroup) {
        if (!mDayGroups.containsKey(rowId)) {
            mDayGroups.put(rowId, dayGroup);
        }
    }

    /**
     * Clears the day group associations on re-bind of the call log.
     */
    @Override
    public void clearDayGroups() {
        mDayGroups.clear();
    }

    /*
     * Get the number from the Contacts, if available, since sometimes
     * the number provided by caller id may not be formatted properly
     * depending on the carrier (roaming) in use at the time of the
     * incoming call.
     * Logic : If the caller-id number starts with a "+", use it
     *         Else if the number in the contacts starts with a "+", use that one
     *         Else if the number in the contacts is longer, use that one
     */
    public String getBetterNumberFromContacts(String number, String countryIso) {
        String matchingNumber = null;
        // Look in the cache first. If it's not found then query the Phones db
        NumberWithCountryIso numberCountryIso = new NumberWithCountryIso(number, countryIso);
        ContactInfo ci = mContactInfoCache.getPossiblyExpired(numberCountryIso);
        if (ci != null && ci != ContactInfo.EMPTY) {
            matchingNumber = ci.number;
        } else {
            try {
                Cursor phonesCursor = mContext.getContentResolver().query(
                        Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, number),
                        PhoneQuery._PROJECTION, null, null, null);
                if (phonesCursor != null) {
                    try {
                        if (phonesCursor.moveToFirst()) {
                            matchingNumber = phonesCursor.getString(PhoneQuery.MATCHED_NUMBER);
                        }
                    } finally {
                        phonesCursor.close();
                    }
                }
            } catch (Exception e) {
                // Use the number from the call log
            }
        }
        if (!TextUtils.isEmpty(matchingNumber) &&
                (matchingNumber.startsWith("+")
                        || matchingNumber.length() > number.length())) {
            number = matchingNumber;
        }
        return number;
    }

    /**
     * Retrieves the call Ids represented by the current call log row.
     *
     * @param cursor Call log cursor to retrieve call Ids from.
     * @param groupSize Number of calls associated with the current call log row.
     * @return Array of call Ids.
     */
    private long[] getCallIds(final Cursor cursor, final int groupSize) {
        // We want to restore the position in the cursor at the end.
        int startingPosition = cursor.getPosition();
        long[] ids = new long[groupSize];
        // Copy the ids of the rows in the group.
        for (int index = 0; index < groupSize; ++index) {
            ids[index] = cursor.getLong(CallLogQuery.ID);
            cursor.moveToNext();
        }
        cursor.moveToPosition(startingPosition);
        return ids;
    }

    /**
     * Determines the description for a day group.
     *
     * @param group The day group to retrieve the description for.
     * @return The day group description.
     */
    private CharSequence getGroupDescription(int group) {
       if (group == CallLogGroupBuilder.DAY_GROUP_TODAY) {
           return mContext.getResources().getString(R.string.call_log_header_today);
       } else if (group == CallLogGroupBuilder.DAY_GROUP_YESTERDAY) {
           return mContext.getResources().getString(R.string.call_log_header_yesterday);
       } else {
           return mContext.getResources().getString(R.string.call_log_header_other);
       }
    }

    public void onBadDataReported(String number) {
        mContactInfoCache.expireAll();
        mReportedToast.show();
    }

    /**
     * Manages the state changes for the UI interaction where a call log row is expanded.
     *
     * @param view The view that was tapped
     * @param animate Whether or not to animate the expansion/collapse
     * @param forceExpand Whether or not to force the call log row into an expanded state regardless
     *        of its previous state
     */
    private void handleRowExpanded(View view, boolean animate, boolean forceExpand) {
        final CallLogListItemViews views = (CallLogListItemViews) view.getTag();

        if (forceExpand && isExpanded(views.rowId)) {
            return;
        }

        // Hide or show the actions view.
        boolean expanded = toggleExpansion(views.rowId);

        // Trigger loading of the viewstub and visual expand or collapse.
        expandOrCollapseActions(view, expanded);

        // Animate the expansion or collapse.
        if (mCallItemExpandedListener != null) {
            if (animate) {
                mCallItemExpandedListener.onItemExpanded(view);
            }

            // Animate the collapse of the previous item if it is still visible on screen.
            if (mPreviouslyExpanded != NONE_EXPANDED) {
                View previousItem = mCallItemExpandedListener.getViewForCallId(
                        mPreviouslyExpanded);

                if (previousItem != null) {
                    expandOrCollapseActions(previousItem, false);
                    if (animate) {
                        mCallItemExpandedListener.onItemExpanded(previousItem);
                    }
                }
                mPreviouslyExpanded = NONE_EXPANDED;
            }
        }
    }

    /**
     * Invokes the "add contact" activity given the expanded contact information stored in a
     * lookup URI.  This can include, for example, address and website information.
     *
     * @param lookupUri The lookup URI.
     */
    private void addContactFromLookupUri(Uri lookupUri) {
        Contact contactToSave = ContactLoader.parseEncodedContactEntity(lookupUri);
        if (contactToSave == null) {
            return;
        }

        // Note: This code mirrors code in Contacts/QuickContactsActivity.
        final Intent intent = new Intent(Intent.ACTION_INSERT_OR_EDIT);
        intent.setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE);

        ArrayList<ContentValues> values = contactToSave.getContentValues();
        // Only pre-fill the name field if the provided display name is an nickname
        // or better (e.g. structured name, nickname)
        if (contactToSave.getDisplayNameSource()
                >= ContactsContract.DisplayNameSources.NICKNAME) {
            intent.putExtra(ContactsContract.Intents.Insert.NAME,
                    contactToSave.getDisplayName());
        } else if (contactToSave.getDisplayNameSource()
                == ContactsContract.DisplayNameSources.ORGANIZATION) {
            // This is probably an organization. Instead of copying the organization
            // name into a name entry, copy it into the organization entry. This
            // way we will still consider the contact an organization.
            final ContentValues organization = new ContentValues();
            organization.put(ContactsContract.CommonDataKinds.Organization.COMPANY,
                    contactToSave.getDisplayName());
            organization.put(ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE);
            values.add(organization);
        }

        // Last time used and times used are aggregated values from the usage stat
        // table. They need to be removed from data values so the SQL table can insert
        // properly
        for (ContentValues value : values) {
            value.remove(ContactsContract.Data.LAST_TIME_USED);
            value.remove(ContactsContract.Data.TIMES_USED);
        }
        intent.putExtra(ContactsContract.Intents.Insert.DATA, values);

        DialerUtils.startActivityWithErrorToast(mContext, intent,
                R.string.add_contact_not_available);
    }
}
