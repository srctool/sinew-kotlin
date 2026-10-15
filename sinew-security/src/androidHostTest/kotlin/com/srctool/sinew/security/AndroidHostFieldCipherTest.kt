package com.srctool.sinew.security

/** The contract on Android's Tink code in memory: the host has no Keystore (the device test has). */
class AndroidHostFieldCipherTest : FieldCipherContract() {
    private var keyset = MemoryKeyset(newKeyset())

    override suspend fun newCipher(): FieldCipher {
        keyset = MemoryKeyset(newKeyset())
        return reopen()
    }

    override suspend fun reopen(): FieldCipher = LayoutFieldCipher { TinkKeys(keyset) }
}
