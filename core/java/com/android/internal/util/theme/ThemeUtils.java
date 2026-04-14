/*
 * SPDX-FileCopyrightText: Altair ROM Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.util.theme;

import static android.os.UserHandle.USER_SYSTEM;

import android.content.ContentResolver;
import android.content.Context;
import android.content.om.IOverlayManager;
import android.content.om.OverlayInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.PathShape;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.PathParser;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
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

    private static ThemeUtils instance;

    private final WeakReference<Context> mContext;
    private final IOverlayManager mOverlayManager;
    private final PackageManager pm;

    private ThemeUtils(Context context) {
        mContext = new WeakReference<>(context);
        mOverlayManager = IOverlayManager.Stub
                .asInterface(ServiceManager.getService(Context.OVERLAY_SERVICE));
        pm = context.getPackageManager();
    }

    public static ThemeUtils getInstance(Context context) {
        if (instance == null) {
            synchronized (ThemeUtils.class) {
                if (instance == null) {
                    instance = new ThemeUtils(context);
                }
            }
        }
        return instance;
    }

    public void setOverlayEnabled(String category, String packageName) {
		setOverlayEnabled(category, packageName, "android");
	}

    public void setOverlayEnabled(String category, String packageName, String target) {
        final String currentPackageName = getOverlayInfos(category, target).stream()
                .filter(OverlayInfo::isEnabled)
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
        Context context = mContext.get();
        if (context == null) return;
        final String overlayPackageJson = Settings.Secure.getStringForUser(
                context.getContentResolver(),
                Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES,
                UserHandle.USER_CURRENT);
        JSONObject object;
        try {
            object = overlayPackageJson == null
                    ? new JSONObject()
                    : new JSONObject(overlayPackageJson);
            if (disable) {
                if (object.has(category)) object.remove(category);
            } else {
                object.put(category, packageName);
            }
            Settings.Secure.putStringForUser(context.getContentResolver(),
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
        List<String> packages = new ArrayList<>();
        overlays.add(target);
        for (OverlayInfo info : getOverlayInfos(category, target)) {
            if (category.equals(info.getCategory())) {
                packages.add(info.getPackageName());
            }
        }
        Collections.sort(packages);
        overlays.addAll(packages);
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
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException while getting overlay info: " + e.getMessage(), e);
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
        final List<Typeface> fontList = new ArrayList<>();
        for (String overlayPackage : getOverlayPackagesForCategory(FONT_KEY)) {
            try {
                Resources overlayRes = getResourcesForPackage(overlayPackage);
                int fontId = overlayRes.getIdentifier(
                        "config_bodyFontFamily", "string", overlayPackage);
                if (fontId != 0) {
                    String fontName = overlayRes.getString(fontId);
                    fontList.add(Typeface.create(fontName, Typeface.NORMAL));
                }
            } catch (NameNotFoundException | Resources.NotFoundException e) {
                Log.e(TAG, "Error fetching fonts for package: " + overlayPackage, e);
            }
        }
        return fontList;
    }

    public List<ShapeDrawable> getShapeDrawables() {
        final List<ShapeDrawable> shapeList = new ArrayList<>();
        for (String overlayPackage : getOverlayPackagesForCategory(ICON_SHAPE_KEY)) {
            ShapeDrawable drawable = createShapeDrawable(overlayPackage);
            if (drawable != null) {
                shapeList.add(drawable);
            }
        }
        return shapeList;
    }

    public ShapeDrawable createShapeDrawable(String overlayPackage) {
        try {
            if (overlayPackage.equals("default")) overlayPackage = "android";
            Resources overlayRes = getResourcesForPackage(overlayPackage);
            String shape = overlayRes.getString(overlayRes.getIdentifier(
                    "config_icon_mask", "string", overlayPackage));
            if (!TextUtils.isEmpty(shape)) {
                Path path = PathParser.createPathFromPathData(shape);
                PathShape pathShape = new PathShape(path, 100f, 100f);
                ShapeDrawable shapeDrawable = new ShapeDrawable(pathShape);
                Context context = mContext.get();
                if (context != null) {
                    int thumbSize = (int) (context.getResources()
                            .getDisplayMetrics().density * 72);
                    shapeDrawable.setIntrinsicHeight(thumbSize);
                    shapeDrawable.setIntrinsicWidth(thumbSize);
                }
                return shapeDrawable;
            }
        } catch (NameNotFoundException | Resources.NotFoundException e) {
            Log.e(TAG, "Error creating shape drawable for package: " + overlayPackage, e);
        }
        return null;
    }

    public boolean isOverlayEnabled(String overlayPackage) {
        try {
            OverlayInfo info = mOverlayManager.getOverlayInfo(overlayPackage, USER_SYSTEM);
            return info != null && info.isEnabled();
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException while checking if overlay is enabled: "
                    + e.getMessage(), e);
        }
        return false;
    }

    public boolean isDefaultOverlay(String category) {
        return getOverlayPackagesForCategory(category).stream()
                .noneMatch(pkg -> isOverlayEnabled(pkg));
    }

    private Resources getResourcesForPackage(String overlayPackage)
            throws NameNotFoundException {
        return overlayPackage.equals("android")
                ? Resources.getSystem()
                : pm.getResourcesForApplication(overlayPackage);
    }
}
