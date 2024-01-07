/*
 * Copyright (c) 2018, Daniel Gultsch All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation and/or
 * other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package eu.siacs.conversations.ui.util;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.preference.PreferenceManager;

import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Presence;
import eu.siacs.conversations.ui.ConversationFragment;
import eu.siacs.conversations.utils.UIHelper;

public class SendButtonTool {

	public static SendButtonAction getAction(final Activity activity, final Conversation c, final String text) {
		if (activity == null) {
			return SendButtonAction.TEXT;
		}
		final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
		final boolean empty = text.length() == 0;
		final boolean conference = c.getMode() == Conversation.MODE_MULTI;
		if (c.getCorrectingMessage() != null && (empty || (text.equals(c.getCorrectingMessage().getBody()) && (c.getThread() == c.getCorrectingMessage().getThread() || (c.getThread() != null && c.getThread().equals(c.getCorrectingMessage().getThread())))))) {
			return SendButtonAction.CANCEL;
		} else if (conference && !c.getAccount().httpUploadAvailable()) {
			if (empty && c.getNextCounterpart() != null) {
				return SendButtonAction.CANCEL;
			} else {
				return SendButtonAction.TEXT;
			}
		} else {
			if (empty) {
				if (conference && c.getNextCounterpart() != null) {
					return SendButtonAction.CANCEL;
				} else {
					String setting = preferences.getString("quick_action", activity.getResources().getString(R.string.quick_action));
					if (!"none".equals(setting) && UIHelper.receivedLocationQuestion(c.getLatestMessage())) {
						return SendButtonAction.SEND_LOCATION;
					} else {
						if ("recent".equals(setting)) {
							setting = preferences.getString(ConversationFragment.RECENTLY_USED_QUICK_ACTION, SendButtonAction.TEXT.toString());
							return SendButtonAction.valueOfOrDefault(setting);
						} else {
							return SendButtonAction.valueOfOrDefault(setting);
						}
					}
				}
			} else {
				return SendButtonAction.TEXT;
			}
		}
	}

	public static int colorForStatus(Activity activity, Presence.Status status) {
		switch (status) {
		case CHAT:
		case ONLINE:
			return 0xff4ab04a;
		case AWAY:
			return 0xfff8b990;
		case XA:
		case DND:
			return 0xffe97975;
		default:
			return StyledAttributes.getColor(activity, R.attr.icon_tint);
		}
	}

	public static Drawable getSendButtonImageResource(Activity activity, SendButtonAction action, Presence.Status status, boolean canSend) {
		final Drawable d;
		switch (action) {
			case TEXT:
				d = canSend ?
					activity.getResources().getDrawable(R.drawable.ic_send_text_online) :
					activity.getResources().getDrawable(R.drawable.ic_attach_file_white_24dp);
				break;
			case RECORD_VIDEO:
				d = activity.getResources().getDrawable(R.drawable.ic_send_videocam_online);
				break;
			case TAKE_PHOTO:
				d = activity.getResources().getDrawable(R.drawable.ic_send_photo_online);
				break;
			case RECORD_VOICE:
				d = activity.getResources().getDrawable(R.drawable.ic_send_voice_online);
				break;
			case SEND_LOCATION:
				d = activity.getResources().getDrawable(R.drawable.ic_send_location_online);
				break;
			case CANCEL:
				d = activity.getResources().getDrawable(R.drawable.ic_send_cancel_online);
				break;
			case CHOOSE_PICTURE:
				d = activity.getResources().getDrawable(R.drawable.ic_send_picture_online);
				break;
			default:
				return null;
		}
		d.mutate().setColorFilter(colorForStatus(activity, status), PorterDuff.Mode.SRC_IN);
		return d;
	}
}
