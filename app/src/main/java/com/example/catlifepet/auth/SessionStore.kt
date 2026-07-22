package com.example.catlifepet.auth

interface SessionStore {
    fun readRefreshToken(): String?
    fun writeRefreshToken(refreshToken: String)
    fun clear()
}
