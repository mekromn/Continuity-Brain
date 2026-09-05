package com.mekromn.continuitybrain.autosync

import com.mekromn.continuitybrain.ContinuityBrainApplication

val ContinuityBrainApplication.autoImportController: AutoImportController
    get() = AutoImportController(this, repository)
