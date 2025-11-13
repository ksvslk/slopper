package com.indrek.slopper.domain.model

enum class ModelDelivery {
    FAST_FOLLOW, // Automatically downloads after install
    ON_DEMAND    // Downloads only when the user clicks
}

// 2. Update your Model enum to use the new properties
enum class Model(
    val displayName: String,
    val assetPackName: String,  // The name of the module (e.g., "llama_3_2_1b")
    val fileNameInPack: String, // The file name *inside* the pack's assets
    val deliveryType: ModelDelivery, // Use the new enum here
    val licenseFileNameInPack: String // The license file *inside* the pack's assets
) {
    GEMMA3_1B_IT(
        "Gemma3-1B-IT",
        "gemma3_1b_it", // Make sure this matches your other module's name
        "Gemma3-1B-IT_seq128_q8_ekv1280.task",
        ModelDelivery.FAST_FOLLOW, // Set other models as ON_DEMAND
        "gemma3_1b_it_license.txt" // <-- Ensure this file exists in your asset pack
    ),
    GEMMA3_270M(
        "Gemma3-270m-IT",
        "gemma3_270m", // Make sure this matches your other module's name
        "gemma3-270m-it-q8.task",
        ModelDelivery.ON_DEMAND, // Set other models as ON_DEMAND
        "gemma3_270m_license.txt" // <-- Ensure this file exists in your asset pack
    ),
    QWEN2_5(
        "Qwen2.5-0.5B",
        "qwen2_5", // Make sure this matches your other module's name
        "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
        ModelDelivery.ON_DEMAND, // Set other models as ON_DEMAND
        "qwen2_5_license.txt" // <-- Ensure this file exists in your asset pack
    ),

}
