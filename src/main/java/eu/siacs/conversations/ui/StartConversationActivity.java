package eu.siacs.conversations.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Dialog;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.Editable;
import android.text.Html;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.util.Pair;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.MenuRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.ContextCompat;
import androidx.databinding.DataBindingUtil;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.cheogram.android.FinishOnboarding;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;
import com.google.common.collect.Iterables;
import com.leinardi.android.speeddial.SpeedDialActionItem;
import com.leinardi.android.speeddial.SpeedDialView;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import eu.siacs.conversations.BuildConfig;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivityStartConversationBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Bookmark;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.ListItem;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.entities.Presence;
import eu.siacs.conversations.services.QuickConversationsService;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.services.XmppConnectionService.OnRosterUpdate;
import eu.siacs.conversations.ui.adapter.ListItemAdapter;
import eu.siacs.conversations.ui.interfaces.OnBackendConnected;
import eu.siacs.conversations.ui.util.JidDialog;
import eu.siacs.conversations.ui.util.MenuDoubleTabUtil;
import eu.siacs.conversations.ui.util.PendingItem;
import eu.siacs.conversations.ui.util.SoftKeyboardUtils;
import eu.siacs.conversations.ui.widget.SwipeRefreshListFragment;
import eu.siacs.conversations.utils.AccountUtils;
import eu.siacs.conversations.utils.UIHelper;
import eu.siacs.conversations.utils.XmppUri;
import eu.siacs.conversations.utils.XEP0392Helper;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.OnUpdateBlocklist;
import eu.siacs.conversations.xmpp.XmppConnection;
import eu.siacs.conversations.xmpp.forms.Data;
import eu.siacs.conversations.xmpp.stanzas.IqPacket;

public class StartConversationActivity extends XmppActivity implements XmppConnectionService.OnConversationUpdate, OnRosterUpdate, OnUpdateBlocklist, CreatePrivateGroupChatDialog.CreateConferenceDialogListener, JoinConferenceDialog.JoinConferenceDialogListener, SwipeRefreshLayout.OnRefreshListener, CreatePublicChannelDialog.CreatePublicChannelDialogListener {

    private static final String PREF_KEY_CONTACT_INTEGRATION_CONSENT = "contact_list_integration_consent";

    public static final String EXTRA_INVITE_URI = "eu.siacs.conversations.invite_uri";

    private final int REQUEST_SYNC_CONTACTS = 0x28cf;
    private final int REQUEST_CREATE_CONFERENCE = 0x39da;
    private final PendingItem<Intent> pendingViewIntent = new PendingItem<>();
    private final PendingItem<String> mInitialSearchValue = new PendingItem<>();
    private final AtomicBoolean oneShotKeyboardSuppress = new AtomicBoolean();
    public ListItem contextItem;
    private ListPagerAdapter mListPagerAdapter;
    private final List<ListItem> contacts = new ArrayList<>();
    private ListItemAdapter mContactsAdapter;
    private TagsAdapter mTagsAdapter = new TagsAdapter();
    private final List<ListItem> conferences = new ArrayList<>();
    private ListItemAdapter mConferenceAdapter;
    private final ArrayList<String> mActivatedAccounts = new ArrayList<>();
    private EditText mSearchEditText;
    private final AtomicBoolean mRequestedContactsPermission = new AtomicBoolean(false);
    private final AtomicBoolean mOpenedFab = new AtomicBoolean(false);
    private boolean mHideOfflineContacts = false;
    private boolean createdByViewIntent = false;
    private final MenuItem.OnActionExpandListener mOnActionExpandListener = new MenuItem.OnActionExpandListener() {

        @Override
        public boolean onMenuItemActionExpand(MenuItem item) {
            mSearchEditText.post(() -> {
                updateSearchViewHint();
                mSearchEditText.requestFocus();
                if (oneShotKeyboardSuppress.compareAndSet(true, false)) {
                    return;
                }
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(mSearchEditText, InputMethodManager.SHOW_IMPLICIT);
                }
            });
            if (binding.speedDial.isOpen()) {
                binding.speedDial.close();
            }
            return true;
        }

        @Override
        public boolean onMenuItemActionCollapse(MenuItem item) {
            SoftKeyboardUtils.hideSoftKeyboard(StartConversationActivity.this);
            mSearchEditText.setText("");
            filter(null);
            navigateBack();
            return true;
        }
    };
    private final TextWatcher mSearchTextWatcher = new TextWatcher() {

        @Override
        public void afterTextChanged(Editable editable) {
            filter(editable.toString());
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }
    };
    private MenuItem mMenuSearchView;
    private final ListItemAdapter.OnTagClickedListener mOnTagClickedListener = new ListItemAdapter.OnTagClickedListener() {
        @Override
        public void onTagClicked(String tag) {
            if (mMenuSearchView != null) {
                mMenuSearchView.expandActionView();
                mSearchEditText.setText("");
                mSearchEditText.append(tag);
                filter(tag);
            }
        }
    };
    private Pair<Integer, Intent> mPostponedActivityResult;
    private Toast mToast;
    private final UiCallback<Conversation> mAdhocConferenceCallback = new UiCallback<Conversation>() {
        @Override
        public void success(final Conversation conversation) {
            runOnUiThread(() -> {
                hideToast();
                switchToConversation(conversation);
            });
        }

        @Override
        public void error(final int errorCode, Conversation object) {
            runOnUiThread(() -> replaceToast(getString(errorCode)));
        }

        @Override
        public void userInputRequired(PendingIntent pi, Conversation object) {

        }
    };
    private ActivityStartConversationBinding binding;
    private final TextView.OnEditorActionListener mSearchDone = new TextView.OnEditorActionListener() {
        @Override
        public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
            int pos = binding.startConversationViewPager.getCurrentItem();
            if (pos == 0) {
                if (contacts.size() == 1) {
                    openConversation(contacts.get(0));
                    return true;
                } else if (contacts.size() == 0 && conferences.size() == 1) {
                    openConversationsForBookmark((Bookmark) conferences.get(0));
                    return true;
                }
            } else {
                if (conferences.size() == 1) {
                    openConversationsForBookmark((Bookmark) conferences.get(0));
                    return true;
                } else if (conferences.size() == 0 && contacts.size() == 1) {
                    openConversation(contacts.get(0));
                    return true;
                }
            }
            SoftKeyboardUtils.hideSoftKeyboard(StartConversationActivity.this);
            mListPagerAdapter.requestFocus(pos);
            return true;
        }
    };

    public static void populateAccountSpinner(final Context context, final List<String> accounts, final AutoCompleteTextView spinner) {
        if (accounts.isEmpty()) {
            ArrayAdapter<String> adapter = new ArrayAdapter<>(context,
                    R.layout.item_autocomplete,
                    Collections.singletonList(context.getString(R.string.no_accounts)));
            adapter.setDropDownViewResource(R.layout.item_autocomplete);
            spinner.setAdapter(adapter);
            spinner.setEnabled(false);
        } else {
            final ArrayAdapter<String> adapter = new ArrayAdapter<>(context, R.layout.item_autocomplete, accounts);
            adapter.setDropDownViewResource(R.layout.item_autocomplete);
            spinner.setAdapter(adapter);
            spinner.setEnabled(true);
            spinner.setText(Iterables.getFirst(accounts,null),false);
        }
    }

    public static void launch(Context context) {
        final Intent intent = new Intent(context, StartConversationActivity.class);
        context.startActivity(intent);
    }

    private static Intent createLauncherIntent(Context context) {
        final Intent intent = new Intent(context, StartConversationActivity.class);
        intent.setAction(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        return intent;
    }

    private static boolean isViewIntent(final Intent i) {
        return i != null && (Intent.ACTION_VIEW.equals(i.getAction()) || Intent.ACTION_SENDTO.equals(i.getAction()) || i.hasExtra(EXTRA_INVITE_URI));
    }

    protected void hideToast() {
        if (mToast != null) {
            mToast.cancel();
        }
    }

    protected void replaceToast(String msg) {
        hideToast();
        mToast = Toast.makeText(this, msg, Toast.LENGTH_LONG);
        mToast.show();
    }

    @Override
    public void onRosterUpdate(final XmppConnectionService.UpdateRosterReason reason, final Contact contact) {
        this.refreshUi();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_start_conversation);
        Activities.setStatusAndNavigationBarColors(this, binding.getRoot());
        setSupportActionBar(binding.toolbar);
        configureActionBar(getSupportActionBar());

        inflateFab(binding.speedDial, R.menu.start_conversation_fab_submenu);
        binding.tabLayout.setupWithViewPager(binding.startConversationViewPager);
        binding.startConversationViewPager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                updateSearchViewHint();
            }
        });
        mListPagerAdapter = new ListPagerAdapter(getSupportFragmentManager());
        binding.startConversationViewPager.setAdapter(mListPagerAdapter);

        mConferenceAdapter = new ListItemAdapter(this, conferences);
        mContactsAdapter = new ListItemAdapter(this, contacts);
        mContactsAdapter.setOnTagClickedListener(this.mOnTagClickedListener);

        final SharedPreferences preferences = getPreferences();

        this.mHideOfflineContacts = QuickConversationsService.isConversations() && preferences.getBoolean("hide_offline", false);

        final boolean startSearching = preferences.getBoolean("start_searching", getResources().getBoolean(R.bool.start_searching));

        final Intent intent;
        if (savedInstanceState == null) {
            intent = getIntent();
        } else {
            createdByViewIntent = savedInstanceState.getBoolean("created_by_view_intent", false);
            final String search = savedInstanceState.getString("search");
            if (search != null) {
                mInitialSearchValue.push(search);
            }
            intent = savedInstanceState.getParcelable("intent");
        }

        if (intent.getBooleanExtra("init", false)) {
            pendingViewIntent.push(intent);
        }

        if (isViewIntent(intent)) {
            pendingViewIntent.push(intent);
            createdByViewIntent = true;
            setIntent(createLauncherIntent(this));
        } else if (startSearching && mInitialSearchValue.peek() == null) {
            mInitialSearchValue.push("");
        }
        mRequestedContactsPermission.set(savedInstanceState != null && savedInstanceState.getBoolean("requested_contacts_permission", false));
        mOpenedFab.set(savedInstanceState != null && savedInstanceState.getBoolean("opened_fab", false));
        binding.speedDial.setOnActionSelectedListener(actionItem -> {
            final String searchString = mSearchEditText != null ? mSearchEditText.getText().toString() : null;
            final String prefilled;
            if (isValidJid(searchString)) {
                prefilled = Jid.ofEscaped(searchString).toEscapedString();
            } else {
                prefilled = null;
            }
            switch (actionItem.getId()) {
                case R.id.discover_public_channels:
                    if (QuickConversationsService.isPlayStoreFlavor()) {
                        throw new IllegalStateException("Channel discovery is not available on Google Play flavor");
                    } else {
                        startActivity(new Intent(this, ChannelDiscoveryActivity.class));
                    }
                    break;
                case R.id.create_private_group_chat:
                    showCreatePrivateGroupChatDialog();
                    break;
                case R.id.create_public_channel:
                    showPublicChannelDialog();
                    break;
                case R.id.create_contact:
                    showCreateContactDialog(prefilled, null);
                    break;
            }
            return false;
        });
    }

    private void inflateFab(final SpeedDialView speedDialView, final @MenuRes int menuRes) {
        speedDialView.clearActionItems();
        final PopupMenu popupMenu = new PopupMenu(this, new View(this));
        popupMenu.inflate(menuRes);
        final Menu menu = popupMenu.getMenu();
        for (int i = 0; i < menu.size(); i++) {
            final MenuItem menuItem = menu.getItem(i);
            if (QuickConversationsService.isPlayStoreFlavor() && menuItem.getItemId() == R.id.discover_public_channels) {
                continue;
            }
            final SpeedDialActionItem actionItem = new SpeedDialActionItem.Builder(menuItem.getItemId(), menuItem.getIcon())
                    .setLabel(menuItem.getTitle() != null ? menuItem.getTitle().toString() : null)
                    .setFabImageTintColor(MaterialColors.getColor(speedDialView, com.google.android.material.R.attr.colorOnSurface))
                    .setFabBackgroundColor(MaterialColors.getColor(speedDialView, com.google.android.material.R.attr.colorSurfaceContainerHighest))
                    .create();
            speedDialView.addActionItem(actionItem);
        }
    }

    public static boolean isValidJid(String input) {
        try {
            Jid jid = Jid.ofEscaped(input);
            return !jid.isDomainJid();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        Intent pendingIntent = pendingViewIntent.peek();
        savedInstanceState.putParcelable("intent", pendingIntent != null ? pendingIntent : getIntent());
        savedInstanceState.putBoolean("requested_contacts_permission", mRequestedContactsPermission.get());
        savedInstanceState.putBoolean("opened_fab", mOpenedFab.get());
        savedInstanceState.putBoolean("created_by_view_intent", createdByViewIntent);
        if (mMenuSearchView != null && mMenuSearchView.isActionViewExpanded()) {
            savedInstanceState.putString("search", mSearchEditText != null ? mSearchEditText.getText().toString() : null);
        }
        super.onSaveInstanceState(savedInstanceState);
    }

    @Override
    public void onStart() {
        super.onStart();
        if (pendingViewIntent.peek() == null) {
            askForContactsPermissions();
        }
        mConferenceAdapter.refreshSettings();
        mContactsAdapter.refreshSettings();
    }

    @Override
    public void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        if (xmppConnectionServiceBound) {
            processViewIntent(intent);
        } else {
            pendingViewIntent.push(intent);
        }
        setIntent(createLauncherIntent(this));
    }

    protected void openConversationForContact(int position) {
        openConversation(contacts.get(position));
    }

    protected void openConversation(ListItem item) {
        if (item instanceof Contact) {
            openConversationForContact((Contact) item);
        } else {
            openConversationsForBookmark((Bookmark) item);
        }
    }

    protected void openConversationForContact(Contact contact) {
        Conversation conversation = xmppConnectionService.findOrCreateConversation(contact.getAccount(), contact.getJid(), false, true);
        SoftKeyboardUtils.hideSoftKeyboard(this);
        switchToConversation(conversation);
    }

    protected void openConversationForBookmark(int position) {
        Bookmark bookmark = (Bookmark) conferences.get(position);
        openConversationsForBookmark(bookmark);
    }

    protected void shareBookmarkUri() {
        shareAsChannel(this, contextItem.getJid().asBareJid().toEscapedString());
    }

    protected void shareBookmarkUri(int position) {
        Bookmark bookmark = (Bookmark) conferences.get(position);
        shareAsChannel(this, bookmark.getJid().asBareJid().toEscapedString());
    }

    public static void shareAsChannel(final Context context, final String address) {
        Intent shareIntent = new Intent();
        shareIntent.setAction(Intent.ACTION_SEND);
        shareIntent.putExtra(Intent.EXTRA_TEXT, "xmpp:" + Uri.encode(address, "@/+") + "?join");
        shareIntent.setType("text/plain");
        try {
            context.startActivity(Intent.createChooser(shareIntent, context.getText(R.string.share_uri_with)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(context, R.string.no_application_to_share_uri, Toast.LENGTH_SHORT).show();
        }
    }

    protected void openConversationsForBookmark(final Bookmark bookmark) {
        final Jid jid = bookmark.getFullJid();
        if (jid == null) {
            Toast.makeText(this, R.string.invalid_jid, Toast.LENGTH_SHORT).show();
            return;
        }
        final Conversation conversation = xmppConnectionService.findOrCreateConversation(bookmark.getAccount(), jid, true, true, true);
        bookmark.setConversation(conversation);
        if (!bookmark.autojoin()) {
            bookmark.setAutojoin(true);
            xmppConnectionService.createBookmark(bookmark.getAccount(), bookmark);
        }
        SoftKeyboardUtils.hideSoftKeyboard(this);
        switchToConversation(conversation);
    }

    protected void openDetailsForContact() {
        switchToContactDetails((Contact) contextItem);
    }

    protected void showQrForContact() {
        showQrCode("xmpp:" + contextItem.getJid().asBareJid().toEscapedString());
    }

    protected void toggleContactBlock() {
        BlockContactDialog.show(this, (Contact) contextItem);
    }

    protected void deleteContact() {
        final Contact contact = (Contact) contextItem;
        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setNegativeButton(R.string.cancel, null);
        builder.setTitle(R.string.action_delete_contact);
        builder.setMessage(JidDialog.style(this, R.string.remove_contact_text, contact.getJid().toEscapedString()));
        builder.setPositiveButton(R.string.delete, (dialog, which) -> {
            xmppConnectionService.deleteContactOnServer(contact);
            filter(mSearchEditText.getText().toString());
        });
        builder.create().show();
    }

    protected void deleteConference() {
        final Bookmark bookmark = (Bookmark) contextItem;
        final var conversation = bookmark.getConversation();
        final boolean hasConversation = conversation != null;
        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setNegativeButton(R.string.cancel, null);
        builder.setTitle(R.string.delete_bookmark);
        if (hasConversation) {
            builder.setMessage(JidDialog.style(this, R.string.remove_bookmark_and_close, bookmark.getJid().toEscapedString()));
        } else {
            builder.setMessage(JidDialog.style(this, R.string.remove_bookmark, bookmark.getJid().toEscapedString()));
        }
        builder.setPositiveButton(hasConversation ? R.string.delete_and_close : R.string.delete, (dialog, which) -> {
            bookmark.setConversation(null);
            final Account account = bookmark.getAccount();
            xmppConnectionService.deleteBookmark(account, bookmark);
            if (conversation != null) {
                xmppConnectionService.archiveConversation(conversation);
            }
            filter(mSearchEditText.getText().toString());
        });
        builder.create().show();

    }

    @SuppressLint("InflateParams")
    protected void showCreateContactDialog(final String prefilledJid, final Invite invite) {
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
        Fragment prev = getSupportFragmentManager().findFragmentByTag(FRAGMENT_TAG_DIALOG);
        if (prev != null) {
            ft.remove(prev);
        }
        ft.addToBackStack(null);
        EnterJidDialog dialog = EnterJidDialog.newInstance(
                mActivatedAccounts,
                "Start Conversation",
                getString(R.string.message),
                "Call",
                prefilledJid,
                invite == null ? null : invite.account,
                invite == null || !invite.hasFingerprints(),
                true,
                EnterJidDialog.SanityCheck.ALLOW_MUC
        );

        dialog.setOnEnterJidDialogPositiveListener((accountJid, contactJid, call, save) -> {
            if (!xmppConnectionServiceBound) {
                return false;
            }

            final Account account = xmppConnectionService.findAccountByJid(accountJid);
            if (account == null) {
                return true;
            }
            final Contact contact = account.getRoster().getContact(contactJid);

            if (invite != null && invite.getName() != null) {
                contact.setServerName(invite.getName());
            }

            if (contact.isSelf() || contact.showInRoster()) {
                switchToConversationDoNotAppend(contact, invite == null ? null : invite.getBody(), call ? "call" : null);
                return true;
            }

            xmppConnectionService.checkIfMuc(account, contactJid, (isMuc) -> {
                runOnUiThread(() -> {
                    if (isMuc) {
                        if (save) {
                            Bookmark bookmark = account.getBookmark(contactJid);
                            if (bookmark != null) {
                                openConversationsForBookmark(bookmark);
                            } else {
                                bookmark = new Bookmark(account, contactJid.asBareJid());
                                bookmark.setAutojoin(getBooleanPreference("autojoin", R.bool.autojoin));
                                final String nick = contactJid.getResource();
                                if (nick != null && !nick.isEmpty() && !nick.equals(MucOptions.defaultNick(account))) {
                                    bookmark.setNick(nick);
                                }
                                xmppConnectionService.createBookmark(account, bookmark);
                                final Conversation conversation = xmppConnectionService
                                        .findOrCreateConversation(account, contactJid, true, true, true);
                                bookmark.setConversation(conversation);
                                switchToConversationDoNotAppend(conversation, invite == null ? null : invite.getBody());
                            }
                        } else {
                            final Conversation conversation = xmppConnectionService.findOrCreateConversation(account, contactJid, true, true, true);
                            switchToConversationDoNotAppend(conversation, invite == null ? null : invite.getBody());
                        }
                    } else {
                        if (save) {
                            final String preAuth = invite == null ? null : invite.getParameter(XmppUri.PARAMETER_PRE_AUTH);
                            xmppConnectionService.createContact(contact, true, preAuth);
                            if (invite != null && invite.hasFingerprints()) {
                                xmppConnectionService.verifyFingerprints(contact, invite.getFingerprints());
                            }
                        }
                        switchToConversationDoNotAppend(contact, invite == null ? null : invite.getBody(), call ? "call" : null);
                    }

                    try {
                        dialog.dismiss();
                    } catch (final IllegalStateException e) { }
                });
            });

            return false;
        });
        dialog.show(ft, FRAGMENT_TAG_DIALOG);
    }

    @SuppressLint("InflateParams")
    protected void showJoinConferenceDialog(final String prefilledJid, final Invite invite) {
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
        Fragment prev = getSupportFragmentManager().findFragmentByTag(FRAGMENT_TAG_DIALOG);
        if (prev != null) {
            ft.remove(prev);
        }
        ft.addToBackStack(null);
        JoinConferenceDialog joinConferenceFragment = JoinConferenceDialog.newInstance(prefilledJid, invite.getParameter("password"), mActivatedAccounts);
        joinConferenceFragment.show(ft, FRAGMENT_TAG_DIALOG);
    }

    private void showCreatePrivateGroupChatDialog() {
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
        Fragment prev = getSupportFragmentManager().findFragmentByTag(FRAGMENT_TAG_DIALOG);
        if (prev != null) {
            ft.remove(prev);
        }
        ft.addToBackStack(null);
        CreatePrivateGroupChatDialog createConferenceFragment = CreatePrivateGroupChatDialog.newInstance(mActivatedAccounts);
        createConferenceFragment.show(ft, FRAGMENT_TAG_DIALOG);
    }

    private void showPublicChannelDialog() {
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
        Fragment prev = getSupportFragmentManager().findFragmentByTag(FRAGMENT_TAG_DIALOG);
        if (prev != null) {
            ft.remove(prev);
        }
        ft.addToBackStack(null);
        CreatePublicChannelDialog dialog = CreatePublicChannelDialog.newInstance(mActivatedAccounts);
        dialog.show(ft, FRAGMENT_TAG_DIALOG);
    }

    public static Account getSelectedAccount(final Context context, final AutoCompleteTextView spinner) {
        if (spinner == null || !spinner.isEnabled()) {
            return null;
        }
        if (context instanceof XmppActivity) {
            final Jid jid;
            try {
                jid = Jid.ofEscaped(spinner.getText().toString());
            } catch (final IllegalArgumentException e) {
                return null;
            }
            final XmppConnectionService service = ((XmppActivity) context).xmppConnectionService;
            if (service == null) {
                return null;
            }
            return service.findAccountByJid(jid);
        } else {
            return null;
        }
    }

    protected void switchToConversation(Contact contact) {
        Conversation conversation = xmppConnectionService.findOrCreateConversation(contact.getAccount(), contact.getJid(), false, true);
        switchToConversation(conversation);
    }

    protected void switchToConversationDoNotAppend(Contact contact, String body) {
        switchToConversationDoNotAppend(contact, body, null);
    }

    protected void switchToConversationDoNotAppend(Contact contact, String body, String postInit) {
        Conversation conversation = xmppConnectionService.findOrCreateConversation(contact.getAccount(), contact.getJid(), false, true);
        switchToConversation(conversation, body, false, null, false, true, postInit);
    }

    @Override
    public void invalidateOptionsMenu() {
        boolean isExpanded = mMenuSearchView != null && mMenuSearchView.isActionViewExpanded();
        String text = mSearchEditText != null ? mSearchEditText.getText().toString() : "";
        if (isExpanded) {
            mInitialSearchValue.push(text);
            oneShotKeyboardSuppress.set(true);
        }
        super.invalidateOptionsMenu();
    }

    private void updateSearchViewHint() {
        if (binding == null || mSearchEditText == null) {
            return;
        }
        if (binding.startConversationViewPager.getCurrentItem() == 0) {
            mSearchEditText.setHint("Search chats");
            mSearchEditText.setContentDescription("Search chats");
        } else {
            mSearchEditText.setHint(R.string.search_group_chats);
            mSearchEditText.setContentDescription(getString(R.string.search_group_chats));
        }
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        getMenuInflater().inflate(R.menu.start_conversation, menu);
        AccountUtils.showHideMenuItems(menu);
        final MenuItem menuHideOffline = menu.findItem(R.id.action_hide_offline);
        final MenuItem qrCodeScanMenuItem = menu.findItem(R.id.action_scan_qr_code);
        final MenuItem privacyPolicyMenuItem = menu.findItem(R.id.action_privacy_policy);
        privacyPolicyMenuItem.setVisible(
                BuildConfig.PRIVACY_POLICY != null
                        && QuickConversationsService.isPlayStoreFlavor());
        qrCodeScanMenuItem.setVisible(isCameraFeatureAvailable());
        if (QuickConversationsService.isQuicksy()) {
            menuHideOffline.setVisible(false);
        } else {
            menuHideOffline.setVisible(true);
            menuHideOffline.setChecked(this.mHideOfflineContacts);
        }
        mMenuSearchView = menu.findItem(R.id.action_search);
        mMenuSearchView.setOnActionExpandListener(mOnActionExpandListener);
        View mSearchView = mMenuSearchView.getActionView();
        mSearchEditText = mSearchView.findViewById(R.id.search_field);
        mSearchEditText.addTextChangedListener(mSearchTextWatcher);
        mSearchEditText.setOnEditorActionListener(mSearchDone);

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        boolean showDynamicTags = preferences.getBoolean("show_dynamic_tags", getResources().getBoolean(R.bool.show_dynamic_tags));
        if (showDynamicTags) {
            RecyclerView tags = mSearchView.findViewById(R.id.tags);
            androidx.recyclerview.widget.DividerItemDecoration spacer = new androidx.recyclerview.widget.DividerItemDecoration(tags.getContext(), LinearLayoutManager.HORIZONTAL);
            spacer.setDrawable(getResources().getDrawable(R.drawable.horizontal_space));
            tags.addItemDecoration(spacer);
            tags.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
            tags.setAdapter(mTagsAdapter);
        }

        String initialSearchValue = mInitialSearchValue.pop();
        if (initialSearchValue != null) {
            mMenuSearchView.expandActionView();
            try {
                mSearchEditText.append(initialSearchValue);
            } catch (final StringIndexOutOfBoundsException e) {
                mSearchEditText.setText(initialSearchValue);
            }
            filter(initialSearchValue);
        }
        updateSearchViewHint();
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (MenuDoubleTabUtil.shouldIgnoreTap()) {
            return false;
        }
        switch (item.getItemId()) {
            case android.R.id.home:
                navigateBack();
                return true;
            case R.id.action_scan_qr_code:
                UriHandlerActivity.scan(this);
                return true;
            case R.id.action_hide_offline:
                mHideOfflineContacts = !item.isChecked();
                getPreferences().edit().putBoolean("hide_offline", mHideOfflineContacts).apply();
                if (mSearchEditText != null) {
                    filter(mSearchEditText.getText().toString());
                }
                invalidateOptionsMenu();
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_SEARCH && !event.isLongPress()) {
            openSearch();
            return true;
        }
        int c = event.getUnicodeChar();
        if (c > 32) {
            if (mSearchEditText != null && !mSearchEditText.isFocused()) {
                openSearch();
                mSearchEditText.append(Character.toString((char) c));
                return true;
            }
        }
        return super.onKeyUp(keyCode, event);
    }

    private void openSearch() {
        if (mMenuSearchView != null) {
            mMenuSearchView.expandActionView();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        if (resultCode == RESULT_OK) {
            if (xmppConnectionServiceBound) {
                this.mPostponedActivityResult = null;
                if (requestCode == REQUEST_CREATE_CONFERENCE) {
                    Account account = extractAccount(intent);
                    final String name = intent.getStringExtra(ChooseContactActivity.EXTRA_GROUP_CHAT_NAME);
                    final List<Jid> jids = ChooseContactActivity.extractJabberIds(intent);
                    if (account != null && jids.size() > 0) {
                        // This hardcodes cheogram.com and is in general a terrible hack
                        // Ideally this would be based around XEP-0033 but until we think of a good fallback behaviour we keep using this gross commas thing
                        if (jids.stream().allMatch(jid -> jid.getDomain().toString().equals("cheogram.com"))) {
                            new AlertDialog.Builder(this)
                                .setMessage("You appear to be creating a group with only SMS contacts. Would you like to create a channel or an MMS group text?")
                                .setNeutralButton("Channel", (d, w) -> {
                                    if (xmppConnectionService.createAdhocConference(account, name, jids, mAdhocConferenceCallback)) {
                                        mToast = Toast.makeText(this, R.string.creating_conference, Toast.LENGTH_LONG);
                                        mToast.show();
                                    }
                                }).setPositiveButton("Group Text", (d, w) -> {
                                    Jid groupJid = Jid.ofLocalAndDomain(jids.stream().map(jid -> jid.getLocal()).sorted().collect(Collectors.joining(",")), "cheogram.com");
                                    Contact group = account.getRoster().getContact(groupJid);
                                    if (name != null && !name.equals("")) group.setServerName(name);
                                    xmppConnectionService.createContact(group, true);
                                    switchToConversation(group);
                                }).create().show();
                        } else {
                            if (xmppConnectionService.createAdhocConference(account, name, jids, mAdhocConferenceCallback)) {
                                mToast = Toast.makeText(this, R.string.creating_conference, Toast.LENGTH_LONG);
                                mToast.show();
                            }
                        }
                    }
                }
            } else {
                this.mPostponedActivityResult = new Pair<>(requestCode, intent);
            }
        }
        super.onActivityResult(requestCode, requestCode, intent);
    }

    private void askForContactsPermissions() {
        if (QuickConversationsService.isContactListIntegration(this)) {
            if (checkSelfPermission(Manifest.permission.READ_CONTACTS)
                    != PackageManager.PERMISSION_GRANTED) {
                if (mRequestedContactsPermission.compareAndSet(false, true)) {
                    final String consent =
                            PreferenceManager.getDefaultSharedPreferences(getApplicationContext())
                                    .getString(PREF_KEY_CONTACT_INTEGRATION_CONSENT, null);
                    final boolean requiresConsent =
                            (QuickConversationsService.isQuicksy()
                                            || QuickConversationsService.isPlayStoreFlavor())
                                    && !"agreed".equals(consent);
                    if (requiresConsent && "declined".equals(consent)) {
                        Log.d(Config.LOGTAG,"not asking for contacts permission because consent has been declined");
                        return;
                    }
                    if (requiresConsent
                            || shouldShowRequestPermissionRationale(
                                    Manifest.permission.READ_CONTACTS)) {
                        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
                        final AtomicBoolean requestPermission = new AtomicBoolean(false);
                        if (QuickConversationsService.isQuicksy()) {
                            builder.setTitle(R.string.quicksy_wants_your_consent);
                            builder.setMessage(
                                    Html.fromHtml(
                                            getString(R.string.sync_with_contacts_quicksy_static)));
                        } else {
                            builder.setTitle(R.string.sync_with_contacts);
                            builder.setMessage(
                                    getString(
                                            R.string.sync_with_contacts_long,
                                            getString(R.string.app_name)));
                        }
                        @StringRes int confirmButtonText;
                        if (requiresConsent) {
                            confirmButtonText = R.string.agree_and_continue;
                        } else {
                            confirmButtonText = R.string.next;
                        }
                        builder.setPositiveButton(
                                confirmButtonText,
                                (dialog, which) -> {
                                    if (requiresConsent) {
                                        PreferenceManager.getDefaultSharedPreferences(
                                                        getApplicationContext())
                                                .edit()
                                                .putString(
                                                        PREF_KEY_CONTACT_INTEGRATION_CONSENT, "agreed")
                                                .apply();
                                    }
                                    if (requestPermission.compareAndSet(false, true)) {
                                        requestPermissions(
                                                new String[] {Manifest.permission.READ_CONTACTS},
                                                REQUEST_SYNC_CONTACTS);
                                    }
                                });
                        if (requiresConsent) {
                            builder.setNegativeButton(R.string.decline, (dialog, which) -> PreferenceManager.getDefaultSharedPreferences(
                                            getApplicationContext())
                                    .edit()
                                    .putString(
                                            PREF_KEY_CONTACT_INTEGRATION_CONSENT, "declined")
                                    .apply());
                        } else {
                            builder.setOnDismissListener(
                                    dialog -> {
                                        if (requestPermission.compareAndSet(false, true)) {
                                            requestPermissions(
                                                    new String[] {
                                                        Manifest.permission.READ_CONTACTS
                                                    },
                                                    REQUEST_SYNC_CONTACTS);
                                        }
                                    });
                        }
                        builder.setCancelable(requiresConsent);
                        final AlertDialog dialog = builder.create();
                        dialog.setCanceledOnTouchOutside(requiresConsent);
                        dialog.setOnShowListener(
                                dialogInterface -> {
                                    final TextView tv = dialog.findViewById(android.R.id.message);
                                    if (tv != null) {
                                        tv.setMovementMethod(LinkMovementMethod.getInstance());
                                    }
                                });
                        dialog.show();
                    } else {
                        requestPermissions(
                                new String[] {Manifest.permission.READ_CONTACTS},
                                REQUEST_SYNC_CONTACTS);
                    }
                }
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length > 0)
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                ScanActivity.onRequestPermissionResult(this, requestCode, grantResults);
                if (requestCode == REQUEST_SYNC_CONTACTS && xmppConnectionServiceBound) {
                    if (QuickConversationsService.isQuicksy()) {
                        setRefreshing(true);
                    }
                    xmppConnectionService.loadPhoneContacts();
                    xmppConnectionService.startContactObserver();
                }
            }
    }

    private void configureHomeButton() {
        final ActionBar actionBar = getSupportActionBar();
        if (actionBar == null) {
            return;
        }
        boolean openConversations = !createdByViewIntent && !xmppConnectionService.isConversationsListEmpty(null);
        actionBar.setDisplayHomeAsUpEnabled(openConversations);
        actionBar.setDisplayHomeAsUpEnabled(openConversations);

    }

    @Override
    protected void onBackendConnected() {
        if (QuickConversationsService.isContactListIntegration(this)
                && (Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                        || checkSelfPermission(Manifest.permission.READ_CONTACTS)
                                == PackageManager.PERMISSION_GRANTED)) {
            xmppConnectionService.getQuickConversationsService().considerSyncBackground(false);
        }
        if (mPostponedActivityResult != null) {
            onActivityResult(mPostponedActivityResult.first, RESULT_OK, mPostponedActivityResult.second);
            this.mPostponedActivityResult = null;
        }
        this.mActivatedAccounts.clear();
        this.mActivatedAccounts.addAll(AccountUtils.getEnabledAccounts(xmppConnectionService));
        configureHomeButton();
        Intent intent = pendingViewIntent.pop();

        final boolean onboardingCancel = xmppConnectionService.getPreferences().getString("onboarding_action", "").equals("cancel");
        if (onboardingCancel) xmppConnectionService.getPreferences().edit().remove("onboarding_action").commit();

        if ((xmppConnectionService.isOnboarding() || (intent != null && intent.getBooleanExtra("init", false))) && !onboardingCancel && !xmppConnectionService.getAccounts().isEmpty()) {
            Account selectedAccount = xmppConnectionService.getAccounts().get(0);
            final String accountJid = intent == null ? null : intent.getStringExtra(EXTRA_ACCOUNT);
            intent = null;
            boolean hasPstnOrSms = false;
            Account onboardingAccount = null;
            outer:
            for (Account account : xmppConnectionService.getAccounts()) {
                if (onboardingAccount == null && account.getJid().getDomain().equals(Config.ONBOARDING_DOMAIN)) onboardingAccount = account;

                if (accountJid != null) {
                    if(account.getJid().asBareJid().toEscapedString().equals(accountJid)) {
                        selectedAccount = account;
                    } else {
                        continue;
                    }
                }

                for (Contact contact : account.getRoster().getContacts()) {
                    if (contact.getPresences().anyIdentity("gateway", "pstn")) {
                        hasPstnOrSms = true;
                        break outer;
                    }
                    if (contact.getPresences().anyIdentity("gateway", "sms")) {
                        hasPstnOrSms = true;
                        break outer;
                    }
                }
            }

            if (!hasPstnOrSms) {
                if (onboardingAccount != null && !selectedAccount.getJid().equals(onboardingAccount.getJid())) {
                    FinishOnboarding.finish(xmppConnectionService, this, onboardingAccount, selectedAccount);
                } else {
                    startCommand(selectedAccount, Jid.of("cheogram.com/CHEOGRAM%jabber:iq:register"), "jabber:iq:register");
                    finish();
                    return;
                }
            }
        }

        if (intent != null && processViewIntent(intent)) {
            filter(null);
        } else {
            if (mSearchEditText != null) {
                filter(mSearchEditText.getText().toString());
            } else {
                filter(null);
            }
        }
        Fragment fragment = getSupportFragmentManager().findFragmentByTag(FRAGMENT_TAG_DIALOG);
        if (fragment instanceof OnBackendConnected) {
            Log.d(Config.LOGTAG, "calling on backend connected on dialog");
            ((OnBackendConnected) fragment).onBackendConnected();
        }
        if (QuickConversationsService.isQuicksy()) {
            setRefreshing(xmppConnectionService.getQuickConversationsService().isSynchronizing());
        }
        if (QuickConversationsService.isConversations() && AccountUtils.hasEnabledAccounts(xmppConnectionService) && this.contacts.size() == 0 && this.conferences.size() == 0 && mOpenedFab.compareAndSet(false, true)) {
            binding.speedDial.open();
        }
    }

    protected boolean processViewIntent(@NonNull Intent intent) {
        final String inviteUri = intent.getStringExtra(EXTRA_INVITE_URI);
        if (inviteUri != null) {
            final Invite invite = new Invite(inviteUri);
            invite.account = intent.getStringExtra(EXTRA_ACCOUNT);
            if (invite.isValidJid()) {
                return invite.invite();
            }
        }
        final String action = intent.getAction();
        if (action == null) {
            return false;
        }
        switch (action) {
            case Intent.ACTION_SENDTO:
            case Intent.ACTION_VIEW:
                Uri uri = intent.getData();
                if (uri != null) {
                    Invite invite = new Invite(intent.getData(), intent.getBooleanExtra("scanned", false));
                    invite.account = intent.getStringExtra(EXTRA_ACCOUNT);
                    invite.forceDialog = intent.getBooleanExtra("force_dialog", false);
                    return invite.invite();
                } else {
                    return false;
                }
        }
        return false;
    }

    private boolean handleJid(Invite invite) {
        final List<Contact> contacts = xmppConnectionService.findContacts(invite.getJid(), invite.account);
        final Conversation muc = xmppConnectionService.findFirstMuc(invite.getJid(), invite.account);
        if (invite.isAction(XmppUri.ACTION_JOIN) || (contacts.isEmpty() && muc != null)) {
            if (muc != null && !invite.forceDialog) {
                if (invite.getParameter("password") != null) {
                    xmppConnectionService.providePasswordForMuc(muc, invite.getParameter("password"));
                }
                switchToConversationDoNotAppend(muc, invite.getBody());
                return true;
            } else {
                showJoinConferenceDialog(invite.getJid().asBareJid().toEscapedString(), invite);
                return false;
            }
        } else if (contacts.size() == 0) {
            showCreateContactDialog(invite.getJid().toEscapedString(), invite);
            return false;
        } else if (contacts.size() == 1) {
            Contact contact = contacts.get(0);
            if (!invite.isSafeSource() && invite.hasFingerprints()) {
                displayVerificationWarningDialog(contact, invite);
            } else {
                if (invite.hasFingerprints()) {
                    if (xmppConnectionService.verifyFingerprints(contact, invite.getFingerprints())) {
                        Toast.makeText(this, R.string.verified_fingerprints, Toast.LENGTH_SHORT).show();
                    }
                }
                if (invite.account != null) {
                    xmppConnectionService.getShortcutService().report(contact);
                }
                switchToConversationDoNotAppend(contact, invite.getBody());
            }
            return true;
        } else {
            if (mMenuSearchView != null) {
                mMenuSearchView.expandActionView();
                mSearchEditText.setText("");
                mSearchEditText.append(invite.getJid().toEscapedString());
                filter(invite.getJid().toEscapedString());
            } else {
                mInitialSearchValue.push(invite.getJid().toEscapedString());
            }
            return true;
        }
    }

    private void displayVerificationWarningDialog(final Contact contact, final Invite invite) {
        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle(R.string.verify_omemo_keys);
        View view = getLayoutInflater().inflate(R.layout.dialog_verify_fingerprints, null);
        final CheckBox isTrustedSource = view.findViewById(R.id.trusted_source);
        TextView warning = view.findViewById(R.id.warning);
        warning.setText(JidDialog.style(this, R.string.verifying_omemo_keys_trusted_source, contact.getJid().asBareJid().toEscapedString(), contact.getDisplayName()));
        builder.setView(view);
        builder.setPositiveButton(R.string.confirm, (dialog, which) -> {
            if (isTrustedSource.isChecked() && invite.hasFingerprints()) {
                xmppConnectionService.verifyFingerprints(contact, invite.getFingerprints());
            }
            switchToConversationDoNotAppend(contact, invite.getBody());
        });
        builder.setNegativeButton(R.string.cancel, (dialog, which) -> StartConversationActivity.this.finish());
        AlertDialog dialog = builder.create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnCancelListener(dialog1 -> StartConversationActivity.this.finish());
        dialog.show();
    }

    protected void filter(String needle) {
        if (xmppConnectionServiceBound) {
            this.filterContacts(needle);
            this.filterConferences(needle);
        }
    }

    protected void filterContacts(String needle) {
        this.contacts.clear();
        ArrayList<ListItem.Tag> tags = new ArrayList<>();
        final List<Account> accounts = xmppConnectionService.getAccounts();
        boolean foundSopranica = false;
        for (final Account account : accounts) {
            if (account.isEnabled()) {
                for (Contact contact : account.getRoster().getContacts()) {
                    Presence.Status s = contact.getShownStatus();
                    if (contact.showInContactList() && contact.match(this, needle)
                            && (!this.mHideOfflineContacts
                            || (needle != null && !needle.trim().isEmpty())
                            || s.compareTo(Presence.Status.OFFLINE) < 0)) {
                        this.contacts.add(contact);
                        tags.addAll(contact.getTags(this));
                    }
                }

                final Contact self = new Contact(account.getSelfContact());
                self.setSystemName("Note to Self");
                if (self.match(this, needle)) {
                    this.contacts.add(self);
                }

                for (Bookmark bookmark : account.getBookmarks()) {
                    if (bookmark.match(this, needle)) {
                        if (bookmark.getJid().toString().equals("discuss@conference.soprani.ca")) {
                            foundSopranica = true;
                        }
                        this.contacts.add(bookmark);
                        tags.addAll(bookmark.getTags(this));
                    }
                }
            }
        }

        Comparator<Map.Entry<ListItem.Tag,Integer>> sortTagsBy = Map.Entry.comparingByValue(Comparator.reverseOrder());
        sortTagsBy = sortTagsBy.thenComparing(entry -> entry.getKey().getName());

        mTagsAdapter.setTags(
            tags.stream()
            .collect(Collectors.toMap((x) -> x, (t) -> 1, (c1, c2) -> c1 + c2))
            .entrySet().stream()
            .sorted(sortTagsBy)
            .map(e -> e.getKey()).collect(Collectors.toList())
        );
        Collections.sort(this.contacts);

        final boolean sopranicaDeleted = getPreferences().getBoolean("cheogram_sopranica_bookmark_deleted", false);

        if (!sopranicaDeleted && !foundSopranica && (needle == null || needle.equals("")) && xmppConnectionService.getAccounts().size() > 0) {
            Bookmark bookmark = new Bookmark(
                xmppConnectionService.getAccounts().get(0),
                Jid.of("discuss@conference.soprani.ca")
            );
            bookmark.setBookmarkName("Soprani.ca / Cheogram Discussion");
            bookmark.addChild("group").setContent("support");
            this.contacts.add(0, bookmark);
        }

        mContactsAdapter.notifyDataSetChanged();
    }

    protected void filterConferences(String needle) {
        this.conferences.clear();
        for (final Account account : xmppConnectionService.getAccounts()) {
            if (account.isEnabled()) {
                for (final Bookmark bookmark : account.getBookmarks()) {
                    if (bookmark.match(this, needle)) {
                        this.conferences.add(bookmark);
                    }
                }
            }
        }
        Collections.sort(this.conferences);
        mConferenceAdapter.notifyDataSetChanged();
    }

    @Override
    public void OnUpdateBlocklist(final Status status) {
        refreshUi();
    }

    @Override
    protected void refreshUiReal() {
        if (mSearchEditText != null) {
            filter(mSearchEditText.getText().toString());
        }
        configureHomeButton();
        if (QuickConversationsService.isQuicksy()) {
            setRefreshing(xmppConnectionService.getQuickConversationsService().isSynchronizing());
        }
    }

    @Override
    public void onBackPressed() {
        if (binding.speedDial.isOpen()) {
            binding.speedDial.close();
            return;
        }
        navigateBack();
    }

    private void navigateBack() {
        if (!createdByViewIntent && xmppConnectionService != null && !xmppConnectionService.isConversationsListEmpty(null)) {
            Intent intent = new Intent(this, ConversationsActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(intent);
        }
        finish();
    }

    @Override
    public void onCreateDialogPositiveClick(AutoCompleteTextView spinner, String name) {
        if (!xmppConnectionServiceBound) {
            return;
        }
        final Account account = getSelectedAccount(this, spinner);
        if (account == null) {
            return;
        }
        Intent intent = new Intent(getApplicationContext(), ChooseContactActivity.class);
        intent.putExtra(ChooseContactActivity.EXTRA_SHOW_ENTER_JID, false);
        intent.putExtra(ChooseContactActivity.EXTRA_SELECT_MULTIPLE, true);
        intent.putExtra(ChooseContactActivity.EXTRA_GROUP_CHAT_NAME, name.trim());
        intent.putExtra(ChooseContactActivity.EXTRA_ACCOUNT, account.getJid().asBareJid().toEscapedString());
        intent.putExtra(ChooseContactActivity.EXTRA_TITLE_RES_ID, R.string.choose_participants);
        startActivityForResult(intent, REQUEST_CREATE_CONFERENCE);
    }

    @Override
    public void onJoinDialogPositiveClick(Dialog dialog, AutoCompleteTextView spinner, TextInputLayout layout, AutoCompleteTextView jid, String password) {
        if (!xmppConnectionServiceBound) {
            return;
        }
        final Account account = getSelectedAccount(this, spinner);
        if (account == null) {
            return;
        }
        final String input = jid.getText().toString().trim();
        Jid conferenceJid;
        try {
            conferenceJid = Jid.ofEscaped(input);
        } catch (final IllegalArgumentException e) {
            final XmppUri xmppUri = new XmppUri(input);
            if (xmppUri.isValidJid() && xmppUri.isAction(XmppUri.ACTION_JOIN)) {
                final Editable editable = jid.getEditableText();
                editable.clear();
                editable.append(xmppUri.getJid().toEscapedString());
                conferenceJid = xmppUri.getJid();
            } else {
                layout.setError(getString(R.string.invalid_jid));
                return;
            }
        }
        final var existingBookmark = account.getBookmark(conferenceJid);
        if (existingBookmark != null) {
            openConversationsForBookmark(existingBookmark);
        } else {
            final var bookmark = new Bookmark(account, conferenceJid.asBareJid());
            bookmark.setAutojoin(true);
            final String nick = conferenceJid.getResource();
            if (nick != null && !nick.isEmpty() && !nick.equals(MucOptions.defaultNick(account))) {
                bookmark.setNick(nick);
            }
            xmppConnectionService.createBookmark(account, bookmark);
            final Conversation conversation = xmppConnectionService
                    .findOrCreateConversation(account, conferenceJid, true, true, null, true, password);

            bookmark.setConversation(conversation);
            switchToConversation(conversation);
        }
        dialog.dismiss();
    }

    @Override
    public void onConversationUpdate() {
        refreshUi();
    }

    @Override
    public void onRefresh() {
        Log.d(Config.LOGTAG, "user requested to refresh");
        if (QuickConversationsService.isQuicksy() && xmppConnectionService != null) {
            xmppConnectionService.getQuickConversationsService().considerSyncBackground(true);
        }
    }


    private void setRefreshing(boolean refreshing) {
        MyListFragment fragment = (MyListFragment) mListPagerAdapter.getItem(0);
        if (fragment != null) {
            fragment.setRefreshing(refreshing);
        }
    }

    @Override
    public void onCreatePublicChannel(Account account, String name, Jid address) {
        mToast = Toast.makeText(this, R.string.creating_channel, Toast.LENGTH_LONG);
        mToast.show();
        xmppConnectionService.createPublicChannel(account, name, address, new UiCallback<Conversation>() {
            @Override
            public void success(Conversation conversation) {
                runOnUiThread(() -> {
                    hideToast();
                    switchToConversation(conversation);
                });

            }

            @Override
            public void error(int errorCode, Conversation conversation) {
                runOnUiThread(() -> {
                    replaceToast(getString(errorCode));
                    switchToConversation(conversation);
                });
            }

            @Override
            public void userInputRequired(PendingIntent pi, Conversation object) {

            }
        });
    }

    public static class MyListFragment extends SwipeRefreshListFragment {
        private AdapterView.OnItemClickListener mOnItemClickListener;
        private int mResContextMenu;

        public void setContextMenu(final int res) {
            this.mResContextMenu = res;
        }

        @Override
        public void onListItemClick(final ListView l, final View v, final int position, final long id) {
            if (mOnItemClickListener != null) {
                mOnItemClickListener.onItemClick(l, v, position, id);
            }
        }

        public void setOnListItemClickListener(AdapterView.OnItemClickListener l) {
            this.mOnItemClickListener = l;
        }

        @Override
        public void onViewCreated(@NonNull final View view, final Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);
            registerForContextMenu(getListView());
            getListView().setFastScrollEnabled(true);
            getListView().setDivider(null);
            getListView().setDividerHeight(0);
        }

        @Override
        public void onCreateContextMenu(@NonNull final ContextMenu menu, @NonNull final View v, final ContextMenuInfo menuInfo) {
            super.onCreateContextMenu(menu, v, menuInfo);
            final StartConversationActivity activity = (StartConversationActivity) getActivity();
            if (activity == null) {
                return;
            }
            final AdapterView.AdapterContextMenuInfo acmi = (AdapterContextMenuInfo) menuInfo;
            activity.contextItem = null;
            if (mResContextMenu == R.menu.contact_context) {
                activity.contextItem = activity.contacts.get(acmi.position);
            } else if (mResContextMenu == R.menu.conference_context) {
                activity.contextItem = activity.conferences.get(acmi.position);
            }
            if (activity.contextItem instanceof Bookmark) {
                activity.getMenuInflater().inflate(R.menu.conference_context, menu);
                final Bookmark bookmark = (Bookmark) activity.contextItem;
                final Conversation conversation = bookmark.getConversation();
                final MenuItem share = menu.findItem(R.id.context_share_uri);
                final MenuItem delete = menu.findItem(R.id.context_delete_conference);
                if (conversation != null) {
                    delete.setTitle(R.string.delete_and_close);
                } else {
                    delete.setTitle(R.string.delete_bookmark);
                }
                share.setVisible(conversation == null || !conversation.isPrivateAndNonAnonymous());
            } else if (activity.contextItem instanceof Contact) {
                activity.getMenuInflater().inflate(R.menu.contact_context, menu);
                final Contact contact = (Contact) activity.contextItem;
                final MenuItem blockUnblockItem = menu.findItem(R.id.context_contact_block_unblock);
                final MenuItem showContactDetailsItem = menu.findItem(R.id.context_contact_details);
                final MenuItem deleteContactMenuItem = menu.findItem(R.id.context_delete_contact);
                if (contact.isSelf()) {
                    showContactDetailsItem.setVisible(false);
                }
                deleteContactMenuItem.setVisible(contact.showInRoster() && !contact.getOption(Contact.Options.SYNCED_VIA_OTHER));
                final XmppConnection xmpp = contact.getAccount().getXmppConnection();
                if (xmpp != null && xmpp.getFeatures().blocking() && !contact.isSelf()) {
                    if (contact.isBlocked()) {
                        blockUnblockItem.setTitle(R.string.unblock_contact);
                    } else {
                        blockUnblockItem.setTitle(R.string.block_contact);
                    }
                } else {
                    blockUnblockItem.setVisible(false);
                }
            }
        }

        @Override
        public boolean onContextItemSelected(final MenuItem item) {
            StartConversationActivity activity = (StartConversationActivity) getActivity();
            if (activity == null) {
                return true;
            }
            switch (item.getItemId()) {
                case R.id.context_contact_details:
                    activity.openDetailsForContact();
                    break;
                case R.id.context_show_qr:
                    activity.showQrForContact();
                    break;
                case R.id.context_contact_block_unblock:
                    activity.toggleContactBlock();
                    break;
                case R.id.context_delete_contact:
                    activity.deleteContact();
                    break;
                case R.id.context_share_uri:
                    activity.shareBookmarkUri();
                    break;
                case R.id.context_delete_conference:
                    activity.deleteConference();
            }
            return true;
        }
    }

    public class ListPagerAdapter extends PagerAdapter {
        private final FragmentManager fragmentManager;
        private final MyListFragment[] fragments;

        ListPagerAdapter(FragmentManager fm) {
            fragmentManager = fm;
            fragments = new MyListFragment[2];
        }

        public void requestFocus(int pos) {
            if (fragments.length > pos) {
                fragments[pos].getListView().requestFocus();
            }
        }

        @Override
        public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
            FragmentTransaction trans = fragmentManager.beginTransaction();
            trans.remove(fragments[position]);
            trans.commit();
            fragments[position] = null;
        }

        @NonNull
        @Override
        public Fragment instantiateItem(@NonNull ViewGroup container, int position) {
            final Fragment fragment = getItem(position);
            final FragmentTransaction trans = fragmentManager.beginTransaction();
            trans.add(container.getId(), fragment, "fragment:" + position);
            try {
                trans.commit();
            } catch (IllegalStateException e) {
                //ignore
            }
            return fragment;
        }

        @Override
        public int getCount() {
            return fragments.length;
        }

        @Override
        public boolean isViewFromObject(@NonNull View view, @NonNull Object fragment) {
            return ((Fragment) fragment).getView() == view;
        }

        @Nullable
        @Override
        public CharSequence getPageTitle(int position) {
            switch (position) {
                case 0:
                    return getResources().getString(R.string.contacts);
                case 1:
                    return getResources().getString(R.string.group_chats);
                default:
                    return super.getPageTitle(position);
            }
        }

        Fragment getItem(int position) {
            if (fragments[position] == null) {
                final MyListFragment listFragment = new MyListFragment();
                if (position == 1) {
                    listFragment.setListAdapter(mConferenceAdapter);
                    listFragment.setContextMenu(R.menu.conference_context);
                    listFragment.setOnListItemClickListener((arg0, arg1, p, arg3) -> openConversationForBookmark(p));
                } else {
                    listFragment.setListAdapter(mContactsAdapter);
                    listFragment.setContextMenu(R.menu.contact_context);
                    listFragment.setOnListItemClickListener((arg0, arg1, p, arg3) -> openConversationForContact(p));
                    if (QuickConversationsService.isQuicksy()) {
                        listFragment.setOnRefreshListener(StartConversationActivity.this);
                    }
                }
                fragments[position] = listFragment;
            }
            return fragments[position];
        }
    }

    public static void addInviteUri(Intent to, Intent from) {
        if (from != null && from.hasExtra(EXTRA_INVITE_URI)) {
            final String invite = from.getStringExtra(EXTRA_INVITE_URI);
            to.putExtra(EXTRA_INVITE_URI, invite);
        }
    }

    private class Invite extends XmppUri {

        public String account;

        boolean forceDialog = false;


        Invite(final String uri) {
            super(uri);
        }

        Invite(Uri uri, boolean safeSource) {
            super(uri, safeSource);
        }

        boolean invite() {
            if (!isValidJid()) {
                Toast.makeText(StartConversationActivity.this, R.string.invalid_jid, Toast.LENGTH_SHORT).show();
                return false;
            }
            if (getJid() != null) {
                return handleJid(this);
            }
            return false;
        }
    }

    class TagsAdapter extends RecyclerView.Adapter<TagsAdapter.ViewHolder> {
        class ViewHolder extends RecyclerView.ViewHolder {
            protected TextView tv;

            public ViewHolder(View v) {
                super(v);
                tv = (TextView) v;
                tv.setOnClickListener(view -> {
                    String needle = mSearchEditText.getText().toString();
                    String tag = tv.getText().toString();
                    String[] parts = needle.split("[,\\s]+");
                    if(needle.isEmpty()) {
                        needle = tag;
                    } else if (tag.toLowerCase(Locale.US).contains(parts[parts.length-1])) {
                        needle = needle.replace(parts[parts.length-1], tag);
                    } else {
                        needle += ", " + tag;
                    }
                    mSearchEditText.setText("");
                    mSearchEditText.append(needle);
                    filter(needle);
                });
            }

            public void setTag(ListItem.Tag tag) {
                tv.setText(tag.getName());
                tv.setBackgroundTintList(ColorStateList.valueOf(MaterialColors.harmonizeWithPrimary(StartConversationActivity.this,XEP0392Helper.rgbFromNick(tag.getName()))));
            }
        }

        protected List<ListItem.Tag> tags = new ArrayList<>();

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup viewGroup, int i) {
            View view = LayoutInflater.from(viewGroup.getContext()).inflate(R.layout.list_item_tag, null);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(ViewHolder viewHolder, int i) {
            viewHolder.setTag(tags.get(i));
        }

        @Override
        public int getItemCount() {
            return tags.size();
        }

        public void setTags(final List<ListItem.Tag> tags) {
            ListItem.Tag channelTag = new ListItem.Tag("Channel");
            String needle = mSearchEditText == null ? "" : mSearchEditText.getText().toString().toLowerCase(Locale.US).trim();
            HashSet<String> parts = new HashSet<>(Arrays.asList(needle.split("[,\\s]+")));
            this.tags = tags.stream().filter(
                tag -> !tag.equals(channelTag) && !parts.contains(tag.getName().toLowerCase(Locale.US))
            ).collect(Collectors.toList());
            if (!parts.contains("channel") && tags.contains(channelTag)) this.tags.add(0, channelTag);
            notifyDataSetChanged();
        }
    }
}
