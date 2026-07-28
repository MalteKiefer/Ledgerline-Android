package de.ledgerline.app.core.finance

import de.ledgerline.app.domain.model.PaymentMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentMethodsTest {

    @Test fun normalize_and_mask_iban() {
        assertEquals("DE89370400440532013000", PaymentMethods.normalizeIban("de89 3704 0044 0532 0130 00"))
        assertEquals("DE89 •••• •••• •••• •••• 3000", PaymentMethods.maskIban("DE89370400440532013000"))
        assertEquals("", PaymentMethods.maskIban(""))
        assertEquals("DE89 0000", PaymentMethods.maskIban("DE890000")) // <= 8 chars: just grouped
    }

    @Test fun card_network_and_mask() {
        assertEquals("visa", PaymentMethods.cardNetworkOf("4111 1111 1111 1111"))
        assertEquals("mastercard", PaymentMethods.cardNetworkOf("5500 0000 0000 0004"))
        assertEquals("amex", PaymentMethods.cardNetworkOf("3400 000000 00009"))
        assertEquals("other", PaymentMethods.cardNetworkOf("6011 0000 0000 0004"))
        assertEquals("•••• •••• •••• 1234", PaymentMethods.maskCard("9999 8888 7777 1234"))
    }

    @Test fun subtitle_per_type() {
        assertEquals("DE89 •••• •••• •••• •••• 3000", PaymentMethods.subtitle(PaymentMethod("a", type = "bank", iban = "DE89370400440532013000")))
        assertEquals("Visa · •••• •••• •••• 1234", PaymentMethods.subtitle(PaymentMethod("b", type = "card", cardNetwork = "visa", cardNumber = "4000000000001234")))
        assertEquals("me@x.io", PaymentMethods.subtitle(PaymentMethod("c", type = "paypal", email = "me@x.io")))
        assertEquals("", PaymentMethods.subtitle(PaymentMethod("d", type = "cash")))
    }

    @Test fun validity_rules() {
        assertFalse(PaymentMethods.isValid(PaymentMethod("a", type = "bank", label = "")))               // no label
        assertFalse(PaymentMethods.isValid(PaymentMethod("a", type = "bank", label = "Giro")))            // bank needs iban/acct
        assertTrue(PaymentMethods.isValid(PaymentMethod("a", type = "bank", label = "Giro", iban = "DE1")))
        assertTrue(PaymentMethods.isValid(PaymentMethod("a", type = "cash", label = "Kasse")))            // cash: label only
        assertFalse(PaymentMethods.isValid(PaymentMethod("a", type = "card", label = "V")))               // card needs number
    }

    @Test fun sorted_banks_first_then_by_label() {
        val list = listOf(
            PaymentMethod("1", type = "card", label = "Zebra"),
            PaymentMethod("2", type = "bank", label = "Beta"),
            PaymentMethod("3", type = "bank", label = "Alpha"),
            PaymentMethod("4", type = "cash", label = "Kasse", trashed = true),
        )
        assertEquals(listOf("Alpha", "Beta", "Zebra"), PaymentMethods.sorted(list).map { it.label })
    }
}
