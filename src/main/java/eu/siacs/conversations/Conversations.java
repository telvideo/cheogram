package eu.siacs.conversations;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import androidx.appcompat.app.AppCompatDelegate;

import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.DynamicColorsOptions;

import eu.siacs.conversations.utils.ExceptionHelper;
import eu.siacs.conversations.utils.ThemeHelper;

public class Conversations extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        ExceptionHelper.init(getApplicationContext());
        applyThemeSettings();
    }

    public void applyThemeSettings() {
        final var sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        if (sharedPreferences == null) {
            return;
        }
        applyThemeSettings(sharedPreferences);
    }

    private void applyThemeSettings(final SharedPreferences sharedPreferences) {
        AppCompatDelegate.setDefaultNightMode(getDesiredNightMode(this, sharedPreferences));
        var dynamicColorsOptions =
                new DynamicColorsOptions.Builder()
                        .setPrecondition((activity, t) -> isDynamicColorsDesired(activity))
                        .build();
        DynamicColors.applyToActivitiesIfAvailable(this, dynamicColorsOptions);
    }

    public static int getDesiredNightMode(final Context context) {
        final var sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        if (sharedPreferences == null) {
            return AppCompatDelegate.getDefaultNightMode();
        }
        return getDesiredNightMode(context, sharedPreferences);
    }

    public static boolean isDynamicColorsDesired(final Context context) {
        final var preferences = PreferenceManager.getDefaultSharedPreferences(context);
        if (isCustomColorsDesired(context)) return false;
        return preferences.getBoolean(AppSettings.DYNAMIC_COLORS, false);
    }

    public static boolean isCustomColorsDesired(final Context context) {
        final var sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        final String theme =
                sharedPreferences.getString(AppSettings.THEME, context.getString(R.string.theme));
        return "custom".equals(theme);
    }

    private static int getDesiredNightMode(
            final Context context, final SharedPreferences sharedPreferences) {
        var theme =
                sharedPreferences.getString(AppSettings.THEME, context.getString(R.string.theme));

        // Migrate old themes to equivalent custom
        if ("oledblack".equals(theme)) {
            theme = "custom";
            final var p = PreferenceManager.getDefaultSharedPreferences(context);
            p
                .edit()
                .putString(AppSettings.THEME, "custom")
                .putBoolean("custom_theme_automatic", false)
                .putBoolean("custom_theme_dark", true)
                .putInt("custom_dark_theme_primary", context.getColor(R.color.white))
                .putInt("custom_dark_theme_primary_dark", context.getColor(android.R.color.black))
                .putInt("custom_dark_theme_accent", context.getColor(R.color.yeller))
                .putInt("custom_dark_theme_background_primary", context.getColor(android.R.color.black))
                .commit();
        }

        // Migrate old themes to equivalent custom
        if ("obsidian".equals(theme)) {
            theme = "custom";
            final var p = PreferenceManager.getDefaultSharedPreferences(context);
            p
                .edit()
                .putString(AppSettings.THEME, "custom")
                .putBoolean("custom_theme_automatic", false)
                .putBoolean("custom_theme_dark", true)
                .putInt("custom_dark_theme_primary", context.getColor(R.color.black_perpy))
                .putInt("custom_dark_theme_primary_dark", context.getColor(R.color.black_perpy))
                .putInt("custom_dark_theme_accent", context.getColor(R.color.yeller))
                .putInt("custom_dark_theme_background_primary", context.getColor(R.color.blacker_perpy))
                .commit();
        }

        if ("custom".equals(theme)) {
            if (sharedPreferences.getBoolean("custom_theme_automatic", false)) return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
            return sharedPreferences.getBoolean("custom_theme_dark", false) ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO;
        }

        return getDesiredNightMode(theme);
    }

    public static int getDesiredNightMode(final String theme) {
        if ("automatic".equals(theme)) {
            return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        } else if ("light".equals(theme)) {
            return AppCompatDelegate.MODE_NIGHT_NO;
        } else {
            return AppCompatDelegate.MODE_NIGHT_YES;
        }
    }
}
