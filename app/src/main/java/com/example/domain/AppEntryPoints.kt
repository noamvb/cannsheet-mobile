package com.example.domain

/**
 * The start-route extra shared by every out-of-app entry point (home-screen
 * widget, notifications). Lives in domain for the same reason the pen-state
 * helpers do: ui -> widget -> ui and notifications -> widget are both cycles
 * waiting to happen.
 */
const val EXTRA_START_ROUTE = "com.noamv.cannsheet.mobile.widget.START_ROUTE"

/** Set alongside [EXTRA_START_ROUTE] to request the cart picker on arrival. */
const val EXTRA_OPEN_CART_PICKER = "com.noamv.cannsheet.mobile.widget.OPEN_CART_PICKER"
