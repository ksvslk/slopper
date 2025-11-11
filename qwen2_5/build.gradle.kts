plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("qwen2_5")
    dynamicDelivery {
        deliveryType = "on-demand"
    }
}