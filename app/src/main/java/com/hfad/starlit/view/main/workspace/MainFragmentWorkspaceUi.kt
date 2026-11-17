package com.hfad.starlit.view.main.workspace

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.hfad.starlit.R
import com.hfad.starlit.adapter.WorkspaceFileAdapter
import com.hfad.starlit.adapter.WorkspaceFileOpenMode
import com.hfad.starlit.adapter.WorkspaceFileOpenPolicy
import com.hfad.starlit.workspace.AgentWorkspace
import com.hfad.starlit.workspace.WorkspaceNode
import com.hfad.starlit.view.webapp.VirtualAppActivity
import com.hfad.starlit.view.workspace.JsonViewerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import com.hfad.starlit.view.main.MainFragment
import com.hfad.starlit.view.main.chat.*
import com.hfad.starlit.view.main.drawer.*
import com.hfad.starlit.view.main.chat.CodeEditorSource

internal fun MainFragment.setupWorkspacePage() {
        AgentWorkspace.ensureWorkspace(requireContext())
        workspaceFileAdapter = WorkspaceFileAdapter(
            onFileClick = { node -> handleWorkspaceFileClick(node) },
            onFileLongClick = { node -> handleWorkspaceFileLongClick(node) }
        )
        workspaceBinding.rvWorkspaceTree.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = workspaceFileAdapter
            itemAnimator = null
        }
        refreshWorkspaceTree()
    }

internal fun MainFragment.handleWorkspaceFileClick(node: WorkspaceNode) {
        val file = node.absolutePath?.let(::File) ?: return
        if (!file.exists()) {
            Toast.makeText(requireContext(), "文件不存在", Toast.LENGTH_SHORT).show()
            refreshWorkspaceTree()
            return
        }

        when (WorkspaceFileOpenPolicy.modeForWorkspacePath(node.displayPath, file.name)) {
            WorkspaceFileOpenMode.CODE_VIEWER -> openWorkspaceCodeViewer(file)
            WorkspaceFileOpenMode.WEB_APP -> {
                val intent = Intent(requireContext(), VirtualAppActivity::class.java).apply {
                    putExtra(VirtualAppActivity.EXTRA_HTML_PATH, file.absolutePath)
                }
                startActivity(intent)
            }
            WorkspaceFileOpenMode.JSON -> {
                val intent = Intent(requireContext(), JsonViewerActivity::class.java).apply {
                    putExtra(JsonViewerActivity.EXTRA_JSON_PATH, file.absolutePath)
                }
                startActivity(intent)
            }
            WorkspaceFileOpenMode.TEXT -> openTextFileEditor(file)
            WorkspaceFileOpenMode.UNSUPPORTED -> showUnsupportedWorkspaceFile(file)
        }
    }

internal fun MainFragment.openWorkspaceCodeViewer(file: File) {
        viewLifecycleOwner.lifecycleScope.launch {
            val contentResult = withContext(Dispatchers.IO) {
                runCatching { file.readText() }
            }
            val content = contentResult.getOrElse {
                Toast.makeText(requireContext(), "读取失败: ${it.message}", Toast.LENGTH_SHORT).show()
                return@launch
            }
            showCodeViewer(
                source = CodeEditorSource.WORKSPACE_FILE,
                title = file.name,
                status = codePreviewMetadata(file.name, content),
                language = codePreviewLanguage(file.name),
                content = content
            )
        }
    }

internal fun MainFragment.showUnsupportedWorkspaceFile(file: File) {
        val typeName = file.extension.ifBlank { "该" }
        Toast.makeText(requireContext(), "暂不支持打开 $typeName 类型文件", Toast.LENGTH_SHORT).show()
    }

internal fun MainFragment.openTextFileEditor(file: File) {
        lifecycleScope.launch {
            val contentResult = withContext(Dispatchers.IO) {
                runCatching { file.readText() }
            }
            val content = contentResult.getOrElse {
                Toast.makeText(requireContext(), "读取失败: ${it.message}", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val dialogRoot = FrameLayout(requireContext()).apply {
                background = glassDialogSurface(26f)
            }
            val contentLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                isFocusableInTouchMode = true
                setPadding(0, dp(8), 0, dp(10))
            }
            dialogRoot.addView(
                contentLayout,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )

            val header = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(24), dp(14), dp(20), dp(14))
            }
            header.addView(
                TextView(requireContext()).apply {
                    text = file.name
                    textSize = 21f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.mt_text_primary))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                    includeFontPadding = false
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            header.addView(
                TextView(requireContext()).apply {
                    text = file.extension.ifBlank { "TEXT" }.uppercase(Locale.getDefault())
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.mt_primary_dark))
                    background = roundedBackground(Color.argb(150, 226, 239, 255), 12f)
                },
                LinearLayout.LayoutParams(dp(46), dp(25)).apply {
                    marginStart = dp(14)
                }
            )
            contentLayout.addView(header)

            val editor = EditText(requireContext()).apply {
                setText(content)
                setSelection(text?.length ?: 0)
                gravity = Gravity.TOP or Gravity.START
                inputType = InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setHorizontallyScrolling(false)
                overScrollMode = View.OVER_SCROLL_NEVER
                textSize = 14f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.mt_text_primary))
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = roundedStrokeBackground(
                    fillColor = Color.argb(190, 247, 250, 255),
                    strokeColor = Color.argb(180, 218, 228, 242),
                    radius = 18f
                )
            }
            val editorHeight = (resources.displayMetrics.heightPixels * 0.42f)
                .roundToInt()
                .coerceIn(dp(260), dp(420))
            contentLayout.addView(
                editor,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    editorHeight
                ).apply {
                    marginStart = dp(20)
                    marginEnd = dp(20)
                }
            )

            val actions = LinearLayout(requireContext()).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                setPadding(dp(20), dp(12), dp(20), 0)
            }
            val cancelButton = TextView(requireContext()).apply {
                text = "取消"
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(requireContext(), R.color.mt_text_secondary))
                background = ContextCompat.getDrawable(requireContext(), selectableItemBackgroundRes())
            }
            val saveButton = TextView(requireContext()).apply {
                text = "保存"
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(requireContext(), R.color.mt_primary_dark))
                background = roundedStrokeBackground(
                    fillColor = Color.argb(170, 223, 239, 255),
                    strokeColor = Color.argb(190, 177, 211, 252),
                    radius = 16f
                )
            }
            actions.addView(cancelButton, LinearLayout.LayoutParams(dp(72), dp(44)))
            actions.addView(
                saveButton,
                LinearLayout.LayoutParams(dp(84), dp(44)).apply {
                    marginStart = dp(8)
                }
            )
            contentLayout.addView(actions)

            val dialog = Dialog(requireContext())
            dialog.setContentView(dialogRoot)
            cancelButton.setOnClickListener { dialog.dismiss() }
            saveButton.setOnClickListener {
                lifecycleScope.launch {
                    val saveResult = withContext(Dispatchers.IO) {
                        runCatching { file.writeText(editor.text.toString()) }
                    }
                    saveResult
                        .onSuccess {
                            Toast.makeText(requireContext(), "已保存", Toast.LENGTH_SHORT).show()
                            refreshWorkspaceTree()
                            dialog.dismiss()
                        }
                        .onFailure { error ->
                            Toast.makeText(requireContext(), "保存失败: ${error.message}", Toast.LENGTH_SHORT).show()
                        }
                }
            }
            contentLayout.requestFocus()
            dialog.show()
            configureFloatingGlassWindow(
                dialog = dialog,
                width = (resources.displayMetrics.widthPixels * 0.9f).roundToInt(),
                height = editorHeight + dp(142),
                blurRadiusDp = 14,
                dimAmount = 0.16f
            )
            dialog.window?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            )
        }
    }

internal fun MainFragment.handleWorkspaceFileLongClick(node: WorkspaceNode): Boolean {
        if (!node.displayPath.startsWith("/workspace/${AgentWorkspace.WEB_DIR}/")) {
            return false
        }
        val file = node.absolutePath?.let(::File) ?: return false
        if (!file.exists()) {
            Toast.makeText(requireContext(), "文件不存在", Toast.LENGTH_SHORT).show()
            refreshWorkspaceTree()
            return true
        }

        showActionMenu(
            listOf(
                ActionMenuItem("改名", R.drawable.ic_edit_outline) {
                    showRenameWorkspaceFileDialog(file)
                },
                ActionMenuItem("删除", R.drawable.ic_delete_outline) {
                    confirmDeleteWorkspaceFile(file)
                }
            )
        )
        return true
    }

internal fun MainFragment.showRenameWorkspaceFileDialog(file: File) {
        val editor = EditText(requireContext()).apply {
            tag = "glass:input"
            setText(file.name)
            setSelection(0, file.nameWithoutExtension.length.coerceAtMost(file.name.length))
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            textSize = 15f
        }

        val paddingHorizontal = (16 * resources.displayMetrics.density).toInt()
        val paddingTop = (8 * resources.displayMetrics.density).toInt()
        val editorContainer = FrameLayout(requireContext()).apply {
            setPadding(paddingHorizontal, paddingTop, paddingHorizontal, 0)
            addView(
                editor,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("改名文件")
            .setView(editorContainer)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    val newName = normalizeWorkspaceFileName(
                        editor.text.toString(),
                        file.extension
                    )
                    if (newName == null) {
                        Toast.makeText(requireContext(), "文件名不能为空", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    if (newName == file.name) {
                        dialog.dismiss()
                        return@setOnClickListener
                    }
                    val target = File(file.parentFile, newName)
                    if (target.exists()) {
                        Toast.makeText(requireContext(), "已存在同名文件", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    if (file.renameTo(target)) {
                        Toast.makeText(requireContext(), "已改名", Toast.LENGTH_SHORT).show()
                        refreshWorkspaceTree()
                        dialog.dismiss()
                    } else {
                        Toast.makeText(requireContext(), "改名失败", Toast.LENGTH_SHORT).show()
                    }
                }
        }
        dialog.show()
        decorateAlertDialog(dialog)
    }

internal fun MainFragment.normalizeWorkspaceFileName(input: String, originalExtension: String): String? {
        val sanitized = input
            .trim()
            .replace(Regex("[\\\\/:*?\"<>|]+"), "_")
            .trim('_', '-', '.', ' ')

        if (sanitized.isBlank()) return null
        if (sanitized.contains('.')) return sanitized
        return originalExtension
            .takeIf { it.isNotBlank() }
            ?.let { "$sanitized.$it" }
            ?: sanitized
    }

internal fun MainFragment.confirmDeleteWorkspaceFile(file: File) {
        val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("删除文件")
            .setMessage("确定要删除「${file.name}」吗？")
            .setPositiveButton("删除") { _, _ ->
                val deleted = file.delete()
                if (deleted) {
                    Toast.makeText(requireContext(), "已删除", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "删除失败", Toast.LENGTH_SHORT).show()
                }
                refreshWorkspaceTree()
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
        decorateAlertDialog(dialog)
    }

internal fun MainFragment.refreshWorkspaceTree() {
        if (hasWorkspaceFileAdapter) {
            workspaceFileAdapter.submitNodes(AgentWorkspace.loadWorkspaceTree(requireContext()))
        }
    }
