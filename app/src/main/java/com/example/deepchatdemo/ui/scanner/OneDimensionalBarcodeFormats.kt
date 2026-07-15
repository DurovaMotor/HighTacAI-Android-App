package com.example.deepchatdemo.ui.scanner

import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode

internal val oneDimensionalBarcodeFormats = intArrayOf(
    Barcode.FORMAT_CODE_128,
    Barcode.FORMAT_CODE_39,
    Barcode.FORMAT_CODE_93,
    Barcode.FORMAT_CODABAR,
    Barcode.FORMAT_EAN_13,
    Barcode.FORMAT_EAN_8,
    Barcode.FORMAT_ITF,
    Barcode.FORMAT_UPC_A,
    Barcode.FORMAT_UPC_E
)

internal fun BarcodeScannerOptions.Builder.setOneDimensionalBarcodeFormats(): BarcodeScannerOptions.Builder {
    return setBarcodeFormats(
        oneDimensionalBarcodeFormats.first(),
        *oneDimensionalBarcodeFormats.drop(1).toIntArray()
    )
}

internal fun isOneDimensionalBarcodeFormat(format: Int): Boolean {
    return format in oneDimensionalBarcodeFormats
}
