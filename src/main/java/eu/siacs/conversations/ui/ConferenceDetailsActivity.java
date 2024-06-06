package eu.siacs.conversations.ui;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.view.ViewCompat;
import androidx.databinding.DataBindingUtil;

import com.cheogram.android.Util;

import com.google.android.material.color.MaterialColors;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Ints;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivityMucDetailsBinding;
import eu.siacs.conversations.databinding.ThreadRowBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Bookmark;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.ListItem;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.entities.MucOptions.User;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.services.XmppConnectionService.OnConversationUpdate;
import eu.siacs.conversations.services.XmppConnectionService.OnMucRosterUpdate;
import eu.siacs.conversations.ui.adapter.MediaAdapter;
import eu.siacs.conversations.ui.adapter.UserPreviewAdapter;
import eu.siacs.conversations.ui.interfaces.OnMediaLoaded;
import eu.siacs.conversations.ui.util.Attachment;
import eu.siacs.conversations.ui.util.AvatarWorkerTask;
import eu.siacs.conversations.ui.util.GridManager;
import eu.siacs.conversations.ui.util.MenuDoubleTabUtil;
import eu.siacs.conversations.ui.util.MucConfiguration;
import eu.siacs.conversations.ui.util.MucDetailsContextMenuHelper;
import eu.siacs.conversations.ui.util.MyLinkify;
import eu.siacs.conversations.ui.util.SoftKeyboardUtils;
import eu.siacs.conversations.utils.AccountUtils;
import eu.siacs.conversations.utils.Compatibility;
import eu.siacs.conversations.utils.StringUtils;
import eu.siacs.conversations.utils.StylingHelper;
import eu.siacs.conversations.utils.UIHelper;
import eu.siacs.conversations.utils.XmppUri;
import eu.siacs.conversations.utils.XEP0392Helper;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.XmppConnection;
import me.drakeet.support.toast.ToastCompat;

import static eu.siacs.conversations.entities.Bookmark.printableValue;
import static eu.siacs.conversations.utils.StringUtils.changed;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class ConferenceDetailsActivity extends XmppActivity implements OnConversationUpdate, OnMucRosterUpdate, XmppConnectionService.OnAffiliationChanged, XmppConnectionService.OnConfigurationPushed, XmppConnectionService.OnRoomDestroy, TextWatcher, OnMediaLoaded {
    public static final String ACTION_VIEW_MUC = "view_muc";

    private Conversation mConversation;
    private ActivityMucDetailsBinding binding;
    private MediaAdapter mMediaAdapter;
    private UserPreviewAdapter mUserPreviewAdapter;
    private String uuid = null;

    private boolean mAdvancedMode = false;
    private boolean showDynamicTags = true;

    private final UiCallback<Conversation> renameCallback = new UiCallback<Conversation>() {
        @Override
        public void success(Conversation object) {
            displayToast(getString(R.string.your_nick_has_been_changed));
            runOnUiThread(() -> {
                updateView();
            });

        }

        @Override
        public void error(final int errorCode, Conversation object) {
            displayToast(getString(errorCode));
        }

        @Override
        public void userInputRequired(PendingIntent pi, Conversation object) {

        }
    };

    public static void open(final Activity activity, final Conversation conversation) {
        Intent intent = new Intent(activity, ConferenceDetailsActivity.class);
        intent.setAction(ConferenceDetailsActivity.ACTION_VIEW_MUC);
        intent.putExtra("uuid", conversation.getUuid());
        activity.startActivity(intent);
    }

    private final OnClickListener mNotifyStatusClickListener = new OnClickListener() {
        @Override
        public void onClick(View v) {
            final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(ConferenceDetailsActivity.this);
            builder.setTitle(R.string.pref_notification_settings);
            String[] choices = {
                    getString(R.string.notify_on_all_messages),
                    getString(R.string.notify_only_when_highlighted),
                    getString(R.string.notify_only_when_highlighted_or_replied),
                    getString(R.string.notify_never)
            };
            final AtomicInteger choice;
            if (mConversation.getLongAttribute(Conversation.ATTRIBUTE_MUTED_TILL, 0) == Long.MAX_VALUE) {
                choice = new AtomicInteger(3);
            } else {
                choice = new AtomicInteger(mConversation.alwaysNotify() ? 0 : (mConversation.notifyReplies() ? 2 : 1));
            }
            builder.setSingleChoiceItems(choices, choice.get(), (dialog, which) -> choice.set(which));
            builder.setNegativeButton(R.string.cancel, null);
            builder.setPositiveButton(R.string.ok, (dialog, which) -> {
                if (choice.get() == 3) {
                    mConversation.setMutedTill(Long.MAX_VALUE);
                } else {
                    mConversation.setMutedTill(0);
                    mConversation.setAttribute(Conversation.ATTRIBUTE_ALWAYS_NOTIFY, String.valueOf(choice.get() == 0));
                    mConversation.setAttribute(Conversation.ATTRIBUTE_NOTIFY_REPLIES, String.valueOf(choice.get() == 2));
                }
                xmppConnectionService.updateConversation(mConversation);
                updateView();
            });
            builder.create().show();
        }
    };

    private final OnClickListener mChangeConferenceSettings = new OnClickListener() {
        @Override
        public void onClick(View v) {
            final MucOptions mucOptions = mConversation.getMucOptions();
            final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(ConferenceDetailsActivity.this);
            MucConfiguration configuration = MucConfiguration.get(ConferenceDetailsActivity.this, mAdvancedMode, mucOptions);
            builder.setTitle(configuration.title);
            final boolean[] values = configuration.values;
            builder.setMultiChoiceItems(configuration.names, values, (dialog, which, isChecked) -> values[which] = isChecked);
            builder.setNegativeButton(R.string.cancel, null);
            builder.setPositiveButton(R.string.confirm, (dialog, which) -> {
                final Bundle options = configuration.toBundle(values);
                options.putString("muc#roomconfig_persistentroom", "1");
                options.putString("{http://prosody.im/protocol/muc}roomconfig_allowmemberinvites", options.getString("muc#roomconfig_allowinvites"));
                xmppConnectionService.pushConferenceConfiguration(mConversation,
                        options,
                        ConferenceDetailsActivity.this);
            });
            builder.create().show();
        }
    };


    @Override
    public void onConversationUpdate() {
        refreshUi();
    }

    @Override
    public void onMucRosterUpdate() {
        refreshUi();
    }

    @Override
    protected void refreshUiReal() {
        updateView();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_muc_details);
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        showDynamicTags = preferences.getBoolean("show_dynamic_tags", getResources().getBoolean(R.bool.show_dynamic_tags));
        Activities.setStatusAndNavigationBarColors(this, binding.getRoot());
        this.binding.changeConferenceButton.setOnClickListener(this.mChangeConferenceSettings);
        setSupportActionBar(binding.toolbar);
        configureActionBar(getSupportActionBar());
        this.binding.editNickButton.setOnClickListener(v -> quickEdit(mConversation.getMucOptions().getActualNick(),
                R.string.nickname,
                value -> {
                    if (xmppConnectionService.renameInMuc(mConversation, value, renameCallback)) {
                        return null;
                    } else {
                        return getString(R.string.invalid_muc_nick);
                    }
                }));
        this.mAdvancedMode = getPreferences().getBoolean("advanced_muc_mode", false);
        this.binding.mucInfoMore.setVisibility(this.mAdvancedMode ? View.VISIBLE : View.GONE);
        this.binding.notificationStatusButton.setOnClickListener(this.mNotifyStatusClickListener);
        this.binding.yourPhoto.setOnClickListener(v -> {
            final MucOptions mucOptions = mConversation.getMucOptions();
            if (!mucOptions.hasVCards()) {
                Toast.makeText(this, R.string.host_does_not_support_group_chat_avatars, Toast.LENGTH_SHORT).show();
                return;
            }
            if (!mucOptions.getSelf().getAffiliation().ranks(MucOptions.Affiliation.OWNER)) {
                Toast.makeText(this, R.string.only_the_owner_can_change_group_chat_avatar, Toast.LENGTH_SHORT).show();
                return;
            }
            final Intent intent = new Intent(this, PublishGroupChatProfilePictureActivity.class);
            intent.putExtra("uuid", mConversation.getUuid());
            startActivity(intent);
        });
        this.binding.yourPhoto.setOnLongClickListener(v -> {
            PopupMenu popupMenu = new PopupMenu(this, v);
            popupMenu.inflate(R.menu.conference_photo);
            popupMenu.setOnMenuItemClickListener(menuItem -> {
                switch (menuItem.getItemId()) {
                    case R.id.action_block_avatar:
                        new AlertDialog.Builder(this)
                            .setTitle(R.string.block_media)
                            .setMessage("Do you really want to block this avatar?")
                            .setPositiveButton(R.string.yes, (dialog, whichButton) -> {
                                    xmppConnectionService.blockMedia(xmppConnectionService.getFileBackend().getAvatarFile(mConversation.getContact().getAvatarFilename()));
                                    xmppConnectionService.getFileBackend().getAvatarFile(mConversation.getContact().getAvatarFilename()).delete();
                                    avatarService().clear(mConversation);
                                    mConversation.getContact().setAvatar(null);
                                    xmppConnectionService.updateConversationUi();
                            })
                            .setNegativeButton(R.string.no, null).show();
                        return true;
                }
                return true;
            });
            popupMenu.show();
            return true;
        });
        this.binding.editMucNameButton.setOnClickListener(this::onMucEditButtonClicked);
        this.binding.mucEditTitle.addTextChangedListener(this);
        this.binding.mucEditSubject.addTextChangedListener(this);
        //this.binding.mucEditSubject.addTextChangedListener(new StylingHelper.MessageEditorStyler(this.binding.mucEditSubject));
        this.binding.editTags.addTextChangedListener(this);
        this.mMediaAdapter = new MediaAdapter(this, R.dimen.media_size);
        this.mUserPreviewAdapter = new UserPreviewAdapter();
        this.binding.media.setAdapter(mMediaAdapter);
        this.binding.users.setAdapter(mUserPreviewAdapter);
        GridManager.setupLayoutManager(this, this.binding.media, R.dimen.media_size);
        GridManager.setupLayoutManager(this, this.binding.users, R.dimen.media_size);
        this.binding.recentThreads.setOnItemClickListener((a0, v, pos, a3) -> {
            final Conversation.Thread thread = (Conversation.Thread) binding.recentThreads.getAdapter().getItem(pos);
            switchToConversation(mConversation, null, false, null, false, true, null, thread.getThreadId());
        });
        this.binding.invite.setOnClickListener(v -> inviteToConversation(mConversation));
        this.binding.showUsers.setOnClickListener(v -> {
            Intent intent = new Intent(this, MucUsersActivity.class);
            intent.putExtra("uuid", mConversation.getUuid());
            startActivity(intent);
        });
        this.binding.relatedMucs.setOnClickListener(v -> {
            final Intent intent = new Intent(this, ChannelDiscoveryActivity.class);
            intent.putExtra("services", new String[]{ mConversation.getJid().getDomain().toEscapedString(), mConversation.getAccount().getJid().toEscapedString() });
            startActivity(intent);
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        binding.mediaWrapper.setVisibility(Compatibility.hasStoragePermission(this) ? View.VISIBLE : View.GONE);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem menuItem) {
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
            case R.id.action_save_as_bookmark:
                saveAsBookmark();
                break;
            case R.id.action_destroy_room:
                destroyRoom();
                break;
            case R.id.action_advanced_mode:
                this.mAdvancedMode = !menuItem.isChecked();
                menuItem.setChecked(this.mAdvancedMode);
                getPreferences().edit().putBoolean("advanced_muc_mode", mAdvancedMode).apply();
                final boolean online = mConversation != null && mConversation.getMucOptions().online();
                this.binding.mucInfoMore.setVisibility(this.mAdvancedMode && online ? View.VISIBLE : View.GONE);
                invalidateOptionsMenu();
                updateView();
                break;
        }
        return super.onOptionsItemSelected(menuItem);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        final User user = mUserPreviewAdapter.getSelectedUser();
        if (user == null) {
            Toast.makeText(this, R.string.unable_to_perform_this_action, Toast.LENGTH_SHORT).show();
            return true;
        }
        if (!MucDetailsContextMenuHelper.onContextItemSelected(item, mUserPreviewAdapter.getSelectedUser(), this)) {
            return super.onContextItemSelected(item);
        }
        return true;
    }

    public void onMucEditButtonClicked(View v) {
        if (this.binding.mucEditor.getVisibility() == View.GONE) {
            final MucOptions mucOptions = mConversation.getMucOptions();
            this.binding.mucEditor.setVisibility(View.VISIBLE);
            this.binding.mucDisplay.setVisibility(View.GONE);
            this.binding.editMucNameButton.setImageResource(R.drawable.ic_cancel_24dp);
            final String name = mucOptions.getName();
            this.binding.mucEditTitle.setText("");
            final boolean owner = mucOptions.getSelf().getAffiliation().ranks(MucOptions.Affiliation.OWNER);
            if (owner || printableValue(name)) {
                this.binding.mucEditTitle.setVisibility(View.VISIBLE);
                if (name != null) {
                    this.binding.mucEditTitle.append(name);
                }
            } else {
                this.binding.mucEditTitle.setVisibility(View.GONE);
            }
            this.binding.mucEditTitle.setEnabled(owner);
            final String subject = mucOptions.getSubject();
            this.binding.mucEditSubject.setText("");
            if (subject != null) {
                this.binding.mucEditSubject.append(subject);
            }
            this.binding.mucEditSubject.setEnabled(mucOptions.canChangeSubject());
            if (!owner) {
                this.binding.mucEditSubject.requestFocus();
            }

            final Bookmark bookmark = mConversation.getBookmark();
            if (bookmark != null && mConversation.getAccount().getXmppConnection().getFeatures().bookmarks2() && showDynamicTags) {
                for (final ListItem.Tag group : bookmark.getGroupTags()) {
                    binding.editTags.addObjectSync(group);
                }
                ArrayList<ListItem.Tag> tags = new ArrayList<>();
                for (final Account account : xmppConnectionService.getAccounts()) {
                    for (Contact contact : account.getRoster().getContacts()) {
                        tags.addAll(contact.getTags(this));
                    }
                    for (Bookmark bmark : account.getBookmarks()) {
                        tags.addAll(bmark.getTags(this));
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
                this.binding.editTags.setVisibility(View.VISIBLE);
            } else {
                this.binding.editTags.setVisibility(View.GONE);
            }
        } else {
            String subject = this.binding.mucEditSubject.isEnabled() ? this.binding.mucEditSubject.getEditableText().toString().trim() : null;
            String name = this.binding.mucEditTitle.isEnabled() ? this.binding.mucEditTitle.getEditableText().toString().trim() : null;
            onMucInfoUpdated(subject, name);

            final Bookmark bookmark = mConversation.getBookmark();
            if (bookmark != null && mConversation.getAccount().getXmppConnection().getFeatures().bookmarks2()) {
                bookmark.setGroups(binding.editTags.getObjects().stream().map(tag -> tag.getName()).collect(Collectors.toList()));
                xmppConnectionService.createBookmark(bookmark.getAccount(), bookmark);
            }

            SoftKeyboardUtils.hideSoftKeyboard(this);
            hideEditor();
            updateView();
        }
    }

    private void hideEditor() {
        this.binding.mucEditor.setVisibility(View.GONE);
        this.binding.mucDisplay.setVisibility(View.VISIBLE);
        this.binding.editMucNameButton.setImageResource(R.drawable.ic_edit_24dp);
    }

    private void onMucInfoUpdated(String subject, String name) {
        final MucOptions mucOptions = mConversation.getMucOptions();
        if (mucOptions.canChangeSubject() && changed(mucOptions.getSubject(), subject)) {
            xmppConnectionService.pushSubjectToConference(mConversation, subject);
        }
        if (mucOptions.getSelf().getAffiliation().ranks(MucOptions.Affiliation.OWNER) && changed(mucOptions.getName(), name)) {
            Bundle options = new Bundle();
            options.putString("muc#roomconfig_persistentroom", "1");
            options.putString("muc#roomconfig_roomname", StringUtils.nullOnEmpty(name));
            xmppConnectionService.pushConferenceConfiguration(mConversation, options, this);
        }
    }


    @Override
    protected String getShareableUri(boolean http) {
        if (mConversation != null) {
            if (http) {
                return "https://conversations.im/j/" + XmppUri.lameUrlEncode(mConversation.getJid().asBareJid().toEscapedString());
            } else {
                return "xmpp:" + Uri.encode(mConversation.getJid().asBareJid().toEscapedString(), "@/+") + "?join";
            }
        } else {
            return null;
        }
    }

    @Override
    public boolean onPrepareOptionsMenu(final Menu menu) {
        final MenuItem menuItemSaveBookmark = menu.findItem(R.id.action_save_as_bookmark);
        final MenuItem menuItemAdvancedMode = menu.findItem(R.id.action_advanced_mode);
        final MenuItem menuItemDestroyRoom = menu.findItem(R.id.action_destroy_room);
        menuItemAdvancedMode.setChecked(mAdvancedMode);
        if (mConversation == null) {
            return true;
        }
        menuItemSaveBookmark.setVisible(mConversation.getBookmark() == null);
        menuItemDestroyRoom.setVisible(mConversation.getMucOptions().getSelf().getAffiliation().ranks(MucOptions.Affiliation.OWNER));
        return true;
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        final boolean groupChat = mConversation != null && mConversation.isPrivateAndNonAnonymous();
        getMenuInflater().inflate(R.menu.muc_details, menu);
        final MenuItem share = menu.findItem(R.id.action_share);
        share.setVisible(!groupChat);
        final MenuItem destroy = menu.findItem(R.id.action_destroy_room);
        destroy.setTitle(groupChat ? R.string.destroy_room : R.string.destroy_channel);
        AccountUtils.showHideMenuItems(menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public void onMediaLoaded(List<Attachment> attachments) {
        runOnUiThread(() -> {
            int limit = GridManager.getCurrentColumnCount(binding.media);
            mMediaAdapter.setAttachments(attachments.subList(0, Math.min(limit, attachments.size())));
            binding.mediaWrapper.setVisibility(attachments.size() > 0 ? View.VISIBLE : View.GONE);
        });

    }


    protected void saveAsBookmark() {
        xmppConnectionService.saveConversationAsBookmark(mConversation, mConversation.getMucOptions().getName());
    }

    protected void destroyRoom() {
        final boolean groupChat = mConversation != null && mConversation.isPrivateAndNonAnonymous();
        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle(groupChat ? R.string.destroy_room : R.string.destroy_channel);
        builder.setMessage(groupChat ? R.string.destroy_room_dialog : R.string.destroy_channel_dialog);
        builder.setPositiveButton(R.string.ok, (dialog, which) -> {
            xmppConnectionService.destroyRoom(mConversation, ConferenceDetailsActivity.this);
        });
        builder.setNegativeButton(R.string.cancel, null);
        final AlertDialog dialog = builder.create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
    }

    @Override
    protected void onBackendConnected() {
        if (mPendingConferenceInvite != null) {
            mPendingConferenceInvite.execute(this);
            mPendingConferenceInvite = null;
        }
        if (getIntent().getAction().equals(ACTION_VIEW_MUC)) {
            this.uuid = getIntent().getExtras().getString("uuid");
        }
        if (uuid != null) {
            this.mConversation = xmppConnectionService.findConversationByUuid(uuid);
            if (this.mConversation != null) {
                if (Compatibility.hasStoragePermission(this)) {
                    final int limit = GridManager.getCurrentColumnCount(this.binding.media);
                    xmppConnectionService.getAttachments(this.mConversation, limit, this);
                    this.binding.showMedia.setOnClickListener((v) -> MediaBrowserActivity.launch(this, mConversation));
                }

                binding.storeInCache.setChecked(mConversation.storeInCache());
                binding.storeInCache.setOnCheckedChangeListener((v, checked) -> {
                    mConversation.setStoreInCache(checked);
                    xmppConnectionService.updateConversation(mConversation);
                });

                updateView();
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (this.binding.mucEditor.getVisibility() == View.VISIBLE) {
            hideEditor();
        } else {
            super.onBackPressed();
        }
    }

    private void updateView() {
        invalidateOptionsMenu();
        if (mConversation == null) {
            return;
        }
        final MucOptions mucOptions = mConversation.getMucOptions();
        final User self = mucOptions.getSelf();
        final String account = mConversation.getAccount().getJid().asBareJid().toEscapedString();
        setTitle(mucOptions.isPrivateAndNonAnonymous() ? R.string.action_muc_details : R.string.channel_details);
        final Bookmark bookmark = mConversation.getBookmark();
        final XmppConnection connection = mConversation.getAccount().getXmppConnection();
        this.binding.editMucNameButton.setVisibility((self.getAffiliation().ranks(MucOptions.Affiliation.OWNER) || mucOptions.canChangeSubject() || (bookmark != null && connection != null && connection.getFeatures().bookmarks2())) ? View.VISIBLE : View.GONE);
        this.binding.detailsAccount.setText(getString(R.string.using_account, account));
        this.binding.truejid.setVisibility(View.GONE);
        if (mConversation.isPrivateAndNonAnonymous()) {
            this.binding.jid.setText(getString(R.string.hosted_on, mConversation.getJid().getDomain()));
            this.binding.truejid.setText(mConversation.getJid().asBareJid().toEscapedString());
            if (mAdvancedMode) this.binding.truejid.setVisibility(View.VISIBLE);
        } else {
            this.binding.jid.setText(mConversation.getJid().asBareJid().toEscapedString());
        }
        AvatarWorkerTask.loadAvatar(mConversation, binding.yourPhoto, R.dimen.avatar_on_details_screen_size);
        String roomName = mucOptions.getName();
        String subject = mucOptions.getSubject();
        final boolean hasTitle;
        if (printableValue(roomName)) {
            this.binding.mucTitle.setText(roomName);
            this.binding.mucTitle.setVisibility(View.VISIBLE);
            hasTitle = true;
        } else if (!printableValue(subject)) {
            this.binding.mucTitle.setText(mConversation.getName());
            hasTitle = true;
            this.binding.mucTitle.setVisibility(View.VISIBLE);
        } else {
            hasTitle = false;
            this.binding.mucTitle.setVisibility(View.GONE);
        }
        if (printableValue(subject)) {
            SpannableStringBuilder spannable = new SpannableStringBuilder(subject);
            StylingHelper.format(spannable, this.binding.mucSubject.getCurrentTextColor());
            MyLinkify.addLinks(spannable, false);
            this.binding.mucSubject.setText(spannable);
            this.binding.mucSubject.setTextAppearance( subject.length() > (hasTitle ? 128 : 196) ? com.google.android.material.R.style.TextAppearance_Material3_BodyMedium : com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
            this.binding.mucSubject.setAutoLinkMask(0);
            this.binding.mucSubject.setVisibility(View.VISIBLE);
            this.binding.mucSubject.setMovementMethod(LinkMovementMethod.getInstance());
        } else {
            this.binding.mucSubject.setVisibility(View.GONE);
        }
        this.binding.mucYourNick.setText(mucOptions.getActualNick());
        if (mucOptions.online()) {
            this.binding.usersWrapper.setVisibility(View.VISIBLE);
            this.binding.mucInfoMore.setVisibility(this.mAdvancedMode ? View.VISIBLE : View.GONE);
            this.binding.mucRole.setVisibility(View.VISIBLE);
            this.binding.mucRole.setText(getStatus(self));
            if (mucOptions.getSelf().getAffiliation().ranks(MucOptions.Affiliation.OWNER)) {
                this.binding.mucSettings.setVisibility(View.VISIBLE);
                this.binding.mucConferenceType.setText(MucConfiguration.describe(this, mucOptions));
            } else if (!mucOptions.isPrivateAndNonAnonymous() && mucOptions.nonanonymous()) {
                this.binding.mucSettings.setVisibility(View.VISIBLE);
                this.binding.mucConferenceType.setText(R.string.group_chat_will_make_your_jabber_id_public);
            } else {
                this.binding.mucSettings.setVisibility(View.GONE);
            }
            if (mucOptions.mamSupport()) {
                this.binding.mucInfoMam.setText(R.string.server_info_available);
            } else {
                this.binding.mucInfoMam.setText(R.string.server_info_unavailable);
            }
            if (self.getAffiliation().ranks(MucOptions.Affiliation.OWNER)) {
                this.binding.changeConferenceButton.setVisibility(View.VISIBLE);
            } else {
                this.binding.changeConferenceButton.setVisibility(View.INVISIBLE);
            }
        } else {
            this.binding.usersWrapper.setVisibility(View.GONE);
            this.binding.mucInfoMore.setVisibility(View.GONE);
            this.binding.mucSettings.setVisibility(View.GONE);
        }

        final long mutedTill = mConversation.getLongAttribute(Conversation.ATTRIBUTE_MUTED_TILL, 0);
        if (mutedTill == Long.MAX_VALUE) {
            this.binding.notificationStatusText.setText(R.string.notify_never);
            this.binding.notificationStatusButton.setImageResource(R.drawable.ic_notifications_off_24dp);
        } else if (System.currentTimeMillis() < mutedTill) {
            this.binding.notificationStatusText.setText(R.string.notify_paused);
            this.binding.notificationStatusButton.setImageResource(R.drawable.ic_notifications_paused_24dp);
        } else if (mConversation.alwaysNotify()) {
            this.binding.notificationStatusText.setText(R.string.notify_on_all_messages);
            this.binding.notificationStatusButton.setImageResource(R.drawable.ic_notifications_24dp);
        } else if (mConversation.notifyReplies()) {
            this.binding.notificationStatusText.setText(R.string.notify_only_when_highlighted_or_replied);
            this.binding.notificationStatusButton.setImageResource(R.drawable.ic_notifications_none_24dp);
        } else {
            this.binding.notificationStatusText.setText(R.string.notify_only_when_highlighted);
            this.binding.notificationStatusButton.setImageResource(R.drawable.ic_notifications_none_24dp);
        }
        final List<User> users = mucOptions.getUsers();
        Collections.sort(users, (a, b) -> {
            if (b.getAffiliation().outranks(a.getAffiliation())) {
                return 1;
            } else if (a.getAffiliation().outranks(b.getAffiliation())) {
                return -1;
            } else {
                if (a.getAvatar() != null && b.getAvatar() == null) {
                    return -1;
                } else if (a.getAvatar() == null && b.getAvatar() != null) {
                    return 1;
                } else {
                    return a.getComparableName().compareToIgnoreCase(b.getComparableName());
                }
            }
        });
        this.mUserPreviewAdapter.submitList(MucOptions.sub(users, GridManager.getCurrentColumnCount(binding.users)));
        this.binding.invite.setVisibility(mucOptions.canInvite() ? View.VISIBLE : View.GONE);
        this.binding.showUsers.setVisibility(users.size() > 0 ? View.VISIBLE : View.GONE);
        this.binding.showUsers.setText(getResources().getQuantityString(R.plurals.view_users, users.size(), users.size()));
        this.binding.usersWrapper.setVisibility(users.size() > 0 || mucOptions.canInvite() ? View.VISIBLE : View.GONE);
        if (users.size() == 0) {
            this.binding.noUsersHints.setText(mucOptions.isPrivateAndNonAnonymous() ? R.string.no_users_hint_group_chat : R.string.no_users_hint_channel);
            this.binding.noUsersHints.setVisibility(View.VISIBLE);
        } else {
            this.binding.noUsersHints.setVisibility(View.GONE);
        }

        if (bookmark == null) {
            binding.tags.setVisibility(View.GONE);
            return;
        }

        final List<Conversation.Thread> recentThreads = mConversation.recentThreads();
        if (recentThreads.isEmpty()) {
            this.binding.recentThreadsWrapper.setVisibility(View.GONE);
        } else {
            final ThreadAdapter threads = new ThreadAdapter();
            threads.addAll(recentThreads);
            this.binding.recentThreads.setAdapter(threads);
            this.binding.recentThreadsWrapper.setVisibility(View.VISIBLE);
            Util.justifyListViewHeightBasedOnChildren(binding.recentThreads);
        }

        final List<ListItem.Tag> tagList = bookmark.getTags(this);
        if (tagList.isEmpty() || !this.showDynamicTags) {
            binding.tags.setVisibility(View.GONE);
        } else {
            final LayoutInflater inflater = getLayoutInflater();
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
            binding.flowWidget.setReferencedIds(Ints.toArray(viewIdBuilder.build()));
        }
    }

    public static String getStatus(Context context, User user, final boolean advanced) {
        if (advanced) {
            return String.format("%s (%s)", context.getString(user.getAffiliation().getResId()), context.getString(user.getRole().getResId()));
        } else {
            return context.getString(user.getAffiliation().getResId());
        }
    }

    private String getStatus(User user) {
        return getStatus(this, user, mAdvancedMode);
    }

    @Override
    public void onAffiliationChangedSuccessful(Jid jid) {
        refreshUi();
    }

    @Override
    public void onAffiliationChangeFailed(Jid jid, int resId) {
        displayToast(getString(resId, jid.asBareJid().toEscapedString()));
    }

    @Override
    public void onRoomDestroySucceeded() {
        finish();
    }

    @Override
    public void onRoomDestroyFailed() {
        final boolean groupChat = mConversation != null && mConversation.isPrivateAndNonAnonymous();
        displayToast(getString(groupChat ? R.string.could_not_destroy_room : R.string.could_not_destroy_channel));
    }

    @Override
    public void onPushSucceeded() {
        displayToast(getString(R.string.modified_conference_options));
    }

    @Override
    public void onPushFailed() {
        displayToast(getString(R.string.could_not_modify_conference_options));
    }

    private void displayToast(final String msg) {
        runOnUiThread(() -> {
            if (isFinishing()) {
                return;
            }
            ToastCompat.makeText(this, msg, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {

    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {

    }

    @Override
    public void afterTextChanged(Editable s) {
        if (mConversation == null) {
            return;
        }
        final MucOptions mucOptions = mConversation.getMucOptions();
        if (this.binding.mucEditor.getVisibility() == View.VISIBLE) {
            boolean subjectChanged = changed(binding.mucEditSubject.getEditableText().toString(), mucOptions.getSubject());
            boolean nameChanged = changed(binding.mucEditTitle.getEditableText().toString(), mucOptions.getName());
            final Bookmark bookmark = mConversation.getBookmark();
            if (subjectChanged || nameChanged || (bookmark != null && mConversation.getAccount().getXmppConnection().getFeatures().bookmarks2())) {
                this.binding.editMucNameButton.setImageResource(R.drawable.ic_save_24dp);
            } else {
                this.binding.editMucNameButton.setImageResource(R.drawable.ic_cancel_24dp);
            }
        }
    }

    class ThreadAdapter extends ArrayAdapter<Conversation.Thread> {
        ThreadAdapter() { super(ConferenceDetailsActivity.this, 0); }

        @Override
        public View getView(int position, View view, @NonNull ViewGroup parent) {
            final ThreadRowBinding binding = DataBindingUtil.inflate(LayoutInflater.from(parent.getContext()), R.layout.thread_row, parent, false);
            final Conversation.Thread item = getItem(position);

            binding.threadIdenticon.setColor(UIHelper.getColorForName(item.getThreadId()));
            binding.threadIdenticon.setHash(UIHelper.identiconHash(item.getThreadId()));

            binding.threadSubject.setText(item.getDisplay());

            return binding.getRoot();
        }
    }
}
