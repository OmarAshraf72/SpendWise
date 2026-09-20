from __future__ import annotations

import re
import unicodedata
from pathlib import Path

import pandas as pd


CANONICAL_GROUPS = {
    "chicken_breast": ["Chicken Breast", "صدور فراخ", "Chicken فراخ مجمدة"],
    "milk_1l": ["Milk 1L", "لبن كامل الدسم", "Fresh Milk لبن", "M1LK 1L"],
    "rice_1kg": ["Rice 1KG", "كيس أرز 1 كيلو", "R1CE 1KG"],
    "banana": ["Banana", "موز بلدي", "Banana موز"],
    "tomatoes": ["Fresh Tomatoes", "طماطم", "Fresh طماطم", "T0MAT0ES"],
    "apples": ["Green Apples", "تفاح", "تفـاح"],
    "cucumber": ["Cucumber Pack", "خيار طازج"],
    "mcchicken_meal": ["McChicken Meal", "McCh1cken MeaI"],
    "margherita_pizza": ["Margherita Pizza", "بيتزا مارجريتا", "P1ZZA MARGHER1TA"],
    "coffee": ["Cappuccino", "قهوة لاتيه", "Coffee قهوة"],
    "burger_meal": ["وجبة برجر", "Burger وجبة"],
    "uber_trip": ["Uber Trip", "رحلة أوبر", "Uber مشوار", "UBER TR1P"],
    "careem_ride": ["Careem Ride", "مشوار كريم", "Careem رحلة", "CAR EEM R1DE"],
    "metro_ticket": ["Cairo Metro Ticket", "تذكرة مترو"],
    "gasoline_92": ["Gasoline 92", "بنزين 92"],
    "dettol_floor_cleaner": ["Dettol Floor Cleaner", "منظف أرضيات ديتول", "Dettol منظف أرضيات", "DettoI Floor C1eaner"],
    "fairy_dish_liquid": ["Fairy Dishwashing Liquid", "سائل غسيل أطباق", "Fairy صابون أطباق"],
    "persil_gel": ["Persil Gel", "مسحوق غسيل برسيل", "PersiI GeI"],
    "clorox_bleach": ["Clorox Bleach", "كلور مبيض"],
    "panadol_extra": ["Panadol Extra", "بنادول إكسترا", "Panadol مسكن", "PANAD0L EXTRA"],
    "brufen_400": ["Brufen 400", "بروفين 400"],
    "folic_acid_600_mcg": ["Folic Acid 600 MCG", "حمض الفوليك", "F0LIC ACID 600 MCG", "FoLic AciD 600 McG"],
    "immulant_plus_20_cap": ["Immulant Plus 20 Cap", "MLANT PLUS 20 CAP"],
    "vitamin_d": ["Vitamin D فيتامين"],
    "cotton_tshirt": ["Cotton T Shirt", "تيشيرت قطن"],
    "running_shoes": ["Running Shoes", "حذاء رياضي", "Nike حذاء"],
    "wireless_headphones": ["Wireless Headphones", "سماعات بلوتوث", "Headphones سماعات", "W1RELESS HEADPH0NES"],
    "phone_charger": ["Phone Charger", "شاحن موبايل", "PH0NE CHARGER"],
    "electricity_bill": ["Electricity Bill", "فاتورة كهرباء", "ELECTR1C1TY B1LL"],
    "internet_bill": ["WE Internet Bill", "فاتورة انترنت WE", "Internet فاتورة شهرية", "WE 1NTERNET B1LL"],
    "water_bill": ["Water Utility Bill", "فاتورة مياه"],
    "gas_bill": ["فاتورة غاز"],
    "mobile_bill": ["Mobile Phone Bill", "Orange فاتورة موبايل"],
    "netflix": ["Netflix", "اشتراك مشاهدة أفلام", "Netflix اشتراك", "NETFL1X"],
    "cinema_ticket": ["Cinema Ticket", "تذكرة سينما", "Cinema تذكرة", "C1NEMA T1CKET"],
    "playstation_game": ["PlayStation Game", "لعبة بلايستيشن"],
    "concert_ticket": ["Concert Ticket", "تذكرة حفلة"],
    "dove_shampoo": ["Dove Shampoo", "شامبو دوف", "Shampoo شامبو", "شامبو D0VE"],
    "nivea_face_cream": ["Nivea Face Cream", "كريم نيفيا للوجه", "Nivea كريم بشرة"],
    "gillette_razor": ["Gillette Razor", "شفرة حلاقة", "G1LLETTE RAZ0R"],
    "haircut": ["Haircut", "قص شعر"],
    "service_fee": ["Service Fee", "رسوم خدمة", "Service مصاريف", "SERV1CE FEE"],
    "charity_donation": ["Charity Donation", "تبرع خيري"],
    "document_printing": ["Document Printing", "طباعة مستندات"],
    "delivery_fee": ["Delivery Fee", "Delivery رسوم"],
    "misc_expense": ["مصاريف متنوعة", "M1SC EXPENSE"],
    "atomic_habits": ["Atomic Habits", "العادات الذرية", "Atomic Habits نسخة عربية", "AT0MIC HAB1TS"],
    "clean_code": ["Clean Code", "Clean Code كتاب", "CLEAN C0DE"],
    "psychology_of_money": ["The Psychology of Money"],
    "pragmatic_programmer": ["The Pragmatic Programmer"],
    "art_of_not_caring": ["كتاب فن اللامبالاة"],
    "awlad_haretna": ["رواية أولاد حارتنا"],
    "learn_python_book": ["كتاب تعلم بايثون"],
}


def _fallback_id(category: str, text: str, occurrence: int) -> str:
    ascii_text = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode().lower()
    slug = re.sub(r"[^a-z0-9]+", "_", ascii_text).strip("_")
    category_slug = re.sub(r"[^a-z0-9]+", "_", category.lower()).strip("_")
    return f"{category_slug}_{slug or occurrence}"


def build_v2_dataset(source: Path, destination: Path) -> pd.DataFrame:
    data = pd.read_csv(source)
    canonical_by_text = {
        text: canonical_id
        for canonical_id, texts in CANONICAL_GROUPS.items()
        for text in texts
    }
    canonical_ids = []
    used = set(CANONICAL_GROUPS)
    for index, row in data.iterrows():
        canonical_id = canonical_by_text.get(row["text"])
        if canonical_id is None:
            base = _fallback_id(row["category"], row["text"], index + 1)
            canonical_id = base
            suffix = 2
            while canonical_id in used:
                canonical_id = f"{base}_{suffix}"
                suffix += 1
        used.add(canonical_id)
        canonical_ids.append(canonical_id)
    result = data.copy()
    result.insert(0, "canonical_id", canonical_ids)
    destination.parent.mkdir(parents=True, exist_ok=True)
    result.to_csv(destination, index=False)
    return result


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    result = build_v2_dataset(
        root / "data" / "categorization_benchmark.csv",
        root / "data" / "categorization_benchmark_v2.csv",
    )
    print(
        f"Wrote {len(result)} examples with {result['canonical_id'].nunique()} canonical products."
    )


if __name__ == "__main__":
    main()
