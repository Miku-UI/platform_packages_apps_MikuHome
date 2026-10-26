/*
 * Copyright (C) 2026 Miku UI
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.miku.android.launcher;

import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;
import static com.android.launcher3.util.Executors.MODEL_EXECUTOR;

import android.app.AlertDialog;
import android.content.Context;

import androidx.preference.Preference;

import com.android.launcher3.LauncherAppState;
import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.R;
import com.android.launcher3.WorkspaceFreeMode;
import com.android.launcher3.settings.SettingsActivity;

/**
 * Home screen settings. Items live in {@code res/xml/launcher_preferences.xml}.
 */
public class MikuLauncherSettingsFragment extends SettingsActivity.LauncherSettingsFragment {

    @Override
    protected boolean initPreference(Preference preference) {
        switch (preference.getKey()) {
            case WorkspaceFreeMode.PREF_KEY:
                preference.setOnPreferenceChangeListener((pref, newValue) -> {
                    boolean enabled = Boolean.TRUE.equals(newValue);
                    Context app = getContext().getApplicationContext();
                    LauncherPrefs.get(app).putSync(LauncherPrefs.WORKSPACE_FREE_MODE.to(enabled));
                    MODEL_EXECUTOR.execute(() -> {
                        LauncherAppState.getInstance(app).getModel()
                                .getModelDbController().switchActiveStore();
                        MAIN_EXECUTOR.execute(() -> LauncherAppState.getInstance(app).getModel()
                                .forceReload("workspace_free_mode"));
                    });
                    return true;
                });
                return true;
            case WorkspaceFreeMode.PREF_CLEAR_KEY:
                preference.setOnPreferenceClickListener(pref -> {
                    Context context = getContext();
                    if (context == null) {
                        return true;
                    }
                    new AlertDialog.Builder(context)
                            .setTitle(R.string.workspace_free_mode_clear_title)
                            .setMessage(R.string.workspace_free_mode_clear_warning)
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(R.string.workspace_free_mode_clear_confirm,
                                    (dialog, which) -> clearFreeLayoutStore(context))
                            .show();
                    return true;
                });
                return true;
            default:
                return super.initPreference(preference);
        }
    }

    private void clearFreeLayoutStore(Context context) {
        Context app = context.getApplicationContext();
        MODEL_EXECUTOR.execute(() -> {
            LauncherAppState.getInstance(app).getModel().getModelDbController().clearFreeStore();
            if (WorkspaceFreeMode.isEnabled(app)) {
                MAIN_EXECUTOR.execute(() -> LauncherAppState.getInstance(app).getModel()
                        .forceReload("workspace_free_mode_clear"));
            }
        });
    }
}
