package com.example.ckbwallet.data.crypto

import org.nervos.ckb.crypto.Blake2b as CkbBlake2b
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Blake2b-256 wrapper using official CKB SDK implementation.
 * Uses BouncyCastle's Blake2bDigest with CKB personalization.
 */
@Singleton
class Blake2b @Inject constructor() {

    /**
     * Hash input bytes using Blake2b-256 with CKB personalization.
     */
    fun hash(input: ByteArray): ByteArray {
        return CkbBlake2b.digest(input)
    }

    /**
     * Create a new hasher for incremental hashing.
     */
    fun newHasher(): Blake2bHasher = Blake2bHasher()
}

/**
 * Incremental Blake2b hasher using official CKB SDK.
 */
class Blake2bHasher {
    private val blake2b = CkbBlake2b()

    fun update(input: ByteArray): Blake2bHasher {
        blake2b.update(input)
        return this
    }

    fun finalize(): ByteArray {
        return blake2b.doFinal()
    }
}
