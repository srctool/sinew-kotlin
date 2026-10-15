package com.srctool.sinew.security

import com.srctool.sinew.models.PlatformContext
import kotlinx.coroutines.await
import kotlin.io.encoding.Base64
import kotlin.js.Promise

internal actual fun platformFieldCipher(platform: PlatformContext, keyAlias: String): FieldCipher =
    LayoutFieldCipher { RingKeys.open(WebCryptoAesGcm, IndexedDbRingStore(keyAlias)) }

internal actual fun fixedKeyCipher(key: ByteArray, keyId: Int): FieldCipher =
    LayoutFieldCipher { RingKeys.fixed(WebCryptoAesGcm, keyId, importAesKey(Base64.encode(key)).await()) }

/**
 * AES-256-GCM through Web Crypto, called directly: cryptography-kotlin's WebCrypto keys are always extractable, and
 * these keys are not. Each nonce comes from `crypto.getRandomValues`. Bytes cross into JavaScript as base64.
 */
internal object WebCryptoAesGcm : RingCrypto<JsAny> {
    override suspend fun newKey(): JsAny = generateAesKey().await()

    override fun newKeyId(): Int = randomInt()

    override suspend fun seal(key: JsAny, plain: ByteArray, associatedData: ByteArray): ByteArray =
        Base64.decode(aesGcmSeal(key, Base64.encode(plain), Base64.encode(associatedData)).await<JsString>().toString())

    override suspend fun open(key: JsAny, body: ByteArray, associatedData: ByteArray): ByteArray =
        Base64.decode(aesGcmOpen(key, Base64.encode(body), Base64.encode(associatedData)).await<JsString>().toString())
}

/**
 * The keyring in IndexedDB (database `sinew.security`, store `keyrings`, keyed by alias), its keys as non-extractable
 * `CryptoKey`s: a page's script can use them but never read them out. Anything else in the page's origin can use
 * them too, which is the documented web level.
 */
internal class IndexedDbRingStore(private val alias: String) : RingStore<JsAny> {
    override suspend fun load(): Keyring<JsAny>? {
        val record = ringLoad(alias).await<JsAny?>() ?: return null
        val keys = (0 until ringSize(record)).associate { ringId(record, it) to ringKey(record, it) }
        val primary = ringPrimary(record)
        return if (keys.isNotEmpty() && primary in keys) Keyring(primary, keys) else null
    }

    override suspend fun save(ring: Keyring<JsAny>) {
        val record = ringRecord(ring.primary)
        ring.keys.forEach { (id, key) -> ringAdd(record, id, key) }
        ringSave(alias, record).await<JsAny?>()
    }

    /** Deletes the keyring: tests start from an empty database. */
    suspend fun delete() {
        ringDelete(alias).await<JsAny?>()
    }
}

private fun generateAesKey(): Promise<JsAny> =
    js("crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt'])")

private fun importAesKey(raw: String): Promise<JsAny> =
    js("crypto.subtle.importKey('raw', Uint8Array.from(atob(raw), (c) => c.charCodeAt(0)), { name: 'AES-GCM' }, false, ['encrypt', 'decrypt'])")

private fun randomInt(): Int = js("crypto.getRandomValues(new Int32Array(1))[0]")

private fun aesGcmSeal(key: JsAny, plain: String, associatedData: String): Promise<JsString> = js(
    """(async () => {
        const bytes = (s) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
        const iv = crypto.getRandomValues(new Uint8Array(12));
        const sealed = new Uint8Array(await crypto.subtle.encrypt(
            { name: 'AES-GCM', iv: iv, additionalData: bytes(associatedData), tagLength: 128 }, key, bytes(plain)));
        const out = new Uint8Array(iv.length + sealed.length);
        out.set(iv);
        out.set(sealed, iv.length);
        let text = '';
        for (const b of out) text += String.fromCharCode(b);
        return btoa(text);
    })()""",
)

private fun aesGcmOpen(key: JsAny, body: String, associatedData: String): Promise<JsString> = js(
    """(async () => {
        const bytes = (s) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
        const all = bytes(body);
        const plain = new Uint8Array(await crypto.subtle.decrypt(
            { name: 'AES-GCM', iv: all.subarray(0, 12), additionalData: bytes(associatedData), tagLength: 128 },
            key, all.subarray(12)));
        let text = '';
        for (const b of plain) text += String.fromCharCode(b);
        return btoa(text);
    })()""",
)

internal fun ringRecord(primary: Int): JsAny = js("({ primary: primary, keys: [] })")

private fun ringAdd(record: JsAny, id: Int, key: JsAny): Unit = js("record.keys.push({ id: id, key: key })")

private fun ringPrimary(record: JsAny): Int = js("record.primary | 0")

private fun ringSize(record: JsAny): Int = js("Array.isArray(record.keys) ? record.keys.length : 0")

private fun ringId(record: JsAny, index: Int): Int = js("record.keys[index].id | 0")

private fun ringKey(record: JsAny, index: Int): JsAny = js("record.keys[index].key")

private fun ringLoad(alias: String): Promise<JsAny?> = js(
    """new Promise((resolve, reject) => {
        const open = indexedDB.open('sinew.security', 1);
        open.onupgradeneeded = () => open.result.createObjectStore('keyrings');
        open.onerror = () => reject(open.error);
        open.onsuccess = () => {
            const db = open.result;
            const get = db.transaction('keyrings', 'readonly').objectStore('keyrings').get(alias);
            get.onsuccess = () => { db.close(); resolve(get.result ?? null); };
            get.onerror = () => { db.close(); reject(get.error); };
        };
    })""",
)

internal fun ringSave(alias: String, record: JsAny): Promise<JsAny?> = js(
    """new Promise((resolve, reject) => {
        const open = indexedDB.open('sinew.security', 1);
        open.onupgradeneeded = () => open.result.createObjectStore('keyrings');
        open.onerror = () => reject(open.error);
        open.onsuccess = () => {
            const db = open.result;
            const tx = db.transaction('keyrings', 'readwrite');
            tx.objectStore('keyrings').put(record, alias);
            tx.oncomplete = () => { db.close(); resolve(null); };
            tx.onerror = () => { db.close(); reject(tx.error); };
            tx.onabort = () => { db.close(); reject(tx.error); };
        };
    })""",
)

private fun ringDelete(alias: String): Promise<JsAny?> = js(
    """new Promise((resolve, reject) => {
        const open = indexedDB.open('sinew.security', 1);
        open.onupgradeneeded = () => open.result.createObjectStore('keyrings');
        open.onerror = () => reject(open.error);
        open.onsuccess = () => {
            const db = open.result;
            const tx = db.transaction('keyrings', 'readwrite');
            tx.objectStore('keyrings').delete(alias);
            tx.oncomplete = () => { db.close(); resolve(null); };
            tx.onerror = () => { db.close(); reject(tx.error); };
        };
    })""",
)
