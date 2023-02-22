package com.rectrl.camera

interface Camera {
    fun startCamera()
    fun stopCamera()
    fun setImageAvailableListener(listener: OnImageAvailableListener)

    fun interface OnImageAvailableListener {
        fun onImageAvailable(nv21: ByteArray, width: Int, height: Int)
    }
}
