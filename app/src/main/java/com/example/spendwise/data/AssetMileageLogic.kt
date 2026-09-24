package com.example.spendwise.data

fun canUpdateAssetMileage(currentMileageKm: Long?, proposedMileageKm: Long): Boolean =
    proposedMileageKm >= 0 && (currentMileageKm == null || proposedMileageKm >= currentMileageKm)
