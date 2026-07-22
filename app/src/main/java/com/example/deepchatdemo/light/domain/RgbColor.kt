package com.example.deepchatdemo.light.domain

data class RgbColor(
    val red: Boolean,
    val green: Boolean,
    val blue: Boolean,
    val label: String = ""
) {
    val isOff: Boolean
        get() = !red && !green && !blue

    companion object {
        val OFF = RgbColor(red = false, green = false, blue = false)
        val RED = RgbColor(red = true, green = false, blue = false, label = "红")
        val GREEN = RgbColor(red = false, green = true, blue = false, label = "绿")
        val BLUE = RgbColor(red = false, green = false, blue = true, label = "蓝")
        val CYAN = RgbColor(red = false, green = true, blue = true, label = "青")
        val PURPLE = RgbColor(red = true, green = false, blue = true, label = "紫")
        val WHITE = RgbColor(red = true, green = true, blue = true, label = "白")
        val presets = listOf(RED, GREEN, BLUE, CYAN, PURPLE, WHITE)
    }
}
