package eu.siacs.conversations.ui.util;

import android.app.Activity;
import android.preference.PreferenceManager;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.TypefaceSpan;
import android.util.Pair;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.databinding.DataBindingUtil;

import java.util.ArrayList;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.DialogQuickeditBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.entities.MucOptions.User;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.ConferenceDetailsActivity;
import eu.siacs.conversations.ui.ConversationFragment;
import eu.siacs.conversations.ui.ConversationsActivity;
import eu.siacs.conversations.ui.MucUsersActivity;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.utils.UIHelper;
import eu.siacs.conversations.xmpp.Jid;


public final class MucDetailsContextMenuHelper {
    private static final int ACTION_BAN = 0;
    private static final int ACTION_GRANT_MEMBERSHIP = 1;
    private static final int ACTION_REMOVE_MEMBERSHIP = 2;
    private static final int ACTION_GRANT_ADMIN = 3;
    private static final int ACTION_REMOVE_ADMIN = 4;
    private static final int ACTION_GRANT_OWNER = 5;
    private static final int ACTION_REMOVE_OWNER = 6;

    public static void onCreateContextMenu(ContextMenu menu, View v) {
        final XmppActivity activity = XmppActivity.find(v);
        final Object tag = v.getTag();
        if (tag instanceof MucOptions.User && activity != null) {
            activity.getMenuInflater().inflate(R.menu.muc_details_context, menu);
            final MucOptions.User user = (MucOptions.User) tag;
            String name;
            final Contact contact = user.getContact();
            if (contact != null && contact.showInContactList()) {
                name = contact.getDisplayName();
            } else if (user.getRealJid() != null) {
                name = user.getRealJid().asBareJid().toString();
            } else {
                name = user.getNick();
            }
            menu.setHeaderTitle(name);
            MucDetailsContextMenuHelper.configureMucDetailsContextMenu(activity, menu, user.getConversation(), user);
        }
    }

    public static Pair<CharSequence[], Integer[]> getPermissionsChoices(Activity activity, Conversation conversation, User user) {
        ArrayList<CharSequence> items = new ArrayList<>();
        ArrayList<Integer> actions = new ArrayList<>();
        final User self = conversation.getMucOptions().getSelf();
        final MucOptions mucOptions = conversation.getMucOptions();
        final boolean isGroupChat = mucOptions.isPrivateAndNonAnonymous();
        if ((self.getAffiliation().ranks(MucOptions.Affiliation.ADMIN) && self.getAffiliation().outranks(user.getAffiliation())) || self.getAffiliation() == MucOptions.Affiliation.OWNER) {
            if (!Config.DISABLE_BAN && user.getAffiliation() != MucOptions.Affiliation.OUTCAST) {
                items.add(activity.getString(isGroupChat ? R.string.ban_from_conference : R.string.ban_from_channel));
                actions.add(ACTION_BAN);
            } else if (!Config.DISABLE_BAN) {
                items.add(isGroupChat ? "Unban from group chat" : "Unban from channel");
                actions.add(ACTION_REMOVE_MEMBERSHIP);
            }
            if (!user.getAffiliation().ranks(MucOptions.Affiliation.MEMBER)) {
                items.add(activity.getString(R.string.grant_membership));
                actions.add(ACTION_GRANT_MEMBERSHIP);
            } else if (user.getAffiliation() == MucOptions.Affiliation.MEMBER) {
                items.add(activity.getString(R.string.remove_membership));
                actions.add(ACTION_REMOVE_MEMBERSHIP);
            }
        }
        if (self.getAffiliation().ranks(MucOptions.Affiliation.OWNER)) {
            if (!user.getAffiliation().ranks(MucOptions.Affiliation.ADMIN)) {
                items.add(activity.getString(R.string.grant_admin_privileges));
                actions.add(ACTION_GRANT_ADMIN);
            } else if (user.getAffiliation() == MucOptions.Affiliation.ADMIN) {
                items.add(activity.getString(R.string.remove_admin_privileges));
                actions.add(ACTION_REMOVE_ADMIN);
            }
            if (!user.getAffiliation().ranks(MucOptions.Affiliation.OWNER)) {
                items.add(activity.getString(R.string.grant_owner_privileges));
                actions.add(ACTION_GRANT_OWNER);
            } else if (user.getAffiliation() == MucOptions.Affiliation.OWNER){
                items.add(activity.getString(R.string.remove_owner_privileges));
                actions.add(ACTION_REMOVE_OWNER);
            }
        }
        return new Pair<>(items.toArray(new CharSequence[items.size()]), actions.toArray(new Integer[actions.size()]));
    }

    public static void configureMucDetailsContextMenu(XmppActivity activity, Menu menu, Conversation conversation, User user) {
        final MucOptions mucOptions = conversation.getMucOptions();
        final boolean advancedMode = PreferenceManager.getDefaultSharedPreferences(activity).getBoolean("advanced_muc_mode", false);
        final boolean isGroupChat = mucOptions.isPrivateAndNonAnonymous();
        MenuItem sendPrivateMessage = menu.findItem(R.id.send_private_message);

        MenuItem blockAvatar = menu.findItem(R.id.action_block_avatar);
        if (user != null && user.getAvatar() != null) {
            blockAvatar.setVisible(true);
        }

        MenuItem muteParticipant = menu.findItem(R.id.action_mute_participant);
        MenuItem unmuteParticipant = menu.findItem(R.id.action_unmute_participant);
        if (user != null && user.getOccupantId() != null) {
            if (activity.xmppConnectionService.isMucUserMuted(user)) {
                unmuteParticipant.setVisible(true);
            } else {
                muteParticipant.setVisible(true);
            }
        }

        if (user != null && user.getRealJid() != null) {
            MenuItem showContactDetails = menu.findItem(R.id.action_contact_details);
            MenuItem startConversation = menu.findItem(R.id.start_conversation);
            MenuItem removeFromRoom = menu.findItem(R.id.remove_from_room);
            MenuItem managePermissions = menu.findItem(R.id.manage_permissions);
            removeFromRoom.setTitle(isGroupChat ? R.string.remove_from_room : R.string.remove_from_channel);
            MenuItem invite = menu.findItem(R.id.invite);
            startConversation.setVisible(true);
            final Contact contact = user.getContact();
            final User self = conversation.getMucOptions().getSelf();
            if ((contact != null && contact.showInRoster()) || mucOptions.isPrivateAndNonAnonymous()) {
                showContactDetails.setVisible(contact == null || !contact.isSelf());
            }
            if ((activity instanceof ConferenceDetailsActivity || activity instanceof MucUsersActivity) && user.getRole() == MucOptions.Role.NONE) {
                invite.setVisible(true);
            }
            boolean managePermissionsVisible = false;
            if ((self.getAffiliation().ranks(MucOptions.Affiliation.ADMIN) && self.getAffiliation().outranks(user.getAffiliation())) || self.getAffiliation() == MucOptions.Affiliation.OWNER) {
                if (!user.getAffiliation().ranks(MucOptions.Affiliation.MEMBER)) {
                    managePermissionsVisible = true;
                } else if (user.getAffiliation() == MucOptions.Affiliation.MEMBER) {
                    managePermissionsVisible = true;
                }
                if (!Config.DISABLE_BAN) {
                    managePermissionsVisible = true;
                }
            }
            if (self.getAffiliation().ranks(MucOptions.Affiliation.OWNER)) {
                if (!user.getAffiliation().ranks(MucOptions.Affiliation.OWNER)) {
                    managePermissionsVisible = true;
                } else if (user.getAffiliation() == MucOptions.Affiliation.OWNER){
                    managePermissionsVisible = true;
                }
                if (!user.getAffiliation().ranks(MucOptions.Affiliation.ADMIN)) {
                    managePermissionsVisible = true;
                } else if (user.getAffiliation() == MucOptions.Affiliation.ADMIN) {
                    managePermissionsVisible = true;
                }
            }
            managePermissions.setVisible(managePermissionsVisible);
            sendPrivateMessage.setVisible(user.isOnline() && !isGroupChat && mucOptions.allowPm() && user.getRole().ranks(MucOptions.Role.VISITOR));
        } else {
            sendPrivateMessage.setVisible(user != null && user.isOnline());
            sendPrivateMessage.setEnabled(user != null && mucOptions.allowPm() && user.getRole().ranks(MucOptions.Role.VISITOR));
        }
    }

    public static boolean onContextItemSelected(MenuItem item, User user, XmppActivity activity) {
        return onContextItemSelected(item, user, activity, null);
    }

    public static void maybeModerateRecent(XmppActivity activity, Conversation conversation, User user) {
        if (!conversation.getMucOptions().getSelf().getRole().ranks(MucOptions.Role.MODERATOR) || !conversation.getMucOptions().hasFeature("urn:xmpp:message-moderate:0")) return;

        DialogQuickeditBinding binding = DataBindingUtil.inflate(activity.getLayoutInflater(), R.layout.dialog_quickedit, null, false);
        binding.inputEditText.setText("Spam");
        new AlertDialog.Builder(activity)
            .setTitle(R.string.moderate_recent)
            .setMessage("Do you want to moderate all recent messages from this user?")
            .setView(binding.getRoot())
            .setPositiveButton(R.string.yes, (dialog, whichButton) -> {
                for (Message m : conversation.findMessagesBy(user)) {
                    activity.xmppConnectionService.moderateMessage(conversation.getAccount(), m, binding.inputEditText.getText().toString());
                }
            })
            .setNegativeButton(R.string.no, null).show();
    }

    public static boolean onContextItemSelected(MenuItem item, User user, XmppActivity activity, final String fingerprint) {
        final Conversation conversation = user.getConversation();
        final XmppConnectionService.OnAffiliationChanged onAffiliationChanged = activity instanceof XmppConnectionService.OnAffiliationChanged ? (XmppConnectionService.OnAffiliationChanged) activity : null;
        Jid jid = user.getRealJid();
        switch (item.getItemId()) {
            case R.id.action_contact_details:
                final Jid realJid = user.getRealJid();
                final Account account = conversation.getAccount();
                final Contact contact = realJid == null ? null : account.getRoster().getContact(realJid);
                if (contact != null) {
                    activity.switchToContactDetails(contact, fingerprint);
                }
                return true;
            case R.id.action_block_avatar:
                new AlertDialog.Builder(activity)
                    .setTitle(R.string.block_media)
                    .setMessage("Do you really want to block this avatar?")
                    .setPositiveButton(R.string.yes, (dialog, whichButton) -> {
                        activity.xmppConnectionService.blockMedia(
                            activity.xmppConnectionService.getFileBackend().getAvatarFile(user.getAvatar())
                        );
                        activity.xmppConnectionService.getFileBackend().getAvatarFile(user.getAvatar()).delete();
                        activity.avatarService().clear(user);
                        if (user.getContact() != null) activity.avatarService().clear(user.getContact());
                        user.setAvatar(null);
                        activity.xmppConnectionService.updateConversationUi();
                    })
                    .setNegativeButton(R.string.no, null).show();
                return true;
            case R.id.action_mute_participant:
                if (activity.xmppConnectionService.muteMucUser(user)) {
                    activity.xmppConnectionService.updateConversationUi();
                } else {
                    Toast.makeText(activity, "Failed to mute", Toast.LENGTH_SHORT).show();
                }
                return true;
            case R.id.action_unmute_participant:
                if (activity.xmppConnectionService.unmuteMucUser(user)) {
                    activity.xmppConnectionService.updateConversationUi();
                } else {
                    Toast.makeText(activity, "Failed to unmute", Toast.LENGTH_SHORT).show();
                }
                return true;
            case R.id.start_conversation:
                startConversation(user, activity);
                return true;
            case R.id.manage_permissions:
                Pair<CharSequence[], Integer[]> choices = getPermissionsChoices(activity, conversation, user);
                int[] selected = new int[] { -1 };
                new AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.manage_permission_with_nick, UIHelper.getDisplayName(user)))
                    .setSingleChoiceItems(choices.first, -1, (dialog, whichItem) -> {
                        selected[0] = whichItem;
                    })
                    .setPositiveButton(R.string.action_complete, (dialog, whichButton) -> {
                        switch (selected[0] >= 0 ? choices.second[selected[0]] : -1) {
                            case ACTION_BAN:
                                activity.xmppConnectionService.changeAffiliationInConference(conversation, jid, MucOptions.Affiliation.OUTCAST, onAffiliationChanged);
                                if (user.getRole() != MucOptions.Role.NONE) {
                                    activity.xmppConnectionService.changeRoleInConference(conversation, user.getName(), MucOptions.Role.NONE);
                                }
                                maybeModerateRecent(activity, conversation, user);
                                break;
                            case ACTION_GRANT_MEMBERSHIP:
                            case ACTION_REMOVE_ADMIN:
                            case ACTION_REMOVE_OWNER:
                                activity.xmppConnectionService.changeAffiliationInConference(conversation, jid, MucOptions.Affiliation.MEMBER, onAffiliationChanged);
                                break;
                            case ACTION_GRANT_ADMIN:
                                activity.xmppConnectionService.changeAffiliationInConference(conversation, jid, MucOptions.Affiliation.ADMIN, onAffiliationChanged);
                                break;
                            case ACTION_GRANT_OWNER:
                                activity.xmppConnectionService.changeAffiliationInConference(conversation, jid, MucOptions.Affiliation.OWNER, onAffiliationChanged);
                                break;
                            case ACTION_REMOVE_MEMBERSHIP:
                                activity.xmppConnectionService.changeAffiliationInConference(conversation, jid, MucOptions.Affiliation.NONE, onAffiliationChanged);
                                break;
                        }
                    })
                    .setNeutralButton(R.string.cancel, null).show();
                return true;
            case R.id.remove_from_room:
                removeFromRoom(user, activity, onAffiliationChanged);
                return true;
            case R.id.send_private_message:
                if (activity instanceof ConversationsActivity) {
                    ConversationFragment conversationFragment = ConversationFragment.get(activity);
                    if (conversationFragment != null) {
                        conversationFragment.privateMessageWith(user.getFullJid());
                        return true;
                    }
                }
                activity.privateMsgInMuc(conversation, user.getName());
                return true;
            case R.id.invite:
                if (user.getAffiliation().ranks(MucOptions.Affiliation.MEMBER)) {
                    activity.xmppConnectionService.directInvite(conversation, jid.asBareJid());
                } else {
                    activity.xmppConnectionService.invite(conversation, jid);
                }
                return true;
            default:
                return false;
        }
    }

    private static void removeFromRoom(final User user, XmppActivity activity, XmppConnectionService.OnAffiliationChanged onAffiliationChanged) {
        final Conversation conversation = user.getConversation();
        if (conversation.getMucOptions().membersOnly()) {
            activity.xmppConnectionService.changeAffiliationInConference(conversation, user.getRealJid(), MucOptions.Affiliation.NONE, onAffiliationChanged);
            if (user.getRole() != MucOptions.Role.NONE) {
                activity.xmppConnectionService.changeRoleInConference(conversation, user.getName(), MucOptions.Role.NONE);
            }
        } else {
            final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity);
            builder.setTitle(R.string.ban_from_conference);
            String jid = user.getRealJid().asBareJid().toString();
            SpannableString message = new SpannableString(activity.getString(R.string.removing_from_public_conference, jid));
            int start = message.toString().indexOf(jid);
            if (start >= 0) {
                message.setSpan(new TypefaceSpan("monospace"), start, start + jid.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            builder.setMessage(message);
            builder.setNegativeButton(R.string.cancel, null);
            builder.setPositiveButton(R.string.ban_now, (dialog, which) -> {
                activity.xmppConnectionService.changeAffiliationInConference(conversation, user.getRealJid(), MucOptions.Affiliation.OUTCAST, onAffiliationChanged);
                if (user.getRole() != MucOptions.Role.NONE) {
                    activity.xmppConnectionService.changeRoleInConference(conversation, user.getName(), MucOptions.Role.NONE);
                }
            });
            builder.create().show();
        }
    }

    private static void startConversation(User user, XmppActivity activity) {
        if (user.getRealJid() != null) {
            Conversation newConversation = activity.xmppConnectionService.findOrCreateConversation(user.getAccount(), user.getRealJid().asBareJid(), false, true);
            activity.switchToConversation(newConversation);
        }
    }
}
