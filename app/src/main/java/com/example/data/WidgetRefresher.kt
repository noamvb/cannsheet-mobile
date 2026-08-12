package com.example.data

/** Data-facing boundary for optional home-screen surfaces that mirror repository state. */
fun interface WidgetRefresher {
    fun refreshAll()
}

internal val NoOpWidgetRefresher = WidgetRefresher { }
