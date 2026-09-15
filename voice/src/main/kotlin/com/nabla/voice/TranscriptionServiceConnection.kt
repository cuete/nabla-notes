package com.nabla.voice

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TranscriptionServiceConnection : ServiceConnection {

    private val _service = MutableStateFlow<TranscriptionService?>(null)
    val service: StateFlow<TranscriptionService?> = _service.asStateFlow()

    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        _service.value = (binder as? TranscriptionService.TranscriptionBinder)?.getService()
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        _service.value = null
    }

    /** Explicitly clears the service reference after a clean unbind.
     * Android does NOT call onServiceDisconnected on clean unbind, so stale
     * references must be cleared manually before rebinding.
     */
    fun reset() {
        _service.value = null
    }
}
