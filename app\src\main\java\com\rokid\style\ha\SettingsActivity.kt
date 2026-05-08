package com.rokid.style.ha

import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = EditText(this).apply {
            hint = "Home Assistant URL"
            setText(Prefs.getHaUrl(this@SettingsActivity))
        }
        val token = EditText(this).apply {
            hint = "Long-lived access token"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(Prefs.getHaToken(this@SettingsActivity))
        }
        val entities = EditText(this).apply {
            hint = "Dashboard entities, comma separated"
            setText(Prefs.getDashboardRaw(this@SettingsActivity))
        }
        val reconnect = CheckBox(this).apply {
            text = "Auto reconnect"
            isChecked = Prefs.isAutoReconnect(this@SettingsActivity)
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
            addView(TextView(this@SettingsActivity).apply {
                text = "Home Assistant Settings"
                textSize = 20f
            })
            addView(url)
            addView(token)
            addView(entities)
            addView(reconnect)
            addView(Button(this@SettingsActivity).apply {
                text = "Save"
                setOnClickListener {
                    Prefs.setHaUrl(this@SettingsActivity, url.text.toString())
                    Prefs.setHaToken(this@SettingsActivity, token.text.toString())
                    Prefs.setDashboardRaw(this@SettingsActivity, entities.text.toString())
                    Prefs.setAutoReconnect(this@SettingsActivity, reconnect.isChecked)
                    Toast.makeText(this@SettingsActivity, "Settings saved", Toast.LENGTH_SHORT).show()
                    finish()
                }
            })
        })
    }
}
