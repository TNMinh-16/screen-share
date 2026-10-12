package com.minh.screenshare.state

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/**
 * Trạng thái của phiên truyền màn hình.
 */
sealed class StreamState {
    object Idle : StreamState()
    object Connecting : StreamState()
    data class Streaming(val ip: String, val port: Int) : StreamState()
    data class Error(val message: String) : StreamState()
}

/**
 * Singleton quản lý trạng thái luồng truyền, giúp Service cập nhật trạng thái
 * cho MainActivity một cách đơn giản, an toàn qua LiveData.
 */
object AppState {
    private val _streamState = MutableLiveData<StreamState>(StreamState.Idle)
    val streamState: LiveData<StreamState> = _streamState

    private val _isControlEnabled = MutableLiveData<Boolean>(false)
    val isControlEnabled: LiveData<Boolean> = _isControlEnabled

    private val _isAccessibilityConnected = MutableLiveData<Boolean>(false)
    val isAccessibilityConnected: LiveData<Boolean> = _isAccessibilityConnected

    fun updateState(newState: StreamState) {
        if (newState is StreamState.Idle || newState is StreamState.Error) {
            _isControlEnabled.postValue(false)
        }
        // postValue an toàn khi gọi từ luồng nền (background thread)
        _streamState.postValue(newState)
    }

    fun setControlEnabled(enabled: Boolean) {
        _isControlEnabled.postValue(enabled)
    }

    fun setAccessibilityConnected(connected: Boolean) {
        _isAccessibilityConnected.postValue(connected)
    }
}
