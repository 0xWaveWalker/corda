package net.corda.core.contracts

import net.corda.core.DoNotImplement
import net.corda.core.identity.Party
import net.corda.core.internal.extractFile
import net.corda.core.serialization.CordaSerializable
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.PublicKey
import java.util.jar.JarInputStream

/**
 * An attachment is a ZIP (or an optionally signed JAR) that contains one or more files. Attachments are meant to
 * contain public static data which can be referenced from transactions and utilised from contracts. Good examples
 * of how attachments are meant to be used include:
 *
 * - Calendar data
 * - Fixes (e.g. LIBOR)
 * - Smart contract code
 * - Legal documents
 * - Facts generated by oracles which might be reused a lot.
 *
 * At the moment, non-ZIP attachments are not supported. Support may come in a future release. Using ZIP files for
 * attachments makes it easy to ensure data on the ledger is compressed, which is useful considering that attachments
 * may be widely replicated around the network. It also allows the jarsigner tool to be used to sign an attachment
 * using ordinary certificates of the kind that many organisations already have, and improves the efficiency of
 * attachment resolution in cases where the attachment is logically made up of many small files - e.g. is bytecode.
 * Finally, using ZIPs ensures files have a timestamp associated with them, and enables informational attachments
 * to be password protected (although in current releases password protected ZIPs are likely to fail to work).
 */
@CordaSerializable
@DoNotImplement
interface Attachment : NamedByHash {
    fun open(): InputStream

    @JvmDefault
    fun openAsJAR(): JarInputStream {
        val stream = open()
        return try {
            JarInputStream(stream)
        } catch (e: IOException) {
            stream.use { throw e }
        }
    }

    /**
     * Finds the named file case insensitively and copies it to the output stream.
     * @throws [FileNotFoundException] if the given path doesn't exist in the attachment.
     */
    @JvmDefault
    fun extractFile(path: String, outputTo: OutputStream) = openAsJAR().use { it.extractFile(path, outputTo) }

    /**
     * The parties that have correctly signed the whole attachment.
     * Even though this returns a list of party objects, it is not required that these parties exist on the network, but rather they are a mapping from the signing key to the X.500 name.
     *
     * Note: Anyone can sign attachments, not only Corda parties. It's recommended to use [signerKeys].
     */
    @Deprecated("Use signerKeys. There is no requirement that attachment signers are Corda parties.")
    val signers: List<Party>

    /**
     * The keys that have correctly signed the whole attachment.
     * Can be empty, for example non-contract attachments won't be necessarily be signed.
     */
    val signerKeys: List<PublicKey>

    /**
     * Attachment size in bytes.
     */
    val size: Int
}
