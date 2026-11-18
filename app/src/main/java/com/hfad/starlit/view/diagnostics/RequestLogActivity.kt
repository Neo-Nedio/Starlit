package com.hfad.starlit.view.diagnostics

import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.hfad.starlit.R
import com.hfad.starlit.adapter.RequestLogAdapter
import com.hfad.starlit.data.logging.api.ApiLogEntry
import com.hfad.starlit.data.logging.api.ApiLogStore
import com.hfad.starlit.databinding.ActivityRequestLogBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RequestLogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRequestLogBinding
    private val adapter = RequestLogAdapter()
    private val timeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityRequestLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_refresh) {
                render(ApiLogStore.entries.value)
                true
            } else false
        }

        binding.rvLogs.layoutManager = LinearLayoutManager(this)
        binding.rvLogs.adapter = adapter

        lifecycleScope.launch {
            ApiLogStore.entries.collectLatest { render(it) }
        }
    }

    private fun render(entries: List<ApiLogEntry>) {
        binding.tvStatTotal.text = entries.size.toString()
        binding.tvStatSuccess.text = entries.count { it.success }.toString()
        binding.tvStatFail.text = entries.count {
            !it.success && !it.inProgress && !it.canceled
        }.toString()
        binding.tvLastTime.text = entries.firstOrNull()
            ?.let { timeFormatter.format(Date(it.timestampMs)) }
            ?: "—"
        binding.tvEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        adapter.submitList(entries)
    }
}
