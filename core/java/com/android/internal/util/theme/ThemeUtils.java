/*
 * SPDX-FileCopyrightText: Altair ROM Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.util.theme;

import static android.os.UserHandle.USER_SYSTEM;

import android.app.UiModeManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.om.IOverlayManager;
import android.content.om.OverlayInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ProviderInfo;
import android.content.res.Resources;
import android.content.res.Resources.NotFoundException;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.PathShape;
import android.net.Uri;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.PathParser;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class ThemeUtils {

    public static final String TAG = "ThemeUtils";

    public static final String FONT_KEY = "android.theme.customization.font";
    public static final String ICON_SHAPE_KEY= "android.theme.customization.adaptive_icon_shape";
    public static final String SIGNAL_ICON_KEY = "android.theme.customization.signal_icon";
    public static final String WIFI_ICON_KEY = "android.theme.customization.wifi_icon";
    public static final String NAVBAR_KEY = "android.theme.customization.navbar";
    public static final String DARK_THEME_KEY = "android.theme.customization.system_palette";
    public static final String LOCKSCREEN_FONT_KEY = "android.theme.customization.lockscreen_clock_font";
    public static final String QS_UI_KEY = "android.theme.customization.qs_ui";
    public static final String QS_PANEL_KEY = "android.theme.customization.qs_panel";

    public static final String TARGET_PACKAGE_ANDROID = "android";
    public static final String TARGET_PACKAGE_SYSTEMUI = "com.android.systemui";
    public static final String TARGET_PACKAGE_LAUNCHER = "com.android.launcher3";

    public static final Comparator<OverlayInfo> OVERLAY_INFO_COMPARATOR =
            Comparator.comparingInt(a -> a.priority);

    private Context mContext;
    private IOverlayManager mOverlayManager;
    private PackageManager pm;
    private Resources overlayRes;

    public ThemeUtils(Context context) {
        mContext = context;
        mOverlayManager = IOverlayManager.Stub
                .asInterface(ServiceManager.getService(Context.OVERLAY_SERVICE));
        pm = context.getPackageManager();
    }

    public void setOverlayEnabled(String category, String packageName) {
		setOverlayEnabled(category, packageName, "android");
	}

    public void setOverlayEnabled(String category, String packageName, String target) {
        final String currentPackageName = getOverlayInfos(category, target).stream()
                .filter(info -> info.isEnabled())
                .map(info -> info.packageName)
                .findFirst()
                .orElse(null);

        try {
            if (target.equals(packageName)) {
                if (currentPackageName != null) {
                    mOverlayManager.setEnabled(currentPackageName, false, USER_SYSTEM);
                }
            } else {
                mOverlayManager.setEnabledExclusiveInCategory(packageName, USER_SYSTEM);
            }

            writeSettings(category, packageName, target.equals(packageName));

        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException while setting overlay: " + e.getMessage(), e);
        }
    }

    public void writeSettings(String category, String packageName, boolean disable) {
        final String overlayPackageJson = Settings.Secure.getStringForUser(
                mContext.getContentResolver(),
                Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES, UserHandle.USER_CURRENT);
        JSONObject object;
        try {
            if (overlayPackageJson == null) {
                object = new JSONObject();
            } else {
                object = new JSONObject(overlayPackageJson);
            }
            if (disable) {
                if (object.has(category)) object.remove(category);
            } else {
                object.put(category, packageName);
            }
            Settings.Secure.putStringForUser(mContext.getContentResolver(),
                    Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES,
                    object.toString(), UserHandle.USER_CURRENT);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse THEME_CUSTOMIZATION_OVERLAY_PACKAGES.", e);
        }
    }

    public List<String> getOverlayPackagesForCategory(String category) {
        return getOverlayPackagesForCategory(category, "android");
    }

    public List<String> getOverlayPackagesForCategory(String category, String target) {
        List<String> overlays = new ArrayList<>();
        List<String> mPkgs = new ArrayList<>();
        overlays.add(target);
        for (OverlayInfo info : getOverlayInfos(category, target)) {
            if (category.equals(info.getCategory())) {
                mPkgs.add(info.getPackageName());
            }
        }
        Collections.sort(mPkgs);
        overlays.addAll(mPkgs);
        return overlays;
    }

    public List<OverlayInfo> getOverlayInfos(String category) {
        return getOverlayInfos(category, "android");
    }

    public List<OverlayInfo> getOverlayInfos(String category, String target) {
        final List<OverlayInfo> filteredInfos = new ArrayList<>();
        try {
            List<OverlayInfo> overlayInfos = mOverlayManager
                    .getOverlayInfosForTarget(target, USER_SYSTEM);
            for (OverlayInfo overlayInfo : overlayInfos) {
                if (category.equals(overlayInfo.category)) {
                    filteredInfos.add(overlayInfo);
                }
            }
        } catch (RemoteException re) {
            Log.e(TAG, "RemoteException while getting overlay info: " + re.getMessage(), re);
        }
        filteredInfos.sort(new OverlayInfoComparator());
        return filteredInfos;
    }

    class OverlayInfoComparator implements Comparator {
        public int compare(Object obj1, Object obj2) {
            OverlayInfo o1 = (OverlayInfo) obj1;
            OverlayInfo o2 = (OverlayInfo) obj2;
            return o1.packageName.compareTo(o2.packageName);
        }
    }

    public List<String> getLabels(String category) {
        return getLabels(category, "android");
    }

    public List<String> getLabels(String category, String target) {
        List<String> labels = new ArrayList<>();
        labels.add("Default");
        for (OverlayInfo info : getOverlayInfos(category, target)) {
            if (category.equals(info.getCategory())) {
                try {
                    labels.add(pm.getApplicationInfo(info.packageName, 0)
                            .loadLabel(pm).toString());
                } catch (PackageManager.NameNotFoundException e) {
                    labels.add(info.packageName);
                }
            }
        }
        return labels;
    }

    public List<Typeface> getFonts() {
        final List<Typeface> fontlist = new ArrayList<>();
        for (String overlayPackage : getOverlayPackagesForCategory(FONT_KEY)) {
        	Resources overlayRes = null;
            try {
                overlayRes = overlayPackage.equals("android") ? Resources.getSystem()
                        : pm.getResourcesForApplication(overlayPackage);
                if (overlayRes != null) {
                    int fontId = overlayRes.getIdentifier("config_bodyFontFamily", "string", overlayPackage);
                    if (fontId != 0) {
                        String fontName = overlayRes.getString(fontId);
                        fontlist.add(Typeface.create(fontName, Typeface.NORMAL));
                    }
                }
            } catch (NameNotFoundException | NotFoundException e) {
            	Log.e(TAG, "Error fetching fonts for package: " + overlayPackage, e);
            }
        }
        return fontlist;
    }

    public List<ShapeDrawable> getShapeDrawables() {
        final List<ShapeDrawable> shapelist = new ArrayList<>();
            for (String overlayPackage : getOverlayPackagesForCategory(ICON_SHAPE_KEY)) {
                    shapelist.add(createShapeDrawable(overlayPackage));
            }
        return shapelist;
    }

    public ShapeDrawable createShapeDrawable(String overlayPackage) {
        try {
            if (overlayPackage.equals("android")) {
                overlayRes = Resources.getSystem();
            } else {
                if (overlayPackage.equals("default")) {
                    overlayPackage = "android";
                }
                overlayRes = pm.getResourcesForApplication(overlayPackage);
            }
        } catch (NameNotFoundException | NotFoundException e) {
            // Do nothing
        }
        if (overlayRes == null) {
            Log.e(TAG, "Resources not found for package: " + overlayPackage);
            return null;
        }
        final String shape = overlayRes.getString(
                overlayRes.getIdentifier("config_icon_mask",
                "string", overlayPackage));
        Path path = TextUtils.isEmpty(shape) ? null : PathParser.createPathFromPathData(shape);
        PathShape pathShape = new PathShape(path, 100f, 100f);
        ShapeDrawable shapeDrawable = new ShapeDrawable(pathShape);
        int mThumbSize = (int) (mContext.getResources().getDisplayMetrics().density * 72);
        shapeDrawable.setIntrinsicHeight(mThumbSize);
        shapeDrawable.setIntrinsicWidth(mThumbSize);
        return shapeDrawable;
    }

    public boolean isOverlayEnabled(String overlayPackage) {
        try {
            OverlayInfo info = mOverlayManager.getOverlayInfo(overlayPackage, USER_SYSTEM);
            return info == null ? false : info.isEnabled();
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException while checking if overlay is enabled: " + e.getMessage(), e);
        }
        return false;
    }

    public boolean isDefaultOverlay(String category) {
        return getOverlayPackagesForCategory(category).stream()
               .noneMatch(pkg -> isOverlayEnabled(pkg));
    }
}
