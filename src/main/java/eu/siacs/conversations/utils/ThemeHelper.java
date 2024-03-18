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

package eu.siacs.conversations.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.loader.ResourcesLoader;
import android.os.Build;
import android.preference.PreferenceManager;
import android.util.Log;
import android.util.TypedValue;
import android.widget.TextView;

import androidx.annotation.StyleRes;
import androidx.core.content.ContextCompat;

import com.cheogram.android.ColorResourcesLoaderCreator;

import com.google.android.material.snackbar.Snackbar;

import java.util.HashMap;

import eu.siacs.conversations.R;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.ui.SettingsActivity;

public class ThemeHelper {

	public static HashMap<Integer, Integer> applyCustomColors(final Context context) {
		HashMap<Integer, Integer> colors = new HashMap<>();
		if (Build.VERSION.SDK_INT < 30) return colors;

		final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
		if (sharedPreferences.contains("custom_theme_primary")) colors.put(R.color.custom_theme_primary, sharedPreferences.getInt("custom_theme_primary", 0));
		if (sharedPreferences.contains("custom_theme_primary_dark")) colors.put(R.color.custom_theme_primary_dark, sharedPreferences.getInt("custom_theme_primary_dark", 0));
		if (sharedPreferences.contains("custom_theme_accent")) colors.put(R.color.custom_theme_accent, sharedPreferences.getInt("custom_theme_accent", 0));
		if (sharedPreferences.contains("custom_theme_background_primary")) {
			int background_primary = sharedPreferences.getInt("custom_theme_background_primary", 0);
			int alpha = (background_primary >> 24) & 0xFF;
			int red = (background_primary >> 16) & 0xFF;
			int green = (background_primary >> 8) & 0xFF;
			int blue = background_primary & 0xFF;
			colors.put(R.color.custom_theme_background_primary, background_primary);
			colors.put(R.color.custom_theme_background_secondary, (int)((alpha << 24) | ((int)(red*.9) << 16) | ((int)(green*.9) << 8) | (int)(blue*.9)));
			colors.put(R.color.custom_theme_background_tertiary, (int)((alpha << 24) | ((int)(red*.85) << 16) | ((int)(green*.85) << 8) | (int)(blue*.85)));
		}
		if (sharedPreferences.contains("custom_dark_theme_primary")) colors.put(R.color.custom_dark_theme_primary, sharedPreferences.getInt("custom_dark_theme_primary", 0));
		if (sharedPreferences.contains("custom_dark_theme_primary_dark")) colors.put(R.color.custom_dark_theme_primary_dark, sharedPreferences.getInt("custom_dark_theme_primary_dark", 0));
		if (sharedPreferences.contains("custom_dark_theme_accent")) colors.put(R.color.custom_dark_theme_accent, sharedPreferences.getInt("custom_dark_theme_accent", 0));
		if (sharedPreferences.contains("custom_dark_theme_background_primary")) {
			int background_primary = sharedPreferences.getInt("custom_dark_theme_background_primary", 0);
			int alpha = (background_primary >> 24) & 0xFF;
			int red = (background_primary >> 16) & 0xFF;
			int green = (background_primary >> 8) & 0xFF;
			int blue = background_primary & 0xFF;
			colors.put(R.color.custom_dark_theme_background_primary, background_primary);
			colors.put(R.color.custom_dark_theme_background_secondary, (int)((alpha << 24) | ((int)(red*.5) << 16) | ((int)(green*.5) << 8) | (int)(blue*.5)));
			colors.put(R.color.custom_dark_theme_background_tertiary, (int)((alpha << 24) | ((int)(40 + red*.84) << 16) | ((int)(40 + green*.84) << 8) | (int)(40 + blue*.84)));
		}
		if (colors.isEmpty()) return colors;

		ResourcesLoader loader = ColorResourcesLoaderCreator.create(context, colors);
		try {
			if (loader != null) context.getResources().addLoaders(loader);
		} catch (final IllegalArgumentException e) {
			Log.w(Config.LOGTAG, "Custom colour failed: " + e);
		}
		return colors;
	}

	public static int find(final Context context) {
		final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
		final Resources resources = context.getResources();
		final String setting = sharedPreferences.getString(SettingsActivity.THEME, resources.getString(R.string.theme));
		final boolean dark = isDark(sharedPreferences, resources);
		final String fontSize = sharedPreferences.getString("font_size", resources.getString(R.string.default_font_size));
		switch (fontSize) {
			case "medium":
				if ("obsidian".equals(setting)) return R.style.ConversationsTheme_Obsidian_Medium;
				else if ("oledblack".equals(setting)) return R.style.ConversationsTheme_OLEDBlack_Medium;
				else if ("custom".equals(setting)) return dark ? R.style.ConversationsTheme_CustomDark_Medium : R.style.ConversationsTheme_Custom_Medium;
				return dark ? R.style.ConversationsTheme_Dark_Medium : R.style.ConversationsTheme_Medium;
			case "large":
				if ("obsidian".equals(setting)) return R.style.ConversationsTheme_Obsidian_Large;
				else if ("oledblack".equals(setting)) return R.style.ConversationsTheme_OLEDBlack_Large;
				else if ("custom".equals(setting)) return dark ? R.style.ConversationsTheme_CustomDark_Large : R.style.ConversationsTheme_Custom_Large;
				return dark ? R.style.ConversationsTheme_Dark_Large : R.style.ConversationsTheme_Large;
			default:
				if ("obsidian".equals(setting)) return R.style.ConversationsTheme_Obsidian;
				else if ("oledblack".equals(setting)) return R.style.ConversationsTheme_OLEDBlack;
				else if ("custom".equals(setting)) return dark ? R.style.ConversationsTheme_CustomDark : R.style.ConversationsTheme_Custom;
				return dark ? R.style.ConversationsTheme_Dark : R.style.ConversationsTheme;
		}
	}

	public static int findDialog(Context context) {
		final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
		final Resources resources = context.getResources();
		final boolean dark = isDark(sharedPreferences, resources);
		final String fontSize = sharedPreferences.getString("font_size", resources.getString(R.string.default_font_size));
		switch (fontSize) {
			case "medium":
				return dark ? R.style.ConversationsTheme_Dark_Dialog_Medium : R.style.ConversationsTheme_Dialog_Medium;
			case "large":
				return dark ? R.style.ConversationsTheme_Dark_Dialog_Large : R.style.ConversationsTheme_Dialog_Large;
			default:
				return dark ? R.style.ConversationsTheme_Dark_Dialog : R.style.ConversationsTheme_Dialog;
		}
	}

	private static boolean isDark(final SharedPreferences sharedPreferences, final Resources resources) {
		final String setting = sharedPreferences.getString(SettingsActivity.THEME, resources.getString(R.string.theme));
		if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && "automatic".equals(setting)) ||
			("custom".equals(setting) && sharedPreferences.getBoolean("custom_theme_automatic", false))
		) {
			return (resources.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
		} else {
			if ("custom".equals(setting)) return sharedPreferences.getBoolean("custom_theme_dark", false);
			return "dark".equals(setting) || "obsidian".equals(setting) || "oledblack".equals(setting);
		}
	}

	public static boolean isDark(@StyleRes int id) {
		switch (id) {
			case R.style.ConversationsTheme_Dark:
			case R.style.ConversationsTheme_Dark_Large:
			case R.style.ConversationsTheme_Dark_Medium:
			case R.style.ConversationsTheme_CustomDark:
			case R.style.ConversationsTheme_CustomDark_Large:
			case R.style.ConversationsTheme_CustomDark_Medium:
			case R.style.ConversationsTheme_Obsidian:
			case R.style.ConversationsTheme_Obsidian_Large:
			case R.style.ConversationsTheme_Obsidian_Medium:
			case R.style.ConversationsTheme_OLEDBlack:
			case R.style.ConversationsTheme_OLEDBlack_Large:
			case R.style.ConversationsTheme_OLEDBlack_Medium:
				return true;
			default:
				return false;
		}
	}

	public static void fix(Snackbar snackbar) {
		final Context context = snackbar.getContext();
		TypedArray typedArray = context.obtainStyledAttributes(new int[]{R.attr.TextSizeBody1});
		final float size = typedArray.getDimension(0,0f);
		typedArray.recycle();
		if (size != 0f) {
			final TextView text = snackbar.getView().findViewById(com.google.android.material.R.id.snackbar_text);
			final TextView action = snackbar.getView().findViewById(com.google.android.material.R.id.snackbar_action);
			if (text != null && action != null) {
				text.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
				action.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
				action.setTextColor(ContextCompat.getColor(context, R.color.blue_a100));
			}
		}
	}
}
