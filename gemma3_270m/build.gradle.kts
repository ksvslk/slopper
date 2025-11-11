plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("gemma3_270m")
    dynamicDelivery {
        deliveryType = "on-demand"
    }
}