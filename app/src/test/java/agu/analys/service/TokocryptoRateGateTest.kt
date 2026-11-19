package agu.analys.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gerbang jeda 429 untuk host gateway Tokocrypto.
 *
 * Yang dijaga: `429` **tidak** boleh menghasilkan percobaan ulang. Sebelum gerbang ini,
 * satu `429` berkembang menjadi `get()` 2× + `getWithFallback()` 2–3 URL + retry
 * `MtfCacheManager` 1×, yaitu 6–8 permintaan ke host yang sudah menolak.
 *
 * Kontrol negatif: kembalikan `code == 429 || code in 500..599` ke cabang retry di
 * `TokocryptoMarketService.get()` → uji `parseRetryAfterMs` tetap lolos tetapi perilaku
 * produksi kembali menembak host; naikkan [TokocryptoRateGate.MAX_COOLDOWN_MS] tanpa
 * koersi → uji "plafon jeda" GAGAL.
 */
class TokocryptoRateGateTest {

    private val t0 = 1_700_000_000_000L

    @After
    fun bersihkan() {
        TokocryptoRateGate.reset()
    }

    @Test
    fun `host resmi tidak dijaga gerbang`() {
        assertFalse(TokocryptoRateGate.appliesTo("https://www.tokocrypto.com/open/v1/common/symbols"))
        assertTrue(TokocryptoRateGate.appliesTo("https://www.tokocrypto.site/api/v3/ticker/24hr"))
        assertTrue(TokocryptoRateGate.appliesTo("https://cloudme-toko.2meta.app/api/v1/ticker/24hr"))
    }

    @Test
    fun `jeda kosong memakai bawaan dan menghormati Retry-After detik`() {
        assertEquals(
            TokocryptoRateGate.DEFAULT_COOLDOWN_MS,
            TokocryptoRateGate.parseRetryAfterMs(null, t0)
        )
        assertEquals(
            TokocryptoRateGate.DEFAULT_COOLDOWN_MS,
            TokocryptoRateGate.parseRetryAfterMs("   ", t0)
        )
        assertEquals(120_000L, TokocryptoRateGate.parseRetryAfterMs("120", t0))
        assertEquals(0L, TokocryptoRateGate.parseRetryAfterMs("0", t0))
    }

    @Test
    fun `Retry-After berupa tanggal HTTP dipakai sebagai selisih`() {
        // 2026-10-21T07:28:00Z = 1792567680000 ms; "now" satu menit sebelumnya.
        val header = "Wed, 21 Oct 2026 07:28:00 GMT"
        assertEquals(60_000L, TokocryptoRateGate.parseRetryAfterMs(header, 1_792_567_620_000L))
    }

    @Test
    fun `tanggal jauh di masa depan dipotong ke plafon`() {
        assertEquals(
            TokocryptoRateGate.MAX_COOLDOWN_MS,
            TokocryptoRateGate.parseRetryAfterMs("Wed, 21 Oct 2026 07:28:00 GMT", 1_700_000_000_000L)
        )
    }

    @Test
    fun `plafon jeda menahan Retry-After yang tidak wajar`() {
        assertEquals(
            TokocryptoRateGate.MAX_COOLDOWN_MS,
            TokocryptoRateGate.parseRetryAfterMs("999999", t0)
        )
    }

    @Test
    fun `trip menutup host lalu kedaluwarsa sendiri`() {
        assertFalse(TokocryptoRateGate.shouldBlock(t0))

        val until = TokocryptoRateGate.trip("30", t0)
        assertEquals(t0 + 30_000L, until)
        assertTrue(TokocryptoRateGate.shouldBlock(t0))
        assertTrue(TokocryptoRateGate.shouldBlock(t0 + 29_999L))
        assertFalse(TokocryptoRateGate.shouldBlock(t0 + 30_000L))
    }

    @Test
    fun `trip berikutnya tidak memperpendek jeda yang sedang berjalan`() {
        TokocryptoRateGate.trip("120", t0)
        val shorter = TokocryptoRateGate.trip(null, t0 + 1_000L)
        // `trip` kedua (60 detik bawaan dari t0+1s) berakhir sebelum jeda lama; jeda lama menang.
        assertEquals(t0 + 120_000L, shorter)
        assertTrue(TokocryptoRateGate.shouldBlock(t0 + 100_000L))
    }

    @Test
    fun `reset membuka gerbang`() {
        TokocryptoRateGate.trip("60", t0)
        assertTrue(TokocryptoRateGate.shouldBlock(t0))
        TokocryptoRateGate.reset()
        assertFalse(TokocryptoRateGate.shouldBlock(t0))
    }
}
