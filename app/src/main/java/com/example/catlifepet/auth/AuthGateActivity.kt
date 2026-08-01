package com.example.catlifepet.auth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.example.catlifepet.MainActivity

class AuthGateActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val next = if (AuthGraph.repository(this).hasStoredSession()) {
            Intent(this, MainActivity::class.java)
        } else {
            AuthActivity.requiredLoginIntent(this)
        }
        startActivity(next)
        finish()
    }
}
