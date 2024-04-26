package eu.siacs.conversations.entities;

import android.content.Context;

import java.io.Serializable;
import java.util.List;
import java.util.Locale;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.services.AvatarService;
import eu.siacs.conversations.xmpp.Jid;


public interface ListItem extends Comparable<ListItem>, AvatarService.Avatarable {
	String getDisplayName();

	Jid getJid();

	Account getAccount();

	List<Tag> getTags(Context context);

	final class Tag implements Serializable {
		private final String name;

		public Tag(final String name) {
			this.name = name;
		}

		public String getName() {
			return this.name;
		}

		public String toString() {
			return getName();
		}

		public boolean equals(Object o) {
			if (!(o instanceof Tag)) return false;
			Tag ot = (Tag) o;
			return name.toLowerCase(Locale.US).equals(ot.getName().toLowerCase(Locale.US));
		}

		public int hashCode() {
			return name.toLowerCase(Locale.US).hashCode();
		}
	}

	boolean match(Context context, final String needle);
}
