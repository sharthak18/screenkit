package com.screenkit.screenrecorder.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.provider.Settings
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.screenkit.screenrecorder.automation.ScrollDelay
import com.screenkit.screenrecorder.data.SettingsStore

internal object ScrollPreferencesDialog {
    fun show(context: Context, onSaved: () -> Unit) {
        var base = context
        while (base is ContextWrapper && base !is Activity) base = base.baseContext
        val activity = base as? Activity
        if (activity == null && !Settings.canDrawOverlays(context)) return
        val themed = ContextThemeWrapper(activity ?: context, android.R.style.Theme_Material_Dialog_Alert)
        val pad = (20 * context.resources.displayMetrics.density).toInt()
        val layout = LinearLayout(themed).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, 0) }
        layout.addView(TextView(themed).apply { text = "Wait before each scroll, in seconds (0.2–3600). This also applies to the first video." })
        val input = EditText(themed).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            setText((SettingsStore.autoScrollIntervalMs(context) / 1000.0).toString())
            selectAll()
        }
        layout.addView(input)
        val smart = CheckBox(themed).apply { text = "Smart playback / ads (experimental)"; isChecked = SettingsStore.autoScrollSmartEnabled(context) }
        layout.addView(smart)
        layout.addView(TextView(themed).apply {
            text = "Smart mode reads visible playback/ad labels in YouTube, Instagram and Facebook while Auto Scroll runs. Processing stays on this device; text is not saved or sent. It can use a visible Skip ad button, skip labelled sponsored feed items, and hold during recognised in-video ad breaks. It cannot bypass unskippable ads or reliably detect hidden/localised controls. Without playback info it uses your timer. Use Hold / Next when needed.\n\nSet session duration to Infinite if your delay is longer than the run time."
            textSize = 12f
        })
        val dialog = AlertDialog.Builder(themed).setTitle("Custom scroll timing")
            .setView(android.widget.ScrollView(themed).apply { addView(layout) }).setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        if (activity == null) dialog.window?.setType(if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val ms = ScrollDelay.parseSeconds(input.text.toString())
                if (ms == null) input.error = "Enter 0.2–3600 seconds (for example 30, 90 or 300)."
                else {
                    SettingsStore.setAutoScrollIntervalMs(context, ms)
                    SettingsStore.setAutoScrollSmartEnabled(context, smart.isChecked)
                    onSaved(); dialog.dismiss()
                }
            }
        }
        dialog.show()
    }
}
