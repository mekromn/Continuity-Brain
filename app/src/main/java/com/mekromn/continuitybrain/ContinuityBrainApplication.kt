package com.mekromn.continuitybrain

import android.app.Application
import com.mekromn.continuitybrain.backup.PortableBrainBackup
import com.mekromn.continuitybrain.data.BrainDatabase
import com.mekromn.continuitybrain.data.BrainRepository
import com.mekromn.continuitybrain.data.CryptoVault
import com.mekromn.continuitybrain.importer.ChatGptExportImporter
import com.mekromn.continuitybrain.retrieval.BrainRetrievalService
import com.mekromn.continuitybrain.semantic.LocalEmbeddingEngine
import com.mekromn.continuitybrain.semantic.SemanticIndex

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
    val portableBackup: PortableBrainBackup by lazy {
        PortableBrainBackup(
            database = brainDatabase,
            repository = repository,
            crypto = cryptoVault,
        )
    }
    val embeddingEngine: LocalEmbeddingEngine by lazy {
        LocalEmbeddingEngine(
            context = this,
            database = brainDatabase,
            crypto = cryptoVault,
        )
    }
    val semanticIndex: SemanticIndex by lazy {
        SemanticIndex(
            database = brainDatabase,
            repository = repository,
            crypto = cryptoVault,
            engine = embeddingEngine,
        )
    }
    val retrievalService: BrainRetrievalService by lazy {
        BrainRetrievalService(
            database = brainDatabase,
            repository = repository,
            crypto = cryptoVault,
            semanticIndex = semanticIndex,
        )
    }

    override fun onTerminate() {
        embeddingEngine.close()
        brainDatabase.close()
        super.onTerminate()
    }
}
