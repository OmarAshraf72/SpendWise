package com.example.spendwise.data

class MerchantRepository(private val dao: MerchantDao) {
    val merchants = dao.observeMerchants()

    suspend fun createIfMissing(displayName: String, now: Long): MerchantEntity? {
        val cleanName = displayName.trim().replace(Regex("\\s+"), " ")
        if (cleanName.isBlank()) return null
        val normalized = normalizeMerchantName(cleanName)
        dao.findByNormalizedName(normalized)?.let { return it }
        dao.insert(
            MerchantEntity(
                displayName = cleanName,
                normalizedName = normalized,
                usageCount = 0,
                lastUsedAt = 0,
                createdAt = now,
                defaultCategoryId = null,
                source = MerchantSource.USER
            )
        )
        return dao.findByNormalizedName(normalized)
    }

    suspend fun recordUse(displayName: String, categoryId: Long, now: Long): MerchantEntity? {
        val cleanName = displayName.trim().replace(Regex("\\s+"), " ")
        if (cleanName.isBlank()) return null
        val normalized = normalizeMerchantName(cleanName)
        val merchant = createIfMissing(cleanName, now)
        merchant ?: return null
        dao.recordUse(merchant.id, cleanName, categoryId, now)
        return merchant.copy(
            displayName = cleanName,
            usageCount = merchant.usageCount + 1,
            lastUsedAt = now,
            defaultCategoryId = categoryId,
            source = MerchantSource.USER
        )
    }
}
