package com.rectrl.camera.example

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import com.rectrl.camera.Camera
import com.rectrl.camera.CameraXImpl
import com.rectrl.camera.example.databinding.ActivityLaunchBinding

class LaunchActivity : AppCompatActivity() {
    private lateinit var mBinding: ActivityLaunchBinding

    private lateinit var mCamera: Camera

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mBinding = ActivityLaunchBinding.inflate(layoutInflater, null, false)
        setContentView(mBinding.root)

        mCamera = CameraXImpl(this, this, mBinding.pvFinder.surfaceProvider)
        mCamera.startCamera()
    }
}
