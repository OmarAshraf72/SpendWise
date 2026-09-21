package com.example.spendwise.data

class CategoryRepository(private val dao: CategoryDao) {
    val categories = dao.observeCategories()
    val categoriesForEntry = dao.observeCategoriesForEntry()

    suspend fun getActiveCategories(): List<CategoryEntity> = dao.getActiveCategories()

    suspend fun addCustom(name: String) {
        dao.insert(
            CategoryEntity(
                name = name,
                type = CategoryType.CUSTOM,
                iconName = "menu_book",
                createdAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun renameCustom(id: Long, name: String) = dao.renameCustom(id, name)

    suspend fun archiveCustom(id: Long) = dao.archiveCustom(id)
}
