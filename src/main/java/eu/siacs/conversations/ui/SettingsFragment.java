package eu.siacs.conversations.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.ListView;

import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;

import com.rarepebble.colorpicker.ColorPreference;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.utils.Compatibility;

public class SettingsFragment extends PreferenceFragmentCompat {

	private String page = null;

	@Override
	public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
		setPreferencesFromResource(R.xml.preferences, rootKey);

		// Remove from standard preferences if the flag ONLY_INTERNAL_STORAGE is false
		if (!Config.ONLY_INTERNAL_STORAGE) {
			PreferenceCategory mCategory = (PreferenceCategory) findPreference("security_options");
			if (mCategory != null) {
				Preference cleanCache = findPreference("clean_cache");
				Preference cleanPrivateStorage = findPreference("clean_private_storage");
				mCategory.removePreference(cleanCache);
				mCategory.removePreference(cleanPrivateStorage);
			}
		}
		Compatibility.removeUnusedPreferences(this);

		if (!TextUtils.isEmpty(page)) {
			openPreferenceScreen(page);
		}

	}

	@Override
	public void onActivityCreated(Bundle bundle) {
		super.onActivityCreated(bundle);

		final ListView listView = getActivity().findViewById(android.R.id.list);
		if (listView != null) {
			listView.setDivider(null);
		}
	}

	@Override
	public void onDisplayPreferenceDialog(Preference preference) {
		if (preference instanceof ColorPreference) {
			((ColorPreference) preference).showDialog(this, 0);
		} else super.onDisplayPreferenceDialog(preference);
	}

	public void setActivityIntent(final Intent intent) {
		boolean wasEmpty = TextUtils.isEmpty(page);
		if (intent != null) {
			if (Intent.ACTION_VIEW.equals(intent.getAction())) {
				if (intent.getExtras() != null) {
					this.page = intent.getExtras().getString("page");
					if (wasEmpty) {
						openPreferenceScreen(page);
					}
				}
			}
		}
	}

	private void openPreferenceScreen(final String screenName) {
		final Preference pref = findPreference(screenName);
		if (pref instanceof PreferenceScreen) {
			final PreferenceScreen preferenceScreen = (PreferenceScreen) pref;
			getActivity().setTitle(preferenceScreen.getTitle());
			preferenceScreen.setDependency("");
			setPreferenceScreen((PreferenceScreen) pref);
		}
	}
}
