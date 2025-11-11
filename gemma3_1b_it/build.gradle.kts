plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("gemma3_1b_it")
    dynamicDelivery {
        deliveryType = "fast-follow"
    }
}