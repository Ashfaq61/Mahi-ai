package com.example.calls

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.example.MainActivity

/**
 * Required ACTION_DIAL handler for Android RoleManager.ROLE_DIALER eligibility.
 * Forwards tel: URIs directly into Mahi AI's Call Console without duplicating logic.
 */
class MahiDialerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val number = intent?.data?.schemeSpecificPart.orEmpty()
        val launch = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("open_tab", "CALLS")
            if (number.isNotBlank()) {
                putExtra("dial_number", number)
            }
        }
        startActivity(launch)
        finish()
    }
}
