package com.example.spendwise.data

data class MerchantSeed(val displayName: String, val defaultCategoryName: String? = null)

val egyptMerchantSeeds = listOf(
    MerchantSeed("Carrefour"),
    MerchantSeed("Hyper One"),
    MerchantSeed("Spinneys"),
    MerchantSeed("Metro Market"),
    MerchantSeed("BIM"),
    MerchantSeed("Kazyon"),
    MerchantSeed("Seoudi"),
    MerchantSeed("Gourmet Egypt"),
    MerchantSeed("El Ezaby Pharmacy", "Medicine"),
    MerchantSeed("19011 Pharmacy", "Medicine"),
    MerchantSeed("Misr Pharmacies", "Medicine"),
    MerchantSeed("Talabat", "Restaurants"),
    MerchantSeed("McDonald's", "Restaurants"),
    MerchantSeed("KFC", "Restaurants"),
    MerchantSeed("Pizza Hut", "Restaurants"),
    MerchantSeed("Starbucks", "Restaurants"),
    MerchantSeed("Amazon Egypt"),
    MerchantSeed("Noon"),
    MerchantSeed("Jumia"),
    MerchantSeed("LC Waikiki", "Shopping"),
    MerchantSeed("DeFacto", "Shopping"),
    MerchantSeed("H&M", "Shopping"),
    MerchantSeed("Uber", "Transport"),
    MerchantSeed("inDrive", "Transport"),
    MerchantSeed("DiDi", "Transport"),
    MerchantSeed("Shell"),
    MerchantSeed("TotalEnergies"),
    MerchantSeed("ChillOut"),
    MerchantSeed("Vodafone", "Bills"),
    MerchantSeed("Orange", "Bills"),
    MerchantSeed("Etisalat by e&", "Bills"),
    MerchantSeed("WE", "Bills")
)

class MerchantSeeder(private val dao: MerchantDao) {
    suspend fun seed(categories: List<CategoryEntity>, now: Long = System.currentTimeMillis()) {
        val categoryIds = categories.associate { it.name to it.id }
        dao.insertAll(
            egyptMerchantSeeds.map { seed ->
                MerchantEntity(
                    displayName = seed.displayName,
                    normalizedName = normalizeMerchantName(seed.displayName),
                    usageCount = 0,
                    lastUsedAt = NEVER_USED_AT,
                    createdAt = now,
                    defaultCategoryId = seed.defaultCategoryName?.let(categoryIds::get),
                    source = MerchantSource.SEEDED
                )
            }
        )
    }

    companion object {
        const val NEVER_USED_AT = 0L
    }
}
