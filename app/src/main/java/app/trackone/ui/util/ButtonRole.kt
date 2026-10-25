package app.trackone.ui.util

import android.view.View
import android.widget.Button
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat

/**
 * Makes a clickable view (usually a styled TextView) announce itself as a button. TalkBack reads a
 * plain TextView as just its text, so "Add gold" would not tell a blind user that it can be tapped.
 */
fun View.announceAsButton() {
    ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = Button::class.java.name
        }
    })
}
