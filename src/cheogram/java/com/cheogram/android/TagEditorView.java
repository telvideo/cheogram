package com.cheogram.android;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.google.android.material.color.MaterialColors;

import com.tokenautocomplete.TokenCompleteTextView;

import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.ListItem;
import eu.siacs.conversations.utils.UIHelper;
import eu.siacs.conversations.utils.XEP0392Helper;

public class TagEditorView extends TokenCompleteTextView<ListItem.Tag> {
	public TagEditorView(Context context, AttributeSet attrs) {
		super(context, attrs);
		setTokenClickStyle(TokenCompleteTextView.TokenClickStyle.Delete);
		setThreshold(1);
		performBestGuess(false);
		allowCollapse(false);
	}

	public void clearSync() {
		for (ListItem.Tag tag : getObjects()) {
			removeObjectSync(tag);
		}
	}

	@Override
	protected View getViewForObject(ListItem.Tag tag) {
		LayoutInflater inflater = (LayoutInflater) getContext().getSystemService(Activity.LAYOUT_INFLATER_SERVICE);
		final TextView tv = (TextView) inflater.inflate(R.layout.list_item_tag, (ViewGroup) getParent(), false);
		tv.setText(tag.getName());
      tv.setBackgroundTintList(ColorStateList.valueOf(MaterialColors.harmonizeWithPrimary(getContext(),XEP0392Helper.rgbFromNick(tag.getName()))));
		return tv;
	}

	@Override
	protected ListItem.Tag defaultObject(String completionText) {
		return new ListItem.Tag(completionText);
	}

	@Override
	public boolean shouldIgnoreToken(ListItem.Tag tag) {
		return getObjects().contains(tag);
	}

	@Override
	public void onFocusChanged(boolean hasFocus, int direction, Rect previous) {
		super.onFocusChanged(hasFocus, direction, previous);
		performCompletion();
	}
}
