package com.hfad.starlit.view.main.voice

import android.Manifest
import android.app.Dialog
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.hfad.starlit.data.api.voice.VoiceInputConfig
import com.hfad.starlit.data.api.voice.VoiceTranscriptionApiService
import com.hfad.starlit.data.api.voice.WavAudioRecorder
import com.hfad.starlit.data.preferences.voice.VoiceInputModelStore
import kotlinx.coroutines.launch
import java.io.File
import com.hfad.starlit.view.main.MainFragment
import com.hfad.starlit.view.main.chat.*

internal fun MainFragment.showMimoVoiceKeyDialog(startAfterActivation: Boolean) {
        val dialog = Dialog(requireContext())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(buildMimoVoiceKeyContent(dialog, startAfterActivation))
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        configureFloatingGlassWindow(
            dialog = dialog,
            width = (resources.displayMetrics.widthPixels - dp(40)).coerceAtLeast(dp(300)),
            height = ViewGroup.LayoutParams.WRAP_CONTENT,
            blurRadiusDp = 14,
            dimAmount = 0.16f
        )
    }

internal fun MainFragment.buildMimoVoiceKeyContent(dialog: Dialog, startAfterActivation: Boolean): View {
        val currentApiKey = VoiceInputModelStore.getActiveApiKey(requireContext())
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            background = glassDialogSurface(24f)
            setPadding(dp(22), dp(22), dp(22), dp(20))
        }

        root.addView(
            TextView(requireContext()).apply {
                text = "MiMo 语音输入"
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(45, 52, 68))
                includeFontPadding = false
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            TextView(requireContext()).apply {
                text = "输入可用的 MiMo API Key，校验通过后会激活 ${VoiceTranscriptionApiService.MIMO_ASR_MODEL}。"
                textSize = 13f
                setTextColor(Color.rgb(112, 124, 145))
                setPadding(0, dp(8), 0, 0)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val apiKeyInput = buildVoiceConfigInput(
            hint = "MiMo API Key",
            text = currentApiKey,
            inputTypeValue = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        )

        root.addView(apiKeyInput, voiceConfigInputLayoutParams(topMargin = dp(16)))

        val buttonRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        buttonRow.addView(
            TextView(requireContext()).apply {
                text = "取消"
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(97, 113, 137))
                background = roundedStrokeBackground(
                    fillColor = Color.argb(140, 248, 251, 255),
                    strokeColor = Color.argb(180, 218, 228, 244),
                    radius = 14f
                )
                foreground = ContextCompat.getDrawable(requireContext(), selectableItemBackgroundRes())
                setOnClickListener { dialog.dismiss() }
            },
            LinearLayout.LayoutParams(0, dp(46), 1f)
        )
        buttonRow.addView(
            TextView(requireContext()).apply {
                text = "激活"
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = roundedStrokeBackground(
                    fillColor = Color.rgb(44, 131, 216),
                    strokeColor = Color.rgb(44, 131, 216),
                    radius = 14f
                )
                foreground = ContextCompat.getDrawable(requireContext(), selectableItemBackgroundRes())
                setOnClickListener {
                    val apiKey = apiKeyInput.text?.toString().orEmpty()
                    val button = this
                    button.isEnabled = false
                    button.alpha = 0.65f
                    button.text = "校验中..."
                    viewLifecycleOwner.lifecycleScope.launch {
                        val result = VoiceTranscriptionApiService.activateMimoAsr(apiKey)
                        if (!dialog.isShowing) return@launch
                        button.isEnabled = true
                        button.alpha = 1f
                        button.text = "激活"
                        result.onSuccess { config ->
                            VoiceInputModelStore.setActive(requireContext(), config)
                            Toast.makeText(requireContext(), "MiMo 语音输入已激活", Toast.LENGTH_SHORT).show()
                            dialog.dismiss()
                            if (startAfterActivation) {
                                startVoiceInputWithConfiguredModel()
                            }
                        }.onFailure { error ->
                            Toast.makeText(
                                requireContext(),
                                error.message ?: "MiMo API Key 校验失败",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            },
            LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                marginStart = dp(12)
            }
        )
        root.addView(
            buttonRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(16)
            }
        )

        return root
    }

internal fun MainFragment.buildVoiceConfigInput(
        hint: String,
        text: String,
        inputTypeValue: Int
    ): EditText {
        return EditText(requireContext()).apply {
            this.hint = hint
            setText(text)
            textSize = 14f
            setSingleLine(true)
            setTextColor(Color.rgb(45, 52, 68))
            setHintTextColor(Color.rgb(150, 162, 182))
            inputType = inputTypeValue
            background = roundedStrokeBackground(
                fillColor = Color.argb(235, 247, 250, 255),
                strokeColor = Color.argb(220, 216, 226, 240),
                radius = 14f
            )
            setPadding(dp(14), 0, dp(14), 0)
        }
    }

internal fun MainFragment.voiceConfigInputLayoutParams(topMargin: Int): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(50)
        ).apply {
            this.topMargin = topMargin
        }
    }

internal fun MainFragment.onVoiceInputClicked() {
        when {
            isVoiceTranscribing -> {
                Toast.makeText(requireContext(), "正在识别语音，请稍等", Toast.LENGTH_SHORT).show()
            }
            isVoiceRecording -> {
                stopVoiceInputAndTranscribe()
            }
            VoiceInputModelStore.getActiveConfig(requireContext()) == null -> {
                showMimoVoiceKeyDialog(startAfterActivation = true)
            }
            else -> {
                startVoiceInputWithConfiguredModel()
            }
        }
    }

internal fun MainFragment.startVoiceInputWithConfiguredModel() {
        if (isVoiceRecording || isVoiceTranscribing) return

        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingVoiceStartAfterPermission = true
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val config = resolveVoiceInputConfig()
            if (config == null) {
                showMimoVoiceKeyDialog(startAfterActivation = true)
                return@launch
            }
            startVoiceRecording(config)
        }
    }

internal fun MainFragment.resolveVoiceInputConfig(): VoiceInputConfig? {
        return VoiceInputModelStore.getActiveConfig(requireContext())
    }

internal fun MainFragment.startVoiceRecording(config: VoiceInputConfig) {
        val outputFile = File(requireContext().cacheDir, "voice-input-${System.currentTimeMillis()}.wav")
        val recorder = WavAudioRecorder(outputFile)

        runCatching {
            recorder.start()
        }.onSuccess {
            voiceRecorder = recorder
            voiceInputFile = outputFile
            voiceRecordingConfig = config
            isVoiceRecording = true
            updateVoiceInputButtonState()
            hideKeyboard()
            Toast.makeText(requireContext(), "正在语音输入，再次点击结束", Toast.LENGTH_SHORT).show()
        }.onFailure { error ->
            runCatching { recorder.cancel() }
            outputFile.delete()
            voiceRecorder = null
            voiceInputFile = null
            voiceRecordingConfig = null
            isVoiceRecording = false
            updateVoiceInputButtonState()
            Toast.makeText(requireContext(), "无法开始录音: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

internal fun MainFragment.stopVoiceInputAndTranscribe() {
        val recorder = voiceRecorder ?: return
        val audioFile = voiceInputFile
        val config = voiceRecordingConfig

        isVoiceRecording = false
        isVoiceTranscribing = true
        voiceRecorder = null
        voiceInputFile = null
        voiceRecordingConfig = null
        updateVoiceInputButtonState()

        val pcmBytes = runCatching { recorder.stop() }.getOrDefault(-1L)

        if (pcmBytes <= 0L || audioFile == null || config == null || audioFile.length() <= 0L) {
            audioFile?.delete()
            isVoiceTranscribing = false
            updateVoiceInputButtonState()
            Toast.makeText(requireContext(), "录音时间太短，请重试", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(requireContext(), "正在识别语音...", Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = VoiceTranscriptionApiService.transcribe(config, audioFile)
            audioFile.delete()
            isVoiceTranscribing = false
            updateVoiceInputButtonState()
            result.onSuccess { text ->
                appendVoiceTextToInput(text)
            }.onFailure { error ->
                Toast.makeText(requireContext(), error.message ?: "语音识别失败", Toast.LENGTH_LONG).show()
            }
        }
    }

internal fun MainFragment.appendVoiceTextToInput(text: String) {
        val current = chatBinding.etInput.text?.toString().orEmpty()
        val separator = if (current.isBlank() || current.endsWith("\n") || current.endsWith(" ")) "" else " "
        val next = current + separator + text.trim()
        chatBinding.etInput.setText(next)
        chatBinding.etInput.setSelection(chatBinding.etInput.text?.length ?: 0)
        updateCurrentContextTokens()
    }

internal fun MainFragment.cancelVoiceInput(deleteFile: Boolean = true) {
        runCatching { voiceRecorder?.cancel() }
        if (deleteFile) {
            voiceInputFile?.delete()
        }
        voiceRecorder = null
        voiceInputFile = null
        voiceRecordingConfig = null
        isVoiceRecording = false
        isVoiceTranscribing = false
        pendingVoiceStartAfterPermission = false
        if (_chatBinding != null) {
            updateVoiceInputButtonState()
        }
    }

internal fun MainFragment.updateVoiceInputButtonState() {
        val button = chatBinding.ivVoiceInput
        when {
            isVoiceRecording -> {
                button.isEnabled = true
                button.alpha = 1f
                button.imageTintList = ColorStateList.valueOf(Color.rgb(230, 83, 83))
                button.contentDescription = "结束语音输入"
            }
            isVoiceTranscribing -> {
                button.isEnabled = false
                button.alpha = 0.55f
                button.imageTintList = ColorStateList.valueOf(Color.rgb(112, 124, 145))
                button.contentDescription = "正在识别语音"
            }
            else -> {
                button.isEnabled = true
                button.alpha = 1f
                button.imageTintList = ColorStateList.valueOf(Color.rgb(44, 131, 216))
                button.contentDescription = "语音输入"
            }
        }
    }
