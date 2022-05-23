package com.example.bledemo

import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.annotation.RequiresApi
import com.example.bledemo.bluetooth.BLEServer

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }

    // Run the chat server as long as the app is on screen
    @RequiresApi(Build.VERSION_CODES.N)
    override fun onStart() {
        super.onStart()
        BLEServer.startServer(application)
    }

    override fun onStop() {
        super.onStop()
        BLEServer.stopServer()
    }
}