package eu.siacs.conversations.ui;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.ContactsContract.CommonDataKinds;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Intents;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.inputmethod.InputMethodManager;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.databinding.DataBindingUtil;

import com.cheogram.android.Util;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Ints;

import org.openintents.openpgp.util.OpenPgpUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.crypto.axolotl.AxolotlService;
import eu.siacs.conversations.crypto.axolotl.FingerprintStatus;
import eu.siacs.conversations.crypto.axolotl.XmppAxolotlSession;
import eu.siacs.conversations.databinding.ActivityContactDetailsBinding;
import eu.siacs.conversations.databinding.CommandRowBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Bookmark;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.ListItem;
import eu.siacs.conversations.entities.Presence;
import eu.siacs.conversations.services.AbstractQuickConversationsService;
import eu.siacs.conversations.services.QuickConversationsService;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.services.XmppConnectionService.OnAccountUpdate;
import eu.siacs.conversations.services.XmppConnectionService.OnRosterUpdate;
import eu.siacs.conversations.ui.adapter.MediaAdapter;
import eu.siacs.conversations.ui.interfaces.OnMediaLoaded;
import eu.siacs.conversations.ui.util.Attachment;
import eu.siacs.conversations.ui.util.AvatarWorkerTask;
import eu.siacs.conversations.ui.util.GridManager;
import eu.siacs.conversations.ui.util.JidDialog;
import eu.siacs.conversations.ui.util.MenuDoubleTabUtil;
import eu.siacs.conversations.ui.util.ShareUtil;
import eu.siacs.conversations.ui.util.SoftKeyboardUtils;
import eu.siacs.conversations.utils.AccountUtils;
import eu.siacs.conversations.utils.Compatibility;
import eu.siacs.conversations.utils.Emoticons;
import eu.siacs.conversations.utils.IrregularUnicodeDetector;
import eu.siacs.conversations.utils.PhoneNumberUtilWrapper;
import eu.siacs.conversations.utils.UIHelper;
import eu.siacs.conversations.utils.XEP0392Helper;
import eu.siacs.conversations.utils.XmppUri;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.OnKeyStatusUpdated;
import eu.siacs.conversations.xmpp.OnUpdateBlocklist;
import eu.siacs.conversations.xmpp.XmppConnection;

public class ContactDetailsActivity extends OmemoActivity implements OnAccountUpdate, OnRosterUpdate, OnUpdateBlocklist, OnKeyStatusUpdated, OnMediaLoaded {
    public static final String ACTION_VIEW_CONTACT = "view_contact";
    private final int REQUEST_SYNC_CONTACTS = 0x28cf;
    ActivityContactDetailsBinding binding;
    private MediaAdapter mMediaAdapter;
    protected MenuItem edit = null;
    protected MenuItem save = null;

    private Contact contact;
    private final DialogInterface.OnClickListener removeFromRoster = new DialogInterface.OnClickListener() {

        @Override
        public void onClick(DialogInterface dialog, int which) {
            xmppConnectionService.deleteContactOnServer(contact);
        }
    };
    private final OnCheckedChangeListener mOnSendCheckedChange = new OnCheckedChangeListener() {

        @Override
        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            if (isChecked) {
                if (contact.getOption(Contact.Options.PENDING_SUBSCRIPTION_REQUEST)) {
                    xmppConnectionService.stopPresenceUpdatesTo(contact);
                } else {
                    contact.setOption(Contact.Options.PREEMPTIVE_GRANT);
                }
            } else {
                contact.resetOption(Contact.Options.PREEMPTIVE_GRANT);
                xmppConnectionService.sendPresencePacket(contact.getAccount(), xmppConnectionService.getPresenceGenerator().stopPresenceUpdatesTo(contact));
            }
        }
    };
    private final OnCheckedChangeListener mOnReceiveCheckedChange = new OnCheckedChangeListener() {

        @Override
        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            if (isChecked) {
                xmppConnectionService.sendPresencePacket(contact.getAccount(), xmppConnectionService.getPresenceGenerator().requestPresenceUpdatesFrom(contact));
            } else {
                xmppConnectionService.sendPresencePacket(contact.getAccount(), xmppConnectionService.getPresenceGenerator().stopPresenceUpdatesFrom(contact));
            }
        }
    };
    private Jid accountJid;
    private Jid contactJid;
    private boolean showDynamicTags = false;
    private boolean showLastSeen = false;
    private boolean showInactiveOmemo = false;
    private String messageFingerprint;

    private void checkContactPermissionAndShowAddDialog() {
        if (hasContactsPermission()) {
            showAddToPhoneBookDialog();
        } else if (QuickConversationsService.isContactListIntegration(this) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, REQUEST_SYNC_CONTACTS);
        }
    }

    private boolean hasContactsPermission() {
        if (QuickConversationsService.isContactListIntegration(this) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED;
        } else {
            return true;
        }
    }

    private void showAddToPhoneBookDialog() {
        final Jid jid = contact.getJid();
        final boolean quicksyContact = AbstractQuickConversationsService.isQuicksy()
                && Config.QUICKSY_DOMAIN.equals(jid.getDomain())
                && jid.getLocal() != null;
        final String value;
        if (quicksyContact) {
            value = PhoneNumberUtilWrapper.toFormattedPhoneNumber(this, jid);
        } else {
            value = jid.toEscapedString();
        }
        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle(getString(R.string.action_add_phone_book));
        builder.setMessage(getString(R.string.add_phone_book_text, value));
        builder.setNegativeButton(getString(R.string.cancel), null);
        builder.setPositiveButton(getString(R.string.add), (dialog, which) -> {
            final Intent intent = new Intent(Intent.ACTION_INSERT_OR_EDIT);
            intent.setType(Contacts.CONTENT_ITEM_TYPE);
            if (quicksyContact) {
                intent.putExtra(Intents.Insert.PHONE, value);
            } else {
                intent.putExtra(Intents.Insert.IM_HANDLE, value);
                intent.putExtra(Intents.Insert.IM_PROTOCOL, CommonDataKinds.Im.PROTOCOL_JABBER);
                //TODO for modern use we want PROTOCOL_CUSTOM and an extra field with a value of 'XMPP'
                // however we don’t have such a field and thus have to use the legacy PROTOCOL_JABBER
            }
            intent.putExtra("finishActivityOnSaveCompleted", true);
            try {
                startActivityForResult(intent, 0);
            } catch (ActivityNotFoundException e) {
                Toast.makeText(ContactDetailsActivity.this, R.string.no_application_found_to_view_contact, Toast.LENGTH_SHORT).show();
            }
        });
        builder.create().show();
    }

    @Override
    public void onRosterUpdate(final XmppConnectionService.UpdateRosterReason reason, final Contact contact) {
        refreshUi();
    }

    @Override
    public void onAccountUpdate() {
        refreshUi();
    }

    @Override
    public void OnUpdateBlocklist(final Status status) {
        refreshUi();
    }

    @Override
    protected void refreshUiReal() {
        populateView();
    }

    @Override
    protected String getShareableUri(boolean http) {
        if (http) {
            return "https://conversations.im/i/" + XmppUri.lameUrlEncode(contact.getJid().asBareJid().toEscapedString());
        } else {
            return "xmpp:" + Uri.encode(contact.getJid().asBareJid().toEscapedString(), "@/+");
        }
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        showInactiveOmemo = savedInstanceState != null && savedInstanceState.getBoolean("show_inactive_omemo", false);
        if (getIntent().getAction().equals(ACTION_VIEW_CONTACT)) {
            try {
                this.accountJid = Jid.ofEscaped(getIntent().getExtras().getString(EXTRA_ACCOUNT));
            } catch (final IllegalArgumentException ignored) {
            }
            try {
                this.contactJid = Jid.ofEscaped(getIntent().getExtras().getString("contact"));
            } catch (final IllegalArgumentException ignored) {
            }
        }
        this.messageFingerprint = getIntent().getStringExtra("fingerprint");
        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_contact_details);
        Activities.setStatusAndNavigationBarColors(this, binding.getRoot());

        setSupportActionBar(binding.toolbar);
        configureActionBar(getSupportActionBar());
        binding.showInactiveDevices.setOnClickListener(v -> {
            showInactiveOmemo = !showInactiveOmemo;
            populateView();
        });
        binding.addContactButton.setOnClickListener(v -> showAddToRosterDialog(contact));

        mMediaAdapter = new MediaAdapter(this, R.dimen.media_size);
        this.binding.media.setAdapter(mMediaAdapter);
        GridManager.setupLayoutManager(this, this.binding.media, R.dimen.media_size);
    }

    @Override
    public void onSaveInstanceState(final Bundle savedInstanceState) {
        savedInstanceState.putBoolean("show_inactive_omemo", showInactiveOmemo);
        super.onSaveInstanceState(savedInstanceState);
    }

    @Override
    public void onStart() {
        super.onStart();
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        this.showDynamicTags = preferences.getBoolean(AppSettings.SHOW_DYNAMIC_TAGS, false);
        this.showLastSeen = preferences.getBoolean("last_activity", false);
        binding.mediaWrapper.setVisibility(Compatibility.hasStoragePermission(this) ? View.VISIBLE : View.GONE);
        mMediaAdapter.setAttachments(Collections.emptyList());
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length > 0)
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (requestCode == REQUEST_SYNC_CONTACTS && xmppConnectionServiceBound) {
                    showAddToPhoneBookDialog();
                    xmppConnectionService.loadPhoneContacts();
                    xmppConnectionService.startContactObserver();
                }
            }
    }

    protected void saveEdits() {
        binding.editTags.setVisibility(View.GONE);
        if (edit != null) {
            EditText text = edit.getActionView().findViewById(R.id.search_field);
            contact.setServerName(text.getText().toString());
            contact.setGroups(binding.editTags.getObjects().stream().map(tag -> tag.getName()).collect(Collectors.toList()));
            ContactDetailsActivity.this.xmppConnectionService.pushContactToServer(contact);
            populateView();
            edit.collapseActionView();
        }
        if (save != null) save.setVisible(false);
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem menuItem) {
        if (MenuDoubleTabUtil.shouldIgnoreTap()) {
            return false;
        }
        switch (menuItem.getItemId()) {
            case android.R.id.home:
                finish();
                break;
            case R.id.action_share_http:
                shareLink(true);
                break;
            case R.id.action_share_uri:
                shareLink(false);
                break;
            case R.id.action_delete_contact:
                final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
                builder.setNegativeButton(getString(R.string.cancel), null);
                builder.setTitle(getString(R.string.action_delete_contact))
                        .setMessage(JidDialog.style(this, R.string.remove_contact_text, contact.getJid().toEscapedString()))
                        .setPositiveButton(getString(R.string.delete),
                                removeFromRoster).create().show();
                break;
            case R.id.action_save:
                saveEdits();
                break;
            case R.id.action_edit_contact:
                Uri systemAccount = contact.getSystemAccount();
                if (systemAccount == null) {
                    menuItem.expandActionView();
                    EditText text = menuItem.getActionView().findViewById(R.id.search_field);
                    text.setOnEditorActionListener((v, actionId, event) -> {
                        saveEdits();
                        return true;
                    });
                    text.setText(contact.getServerName());
                    text.requestFocus();
                    InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.showSoftInput(text, InputMethodManager.SHOW_IMPLICIT);
                    }
                    binding.tags.setVisibility(View.GONE);
                    binding.editTags.clearSync();
                    for (final ListItem.Tag group : contact.getGroupTags()) {
                        binding.editTags.addObjectSync(group);
                    }
                    ArrayList<ListItem.Tag> tags = new ArrayList<>();
                    for (final Account account : xmppConnectionService.getAccounts()) {
                        for (Contact contact : account.getRoster().getContacts()) {
                            tags.addAll(contact.getTags(this));
                        }
                        for (Bookmark bookmark : account.getBookmarks()) {
                            tags.addAll(bookmark.getTags(this));
                        }
                    }
                    Comparator<Map.Entry<ListItem.Tag,Integer>> sortTagsBy = Map.Entry.comparingByValue(Comparator.reverseOrder());
                    sortTagsBy = sortTagsBy.thenComparing(entry -> entry.getKey().getName());

                    ArrayAdapter<ListItem.Tag> adapter = new ArrayAdapter<>(
                        this,
                        android.R.layout.simple_list_item_1,
                        tags.stream()
                        .collect(Collectors.toMap((x) -> x, (t) -> 1, (c1, c2) -> c1 + c2))
                        .entrySet().stream()
                       .sorted(sortTagsBy)
                       .map(e -> e.getKey()).collect(Collectors.toList())
                    );
                    binding.editTags.setAdapter(adapter);
                    if (showDynamicTags) binding.editTags.setVisibility(View.VISIBLE);
                    if (save != null) save.setVisible(true);
                } else {
                    menuItem.collapseActionView();
                    if (save != null) save.setVisible(false);
                    Intent intent = new Intent(Intent.ACTION_EDIT);
                    intent.setDataAndType(systemAccount, Contacts.CONTENT_ITEM_TYPE);
                    intent.putExtra("finishActivityOnSaveCompleted", true);
                    try {
                        startActivity(intent);
                    } catch (ActivityNotFoundException e) {
                        Toast.makeText(ContactDetailsActivity.this, R.string.no_application_found_to_view_contact, Toast.LENGTH_SHORT).show();
                    }

                }
                break;
            case R.id.action_block:
                BlockContactDialog.show(this, contact);
                break;
            case R.id.action_unblock:
                BlockContactDialog.show(this, contact);
                break;
        }
        return super.onOptionsItemSelected(menuItem);
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        getMenuInflater().inflate(R.menu.contact_details, menu);
        AccountUtils.showHideMenuItems(menu);
        edit = menu.findItem(R.id.action_edit_contact);
        save = menu.findItem(R.id.action_save);
        MenuItem block = menu.findItem(R.id.action_block);
        MenuItem unblock = menu.findItem(R.id.action_unblock);
        MenuItem edit = menu.findItem(R.id.action_edit_contact);
        MenuItem delete = menu.findItem(R.id.action_delete_contact);
        if (contact == null) {
            return true;
        }
        final XmppConnection connection = contact.getAccount().getXmppConnection();
        if (connection != null && connection.getFeatures().blocking()) {
            if (this.contact.isBlocked()) {
                block.setVisible(false);
            } else {
                unblock.setVisible(false);
            }
        } else {
            unblock.setVisible(false);
            block.setVisible(false);
        }
        if (!contact.showInRoster()) {
            edit.setVisible(false);
            delete.setVisible(false);
        }
        edit.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
            @Override
            public boolean onMenuItemActionCollapse(MenuItem item) {
                SoftKeyboardUtils.hideSoftKeyboard(ContactDetailsActivity.this);
                binding.editTags.setVisibility(View.GONE);
                if (save != null) save.setVisible(false);
                populateView();
                return true;
            }

            @Override
            public boolean onMenuItemActionExpand(MenuItem item) { return true; }
        });
        return super.onCreateOptionsMenu(menu);
    }

    private void populateView() {
        if (contact == null) {
            return;
        }
        if (binding.editTags.getVisibility() != View.GONE) return;
        invalidateOptionsMenu();
        setTitle(contact.getDisplayName());
        if (contact.showInRoster()) {
            binding.detailsSendPresence.setVisibility(View.VISIBLE);
            binding.detailsReceivePresence.setVisibility(View.VISIBLE);
            binding.addContactButton.setVisibility(View.GONE);
            binding.detailsSendPresence.setOnCheckedChangeListener(null);
            binding.detailsReceivePresence.setOnCheckedChangeListener(null);

            List<String> statusMessages = contact.getPresences().getStatusMessages();
            if (statusMessages.size() == 0) {
                binding.statusMessage.setVisibility(View.GONE);
            } else if (statusMessages.size() == 1) {
                final String message = statusMessages.get(0);
                binding.statusMessage.setVisibility(View.VISIBLE);
                final Spannable span = new SpannableString(message);
                if (Emoticons.isOnlyEmoji(message)) {
                    span.setSpan(new RelativeSizeSpan(2.0f), 0, message.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                binding.statusMessage.setText(span);
            } else {
                StringBuilder builder = new StringBuilder();
                binding.statusMessage.setVisibility(View.VISIBLE);
                int s = statusMessages.size();
                for (int i = 0; i < s; ++i) {
                    builder.append(statusMessages.get(i));
                    if (i < s - 1) {
                        builder.append("\n");
                    }
                }
                binding.statusMessage.setText(builder);
            }

            if (contact.getOption(Contact.Options.FROM)) {
                binding.detailsSendPresence.setText(R.string.send_presence_updates);
                binding.detailsSendPresence.setChecked(true);
            } else if (contact.getOption(Contact.Options.PENDING_SUBSCRIPTION_REQUEST)) {
                binding.detailsSendPresence.setChecked(false);
                binding.detailsSendPresence.setText(R.string.send_presence_updates);
            } else {
                binding.detailsSendPresence.setText(R.string.preemptively_grant);
                binding.detailsSendPresence.setChecked(contact.getOption(Contact.Options.PREEMPTIVE_GRANT));
            }
            if (contact.getOption(Contact.Options.TO)) {
                binding.detailsReceivePresence.setText(R.string.receive_presence_updates);
                binding.detailsReceivePresence.setChecked(true);
            } else {
                binding.detailsReceivePresence.setText(R.string.ask_for_presence_updates);
                binding.detailsReceivePresence.setChecked(contact.getOption(Contact.Options.ASKING));
            }
            if (contact.getAccount().isOnlineAndConnected()) {
                binding.detailsReceivePresence.setEnabled(true);
                binding.detailsSendPresence.setEnabled(true);
            } else {
                binding.detailsReceivePresence.setEnabled(false);
                binding.detailsSendPresence.setEnabled(false);
            }
            binding.detailsSendPresence.setOnCheckedChangeListener(this.mOnSendCheckedChange);
            binding.detailsReceivePresence.setOnCheckedChangeListener(this.mOnReceiveCheckedChange);
        } else {
            binding.addContactButton.setVisibility(View.VISIBLE);
            binding.detailsSendPresence.setVisibility(View.GONE);
            binding.detailsReceivePresence.setVisibility(View.GONE);
            binding.statusMessage.setVisibility(View.GONE);
        }

        if (contact.isBlocked() && !this.showDynamicTags) {
            binding.detailsLastseen.setVisibility(View.VISIBLE);
            binding.detailsLastseen.setText(R.string.contact_blocked);
        } else {
            if (showLastSeen
                    && contact.getLastseen() > 0
                    && contact.getPresences().allOrNonSupport(Namespace.IDLE)) {
                binding.detailsLastseen.setVisibility(View.VISIBLE);
                binding.detailsLastseen.setText(UIHelper.lastseen(getApplicationContext(), contact.isActive(), contact.getLastseen()));
            } else {
                binding.detailsLastseen.setVisibility(View.GONE);
            }
        }

        binding.detailsContactjid.setText(IrregularUnicodeDetector.style(this, contact.getJid()));
        final String account = contact.getAccount().getJid().asBareJid().toEscapedString();
        binding.detailsAccount.setText(getString(R.string.using_account, account));
        AvatarWorkerTask.loadAvatar(contact, binding.detailsContactBadge, R.dimen.avatar_on_details_screen_size);
        binding.detailsContactBadge.setOnClickListener(this::onBadgeClick);

        binding.detailsContactKeys.removeAllViews();
        boolean hasKeys = false;
        final LayoutInflater inflater = getLayoutInflater();
        final AxolotlService axolotlService = contact.getAccount().getAxolotlService();
        if (Config.supportOmemo() && axolotlService != null) {
            final Collection<XmppAxolotlSession> sessions = axolotlService.findSessionsForContact(contact);
            boolean anyActive = false;
            for (XmppAxolotlSession session : sessions) {
                anyActive = session.getTrust().isActive();
                if (anyActive) {
                    break;
                }
            }
            boolean skippedInactive = false;
            boolean showsInactive = false;
            boolean showUnverifiedWarning = false;
            for (final XmppAxolotlSession session : sessions) {
                final FingerprintStatus trust = session.getTrust();
                hasKeys |= !trust.isCompromised();
                if (!trust.isActive() && anyActive) {
                    if (showInactiveOmemo) {
                        showsInactive = true;
                    } else {
                        skippedInactive = true;
                        continue;
                    }
                }
                if (!trust.isCompromised()) {
                    boolean highlight = session.getFingerprint().equals(messageFingerprint);
                    addFingerprintRow(binding.detailsContactKeys, session, highlight);
                }
                if (trust.isUnverified()) {
                    showUnverifiedWarning = true;
                }
            }
            binding.unverifiedWarning.setVisibility(showUnverifiedWarning ? View.VISIBLE : View.GONE);
            if (showsInactive || skippedInactive) {
                binding.showInactiveDevices.setText(showsInactive ? R.string.hide_inactive_devices : R.string.show_inactive_devices);
                binding.showInactiveDevices.setVisibility(View.VISIBLE);
            } else {
                binding.showInactiveDevices.setVisibility(View.GONE);
            }
        } else {
            binding.showInactiveDevices.setVisibility(View.GONE);
        }
        final boolean isCameraFeatureAvailable = isCameraFeatureAvailable();
        binding.scanButton.setVisibility(hasKeys && isCameraFeatureAvailable ? View.VISIBLE : View.GONE);
        if (hasKeys) {
            binding.scanButton.setOnClickListener((v) -> ScanActivity.scan(this));
        }
        if (Config.supportOpenPgp() && contact.getPgpKeyId() != 0) {
            hasKeys = true;
            View view = inflater.inflate(R.layout.contact_key, binding.detailsContactKeys, false);
            TextView key = view.findViewById(R.id.key);
            TextView keyType = view.findViewById(R.id.key_type);
            keyType.setText(R.string.openpgp_key_id);
            if ("pgp".equals(messageFingerprint)) {
                keyType.setTextColor(MaterialColors.getColor(keyType, com.google.android.material.R.attr.colorPrimaryVariant));
            }
            key.setText(OpenPgpUtils.convertKeyIdToHex(contact.getPgpKeyId()));
            final OnClickListener openKey = v -> launchOpenKeyChain(contact.getPgpKeyId());
            view.setOnClickListener(openKey);
            key.setOnClickListener(openKey);
            keyType.setOnClickListener(openKey);
            binding.detailsContactKeys.addView(view);
        }
        binding.keysWrapper.setVisibility(hasKeys ? View.VISIBLE : View.GONE);

        final List<ListItem.Tag> tagList = contact.getTags(this);
        final boolean hasMetaTags = contact.isBlocked() || contact.getShownStatus() != Presence.Status.OFFLINE;
        if ((tagList.isEmpty() && !hasMetaTags) || !this.showDynamicTags) {
            binding.tags.setVisibility(View.GONE);
        } else {
            binding.tags.setVisibility(View.VISIBLE);
            binding.tags.removeViews(1, binding.tags.getChildCount() - 1);
            final ImmutableList.Builder<Integer> viewIdBuilder = new ImmutableList.Builder<>();
            for (final ListItem.Tag tag : tagList) {
                final String name = tag.getName();
                final TextView tv = (TextView) inflater.inflate(R.layout.list_item_tag, binding.tags, false);
                tv.setText(name);
                tv.setBackgroundTintList(ColorStateList.valueOf(MaterialColors.harmonizeWithPrimary(this,XEP0392Helper.rgbFromNick(name))));
                final int id = ViewCompat.generateViewId();
                tv.setId(id);
                viewIdBuilder.add(id);
                binding.tags.addView(tv);
            }
            if (contact.isBlocked()) {
                final TextView tv =
                        (TextView)
                                inflater.inflate(
                                        R.layout.list_item_tag, binding.tags, false);
                tv.setText(R.string.blocked);
                tv.setBackgroundTintList(ColorStateList.valueOf(MaterialColors.harmonizeWithPrimary(tv.getContext(), ContextCompat.getColor(tv.getContext(),R.color.gray_800))));
                final int id = ViewCompat.generateViewId();
                tv.setId(id);
                viewIdBuilder.add(id);
                binding.tags.addView(tv);
            } else {
                final Presence.Status status = contact.getShownStatus();
                if (status != Presence.Status.OFFLINE) {
                    final TextView tv =
                            (TextView)
                                    inflater.inflate(
                                            R.layout.list_item_tag, binding.tags, false);
                    UIHelper.setStatus(tv, status);
                    final int id = ViewCompat.generateViewId();
                    tv.setId(id);
                    viewIdBuilder.add(id);
                    binding.tags.addView(tv);
                }
            }
            binding.flowWidget.setReferencedIds(Ints.toArray(viewIdBuilder.build()));
        }
    }

    private void onBadgeClick(final View view) {
        if (QuickConversationsService.isContactListIntegration(this)) {
            final Uri systemAccount = contact.getSystemAccount();
            if (systemAccount == null) {
                checkContactPermissionAndShowAddDialog();
            } else {
                final Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setData(systemAccount);
                try {
                    startActivity(intent);
                } catch (final ActivityNotFoundException e) {
                    Toast.makeText(
                                    this,
                                    R.string.no_application_found_to_view_contact,
                                    Toast.LENGTH_SHORT)
                            .show();
                }
            }
        } else {
            Toast.makeText(
                            this,
                            R.string.contact_list_integration_not_available,
                            Toast.LENGTH_SHORT)
                    .show();
        }
    }

    public void onBackendConnected() {
        if (accountJid != null && contactJid != null) {
            Account account = xmppConnectionService.findAccountByJid(accountJid);
            if (account == null) {
                return;
            }
            this.contact = account.getRoster().getContact(contactJid);
            if (mPendingFingerprintVerificationUri != null) {
                processFingerprintVerification(mPendingFingerprintVerificationUri);
                mPendingFingerprintVerificationUri = null;
            }

            if (Compatibility.hasStoragePermission(this)) {
                final int limit = GridManager.getCurrentColumnCount(this.binding.media);
                xmppConnectionService.getAttachments(account, contact.getJid().asBareJid(), limit, this);
                this.binding.showMedia.setOnClickListener((v) -> MediaBrowserActivity.launch(this, contact));
            }

            final VcardAdapter items = new VcardAdapter();
            binding.profileItems.setAdapter(items);
            binding.profileItems.setOnItemClickListener((a0, v, pos, a3) -> {
                final Uri uri = items.getUri(pos);
                if (uri == null) return;

                if ("xmpp".equals(uri.getScheme())) {
                    switchToConversation(xmppConnectionService.findOrCreateConversation(account, Jid.of(uri.getSchemeSpecificPart()), false, true));
                } else {
                    Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                    try {
                        startActivity(intent);
                    } catch (ActivityNotFoundException e) {
                        Toast.makeText(this, R.string.no_application_found_to_open_link, Toast.LENGTH_SHORT).show();
                    }
                }
            });
            binding.profileItems.setOnItemLongClickListener((a0, v, pos, a3) -> {
                String toCopy = null;
                final Uri uri = items.getUri(pos);
                if (uri != null) toCopy = uri.toString();
                if (toCopy == null) {
                    toCopy = items.getItem(pos).findChildContent("text", Namespace.VCARD4);
                }

                if (toCopy == null) return false;
                if (ShareUtil.copyTextToClipboard(ContactDetailsActivity.this, toCopy, R.string.message)) {
                    Toast.makeText(ContactDetailsActivity.this, R.string.message_copied_to_clipboard, Toast.LENGTH_SHORT).show();
                }
                return true;
            });
            xmppConnectionService.fetchVcard4(account, contact, (vcard4) -> {
                if (vcard4 == null) return;

                runOnUiThread(() -> {
                    for (Element el : vcard4.getChildren()) {
                        if (el.findChildEnsureSingle("uri", Namespace.VCARD4) != null || el.findChildEnsureSingle("text", Namespace.VCARD4) != null) {
                            items.add(el);
                        }
                    }
                    Util.justifyListViewHeightBasedOnChildren(binding.profileItems);
                });
            });

            final var conversation = xmppConnectionService.findOrCreateConversation(account, contact.getJid(), false, true);
            binding.storeInCache.setChecked(conversation.storeInCache());
            binding.storeInCache.setOnCheckedChangeListener((v, checked) -> {
                conversation.setStoreInCache(checked);
                xmppConnectionService.updateConversation(conversation);
            });

            populateView();
        }
    }

    @Override
    public void onKeyStatusUpdated(AxolotlService.FetchStatus report) {
        refreshUi();
    }

    @Override
    protected void processFingerprintVerification(XmppUri uri) {
        if (contact != null && contact.getJid().asBareJid().equals(uri.getJid()) && uri.hasFingerprints()) {
            if (xmppConnectionService.verifyFingerprints(contact, uri.getFingerprints())) {
                Toast.makeText(this, R.string.verified_fingerprints, Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, R.string.invalid_barcode, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onMediaLoaded(List<Attachment> attachments) {
        runOnUiThread(() -> {
            int limit = GridManager.getCurrentColumnCount(binding.media);
            mMediaAdapter.setAttachments(attachments.subList(0, Math.min(limit, attachments.size())));
            binding.mediaWrapper.setVisibility(attachments.size() > 0 ? View.VISIBLE : View.GONE);
        });

    }

    class VcardAdapter extends ArrayAdapter<Element> {
        VcardAdapter() { super(ContactDetailsActivity.this, 0); }

        private Drawable getDrawable(int d) {
            return ContactDetailsActivity.this.getDrawable(d);
        }

        @Override
        public View getView(int position, View view, @NonNull ViewGroup parent) {
            final CommandRowBinding binding = DataBindingUtil.inflate(LayoutInflater.from(parent.getContext()), R.layout.command_row, parent, false);
            final Element item = getItem(position);

            if (item.getName().equals("org")) {
                binding.command.setCompoundDrawablesRelativeWithIntrinsicBounds(getDrawable(R.drawable.ic_business_24dp), null, null, null);
                binding.command.setCompoundDrawablePadding(20);
            } else if (item.getName().equals("impp")) {
                binding.command.setCompoundDrawablesRelativeWithIntrinsicBounds(getDrawable(R.drawable.ic_chat_black_24dp), null, null, null);
                binding.command.setCompoundDrawablePadding(20);
            } else if (item.getName().equals("url")) {
                binding.command.setCompoundDrawablesRelativeWithIntrinsicBounds(getDrawable(R.drawable.ic_link_24dp), null, null, null);
                binding.command.setCompoundDrawablePadding(20);
            }

            final Uri uri = getUri(position);
            if (uri != null && uri.getScheme() != null) {
                if (uri.getScheme().equals("xmpp")) {
                    binding.command.setText(uri.getSchemeSpecificPart());
                    binding.command.setCompoundDrawablesRelativeWithIntrinsicBounds(getDrawable(R.drawable.jabber), null, null, null);
                    binding.command.setCompoundDrawablePadding(20);
                } else if (uri.getScheme().equals("tel")) {
                    binding.command.setText(uri.getSchemeSpecificPart());
                    binding.command.setCompoundDrawablesRelativeWithIntrinsicBounds(getDrawable(R.drawable.ic_call_24dp), null, null, null);
                    binding.command.setCompoundDrawablePadding(20);
                } else if (uri.getScheme().equals("mailto")) {
                    binding.command.setText(uri.getSchemeSpecificPart());
                    binding.command.setCompoundDrawablesRelativeWithIntrinsicBounds(getDrawable(R.drawable.ic_email_24dp), null, null, null);
                    binding.command.setCompoundDrawablePadding(20);
                } else if (uri.getScheme().equals("http") || uri.getScheme().equals("https")) {
                    binding.command.setText(uri.toString());
                    binding.command.setCompoundDrawablesRelativeWithIntrinsicBounds(getDrawable(R.drawable.ic_link_24dp), null, null, null);
                    binding.command.setCompoundDrawablePadding(20);
                } else {
                    binding.command.setText(uri.toString());
                }
            } else {
                final String text = item.findChildContent("text", Namespace.VCARD4);
                binding.command.setText(text);
            }

            return binding.getRoot();
        }

        public Uri getUri(int pos) {
            final Element item = getItem(pos);
            final String uriS = item.findChildContent("uri", Namespace.VCARD4);
            if (uriS != null) return Uri.parse(uriS).normalizeScheme();
            if (item.getName().equals("email")) return Uri.parse("mailto:" + item.findChildContent("text", Namespace.VCARD4));
            return null;
        }
    }
}
