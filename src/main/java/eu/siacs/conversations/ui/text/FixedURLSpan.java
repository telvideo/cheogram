/*
 * Copyright (c) 2017, Daniel Gultsch All rights reserved.
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

package eu.siacs.conversations.ui.text;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.text.Editable;
import android.text.Spanned;
import android.text.style.URLSpan;
import android.view.SoundEffectConstants;
import android.view.View;
import android.widget.Toast;

import com.cheogram.android.BrowserHelper;

import java.util.Arrays;

import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.ui.ConversationsActivity;
import eu.siacs.conversations.ui.ShowLocationActivity;

@SuppressLint("ParcelCreator")
public class FixedURLSpan extends URLSpan {

	protected final Account account;

	public FixedURLSpan(String url) {
		this(url, null);
	}

	public FixedURLSpan(String url, Account account) {
		super(url);
		this.account = account;
	}

    public static void fix(final Editable editable) {
        for (final URLSpan urlspan : editable.getSpans(0, editable.length() - 1, URLSpan.class)) {
            final int start = editable.getSpanStart(urlspan);
            final int end = editable.getSpanEnd(urlspan);
            editable.removeSpan(urlspan);
            editable.setSpan(
                    new FixedURLSpan(urlspan.getURL()),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

	@Override
	public void onClick(View widget) {
		final Uri uri = Uri.parse(getURL());
		final Context context = widget.getContext();
		final boolean candidateToProcessDirectly = "xmpp".equals(uri.getScheme()) || ("https".equals(uri.getScheme()) && "conversations.im".equals(uri.getHost()) && uri.getPathSegments().size() > 1 && Arrays.asList("j","i").contains(uri.getPathSegments().get(0)));
		if (candidateToProcessDirectly && context instanceof ConversationsActivity) {
			if (((ConversationsActivity) context).onXmppUriClicked(uri)) {
				widget.playSoundEffect(SoundEffectConstants.CLICK);
				return;
			}
		}

		if (("sms".equals(uri.getScheme()) || "tel".equals(uri.getScheme())) && context instanceof ConversationsActivity) {
			if (((ConversationsActivity) context).onTelUriClicked(uri, account)) {
				widget.playSoundEffect(SoundEffectConstants.CLICK);
				return;
			}
		}

		if ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) {
			widget.playSoundEffect(SoundEffectConstants.CLICK);
			BrowserHelper.launchUri(context, uri);
			return;
		}

		final Intent intent = new Intent(Intent.ACTION_VIEW, uri);
		if ("geo".equalsIgnoreCase(uri.getScheme())) {
			intent.setClass(context, ShowLocationActivity.class);
		} else {
			intent.setFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
		}
		try {
			context.startActivity(intent);
			widget.playSoundEffect(SoundEffectConstants.CLICK);
		} catch (ActivityNotFoundException e) {
			Toast.makeText(context, R.string.no_application_found_to_open_link, Toast.LENGTH_SHORT).show();
		}
	}
}
