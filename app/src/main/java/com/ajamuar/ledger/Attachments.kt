package com.ajamuar.ledger

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class Receipts(private val context: Context, private val vault: Vault) {
    private fun file(id: String): File {
        java.util.UUID.fromString(id)
        return File(File(context.filesDir, "receipts").apply { check(mkdirs() || isDirectory) }, "$id.bin")
    }
    fun exists(id: String): Boolean = file(id).exists()
    fun save(id: String, bytes: ByteArray) {
        require(bytes.size <= 8_000_000) { "Choose an image smaller than 8 MB" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 12_000_000) { "Choose an image up to 12 megapixels" }
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "Unsupported receipt image" }
        val normalized = try { ByteArrayOutputStream().use { stream -> check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)); stream.toByteArray() } }
            finally { bitmap.recycle(); bytes.fill(0) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(vault.databaseKey(), "AES")); updateAAD("receipt:$id".toByteArray())
        }
        val encrypted = cipher.iv + cipher.doFinal(normalized)
        normalized.fill(0)
        val atomic = AtomicFile(file(id))
        val output = atomic.startWrite()
        try { output.write(encrypted); atomic.finishWrite(output) } catch (e: Exception) { atomic.failWrite(output); throw e }
    }
    fun load(id: String): Bitmap {
        val bytes = file(id).inputStream().use { it.readLimited(12_000_000) }
        require(bytes.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(vault.databaseKey(), "AES"), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            updateAAD("receipt:$id".toByteArray())
        }
        val plain = cipher.doFinal(bytes.copyOfRange(12, bytes.size))
        return try { requireNotNull(BitmapFactory.decodeByteArray(plain, 0, plain.size)) } finally { plain.fill(0) }
    }
    fun remove(id: String) { AtomicFile(file(id)).delete() }
}

fun pdfReport(entries: List<Entry>, title: String, output: java.io.OutputStream) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = android.graphics.Color.rgb(24, 62, 56) }
    val pdf = PdfDocument()
    try {
        val lines = listOf(title, "Net spending INR ${Money.decimal(entries.sumOf { it.personalSpend() })}", "Posted expenses less refunds; transfers and repayments excluded.", "") +
            entries.map { "${it.date()}  ${it.merchant.replace(Regex("[\\r\\n]"), " ").take(35)}  INR ${Money.decimal(it.amount)}  ${it.kind}${if (it.excluded) " (excluded)" else ""}" }
        lines.chunked(42).forEachIndexed { index, pageLines ->
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, index + 1).create())
            pageLines.forEachIndexed { line, text -> page.canvas.drawText(text, 36f, 48f + line * 18f, paint) }
            pdf.finishPage(page)
        }
        pdf.writeTo(output)
    } finally { pdf.close() }
}
