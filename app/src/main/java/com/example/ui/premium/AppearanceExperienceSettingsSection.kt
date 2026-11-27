package com.example.ui.premium

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MovieFilter
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AmbientCinemaIntensity
import com.example.data.model.InterfaceStyle
import com.example.data.model.KrasPremiumTheme
import com.example.data.model.StartupAnimationMode
import com.example.data.model.UserSettings
import com.example.ui.components.KrasLogo
import com.example.ui.components.liquidGlassPanel
import com.example.ui.theme.KrasRed
import com.example.ui.theme.KrasRedBright
import com.example.ui.theme.KrasRedDark
import com.example.ui.theme.OutfitFontFamily
import com.example.ui.theme.TextSecondary

/**
 * Settings → Appearance & Experience section for KRAS TV — Cinematic Premium 2.0.
 *
 * Includes:
 * - Interface Style switcher (Classic Original [Default] vs Cinematic Premium 2.0) — instant, no restart
 * - 5 KRAS Premium Themes with live interactive preview before applying + Reset to Original Colors
 * - Independent switches for all 7 Premium features (including Battery Saving Mode)
 * - Restore Original Appearance button (returns cleanly to Classic Original with zero data loss)
 */
@Composable
fun AppearanceExperienceSettingsCard(
    userSettings: UserSettings,
    isArabic: Boolean,
    onUpdateSettings: (UserSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    val activeAccent = MaterialTheme.colorScheme.primary
    var previewTheme by remember(userSettings.premiumTheme) {
        mutableStateOf(userSettings.premiumTheme)
    }
    var statusBanner by remember { mutableStateOf<String?>(null) }

    val previewPalette = remember(previewTheme) {
        KrasPremiumPalettes.forTheme(previewTheme)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .liquidGlassPanel(cornerRadius = 20.dp, accentGlow = activeAccent)
            .testTag("settings_appearance_experience_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Section Title
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = activeAccent,
                    modifier = Modifier.size(20.dp)
                )
                Column {
                    Text(
                        text = if (isArabic) "المظهر والتجربة" else "Appearance & Experience",
                        color = Color.White,
                        fontFamily = OutfitFontFamily,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = if (isArabic) {
                            "بدّل فوراً بين الواجهة الكلاسيكية الأصلية وبريميوم 2.0 دون فقدان أي بيانات"
                        } else {
                            "Switch instantly between Classic Original and Cinematic Premium 2.0 without restarting"
                        },
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
            }

            // 1. Interface Style Selector
            Text(
                text = if (isArabic) "نمط الواجهة" else "Interface Style",
                color = TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                InterfaceStyle.entries.forEach { style ->
                    val isSelected = userSettings.interfaceStyle == style
                    val isPremiumCard = style == InterfaceStyle.CINEMATIC_PREMIUM_2
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (isSelected) activeAccent.copy(alpha = 0.22f)
                                else Color.White.copy(alpha = 0.06f)
                            )
                            .border(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) activeAccent else Color.White.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(14.dp)
                            )
                            .clickable {
                                onUpdateSettings(userSettings.copy(interfaceStyle = style))
                                statusBanner = if (isArabic) {
                                    "تم التبديل إلى ${style.arabicLabel}"
                                } else {
                                    "Switched to ${style.label}"
                                }
                            }
                            .padding(12.dp)
                            .testTag("interface_style_${style.name.lowercase()}"),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isPremiumCard) {
                                    if (isArabic) "جديد 2.0" else "PREMIUM"
                                } else {
                                    if (isArabic) "افتراضي" else "DEFAULT"
                                },
                                color = if (isSelected) activeAccent else TextSecondary,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = activeAccent,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        Text(
                            text = if (isArabic) style.arabicLabel else style.label,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

            // 1B. Startup Animation Selector (Settings → Appearance & Experience → Startup Animation)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MovieFilter,
                    contentDescription = null,
                    tint = activeAccent,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = if (isArabic) "حركة بدء التشغيل" else "Startup Animation",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = if (isArabic) {
                    "تعمل عند التشغيل البارد فقط وتراعي إعدادات تقليل الحركة في النظام"
                } else {
                    "Plays on cold launch only and respects system reduced-motion settings"
                },
                color = TextSecondary,
                fontSize = 11.sp
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("startup_animation_selector"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StartupAnimationMode.entries.forEach { mode ->
                    val isSelected = userSettings.startupAnimationMode == mode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isSelected) activeAccent.copy(alpha = 0.20f)
                                else Color.White.copy(alpha = 0.05f)
                            )
                            .border(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) activeAccent else Color.White.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable {
                                onUpdateSettings(userSettings.copy(startupAnimationMode = mode))
                                statusBanner = if (isArabic) {
                                    "تم تعيين حركة البدء: ${mode.arabicLabel}"
                                } else {
                                    "Startup animation set to ${mode.label}"
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .testTag("startup_anim_${mode.name.lowercase()}"),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isArabic) mode.arabicLabel else mode.label,
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            val desc = when (mode) {
                                StartupAnimationMode.CINEMATIC_K_ZOOM_THROUGH -> if (isArabic) {
                                    "تغوص الكاميرا بسلاسة عبر حرف K ثلاثي الأبعاد مباشرة إلى الشاشة الرئيسية"
                                } else {
                                    "Camera travels seamlessly through the 3D K directly into Home (1.8s)"
                                }
                                StartupAnimationMode.CLASSIC_ORIGINAL -> if (isArabic) {
                                    "شاشة البداية الكلاسيكية الأصلية لـ KRAS TV"
                                } else {
                                    "Original classic KRAS TV 3D glass intro animation"
                                }
                                StartupAnimationMode.DISABLED -> if (isArabic) {
                                    "دخول فوري إلى الشاشة الرئيسية دون حركة بدء"
                                } else {
                                    "Launch directly into Home with no startup animation"
                                }
                            }
                            Text(
                                text = desc,
                                color = TextSecondary,
                                fontSize = 11.sp,
                                lineHeight = 14.sp
                            )
                        }
                        if (isSelected) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = activeAccent,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            if (userSettings.startupAnimationMode == StartupAnimationMode.CINEMATIC_K_ZOOM_THROUGH) {
                FeatureToggleRow(
                    title = if (isArabic) "صوت الباص السينمائي لحركة البدء" else "Zoom-Through Cinematic Bass Sound",
                    subtitle = if (isArabic) {
                        "تأثير صوتي سينمائي قصير أثناء الغوص عبر حرف K (معطّل افتراضياً)"
                    } else {
                        "Optional short cinematic sub-bass swell during K zoom-through (off by default)"
                    },
                    checked = userSettings.playZoomThroughBassSound,
                    onCheckedChange = {
                        onUpdateSettings(userSettings.copy(playZoomThroughBassSound = it))
                    },
                    accentColor = activeAccent,
                    testTagName = "toggle_zoom_through_bass_sound"
                )
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

            // 1C. KRAS TV 3D Logo Identity & Reversible Restore Original Logo
            Text(
                text = if (isArabic) "هوية شعار KRAS TV" else "KRAS TV Logo Identity",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )

            // Live AMOLED Logo Preview
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black)
                    .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(14.dp))
                    .padding(vertical = 18.dp, horizontal = 12.dp)
                    .testTag("logo_style_preview_box"),
                contentAlignment = Alignment.Center
            ) {
                KrasLogo(
                    height = 30.dp,
                    usePremium3DLogo = userSettings.usePremium3DLogo
                )
            }

            FeatureToggleRow(
                title = if (isArabic) "الشعار المعدني ثلاثي الأبعاد" else "Premium 3D Metallic Logo",
                subtitle = if (isArabic) {
                    "تطبيق تصميم 3D الأحمر المعدني اللامع على حروف R و A و S و T و V مع الإبقاء على K الأصلية"
                } else {
                    "Matches R, A, S, T, V to the original glossy metallic 3D K across the app"
                },
                checked = userSettings.usePremium3DLogo,
                onCheckedChange = { enabled ->
                    onUpdateSettings(userSettings.copy(usePremium3DLogo = enabled))
                    statusBanner = if (enabled) {
                        if (isArabic) "تم تفعيل شعار KRAS TV ثلاثي الأبعاد" else "Premium 3D Logo active"
                    } else {
                        if (isArabic) "تمت استعادة الشعار الأصلي" else "Original KRAS TV logo restored"
                    }
                },
                accentColor = activeAccent,
                testTagName = "toggle_premium_3d_logo"
            )

            OutlinedButton(
                onClick = {
                    onUpdateSettings(userSettings.restoreOriginalLogo())
                    statusBanner = if (isArabic) {
                        "تمت استعادة الشعار الأصلي بنجاح"
                    } else {
                        "Restored original KRAS TV logo — all data preserved"
                    }
                },
                enabled = userSettings.usePremium3DLogo,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color.White,
                    disabledContentColor = TextSecondary
                ),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (userSettings.usePremium3DLogo) KrasRed.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.14f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("restore_original_logo_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Restore,
                    contentDescription = null,
                    tint = if (userSettings.usePremium3DLogo) KrasRedBright else TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isArabic) "استعادة الشعار الأصلي" else "Restore Original Logo",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

            // 2. KRAS Premium Themes (5 Themes with Live Preview Before Applying)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Palette,
                        contentDescription = null,
                        tint = activeAccent,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (isArabic) "ثيمات KRAS بريميوم" else "KRAS Premium Themes",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                TextButton(
                    onClick = {
                        previewTheme = KrasPremiumTheme.MIDNIGHT_BLACK
                        onUpdateSettings(
                            userSettings.copy(
                                accentColorIndex = 0,
                                premiumTheme = KrasPremiumTheme.MIDNIGHT_BLACK
                            )
                        )
                        statusBanner = if (isArabic) {
                            "تمت إعادة الألوان الأصلية"
                        } else {
                            "Reset to original colors"
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.testTag("reset_original_colors_button")
                ) {
                    Text(
                        text = if (isArabic) "إعادة الألوان" else "Reset Colors",
                        color = KrasRedBright,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Horizontal theme swatch selector
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(KrasPremiumTheme.entries, key = { it.name }) { theme ->
                    val pal = KrasPremiumPalettes.forTheme(theme)
                    val isApplied = userSettings.premiumTheme == theme &&
                        userSettings.interfaceStyle == InterfaceStyle.CINEMATIC_PREMIUM_2
                    val isPreviewed = previewTheme == theme

                    Column(
                        modifier = Modifier
                            .width(124.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(pal.gradientTop, pal.background)
                                )
                            )
                            .border(
                                width = if (isPreviewed) 2.dp else 1.dp,
                                color = if (isPreviewed) pal.primaryBright else pal.glassBorder,
                                shape = RoundedCornerShape(14.dp)
                            )
                            .clickable { previewTheme = theme }
                            .padding(10.dp)
                            .testTag("premium_theme_swatch_${theme.name.lowercase()}"),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clip(CircleShape)
                                        .background(pal.primary)
                                )
                                Box(
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clip(CircleShape)
                                        .background(pal.surfaceCard)
                                        .border(1.dp, pal.glassBorder, CircleShape)
                                )
                            }
                            if (isApplied) {
                                Text(
                                    text = if (isArabic) "مفعّل" else "ACTIVE",
                                    color = pal.primaryBright,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                        Text(
                            text = if (isArabic) theme.arabicLabel else theme.label,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Interactive Live Theme Preview Card (Preview Before Applying)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(previewPalette.gradientTop, previewPalette.background)
                        )
                    )
                    .border(1.dp, previewPalette.glassBorder, RoundedCornerShape(16.dp))
                    .padding(12.dp)
                    .testTag("premium_theme_preview_card"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isArabic) previewTheme.arabicLabel else previewTheme.label,
                            color = Color.White,
                            fontFamily = OutfitFontFamily,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = if (isArabic) previewTheme.arabicTagline else previewTheme.tagline,
                            color = Color.White.copy(alpha = 0.72f),
                            fontSize = 11.sp
                        )
                    }

                    Button(
                        onClick = {
                            onUpdateSettings(
                                userSettings.copy(
                                    interfaceStyle = InterfaceStyle.CINEMATIC_PREMIUM_2,
                                    premiumTheme = previewTheme
                                )
                            )
                            statusBanner = if (isArabic) {
                                "تم تطبيق ثيم ${previewTheme.arabicLabel}"
                            } else {
                                "Applied ${previewTheme.label} theme"
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = previewPalette.primary,
                            contentColor = previewPalette.onPrimary
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("apply_premium_theme_button")
                    ) {
                        Text(
                            text = if (isArabic) "تطبيق" else "Apply Theme",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }

                // Mini Preview Bar showing navigation pill + button + surface colors
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(previewPalette.glassTint.copy(alpha = 0.86f))
                        .border(1.dp, previewPalette.glassBorder, RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(previewPalette.primary.copy(alpha = 0.28f))
                            .border(1.dp, previewPalette.navIndicator, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = if (isArabic) "الرئيسية" else "Home",
                            color = previewPalette.primaryBright,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = if (isArabic) "الأفلام" else "Movies",
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 10.sp
                    )
                    Text(
                        text = if (isArabic) "ريلز" else "Reels",
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 10.sp
                    )
                    Text(
                        text = if (isArabic) "مباشر" else "Live TV",
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 10.sp
                    )
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

            // 3. Independent Feature Controls (7 Switches)
            Text(
                text = if (isArabic) "التحكم المستقل بالميزات" else "Independent Feature Controls",
                color = TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )

            FeatureToggleRow(
                title = if (isArabic) "خلفيات سينمائية ديناميكية" else "Cinematic Backgrounds",
                subtitle = if (isArabic) {
                    "خلفيات عالية الدقة بألوان متكيفة من بوستر الفيلم"
                } else {
                    "Adaptive poster colors, crossfade transitions & subtle parallax"
                },
                checked = userSettings.cinematicBackgroundsEnabled,
                onCheckedChange = {
                    onUpdateSettings(userSettings.copy(cinematicBackgroundsEnabled = it))
                },
                accentColor = activeAccent,
                testTagName = "toggle_cinematic_backgrounds"
            )

            FeatureToggleRow(
                title = if (isArabic) "شريط التنقل الزجاجي" else "Liquid Glass Navigation",
                subtitle = if (isArabic) {
                    "شريط عائم شفاف بتأثير ضبابي وانعكاسات ناعمة"
                } else {
                    "Floating translucent glass dock with smooth sliding indicator"
                },
                checked = userSettings.liquidGlassNavigationEnabled,
                onCheckedChange = {
                    onUpdateSettings(userSettings.copy(liquidGlassNavigationEnabled = it))
                },
                accentColor = activeAccent,
                testTagName = "toggle_liquid_glass_navigation"
            )

            FeatureToggleRow(
                title = if (isArabic) "ريلز الأفلام والمسلسلات" else "Movie Reels",
                subtitle = if (isArabic) {
                    "تصفح عمودي سينمائي مع الإعلانات الرسمية وزر شاهد الآن"
                } else {
                    "Vertical swipe discovery feed with official trailers & Watch Now"
                },
                checked = userSettings.movieReelsEnabled,
                onCheckedChange = {
                    onUpdateSettings(userSettings.copy(movieReelsEnabled = it))
                },
                accentColor = activeAccent,
                testTagName = "toggle_movie_reels"
            )

            if (userSettings.movieReelsEnabled) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.04f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FeatureToggleRow(
                        title = if (isArabic) "تشغيل الإعلانات تلقائياً" else "Reels Trailer Autoplay",
                        subtitle = if (isArabic) {
                            "إيقاف التشغيل التلقائي عند الرغبة بالتشغيل اليدوي فقط"
                        } else {
                            "Disable to play official trailers only when tapped"
                        },
                        checked = userSettings.reelsAutoplayEnabled,
                        onCheckedChange = {
                            onUpdateSettings(userSettings.copy(reelsAutoplayEnabled = it))
                        },
                        accentColor = activeAccent,
                        testTagName = "toggle_reels_autoplay"
                    )
                    FeatureToggleRow(
                        title = if (isArabic) "التشغيل التلقائي عبر Wi-Fi فقط" else "Wi-Fi-Only Autoplay",
                        subtitle = if (isArabic) {
                            "توفير بيانات الهاتف عند استخدام شبكة الجوال"
                        } else {
                            "Only autoplay trailers when connected to Wi-Fi"
                        },
                        checked = userSettings.reelsWifiOnlyAutoplay,
                        onCheckedChange = {
                            onUpdateSettings(userSettings.copy(reelsWifiOnlyAutoplay = it))
                        },
                        accentColor = activeAccent,
                        testTagName = "toggle_reels_wifi_only"
                    )
                }
            }

            FeatureToggleRow(
                title = if (isArabic) "وضع إضاءة السينما المحيطة" else "Ambient Cinema Mode",
                subtitle = if (isArabic) {
                    "إضاءة محيطة ناعمة حول مشغل الفيديو دون التأثير على الأداء"
                } else {
                    "Soft dynamic cinema lighting around the video player"
                },
                checked = userSettings.ambientCinemaModeEnabled,
                onCheckedChange = {
                    onUpdateSettings(userSettings.copy(ambientCinemaModeEnabled = it))
                },
                accentColor = activeAccent,
                testTagName = "toggle_ambient_cinema_mode"
            )

            FeatureToggleRow(
                title = if (isArabic) "الحركات السينمائية بريميوم" else "Premium Animations",
                subtitle = if (isArabic) {
                    "انتقالات ناعمة بسرعة 60Hz و 120Hz"
                } else {
                    "Fluid 60Hz / 120Hz spring transitions and parallax motion"
                },
                checked = userSettings.premiumAnimationsEnabled,
                onCheckedChange = {
                    onUpdateSettings(userSettings.copy(premiumAnimationsEnabled = it))
                },
                accentColor = activeAccent,
                testTagName = "toggle_premium_animations"
            )

            FeatureToggleRow(
                title = if (isArabic) "الاهتزاز اللمسي" else "Haptic Feedback",
                subtitle = if (isArabic) {
                    "استجابة لمسية لطيفة عند التنقل بين الأقسام"
                } else {
                    "Subtle tactile confirmation when switching tabs"
                },
                checked = userSettings.hapticFeedbackEnabled,
                onCheckedChange = {
                    onUpdateSettings(userSettings.copy(hapticFeedbackEnabled = it))
                },
                accentColor = activeAccent,
                testTagName = "toggle_haptic_feedback"
            )

            FeatureToggleRow(
                title = if (isArabic) "وضع توفير البطارية" else "Battery Saving Mode",
                subtitle = if (isArabic) {
                    "يقلل الضبابية والإضاءة المحيطة والحركات للحفاظ على البطارية"
                } else {
                    "Reduces blur, ambient lighting & animations to save battery"
                },
                checked = userSettings.batterySavingMode,
                onCheckedChange = {
                    onUpdateSettings(userSettings.copy(batterySavingMode = it))
                },
                accentColor = activeAccent,
                testTagName = "toggle_battery_saving_mode"
            )

            if (statusBanner != null) {
                Text(
                    text = statusBanner!!,
                    color = activeAccent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("appearance_status_banner")
                )
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

            // 4. Restore Original Appearance Button
            OutlinedButton(
                onClick = {
                    previewTheme = KrasPremiumTheme.MIDNIGHT_BLACK
                    onUpdateSettings(userSettings.restoreOriginalAppearance())
                    statusBanner = if (isArabic) {
                        "تمت استعادة المظهر الكلاسيكي الأصلي بالكامل"
                    } else {
                        "Restored Classic Original appearance — all data preserved"
                    }
                },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                border = androidx.compose.foundation.BorderStroke(1.dp, KrasRed.copy(alpha = 0.7f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("restore_original_appearance_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Restore,
                    contentDescription = null,
                    tint = KrasRedBright,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isArabic) "استعادة المظهر الأصلي" else "Restore Original Appearance",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        }
    }
}

/**
 * Settings → Player → Ambient Cinema Mode control block embedded in the existing Player card.
 */
@Composable
fun PlayerAmbientCinemaSettingsBlock(
    userSettings: UserSettings,
    isArabic: Boolean,
    onUpdateSettings: (UserSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    val activeAccent = MaterialTheme.colorScheme.primary

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("settings_player_ambient_cinema_block"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        FeatureToggleRow(
            title = if (isArabic) "وضع إضاءة السينما المحيطة" else "Ambient Cinema Mode",
            subtitle = if (isArabic) {
                "إضاءة محيطة ناعمة تتكيف مع ألوان العمل وتُعطّل تلقائياً عند توفير البطارية"
            } else {
                "Soft adaptive lighting around the player; auto-disables in Battery Saving Mode"
            },
            checked = userSettings.ambientCinemaModeEnabled,
            onCheckedChange = { enabled ->
                onUpdateSettings(userSettings.copy(ambientCinemaModeEnabled = enabled))
            },
            accentColor = activeAccent,
            testTagName = "player_ambient_cinema_switch"
        )

        if (userSettings.ambientCinemaModeEnabled) {
            Text(
                text = if (isArabic) "شدة الإضاءة المحيطة" else "Ambient Lighting Intensity",
                color = TextSecondary,
                fontSize = 12.sp
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AmbientCinemaIntensity.entries.forEach { level ->
                    val isSelected = userSettings.ambientCinemaIntensity == level
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) activeAccent else Color.White.copy(alpha = 0.1f))
                            .clickable {
                                onUpdateSettings(userSettings.copy(ambientCinemaIntensity = level))
                            }
                            .padding(vertical = 8.dp)
                            .testTag("ambient_intensity_${level.name.lowercase()}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isArabic) level.arabicLabel else level.label,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FeatureToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    accentColor: Color,
    testTagName: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                color = TextSecondary,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = accentColor,
                checkedTrackColor = accentColor.copy(alpha = 0.35f)
            ),
            modifier = Modifier.testTag(testTagName)
        )
    }
}
