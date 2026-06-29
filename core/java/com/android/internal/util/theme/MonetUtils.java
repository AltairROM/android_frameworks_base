/*
 * SPDX-FileCopyrightText: Altair ROM Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.util.theme;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.UserHandle;
import android.provider.Settings;

import org.json.JSONException;
import org.json.JSONObject;

public class MonetUtils {

    private static final String OVERLAY_ACCENT_COLOR = "android.theme.customization.accent_color";
    private static final String OVERLAY_SYSTEM_PALETTE = "android.theme.customization.system_palette";
    private static final String OVERLAY_THEME_STYLE = "android.theme.customization.theme_style";
    private static final String OVERLAY_ENHANCED_COLORS = "android.theme.customization.enhanced_colors";
    private static final String OVERLAY_LOCK_CLOCK_COLOR_TYPE = "android.theme.customization.lock_clock_color_type";
    private static final String OVERLAY_LOCK_CLOCK_CUSTOM_COLOR = "android.theme.customization.lock_clock_custom_color";

    public static final String ACCENT_COLOR_DEFAULT = "";
    public static final String THEME_STYLE_DEFAULT = "TONAL_SPOT";
    public static final boolean ENHANCED_COLORS_DEFAULT = false;
    public static final int LOCK_CLOCK_COLOR_DEFAULT = 0;
    public static final int LOCK_CLOCK_COLOR_ACCENT = 1;
    public static final int LOCK_CLOCK_COLOR_CUSTOM = 2;

    private Context mContext;

    public MonetUtils(Context context) {
        mContext = context;
    }

    /*
     * Private helper functions.
     */

    private JSONObject getSettingsJson() throws JSONException {
        final String overlayPackageJson = Settings.Secure.getStringForUser(
                mContext.getContentResolver(),
                Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES,
                UserHandle.USER_CURRENT);
        JSONObject object;
        if (overlayPackageJson == null || overlayPackageJson.isEmpty()) {
            return new JSONObject();
        }
        return new JSONObject(overlayPackageJson);
    }

    private void putSettingsJson(JSONObject object) {
        Settings.Secure.putStringForUser(
                mContext.getContentResolver(),
                Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES,
                object.toString(), UserHandle.USER_CURRENT);
    }

    private void setBooleanValue(String overlay, boolean value) {
        try {
            JSONObject object = getSettingsJson();
            if (!value)
                object.remove(overlay);
            else
                object.putOpt(overlay, 1);
            putSettingsJson(object);
        } catch (JSONException | IllegalArgumentException ignored) {}
    }

    private void setDoubleValue(String overlay, double value) {
        try {
            JSONObject object = getSettingsJson();
            if (value == 0)
                object.remove(overlay);
            else
                object.putOpt(overlay, value);
            putSettingsJson(object);
        } catch (JSONException | IllegalArgumentException ignored) {}
    }

    private void setIntValue(String overlay, int value) {
        try {
            JSONObject object = getSettingsJson();
            if (value == 0)
                object.remove(overlay);
            else
                object.putOpt(overlay, value);
            putSettingsJson(object);
        } catch (JSONException | IllegalArgumentException ignored) {}
    }

    private void setStringValue(String overlay, String value) {
        try {
            JSONObject object = getSettingsJson();
            if (value == null || value == "")
                object.remove(overlay);
            else
                object.putOpt(overlay, value);
            putSettingsJson(object);
        } catch (JSONException | IllegalArgumentException ignored) {}
    }

    private boolean getBooleanValue(String overlay, boolean defaultValue) {
        boolean value;

        try {
            JSONObject object = getSettingsJson();
            value = object.optInt(overlay, defaultValue ? 1 : 0) == 1;
        } catch (JSONException | IllegalArgumentException ignored) {
            value = defaultValue;
        }

        return value;
    }

    private double getDoubleValue(String overlay, double defaultValue) {
        double value;

        try {
            JSONObject object = getSettingsJson();
            value = object.optDouble(overlay, defaultValue);
        } catch (JSONException | IllegalArgumentException ignored) {
            value = defaultValue;
        }

        return value;
    }

    private int getIntValue(String overlay, int defaultValue) {
        int value;

        try {
            JSONObject object = getSettingsJson();
            value = object.optInt(overlay, defaultValue);
        } catch (JSONException | IllegalArgumentException ignored) {
            value = defaultValue;
        }

        return value;
    }

    private String getStringValue(String overlay, String defaultValue) {
        String value;

        try {
            JSONObject object = getSettingsJson();
            value = object.optString(overlay, defaultValue);
        } catch (JSONException | IllegalArgumentException ignored) {
            value = defaultValue;
        }

        return value;
    }

    /*
     * Public class functions.
     */

    // Enhanced accent colors.

    public boolean isEnhancedColorsEnabled() {
        return getBooleanValue(OVERLAY_ENHANCED_COLORS, ENHANCED_COLORS_DEFAULT);
    }

    public void setEnhancedColors(boolean enable) {
        setBooleanValue(OVERLAY_ENHANCED_COLORS, enable);
    }

    // Accent color.

    public boolean isAccentColorSet() {
        return getAccentColor() != ACCENT_COLOR_DEFAULT;
    }

    public String getAccentColor() {
        return getStringValue(OVERLAY_ACCENT_COLOR, ACCENT_COLOR_DEFAULT);
    }

    public void setAccentColor(String color) {
        setStringValue(OVERLAY_ACCENT_COLOR, color);
        setStringValue(OVERLAY_SYSTEM_PALETTE, color);
    }

    // Theme style.

    public String getThemeStyle() {
        return getStringValue(OVERLAY_THEME_STYLE, THEME_STYLE_DEFAULT);
    }

    public void setThemeStyle(String value) {
        setStringValue(OVERLAY_THEME_STYLE, value);
    }

    // Lockscreen clock color.

    public int getLockClockColorType() {
        String value = getStringValue(OVERLAY_LOCK_CLOCK_COLOR_TYPE, Integer.toString(LOCK_CLOCK_COLOR_DEFAULT));
        return Integer.valueOf(value);
    }

    public void setLockClockColorType(int value) {
        setStringValue(OVERLAY_LOCK_CLOCK_COLOR_TYPE, Integer.toString(value));
    }

    public String getLockClockCustomColor() {
        return getStringValue(OVERLAY_LOCK_CLOCK_CUSTOM_COLOR, ACCENT_COLOR_DEFAULT);
    }

    public void setLockClockCustomColor(String color) {
        setStringValue(OVERLAY_LOCK_CLOCK_CUSTOM_COLOR, color);
    }
}
