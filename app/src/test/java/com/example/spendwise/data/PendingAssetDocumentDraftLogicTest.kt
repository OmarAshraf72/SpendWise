package com.example.spendwise.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingAssetDocumentDraftLogicTest {
    @Test fun invoiceWarrantyAndContractRemainVisibleUntilRemoved() {
        val invoice = pending("invoice", AssetDocumentType.INVOICE)
        val warranty = pending("warranty", AssetDocumentType.WARRANTY_CARD)
        val contract = pending("contract", AssetDocumentType.PURCHASE_CONTRACT)
        val first = PendingAssetDocumentDraftLogic.add(emptyList(), invoice)
        assertEquals(listOf(invoice), first)
        val all = PendingAssetDocumentDraftLogic.add(PendingAssetDocumentDraftLogic.add(first, warranty), contract)
        assertEquals(listOf(invoice, warranty, contract), all)
        assertEquals(listOf(invoice, contract), PendingAssetDocumentDraftLogic.remove(all, warranty))
        assertTrue(first.isNotEmpty()) // Drafts do not depend on an asset ID or document action.
    }

    private fun pending(name: String, type: AssetDocumentType) = PendingAssetDocument(
        "assets/document-drafts/00000000-0000-0000-0000-000000000000/$name",
        type, type.displayTitle(), "$name.pdf", "application/pdf", 3
    )
}
