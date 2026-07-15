package com.example.deepchatdemo.ui.scanner

import com.google.mlkit.vision.barcode.common.Barcode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OneDimensionalBarcodeFormatsTest {
    @Test
    fun commonOneDimensionalFormatsAreSupported() {
        listOf(
            Barcode.FORMAT_CODE_128,
            Barcode.FORMAT_CODE_39,
            Barcode.FORMAT_CODE_93,
            Barcode.FORMAT_CODABAR,
            Barcode.FORMAT_EAN_13,
            Barcode.FORMAT_EAN_8,
            Barcode.FORMAT_ITF,
            Barcode.FORMAT_UPC_A,
            Barcode.FORMAT_UPC_E
        ).forEach { format ->
            assertTrue(isOneDimensionalBarcodeFormat(format))
        }
    }

    @Test
    fun twoDimensionalFormatsAreRejected() {
        listOf(
            Barcode.FORMAT_QR_CODE,
            Barcode.FORMAT_AZTEC,
            Barcode.FORMAT_DATA_MATRIX,
            Barcode.FORMAT_PDF417
        ).forEach { format ->
            assertFalse(isOneDimensionalBarcodeFormat(format))
        }
    }
}
