package com.hfad.starlit.view.main.desktop

import android.content.Intent
import android.view.View
import android.widget.Toast
import android.app.ActivityOptions
import androidx.recyclerview.widget.GridLayoutManager
import com.hfad.starlit.adapter.DesktopAppAdapter
import com.hfad.starlit.workspace.DesktopAppScanner
import com.hfad.starlit.view.webapp.VirtualAppActivity
import java.io.File
import com.hfad.starlit.view.main.MainFragment

internal fun MainFragment.setupDesktopPage() {
        desktopAppAdapter = DesktopAppAdapter { item, sourceView ->
            openWebAppWithAnimation(item.htmlPath, sourceView)
        }
        desktopBinding.rvDesktopApps.apply {
            layoutManager = GridLayoutManager(requireContext(), 4)
            adapter = desktopAppAdapter
            itemAnimator = null
            setHasFixedSize(false)
        }
        refreshDesktopApps()
    }

internal fun MainFragment.refreshDesktopApps() {
        if (!hasDesktopAppAdapter) return
        val items = DesktopAppScanner.loadDesktopApps(requireContext())
        desktopAppAdapter.submit(items)
        if (_desktopBinding != null) {
            desktopBinding.tvDesktopEmpty.visibility =
                if (items.isEmpty()) View.VISIBLE else View.GONE
        }
    }

internal fun MainFragment.openWebAppWithAnimation(htmlPath: String, sourceView: View) {
        val file = File(htmlPath)
        if (!file.exists()) {
            Toast.makeText(requireContext(), "文件不存在", Toast.LENGTH_SHORT).show()
            refreshDesktopApps()
            return
        }

        val intent = Intent(requireContext(), VirtualAppActivity::class.java).apply {
            putExtra(VirtualAppActivity.EXTRA_HTML_PATH, file.absolutePath)
        }
        val options = ActivityOptions.makeScaleUpAnimation(
            sourceView,
            0,
            0,
            sourceView.width,
            sourceView.height
        )
        startActivity(intent, options.toBundle())
    }
