package com.example.spendwise.suggestion

object CategorySemanticMetadata {
    private val descriptions = mapOf(
        "Groceries" to "groceries, supermarket food, milk, rice, meat, chicken, pantry staples, packaged food, بقالة، سوبر ماركت، لبن، أرز، لحوم، فراخ، مواد غذائية",
        "Fruits & Vegetables" to "fresh fruit, vegetables, produce, apples, bananas, tomatoes, potatoes, فواكه، خضروات، خضار، تفاح، موز، طماطم، بطاطس",
        "Restaurants" to "restaurants, cafes, takeaway meals, fast food, coffee shops, dining, مطاعم، كافيهات، وجبات، أكل جاهز، قهوة",
        "Transport" to "transport, taxi, ride hailing, metro, bus, train, fuel, parking, مواصلات، تاكسي، أوبر، كريم، مترو، أتوبيس، بنزين، ركن",
        "Household" to "household supplies, cleaning products, detergent, dish soap, disinfectant, home maintenance, مستلزمات منزل، منظفات، مسحوق غسيل، صابون أطباق، مطهرات",
        "Medicine" to "medicines, pharmacy products, vitamins, medical supplies, drugs, supplements, أدوية، صيدلية، فيتامينات، مستلزمات طبية، مكملات غذائية",
        "Shopping" to "clothing, shoes, electronics, accessories, general retail shopping, online shopping, ملابس، أحذية، إلكترونيات، إكسسوارات، تسوق، مشتريات",
        "Bills" to "utility bills, electricity, water, gas, internet, mobile plan, subscriptions due, فواتير، كهرباء، مياه، غاز، إنترنت، موبايل",
        "Entertainment" to "movies, cinema, streaming, games, concerts, amusement, leisure activities, ترفيه، سينما، أفلام، ألعاب، حفلات، اشتراكات مشاهدة",
        "Personal Care" to "personal care, shampoo, soap, skincare, cosmetics, grooming, barber, salon, عناية شخصية، شامبو، صابون، عناية بالبشرة، مستحضرات تجميل، حلاق",
        "Other" to "miscellaneous items, uncategorized purchases, donations, fees, services, other expenses, مصاريف أخرى، متفرقات، رسوم، خدمات، تبرعات، غير مصنف",
        "Books" to "books, novels, textbooks, reading, literature, educational books, technical books, كتب، روايات، مراجع، كتب دراسية، قراءة، أدب"
    )

    private val lexicalTerms = mapOf(
        "Groceries" to setOf("milk", "rice", "chicken", "cheese", "tuna", "لبن", "ارز", "أرز", "فراخ", "جبنة"),
        "Fruits & Vegetables" to setOf(
            "tomato", "tomatoes", "banana", "apple", "orange", "pomegranate", "plum", "guava", "fig",
            "grape", "grapes", "strawberry", "mango", "cucumber", "potato", "onion", "pepper", "zucchini", "carrot",
            "طماطم", "موز", "تفاح", "برتقال", "رمان", "برقوق", "جوافة", "جوافه", "تين", "عنب",
            "فراولة", "فراوله", "مانجو", "خيار", "بطاطس", "بصل", "فلفل", "كوسة", "كوسه", "جزر"
        ),
        "Restaurants" to setOf("restaurant", "cafe", "pizza", "burger", "cappuccino", "latte", "kfc", "مطعم", "كافيه", "بيتزا", "برجر", "قهوة"),
        "Transport" to setOf("uber", "careem", "metro", "taxi", "fuel", "gasoline", "parking", "أوبر", "كريم", "مترو", "تاكسي", "بنزين"),
        "Household" to setOf("dettol", "detergent", "cleaner", "bleach", "persil", "clorox", "fairy", "منظف", "منظفات", "مطهر", "برسيل"),
        "Medicine" to setOf("panadol", "folic", "vitamin", "medicine", "pharmacy", "capsule", "tablet", "بنادول", "دواء", "صيدلية", "فيتامين"),
        "Shopping" to setOf("shirt", "shoes", "electronics", "clothing", "تيشيرت", "ملابس", "أحذية", "الكترونيات", "إلكترونيات"),
        "Bills" to setOf("bill", "electricity", "internet", "utility", "فاتورة", "فواتير", "كهرباء", "انترنت", "إنترنت"),
        "Entertainment" to setOf("cinema", "netflix", "movie", "game", "سينما", "فيلم", "العاب", "ألعاب"),
        "Personal Care" to setOf("shampoo", "skincare", "razor", "gillette", "salon", "شامبو", "عناية", "حلاق"),
        "Books" to setOf("book", "books", "novel", "textbook", "atomic", "رواية", "كتاب", "كتب")
    )

    fun descriptionFor(categoryName: String): String =
        descriptions[categoryName] ?: "$categoryName, $categoryName"

    fun lexicalTermsFor(categoryName: String): Set<String> =
        lexicalTerms[categoryName].orEmpty()
}
