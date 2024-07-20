package com.example.casuelouh

data class Garment(
    val type: String,
    val coloring: String,
    val usage: String,
    val gender: String,
    val pattern: String
)

data class OutfitResponse(
    val outfit: List<Garment>,
    val hotPrompts: List<String>
)