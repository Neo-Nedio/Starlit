package com.hfad.starlit.view.glass

import android.view.View

class GlassHandle internal constructor(
    private val host: GlassHostView,
    val root: View
) : AutoCloseable {

    fun refresh() {
        host.refreshTargets()
    }

    override fun close() {
        host.dispose()
    }
}
