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

import com.google.android.material.color.MaterialColors;

import java.util.HashMap;

import eu.siacs.conversations.R;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.Conversations;

public class ThemeHelper {

	public static HashMap<Integer, Integer> applyCustomColors(final Context context) {
		HashMap<Integer, Integer> colors = new HashMap<>();
		if (Build.VERSION.SDK_INT < 30) return colors;
		if (!Conversations.isCustomColorsDesired(context)) return colors;

		final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
		final var colorMatch = sharedPreferences.getBoolean("custom_theme_color_match", false);
		if (sharedPreferences.contains("custom_theme_primary")) {
			final var base = sharedPreferences.getInt("custom_theme_primary", 0);
			final var roles = MaterialColors.getColorRoles(base, true);
			colors.put(R.color.md_theme_light_primary, colorMatch ? base : roles.getAccent());
			colors.put(R.color.md_theme_light_onPrimary, roles.getOnAccent());
			colors.put(R.color.md_theme_light_primaryContainer, colorMatch ? base : roles.getAccentContainer());
			colors.put(R.color.md_theme_light_onPrimaryContainer, roles.getOnAccentContainer());
		}
		if (sharedPreferences.contains("custom_theme_primary_dark")) {
			final var base = sharedPreferences.getInt("custom_theme_primary_dark", 0);
			final var roles = MaterialColors.getColorRoles(base, true);
			colors.put(R.color.md_theme_light_secondary, colorMatch ? base : roles.getAccent());
			colors.put(R.color.md_theme_light_onSecondary, roles.getOnAccent());
			colors.put(R.color.md_theme_light_secondaryContainer, colorMatch ? base : roles.getAccentContainer());
			colors.put(R.color.md_theme_light_onSecondaryContainer, roles.getOnAccentContainer());
		}
		if (sharedPreferences.contains("custom_theme_accent")) {
			final var base = sharedPreferences.getInt("custom_theme_accent", 0);
			final var roles = MaterialColors.getColorRoles(base, true);
			colors.put(R.color.md_theme_light_tertiary, colorMatch ? base : roles.getAccent());
			colors.put(R.color.md_theme_light_onTertiary, roles.getOnAccent());
			colors.put(R.color.md_theme_light_tertiaryContainer, colorMatch ? base : roles.getAccentContainer());
			colors.put(R.color.md_theme_light_onTertiaryContainer, roles.getOnAccentContainer());
		}
		if (sharedPreferences.contains("custom_theme_background_primary")) {
			int background_primary = sharedPreferences.getInt("custom_theme_background_primary", 0);
			int alpha = (background_primary >> 24) & 0xFF;
			int red = (background_primary >> 16) & 0xFF;
			int green = (background_primary >> 8) & 0xFF;
			int blue = background_primary & 0xFF;
			colors.put(R.color.md_theme_light_background, background_primary);
			colors.put(R.color.md_theme_light_surface, background_primary);
			//colors.put(R.color.md_theme_light_surface, (int)((alpha << 24) | ((int)(red*.9) << 16) | ((int)(green*.9) << 8) | (int)(blue*.9)));
			colors.put(R.color.md_theme_light_surfaceVariant, (int)((alpha << 24) | ((int)(red*.85) << 16) | ((int)(green*.85) << 8) | (int)(blue*.85)));
		}
		if (sharedPreferences.contains("custom_dark_theme_primary")) {
			final var base = sharedPreferences.getInt("custom_dark_theme_primary", 0);
			final var roles = MaterialColors.getColorRoles(base, false);
			colors.put(R.color.md_theme_dark_primary, colorMatch ? base : roles.getAccent());
			colors.put(R.color.md_theme_dark_onPrimary, roles.getOnAccent());
			colors.put(R.color.md_theme_dark_primaryContainer, colorMatch ? base : roles.getAccentContainer());
			colors.put(R.color.md_theme_dark_onPrimaryContainer, roles.getOnAccentContainer());
		}
		if (sharedPreferences.contains("custom_dark_theme_primary_dark")) {
			final var base = sharedPreferences.getInt("custom_dark_theme_primary_dark", 0);
			final var roles = MaterialColors.getColorRoles(base, false);
			colors.put(R.color.md_theme_dark_secondary, colorMatch ? base : roles.getAccent());
			colors.put(R.color.md_theme_dark_onSecondary, roles.getOnAccent());
			colors.put(R.color.md_theme_dark_secondaryContainer, colorMatch ? base : roles.getAccentContainer());
			colors.put(R.color.md_theme_dark_onSecondaryContainer, roles.getOnAccentContainer());
		}
		if (sharedPreferences.contains("custom_dark_theme_accent")) {
			final var base = sharedPreferences.getInt("custom_dark_theme_accent", 0);
			final var roles = MaterialColors.getColorRoles(base, false);
			colors.put(R.color.md_theme_dark_tertiary, colorMatch ? base : roles.getAccent());
			colors.put(R.color.md_theme_dark_onTertiary, roles.getOnAccent());
			colors.put(R.color.md_theme_dark_tertiaryContainer, colorMatch ? base : roles.getAccentContainer());
			colors.put(R.color.md_theme_dark_onTertiaryContainer, roles.getOnAccentContainer());
		}
		if (sharedPreferences.contains("custom_dark_theme_background_primary")) {
			int background_primary = sharedPreferences.getInt("custom_dark_theme_background_primary", 0);
			int alpha = (background_primary >> 24) & 0xFF;
			int red = (background_primary >> 16) & 0xFF;
			int green = (background_primary >> 8) & 0xFF;
			int blue = background_primary & 0xFF;
			colors.put(R.color.md_theme_dark_background, background_primary);
			colors.put(R.color.md_theme_dark_surface, background_primary);
			colors.put(R.color.md_theme_dark_surfaceVariant, (int)((alpha << 24) | ((int)(40 + red*.84) << 16) | ((int)(40 + green*.84) << 8) | (int)(40 + blue*.84)));
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
}
