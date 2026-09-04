package com.mekromn.continuitybrain

import android.app.Application
import com.mekromn.continuitybrain.data.BrainDatabase
import com.mekromn.continuitybrain.data.BrainRepository
import com.mekromn.continuitybrain.data.CryptoVault
import com.mekromn.continuitybrain.importer.ChatGptExportImporter

class ContinuityBrainApplication : Application() {
    val cryptoVault: CryptoVault by lazy { CryptoVault() }
    val brainDatabase: BrainDatabase by lazy { BrainDatabase(this) }
    val repository: BrainRepository by lazy {
        BrainRepository(
            context = this,
            database = brainDatabase,
            crypto = cryptoVault,
        )
    }
    val importer: ChatGptExportImporter by lazy {
        ChatGptExportImporter(
            resolver = contentResolver,
            repository = repository,
            crypto = cryptoVault,
        )
    }
}
