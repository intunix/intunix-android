package com.intunix.sdk

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * [IntunixForm] as a classic Android View, for View-based screens and for wrappers
 * (React Native, Flutter). Works in activities that are not ComponentActivity: it brings
 * its own lifecycle and view-model store when the window has none.
 */
class IntunixFormView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    private var formId by mutableStateOf<String?>(null)
    private var formContext by mutableStateOf<Map<String, Any?>?>(null)
    private var fallback: FallbackOwner? = null

    /** Called once the visitor submits or closes the form. */
    var onFinished: (() -> Unit)? = null
    /** Content height in pixels whenever it changes (0 once the form is done). */
    var onHeightChanged: ((Int) -> Unit)? = null

    private val compose = ComposeView(context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        setContent {
            val f = formId
            Box(Modifier.fillMaxWidth().onSizeChanged { report(it.height) }) {
                if (f != null) IntunixForm(f, Modifier.fillMaxWidth(), formContext) { onFinished?.invoke() }
            }
        }
    }

    private var reported = -1

    init { addView(compose, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)) }

    private fun report(px: Int) {
        if (px == reported) return
        reported = px
        onHeightChanged?.invoke(px)
    }

    /**
     * The form is always measured at its natural height, even when the host fixes this view's
     * height (React Native, Flutter size native views themselves and grow it from the callback).
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        compose.measure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        setMeasuredDimension(
            resolveSize(compose.measuredWidth, widthMeasureSpec),
            resolveSize(compose.measuredHeight, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        compose.layout(0, 0, r - l, compose.measuredHeight)
    }

    // React Native ignores requestLayout from native children; measure and lay out ourselves.
    private val relayout = Runnable {
        measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        layout(left, top, right, bottom)
    }

    override fun requestLayout() {
        super.requestLayout()
        @Suppress("SENSELESS_COMPARISON") // called from the super constructor, before fields exist
        if (relayout != null) post(relayout)
    }

    /** Form id or slug to show. */
    fun setForm(form: String?, context: Map<String, Any?>? = null) {
        formContext = context
        formId = form
    }

    override fun onAttachedToWindow() {
        // Runs before the ComposeView child attaches, so Compose finds these owners.
        fallback?.let { it.resume(); super.onAttachedToWindow(); return }
        val needLifecycle = findViewTreeLifecycleOwner() == null
        val needStore = findViewTreeViewModelStoreOwner() == null
        val needSaved = findViewTreeSavedStateRegistryOwner() == null
        if (needLifecycle || needStore || needSaved) {
            val owner = fallback ?: FallbackOwner().also { fallback = it }
            if (needLifecycle) setViewTreeLifecycleOwner(owner)
            if (needStore) setViewTreeViewModelStoreOwner(owner)
            if (needSaved) setViewTreeSavedStateRegistryOwner(owner)
            owner.resume()
        }
        super.onAttachedToWindow()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Kept (not destroyed) so a re-attach, e.g. scrolling back, keeps the visitor's answers.
        fallback?.pause()
    }

    private class FallbackOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val viewModelStore = ViewModelStore()
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

        init {
            saved.performAttach()
            saved.performRestore(null)
        }

        fun resume() { registry.currentState = Lifecycle.State.RESUMED }
        fun pause() { registry.currentState = Lifecycle.State.CREATED }
    }
}
