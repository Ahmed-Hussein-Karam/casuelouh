package com.example.casuelouh.ui.theme

data class GoogleImagenApiResponse(
    val predictions: List<Prediction>
)

data class Prediction(
    val bytesBase64Encoded: String,
    val mimeType: String
)