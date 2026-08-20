package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.domain.EXTRA_OPEN_CART_PICKER
import com.example.domain.EXTRA_START_ROUTE
import com.example.ui.CannsheetApp
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

class MainActivity : ComponentActivity() {
  private val routeRequests = Channel<String>(capacity = Channel.BUFFERED)
  private val routeRequestFlow = routeRequests.receiveAsFlow()
  private val pickerRequests = Channel<Unit>(capacity = Channel.CONFLATED)
  private val pickerRequestFlow = pickerRequests.receiveAsFlow()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    val startRoute = intent.consumeStartRoute()
    if (intent.consumeOpenCartPicker()) {
      pickerRequests.trySend(Unit)
    }
    render(startRoute)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    intent.consumeStartRoute()?.let(routeRequests::trySend)
    if (intent.consumeOpenCartPicker()) {
      pickerRequests.trySend(Unit)
    }
  }

  private fun render(startRoute: String?) {
    setContent {
      MyApplicationTheme {
        CannsheetApp(
          startDestination = startRoute ?: "consumption",
          routeRequests = routeRequestFlow,
          openCartPickerRequests = pickerRequestFlow,
        )
      }
    }
  }
}

internal fun Intent.consumeStartRoute(): String? =
  getStringExtra(EXTRA_START_ROUTE)?.also { removeExtra(EXTRA_START_ROUTE) }

internal fun Intent.consumeOpenCartPicker(): Boolean =
  getBooleanExtra(EXTRA_OPEN_CART_PICKER, false).also {
    if (it) removeExtra(EXTRA_OPEN_CART_PICKER)
  }
