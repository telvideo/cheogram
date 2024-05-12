package eu.siacs.conversations.ui.adapter;

import android.app.PendingIntent;
import android.content.Context;
import android.content.IntentSender;
import android.content.res.ColorStateList;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.widget.TextView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Ints;

import java.util.ArrayList;
import java.util.List;

import org.openintents.openpgp.util.OpenPgpUtils;

import eu.siacs.conversations.R;
import eu.siacs.conversations.crypto.PgpEngine;
import eu.siacs.conversations.databinding.ItemContactBinding;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.ConferenceDetailsActivity;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.ui.util.AvatarWorkerTask;
import eu.siacs.conversations.ui.util.MucDetailsContextMenuHelper;
import eu.siacs.conversations.utils.Compatibility;
import eu.siacs.conversations.xmpp.Jid;

public class UserAdapter extends ListAdapter<MucOptions.User, UserAdapter.ViewHolder> implements View.OnCreateContextMenuListener {

    static final DiffUtil.ItemCallback<MucOptions.User> DIFF = new DiffUtil.ItemCallback<MucOptions.User>() {
        @Override
        public boolean areItemsTheSame(@NonNull MucOptions.User a, @NonNull MucOptions.User b) {
            final Jid fullA = a.getFullJid();
            final Jid fullB = b.getFullJid();
            final Jid realA = a.getRealJid();
            final Jid realB = b.getRealJid();
            if (fullA != null && fullB != null) {
                return fullA.equals(fullB);
            } else if (realA != null && realB != null) {
                return realA.equals(realB);
            } else {
                return false;
            }
        }

        @Override
        public boolean areContentsTheSame(@NonNull MucOptions.User a, @NonNull MucOptions.User b) {
            return a.equals(b);
        }
    };
    private final boolean advancedMode;
    private MucOptions.User selectedUser = null;

    public UserAdapter(final boolean advancedMode) {
        super(DIFF);
        this.advancedMode = advancedMode;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup, int position) {
        return new ViewHolder(DataBindingUtil.inflate(LayoutInflater.from(viewGroup.getContext()), R.layout.item_contact, viewGroup, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder viewHolder, int position) {
        final MucOptions.User user = getItem(position);
        AvatarWorkerTask.loadAvatar(user, viewHolder.binding.contactPhoto, R.dimen.avatar);
        viewHolder.binding.getRoot().setOnClickListener(v -> {
            final XmppActivity activity = XmppActivity.find(v);
            if (activity == null) {
                return;
            }
            final var contact = user.getContact();
            if (user.getRole() == MucOptions.Role.NONE && contact != null) {
                Toast.makeText(
                                activity,
                                activity.getString(
                                        R.string.user_has_left_conference,
                                        contact.getDisplayName()),
                                Toast.LENGTH_SHORT)
                        .show();
            }
            activity.highlightInMuc(user.getConversation(), user.getName());
        });
        viewHolder.binding.getRoot().setTag(user);
        viewHolder.binding.getRoot().setOnCreateContextMenuListener(this);
        viewHolder.binding.getRoot().setOnLongClickListener(v -> {
            selectedUser = user;
            return false;
        });
        final String name = user.getNick();
        final Contact contact = user.getContact();
        viewHolder.binding.contactJid.setVisibility(View.GONE);
        viewHolder.binding.contactJid.setText("");
        if (contact != null) {
            final String displayName = contact.getDisplayName();
            viewHolder.binding.contactDisplayName.setText(displayName);
            if (name != null && !name.equals(displayName)) {
                viewHolder.binding.contactJid.setVisibility(View.VISIBLE);
                viewHolder.binding.contactJid.setText(name);
            }
        } else {
            viewHolder.binding.contactDisplayName.setText(Strings.nullToEmpty(name));
            viewHolder.binding.contactJid.setText(ConferenceDetailsActivity.getStatus(viewHolder.binding.getRoot().getContext(), user, advancedMode));
        }
        if (advancedMode && user.getPgpKeyId() != 0) {
            viewHolder.binding.key.setVisibility(View.VISIBLE);
            viewHolder.binding.key.setOnClickListener(v -> {
                final XmppActivity activity = XmppActivity.find(v);
                final XmppConnectionService service = activity == null ? null : activity.xmppConnectionService;
                final PgpEngine pgpEngine = service == null ? null : service.getPgpEngine();
                if (pgpEngine != null) {
                    PendingIntent intent = pgpEngine.getIntentForKey(user.getPgpKeyId());
                    if (intent != null) {
                        try {
                            activity.startIntentSenderForResult(intent.getIntentSender(), 0, null, 0, 0, 0, Compatibility.pgpStartIntentSenderOptions());
                        } catch (IntentSender.SendIntentException ignored) {

                        }
                    }
                }
            });
            viewHolder.binding.key.setText(OpenPgpUtils.convertKeyIdToHex(user.getPgpKeyId()));
        } else {
            viewHolder.binding.key.setVisibility(View.GONE);
        }

        final Context context = viewHolder.binding.getRoot().getContext();
        final LayoutInflater inflater = LayoutInflater.from(context);
        viewHolder.binding.tags.setVisibility(View.VISIBLE);
        viewHolder.binding.tags.removeViews(1, viewHolder.binding.tags.getChildCount() - 1);
        final ImmutableList.Builder<Integer> viewIdBuilder = new ImmutableList.Builder<>();
        for (MucOptions.Hat hat : getPseudoHats(viewHolder.binding.getRoot().getContext(), user)) {
            final String tag = hat.toString();
            final TextView tv = (TextView) inflater.inflate(R.layout.list_item_tag, viewHolder.binding.tags, false);
            tv.setText(tag);
            tv.setBackgroundTintList(ColorStateList.valueOf(MaterialColors.harmonizeWithPrimary(context,hat.getColor())));
            final int id = ViewCompat.generateViewId();
            tv.setId(id);
            viewIdBuilder.add(id);
            viewHolder.binding.tags.addView(tv);
        }
        for (MucOptions.Hat hat : user.getHats()) {
            final String tag = hat.toString();
            final TextView tv = (TextView) inflater.inflate(R.layout.list_item_tag, viewHolder.binding.tags, false);
            tv.setText(tag);
            tv.setBackgroundTintList(ColorStateList.valueOf(MaterialColors.harmonizeWithPrimary(context,hat.getColor())));
            final int id = ViewCompat.generateViewId();
            tv.setId(id);
            viewIdBuilder.add(id);
            viewHolder.binding.tags.addView(tv);
        }
        viewHolder.binding.flowWidget.setReferencedIds(Ints.toArray(viewIdBuilder.build()));

        if (viewHolder.binding.tags.getChildCount() < 1) {
            viewHolder.binding.contactJid.setVisibility(View.VISIBLE);
            viewHolder.binding.tags.setVisibility(View.GONE);
        }
    }

    private List<MucOptions.Hat> getPseudoHats(Context context, MucOptions.User user) {
        List<MucOptions.Hat> hats = new ArrayList<>();
        if (user.getAffiliation() != MucOptions.Affiliation.NONE) {
            hats.add(new MucOptions.Hat(null, context.getString(user.getAffiliation().getResId())));
        }
        if (user.getRole() != MucOptions.Role.PARTICIPANT) {
            hats.add(new MucOptions.Hat(null, context.getString(user.getRole().getResId())));
        }
        return hats;
    }

    public MucOptions.User getSelectedUser() {
        return selectedUser;
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        MucDetailsContextMenuHelper.onCreateContextMenu(menu,v);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {

        private final ItemContactBinding binding;

        private ViewHolder(ItemContactBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
