package com.example

import android.app.Application
import com.example.data.repository.DeliveryRepository

class DeliveryApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    instance = this
    DeliveryRepository.initialize(this)
  }

  companion object {
    lateinit var instance: DeliveryApplication
      private set
  }
}
