package com.indrek.slopper
enum class Model(
    val displayName: String,
    val fileName: String,
    val location: ModelLocation
) {
    GEMMA_2B("Gemma 2B (Default)", "gemma-2b-it-2k.task", ModelLocation.ASSETS),
    LLAMA_3_2_1B("Llama 3.2 1B", "Llama-3.2-1B-Instruct_multi-prefill-seq_q8_ekv1280.task", ModelLocation.ASSETS),
    //GEMMA_3N_E4B("Gemma 3n E4B", "gemma-3n-E4B-it-int4.task", ModelLocation.EXTERNAL),
    PHI_4_MINI("Phi 4 Mini", "Phi-4-mini-instruct_multi-prefill-seq_q8_ekv1280.task", ModelLocation.EXTERNAL),
    GEMMA3_1B_IT("Gemma 3 1B", "Gemma3-1B-IT_seq128_q4_block128_ekv4096.task", ModelLocation.EXTERNAL),
}

enum class ModelLocation {
    ASSETS,      // Model is bundled in app assets
    EXTERNAL     // Model is in app's external files directory
}