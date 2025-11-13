package com.indrek.slopper.data

import com.indrek.slopper.C.FREE_TIER_LIMIT

data class UserLimitData(
    val remaining: Int = FREE_TIER_LIMIT,
    val total: Int = FREE_TIER_LIMIT,
    val isReady: Boolean = false // Is the trusted time ready?
)