package com.example.catlifepet.floating

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.TextView
import com.example.catlifepet.R
import com.example.catlifepet.util.ScreenUtils

class PetBubbleView(context: Context) : TextView(context) {
    init {
        background = context.getDrawable(R.drawable.bubble_background)
        gravity = Gravity.CENTER
        textSize = 14f
        setTextColor(0xFF4A3128.toInt())
        maxLines = 2
        visibility = View.GONE
        elevation = ScreenUtils.dp(context, 6).toFloat()
    }

    fun showMessage(message: String) {
        text = message
        visibility = View.VISIBLE
        alpha = 0f
        animate().cancel()
        animate().alpha(1f).setDuration(140L).start()
    }

    fun hide() {
        animate().cancel()
        animate()
            .alpha(0f)
            .setDuration(140L)
            .withEndAction {
                visibility = View.GONE
                alpha = 1f
            }
            .start()
    }
}
