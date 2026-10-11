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

    fun updateState(newState: StreamState) {
        // postValue an toàn khi gọi từ luồng nền (background thread)
        _streamState.postValue(newState)
    }
}
