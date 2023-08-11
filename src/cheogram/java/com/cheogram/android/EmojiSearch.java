package com.cheogram.android;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;

import androidx.databinding.DataBindingUtil;

import com.google.common.collect.Lists;
import com.google.common.io.CharStreams;

import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.Comparable;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import me.xdrop.fuzzywuzzy.FuzzySearch;
import me.xdrop.fuzzywuzzy.algorithms.WeightedRatio;
import me.xdrop.fuzzywuzzy.model.BoundExtractedResult;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.EmojiSearchRowBinding;
import eu.siacs.conversations.utils.ReplacingSerialSingleThreadExecutor;

public class EmojiSearch {
	protected final Set<Emoji> emoji = new TreeSet<>();

	public EmojiSearch(Context context) {
		try {
			final JSONArray data = new JSONArray(CharStreams.toString(new InputStreamReader(context.getResources().openRawResource(R.raw.emoji), "UTF-8")));
			for (int i = 0; i < data.length(); i++) {
				emoji.add(new Emoji(data.getJSONObject(i)));
			}
		} catch (final JSONException | IOException e) {
			throw new IllegalStateException("emoji.json invalid: " + e);
		}
	}

	public synchronized void addEmoji(final Emoji one) {
		emoji.add(one);
	}

	public synchronized List<Emoji> find(final String q) {
		final Set<Emoji> emoticon = new TreeSet<>();
		for (Emoji e : emoji) {
			if (e.emoticonMatch(q)) {
				emoticon.add(e);
			}
		}

		WeightedRatio wr = new WeightedRatio();
		List<BoundExtractedResult<Emoji>> result = FuzzySearch.extractTop(
			q,
			emoji,
			(e) -> e.fuzzyFind,
			(query, s) -> {
				int score = 0;
				String[] kinds = s.split(">");
				for (int i = 0; i < kinds.length; i++) {
					int nscore = Collections.max(Lists.transform(Arrays.asList(kinds[i].split("~")), (x) -> wr.apply(query, x))) - (i * 2);
					if (nscore > score) score = nscore;
				}
				return score;
			},
			10
		);

		List<Emoji> lst = new ArrayList<>(emoticon);
		lst.addAll(Lists.transform(result, (r) -> r.getReferent()));

		List<Emoji> scanList = new ArrayList<>(lst);
		int inserted = 0;
		for (int i = 0; i < scanList.size(); i++) {
			for (Emoji e : emoji) {
				if (e.shortcodeMatch(scanList.get(i).uniquePart())) {
					inserted ++;
					lst.add(i + inserted, e);
				}
			}
		}
		return lst;
	}

	public EmojiSearchAdapter makeAdapter(Activity context) {
		return new EmojiSearchAdapter(context);
	}

	public static class Emoji implements Comparable<Emoji> {
		protected final String unicode;
		protected final int order;
		protected final List<String> tags = new ArrayList<>();
		protected final List<String> emoticon = new ArrayList<>();
		protected final List<String> shortcodes = new ArrayList<>();
		protected final String fuzzyFind;

		public Emoji(final String unicode, final int order, final String fuzzyFind) {
			this.unicode = unicode;
			this.order = order;
			this.fuzzyFind = fuzzyFind;
		}

		public Emoji(JSONObject o) throws JSONException {
			unicode = o.getString("unicode");
			order = o.getInt("order");
			final JSONArray rawTags = o.getJSONArray("tags");
			for (int i = 0; i < rawTags.length(); i++) {
				tags.add(rawTags.getString(i));
			}
			final JSONArray rawEmoticon = o.getJSONArray("emoticon");
			for (int i = 0; i < rawEmoticon.length(); i++) {
				emoticon.add(rawEmoticon.getString(i));
			}
			final JSONArray rawShortcodes = o.getJSONArray("shortcodes");
			for (int i = 0; i < rawShortcodes.length(); i++) {
				shortcodes.add(rawShortcodes.getString(i));
			}
			fuzzyFind = String.join("~", shortcodes) + ">" + String.join("~", tags);
		}

		public boolean emoticonMatch(final String q) {
			for (final String emote : emoticon) {
				if (emote.equals(q) || emote.equals(":" + q)) return true;
			}

			return false;
		}

		public boolean shortcodeMatch(final String q) {
			for (final String shortcode : shortcodes) {
				if (shortcode.equals(q)) return true;
			}

			return false;
		}

		public SpannableStringBuilder toInsert() {
			return new SpannableStringBuilder(unicode);
		}

		public String uniquePart() {
			return unicode;
		}

		@Override
		public int compareTo(Emoji o) {
			if (equals(o)) return 0;
			if (order == o.order) return uniquePart().compareTo(o.uniquePart());
			return order - o.order;
		}

		@Override
		public boolean equals(Object o) {
			if (!(o instanceof Emoji)) return false;

			return uniquePart().equals(((Emoji) o).uniquePart());
		}
	}

	public static class CustomEmoji extends Emoji {
		protected final String source;
		protected final Drawable icon;

		public CustomEmoji(final String shortcode, final String source, final Drawable icon, final String tag) {
			super(null, 10, shortcode + ">" + tag);
			shortcodes.add(shortcode);
			if (tag != null) tags.add(tag);
			this.source = source;
			this.icon = icon;
			if (icon == null) {
				throw new IllegalArgumentException("icon must not be null");
			}
		}

		public SpannableStringBuilder toInsert() {
			SpannableStringBuilder builder = new SpannableStringBuilder(":" + shortcodes.get(0) + ":");
			builder.setSpan(new InlineImageSpan(icon, source), 0, builder.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
			return builder;
		}

		@Override
		public String uniquePart() {
			return source;
		}
	}

	public class EmojiSearchAdapter extends ArrayAdapter<Emoji> {
		ReplacingSerialSingleThreadExecutor executor = new ReplacingSerialSingleThreadExecutor("EmojiSearchAdapter");

		public EmojiSearchAdapter(Activity context) {
			super(context, 0);
		}

		@Override
		public View getView(int position, View view, ViewGroup parent) {
			EmojiSearchRowBinding binding = DataBindingUtil.inflate(LayoutInflater.from(parent.getContext()), R.layout.emoji_search_row, parent, false);
			if (getItem(position) instanceof CustomEmoji) {
				binding.nonunicode.setText(getItem(position).toInsert());
				binding.nonunicode.setVisibility(View.VISIBLE);
				binding.unicode.setVisibility(View.GONE);
			} else {
				binding.unicode.setText(getItem(position).toInsert());
				binding.unicode.setVisibility(View.VISIBLE);
				binding.nonunicode.setVisibility(View.GONE);
			}
			binding.shortcode.setText(getItem(position).shortcodes.get(0));
			return binding.getRoot();
		}

		public void search(final String q) {
			executor.execute(() -> {
				final List<Emoji> results = find(q);
				((Activity) getContext()).runOnUiThread(() -> {
					clear();
					addAll(results);
					notifyDataSetChanged();
				});
			});
		}
	}
}
