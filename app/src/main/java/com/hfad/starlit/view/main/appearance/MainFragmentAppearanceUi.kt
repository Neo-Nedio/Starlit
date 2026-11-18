package com.hfad.starlit.view.main.appearance

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.chip.Chip
import com.hfad.starlit.R
import com.hfad.starlit.data.preferences.appearance.AppearanceSettingsStore
import com.hfad.starlit.data.preferences.appearance.WallpaperStore
import com.hfad.starlit.ui.theme.AutoContrastColor
import com.hfad.starlit.view.glass.LiquidGlass
import kotlin.math.roundToInt
import com.hfad.starlit.view.main.MainFragment
import com.hfad.starlit.view.main.MainActivity
import com.hfad.starlit.view.main.drawer.*

internal fun MainFragment.setupAppBar() {
        binding.ivMenu.setOnClickListener {
            (activity as? MainActivity)?.openDrawer()
        }
        binding.desktopTab.setOnClickListener {
            mainPager.setCurrentItem(MainFragment.PAGE_DESKTOP, true)
        }
        binding.chatTab.setOnClickListener {
            mainPager.setCurrentItem(MainFragment.PAGE_CHAT, true)
        }
        binding.workspaceTab.setOnClickListener {
            mainPager.setCurrentItem(MainFragment.PAGE_WORKSPACE, true)
        }
        binding.newChat.setOnClickListener {
            if (currentPagerPage == MainFragment.PAGE_CHAT) {
                createNewSession()
            } else {
                mainPager.setCurrentItem(MainFragment.PAGE_CHAT, true)
            }
        }
        updateAppBarAction(MainFragment.PAGE_CHAT)
        binding.appBarCapsule.post {
            updateAppBarTitleSlide(MainFragment.PAGE_CHAT, 0f)
        }
    }

internal fun MainFragment.updateAppBarTitleSlide(position: Int, positionOffset: Float) {
        val globalProgress = (position + positionOffset).coerceIn(0f, 2f)
        val availableTitleWidth = binding.titleSwitcher.width
        if (availableTitleWidth <= 0) return

        val capsuleParams = binding.appBarCapsule.layoutParams as FrameLayout.LayoutParams
        val targetCapsuleWidth = dp(118).coerceAtMost(availableTitleWidth)
        if (capsuleParams.width != targetCapsuleWidth) {
            capsuleParams.width = targetCapsuleWidth
            binding.appBarCapsule.layoutParams = capsuleParams
            binding.appBarCapsule.post {
                updateAppBarSegmentIndicator(globalProgress)
            }
            return
        }

        updateAppBarSegmentIndicator(globalProgress)
    }

internal fun MainFragment.updateAppBarSegmentIndicator(globalProgress: Float) {
        val capsule = binding.appBarCapsule
        val availableWidth = capsule.width - capsule.paddingLeft - capsule.paddingRight
        val availableHeight = capsule.height - capsule.paddingTop - capsule.paddingBottom
        if (availableWidth <= 0 || availableHeight <= 0) return

        val segmentWidth = availableWidth / 3f
        val indicator = binding.appBarSegmentIndicator
        val indicatorParams = indicator.layoutParams as FrameLayout.LayoutParams
        val targetWidth = segmentWidth.roundToInt()
        if (indicatorParams.width != targetWidth || indicatorParams.height != availableHeight) {
            indicatorParams.width = targetWidth
            indicatorParams.height = availableHeight
            indicator.layoutParams = indicatorParams
        }

        indicator.translationX = segmentWidth * globalProgress
        updateAppBarTabTint(globalProgress.roundToInt().coerceIn(MainFragment.PAGE_DESKTOP, MainFragment.PAGE_WORKSPACE))
    }

internal fun MainFragment.updateAppBarTabTint(selectedPage: Int) {
        val selectedTint = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.mt_on_primary))
        val idleTint = ColorStateList.valueOf(ColorUtils.setAlphaComponent(adaptiveTextColor, 190))

        binding.ivDesktopTab.imageTintList = if (selectedPage == MainFragment.PAGE_DESKTOP) selectedTint else idleTint
        binding.ivChatTab.imageTintList = if (selectedPage == MainFragment.PAGE_CHAT) selectedTint else idleTint
        binding.ivWorkspaceTab.imageTintList = if (selectedPage == MainFragment.PAGE_WORKSPACE) selectedTint else idleTint

        binding.desktopTab.isSelected = selectedPage == MainFragment.PAGE_DESKTOP
        binding.chatTab.isSelected = selectedPage == MainFragment.PAGE_CHAT
        binding.workspaceTab.isSelected = selectedPage == MainFragment.PAGE_WORKSPACE
    }

internal fun MainFragment.updateAppBarAction(position: Int) {
        binding.newChat.contentDescription = when (position) {
            MainFragment.PAGE_CHAT -> "新建会话"
            else -> "返回聊天"
        }
    }

internal fun MainFragment.applyWallpaper() {
        val wallpaperUri = WallpaperStore.getWallpaperUri(requireContext())
        val appearanceSettings = AppearanceSettingsStore.getSettings(requireContext())
        if (hasChatAdapter) {
            chatAdapter.updateAppearance(appearanceSettings)
        }
        val defaultBackground = ContextCompat.getColor(requireContext(), R.color.mt_background)
        if (wallpaperUri == null) {
            binding.wallpaperBackground.visibility = View.GONE
            binding.wallpaperBackground.setImageDrawable(null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                binding.wallpaperBackground.setRenderEffect(null)
            }
            binding.wallpaperMask.visibility = View.GONE
            mainPager.setBackgroundColor(defaultBackground)
            chatBinding.root.setBackgroundColor(defaultBackground)
            workspaceBinding.root.setBackgroundColor(defaultBackground)
            desktopBinding.root.setBackgroundColor(defaultBackground)
            updateNewChatAppearance()
            val analysis = AutoContrastColor.analyze(requireContext(), appearanceSettings, null)
            dispatchAutoTextColor(appearanceSettings, analysis)
            return
        }

        binding.wallpaperBackground.visibility = View.VISIBLE
        val wallpaperLoaded = runCatching {
            binding.wallpaperBackground.setImageURI(wallpaperUri)
        }.isSuccess
        if (!wallpaperLoaded) {
            WallpaperStore.clearWallpaper(requireContext())
            binding.wallpaperBackground.visibility = View.GONE
            binding.wallpaperBackground.setImageDrawable(null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                binding.wallpaperBackground.setRenderEffect(null)
            }
            binding.wallpaperMask.visibility = View.GONE
            mainPager.setBackgroundColor(defaultBackground)
            chatBinding.root.setBackgroundColor(defaultBackground)
            workspaceBinding.root.setBackgroundColor(defaultBackground)
            desktopBinding.root.setBackgroundColor(defaultBackground)
            updateNewChatAppearance()
            val analysis = AutoContrastColor.analyze(requireContext(), appearanceSettings, null)
            dispatchAutoTextColor(appearanceSettings, analysis)
            Toast.makeText(requireContext(), "壁纸读取失败，已恢复默认背景", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val blur = appearanceSettings.backgroundBlur
            binding.wallpaperBackground.setRenderEffect(
                if (blur > 0f) {
                    RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP)
                } else {
                    null
                }
            )
        }
        val analysis = AutoContrastColor.analyze(
            requireContext(),
            appearanceSettings,
            binding.wallpaperBackground.drawable
        )
        binding.wallpaperMask.visibility =
            if (Color.alpha(analysis.maskColor) > 0) View.VISIBLE else View.GONE
        binding.wallpaperMask.setBackgroundColor(analysis.maskColor)
        mainPager.setBackgroundColor(Color.TRANSPARENT)
        chatBinding.root.setBackgroundColor(Color.TRANSPARENT)
        workspaceBinding.root.setBackgroundColor(Color.TRANSPARENT)
        desktopBinding.root.setBackgroundColor(Color.TRANSPARENT)
        updateNewChatAppearance()
        dispatchAutoTextColor(appearanceSettings, analysis)
    }

internal fun MainFragment.updateNewChatAppearance() {
        binding.newChat.tag = "glass:circle"
        binding.newChat.setBackgroundColor(Color.TRANSPARENT)
        binding.newChat.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(requireContext(), R.color.input_action_blue)
        )
        LiquidGlass.refresh(binding.root)
    }

internal fun MainFragment.dispatchAutoTextColor(
        settings: AppearanceSettingsStore.Settings,
        analysis: AutoContrastColor.Analysis
    ) {
        val autoColor = analysis.textColor
        val effective = if (settings.hasFixedTextColor) settings.chatTextColor else autoColor
        adaptiveTextColor = effective
        if (hasChatAdapter) {
            chatAdapter.updateAutoTextColor(autoColor)
        }
        if (hasDesktopAppAdapter) {
            desktopAppAdapter.updateTextColor(effective)
        }
        if (hasWorkspaceFileAdapter) {
            workspaceFileAdapter.updateTextColor(effective)
        }
        applyAdaptiveForeground(effective)
        applySystemBarContrast(
            analysis.statusBarTextColor,
            analysis.navigationBarTextColor
        )
    }

internal fun MainFragment.applyAdaptiveForeground(color: Int) {
        val secondaryColor = ColorUtils.setAlphaComponent(color, 184)
        val hintColor = ColorUtils.setAlphaComponent(color, 145)
        chatBinding.tvGreeting.setTextColor(color)
        chatBinding.chipStartTask.setTextColor(color)
        chatBinding.chipLongTermMemory.setTextColor(color)
        chatBinding.etInput.setTextColor(color)
        chatBinding.etInput.setHintTextColor(hintColor)
        desktopBinding.tvDesktopEmpty.setTextColor(secondaryColor)
        workspaceBinding.tvWorkspacePath.setTextColor(color)
        workspaceBinding.ivWorkspacePathIcon.imageTintList = ColorStateList.valueOf(secondaryColor)
        binding.ivMenu.imageTintList = ColorStateList.valueOf(secondaryColor)
        updateAppBarTabTint(currentPagerPage)

        for (index in 0 until chatBinding.selectedImageChipGroup.childCount) {
            val chip = chatBinding.selectedImageChipGroup.getChildAt(index) as? Chip ?: continue
            chip.setTextColor(color)
            chip.closeIconTint = ColorStateList.valueOf(secondaryColor)
        }
    }

internal fun MainFragment.applySystemBarContrast(statusBarColor: Int, navigationBarColor: Int) {
        (activity as? MainActivity)?.updateSystemBarIconAppearance(
            darkStatusBarIcons = ColorUtils.calculateLuminance(statusBarColor) < 0.5,
            darkNavigationBarIcons = ColorUtils.calculateLuminance(navigationBarColor) < 0.5
        )
    }
