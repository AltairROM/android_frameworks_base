/*
 * Copyright (C) 2021 The Proton AOSP Project
 * Copyright (C) 2022 Altair ROM Project
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

package com.android.systemui.theme

import android.annotation.ColorInt
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.content.om.FabricatedOverlay
import android.content.res.Resources
import android.os.Handler
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import android.util.TypedValue

import com.android.systemui.Dependency
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.monet.Style
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.theme.ThemeOverlayApplier
import com.android.systemui.theme.ThemeOverlayController
import com.android.systemui.tuner.TunerService
import com.android.systemui.tuner.TunerService.Tunable
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SystemSettings

import dev.kdrag0n.colorkt.Color
import dev.kdrag0n.colorkt.cam.Zcam
import dev.kdrag0n.colorkt.conversion.ConversionGraph.convert
import dev.kdrag0n.colorkt.data.Illuminants
import dev.kdrag0n.colorkt.rgb.Srgb
import dev.kdrag0n.colorkt.tristimulus.CieXyzAbs.Companion.toAbs
import dev.kdrag0n.colorkt.ucs.lab.CieLab

import dev.kdrag0n.monet.theme.DynamicColorScheme
import dev.kdrag0n.monet.theme.MaterialYouTargets

import java.util.concurrent.Executor
import javax.inject.Inject
import kotlin.math.log10
import kotlin.math.pow

@SysUISingleton
class CustomThemeOverlayController @Inject constructor(
    private val context: Context,
    broadcastDispatcher: BroadcastDispatcher,
    @Background bgHandler: Handler,
    @Main mainExecutor: Executor,
    @Background bgExecutor: Executor,
    themeOverlayApplier: ThemeOverlayApplier,
    secureSettings: SecureSettings,
    systemSettings: SystemSettings,
    wallpaperManager: WallpaperManager,
    userManager: UserManager,
    configurationController: ConfigurationController,
    deviceProvisionedController: DeviceProvisionedController,
    userTracker: UserTracker,
    dumpManager: DumpManager,
    featureFlags: FeatureFlags,
    @Main resources: Resources,
    wakefulnessLifecycle: WakefulnessLifecycle
) : ThemeOverlayController(
    context,
    broadcastDispatcher,
    bgHandler,
    mainExecutor,
    bgExecutor,
    themeOverlayApplier,
    secureSettings,
    systemSettings,
    wallpaperManager,
    userManager,
    configurationController,
    deviceProvisionedController,
    userTracker,
    dumpManager,
    featureFlags,
    resources,
    wakefulnessLifecycle,
), Tunable {
    private lateinit var cond: Zcam.ViewingConditions
    private lateinit var targets: MaterialYouTargets

    private var chromaFactor: Double = Double.MIN_VALUE
    private var accurateShades: Boolean = true
    private var whiteLuminance: Double = Double.MIN_VALUE
    private var linearLightness: Boolean = false
    private var colorAccent: Int = 0
    private var richerColors: Boolean = false
    private var tintSurface: Boolean = true

    private val mTunerService: TunerService = Dependency.get(TunerService::class.java)

    override fun start() {
        mTunerService.addTunable(this, PREF_COLOR_OVERRIDE, PREF_WHITE_LUMINANCE,
                PREF_CHROMA_FACTOR, PREF_ACCURATE_SHADES, PREF_LINEAR_LIGHTNESS, PREF_CUSTOM_COLOR,
                PREF_COLOR_ACCENT, PREF_RICHER_COLORS, PREF_TINT_SURFACE)
        super.start()
    }

    override fun onTuningChanged(key: String?, newValue: String?) {
        key?.let {
            if (it.contains(PREF_PREFIX)) {
                colorAccent = Settings.Secure.getInt(mContext.contentResolver,
                        PREF_COLOR_ACCENT, 0)
                // If monet_engine_color_accent == 0, use legacy color override (if enabled)
                if (colorAccent == 0 && Settings.Secure.getInt(mContext.contentResolver,
                        PREF_CUSTOM_COLOR, 0) == 1) {
                    colorAccent = Settings.Secure.getInt(mContext.contentResolver,
                            PREF_COLOR_OVERRIDE, 0)
                }
                tintSurface = Settings.Secure.getInt(mContext.contentResolver,
                        PREF_TINT_SURFACE, 1) == 1
                chromaFactor = (Settings.Secure.getFloat(mContext.contentResolver,
                        PREF_CHROMA_FACTOR, CHROMA_FACTOR_DEFAULT) / 100f).toDouble()
                accurateShades = Settings.Secure.getInt(mContext.contentResolver,
                        PREF_ACCURATE_SHADES, 1) == 1
                richerColors = Settings.Secure.getInt(mContext.contentResolver,
                        PREF_RICHER_COLORS, 0) != 0
                whiteLuminance = parseWhiteLuminanceUser(
                    Settings.Secure.getInt(mContext.contentResolver,
                            PREF_WHITE_LUMINANCE, WHITE_LUMINANCE_USER_DEFAULT)
                )
                linearLightness = Settings.Secure.getInt(mContext.contentResolver,
                        PREF_LINEAR_LIGHTNESS, 0) != 0

                reevaluateSystemTheme(true /* forceReload */)
            }
        }
    }

    // Seed colors
    override fun getNeutralColor(colors: WallpaperColors) = colors.primaryColor.toArgb()
    override fun getAccentColor(colors: WallpaperColors) = getNeutralColor(colors)

    override fun getOverlay(primaryColor: Int, type: Int, style: Style): FabricatedOverlay {
        cond = Zcam.ViewingConditions(
            surroundFactor = Zcam.ViewingConditions.SURROUND_AVERAGE,
            // sRGB
            adaptingLuminance = 0.4 * whiteLuminance,
            // Gray world
            backgroundLuminance = CieLab(
                L = 50.0,
                a = 0.0,
                b = 0.0,
            ).toXyz().y * whiteLuminance,
            referenceWhite = Illuminants.D65.toAbs(whiteLuminance),
        )

        targets = MaterialYouTargets(
            chromaFactor = chromaFactor,
            useLinearLightness = linearLightness,
            cond = cond,
        )

        // Generate color scheme
        val colorScheme = DynamicColorScheme(
            targets = targets,
            seedColor = if (colorAccent != 0) Srgb(colorAccent) else Srgb(primaryColor),
            chromaFactor = when (type) {
                NEUTRAL -> if (tintSurface) chromaFactor else CHROMA_FACTOR_NONE
                else -> chromaFactor
            },
            cond = cond,
            accurateShades = accurateShades,
        )

        val (groupKey, colorsList) = when (type) {
            ACCENT -> "accent" to colorScheme.accentColors
            NEUTRAL -> "neutral" to colorScheme.neutralColors
            else -> error("Unknown type $type")
        }

        return FabricatedOverlay.Builder(context.packageName, groupKey, "android").run {
            colorsList.withIndex().forEach { listEntry ->
                val group = "$groupKey${listEntry.index + 1}"

                listEntry.value.forEach { (shade, color) ->
                    val colorSrgb = color.convert<Srgb>()
                    Log.d(TAG, "Color $group $shade = ${colorSrgb.toHex()}")
                    setColor("system_${group}_$shade", colorSrgb)
                }
            }

            if (type == ACCENT) {
                if (richerColors) {
                    // Adjust system accent colors to use shades that, among other things, make colors
                    // under dark mode a little richer

                    // system_accent1 colors
                    colorsList[0][300]?.let { setColor("accent_device_default_dark", it) }
                    colorsList[0][300]?.let { setColor("accent_primary_device_default", it) }
                    colorsList[0][400]?.let { setColor("accent_primary_variant_dark_device_default", it) }
                    colorsList[0][500]?.let { setColor("accent_device_default_light", it) }
                    colorsList[0][500]?.let { setColor("accent_primary_variant_light_device_default", it) }

                    // system_accent2 colors
                    colorsList[1][300]?.let { setColor("accent_secondary_device_default", it) }
                    colorsList[1][400]?.let { setColor("accent_secondary_variant_dark_device_default", it) }
                    colorsList[1][500]?.let { setColor("accent_secondary_variant_light_device_default", it) }

                    // system_accent3 colors
                    colorsList[2][300]?.let { setColor("accent_tertiary_device_default", it) }
                    colorsList[2][400]?.let { setColor("accent_tertiary_variant_dark_device_default", it) }
                    colorsList[2][500]?.let { setColor("accent_tertiary_variant_light_device_default", it) }

                    // Holo blue colors
                    colorsList[0][200]?.let { setColor("holo_blue_bright", it) }
                    colorsList[0][300]?.let { setColor("holo_blue_light", it) }
                    colorsList[0][500]?.let { setColor("holo_blue_dark", it) }

                    // Material deep teal colors
                    colorsList[0][300]?.let { setColor("material_deep_teal_200", it) }
                    colorsList[0][500]?.let { setColor("material_deep_teal_500", it) }
                } else {
                    // Holo blue colors
                    colorsList[0][100]?.let { setColor("holo_blue_bright", it) }
                    colorsList[0][200]?.let { setColor("holo_blue_light", it) }
                    colorsList[0][500]?.let { setColor("holo_blue_dark", it) }

                    // Material deep teal colors
                    colorsList[0][200]?.let { setColor("material_deep_teal_200", it) }
                    colorsList[0][500]?.let { setColor("material_deep_teal_500", it) }
                }
            }

            // Override special modulated surface colors for performance and consistency
            if (type == NEUTRAL) {
                // surface light = neutral1 20 (L* 98)
                colorsList[0][20]?.let { setColor("surface_light", it) }

                // surface highlight dark = neutral1 650 (L* 35)
                colorsList[0][650]?.let { setColor("surface_highlight_dark", it) }

                // surface_header_dark_sysui = neutral1 950 (L* 5)
                colorsList[0][950]?.let { setColor("surface_header_dark_sysui", it) }
            }

            build()
        }
    }

    companion object {
        private const val TAG = "CustomThemeOverlayController"

        private const val PREF_PREFIX = "monet_engine"
        private const val PREF_CUSTOM_COLOR = "${PREF_PREFIX}_custom_color"
        private const val PREF_COLOR_OVERRIDE = "${PREF_PREFIX}_color_override"
        private const val PREF_CHROMA_FACTOR = "${PREF_PREFIX}_chroma_factor"
        private const val PREF_ACCURATE_SHADES = "${PREF_PREFIX}_accurate_shades"
        private const val PREF_LINEAR_LIGHTNESS = "${PREF_PREFIX}_linear_lightness"
        private const val PREF_WHITE_LUMINANCE = "${PREF_PREFIX}_white_luminance_user"
        private const val PREF_COLOR_ACCENT = "${PREF_PREFIX}_color_accent"
        private const val PREF_RICHER_COLORS = "${PREF_PREFIX}_richer_colors"
        private const val PREF_TINT_SURFACE = "${PREF_PREFIX}_tint_surface"

        private const val CHROMA_FACTOR_DEFAULT = 100.0f
        private const val CHROMA_FACTOR_NONE = 0.0

        private const val WHITE_LUMINANCE_MIN = 1.0
        private const val WHITE_LUMINANCE_MAX = 10000.0
        private const val WHITE_LUMINANCE_USER_MAX = 1000
        private const val WHITE_LUMINANCE_USER_DEFAULT = 425 // ~200.0 divisible by step (decoded = 199.526)

        private fun parseWhiteLuminanceUser(userValue: Int): Double {
            val userSrc = userValue.toDouble() / WHITE_LUMINANCE_USER_MAX
            val userInv = 1.0 - userSrc
            return (10.0).pow(userInv * log10(WHITE_LUMINANCE_MAX))
                    .coerceAtLeast(WHITE_LUMINANCE_MIN)
        }

        private fun FabricatedOverlay.Builder.setColor(name: String, @ColorInt color: Int) =
            setResourceValue("android:color/$name", TypedValue.TYPE_INT_COLOR_ARGB8, color)

        private fun FabricatedOverlay.Builder.setColor(name: String, color: Color): FabricatedOverlay.Builder {
            val rgb = color.convert<Srgb>().toRgb8()
            val argb = rgb or (0xff shl 24)
            return setColor(name, argb)
        }
    }
}
