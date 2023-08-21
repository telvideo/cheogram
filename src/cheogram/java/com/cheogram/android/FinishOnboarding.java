package com.cheogram.android;

import android.util.Log;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.forms.Data;
import eu.siacs.conversations.xmpp.stanzas.IqPacket;

public class FinishOnboarding {
	private static final AtomicBoolean WORKING = new AtomicBoolean(false);
	private static ScheduledExecutorService SCHEDULER = Executors.newScheduledThreadPool(1);

	private static void retry(final XmppConnectionService xmppConnectionService, final XmppActivity activity, final Account onboardAccount, final Account newAccount) {
		WORKING.set(false);
		SCHEDULER.schedule(() -> {
			finish(xmppConnectionService, activity, onboardAccount, newAccount);
		}, 3, TimeUnit.SECONDS);
	}

	public static void finish(final XmppConnectionService xmppConnectionService, final XmppActivity activity, final Account onboardAccount, final Account newAccount) {
		if (!WORKING.compareAndSet(false, true)) return;

		final IqPacket packet = new IqPacket(IqPacket.TYPE.SET);
		packet.setTo(Jid.of("cheogram.com"));
		final Element c = packet.addChild("command", Namespace.COMMANDS);
		c.setAttribute("node", "change jabber id");
		c.setAttribute("action", "execute");

		Log.d(Config.LOGTAG, "" + packet);
		xmppConnectionService.sendIqPacket(onboardAccount, packet, (a, iq) -> {
			Element command = iq.findChild("command", "http://jabber.org/protocol/commands");
			if (command == null) {
				Log.e(Config.LOGTAG, "Did not get expected data form from cheogram, got: " + iq);
				retry(xmppConnectionService, activity, onboardAccount, newAccount);
				return;
			}

			Element form = command.findChild("x", "jabber:x:data");
			Data dataForm = form == null ? null : Data.parse(form);
			if (dataForm == null || dataForm.getFieldByName("new-jid") == null) {
				Log.e(Config.LOGTAG, "Did not get expected data form from cheogram, got: " + iq);
				retry(xmppConnectionService, activity, onboardAccount, newAccount);
				return;
			}

			dataForm.put("new-jid", newAccount.getJid().toEscapedString());
			dataForm.submit();
			command.setAttribute("action", "execute");
			iq.setTo(iq.getFrom());
			iq.setAttribute("type", "set");
			iq.removeAttribute("from");
			iq.removeAttribute("id");
			xmppConnectionService.sendIqPacket(a, iq, (a2, iq2) -> {
				Element command2 = iq2.findChild("command", "http://jabber.org/protocol/commands");
				if (command2 != null && command2.getAttribute("status") != null && command2.getAttribute("status").equals("completed")) {
					final IqPacket regPacket = new IqPacket(IqPacket.TYPE.SET);
					regPacket.setTo(Jid.of("cheogram.com/CHEOGRAM%jabber:iq:register"));
					final Element c2 = regPacket.addChild("command", Namespace.COMMANDS);
					c2.setAttribute("node", "jabber:iq:register");
					c2.setAttribute("action", "execute");
					xmppConnectionService.sendIqPacket(newAccount, regPacket, (a3, iq3) -> {
						Element command3 = iq3.findChild("command", "http://jabber.org/protocol/commands");
						if (command3 == null) {
							Log.e(Config.LOGTAG, "Did not get expected data form from cheogram, got: " + iq3);
							retry(xmppConnectionService, activity, onboardAccount, newAccount);
							return;
						}

						Element form3 = command3.findChild("x", "jabber:x:data");
						Data dataForm3 = form3 == null ? null : Data.parse(form3);
						if (dataForm3 == null || dataForm3.getFieldByName("confirm") == null) {
							Log.e(Config.LOGTAG, "Did not get expected data form from cheogram, got: " + iq3);
							retry(xmppConnectionService, activity, onboardAccount, newAccount);
							return;
						}

						dataForm3.put("confirm", "true");
						dataForm3.submit();
						command3.setAttribute("action", "execute");
						iq3.setTo(iq3.getFrom());
						iq3.setAttribute("type", "set");
						iq3.removeAttribute("from");
						iq3.removeAttribute("id");
						xmppConnectionService.sendIqPacket(newAccount, iq3, (a4, iq4) -> {
							Element command4 = iq4.findChild("command", "http://jabber.org/protocol/commands");
							if (command4 != null && command4.getAttribute("status") != null && command4.getAttribute("status").equals("completed")) {
								xmppConnectionService.createContact(newAccount.getRoster().getContact(iq4.getFrom().asBareJid()), true);
								Conversation withCheogram = xmppConnectionService.findOrCreateConversation(newAccount, iq4.getFrom().asBareJid(), true, true, true);
								xmppConnectionService.markRead(withCheogram);
								xmppConnectionService.clearConversationHistory(withCheogram);
								xmppConnectionService.deleteAccount(onboardAccount);
								activity.switchToConversation(withCheogram, null, false, null, false, false, "command");
								// We don't set WORKING back to false because we suceeded so it should never run again anyway
							} else {
								Log.e(Config.LOGTAG, "Error confirming jid switch, got: " + iq4);
								retry(xmppConnectionService, activity, onboardAccount, newAccount);
							}
						});
					});
				} else {
					Log.e(Config.LOGTAG, "Error during jid switch, got: " + iq2);
					retry(xmppConnectionService, activity, onboardAccount, newAccount);
				}
			});
		});
	}
}
