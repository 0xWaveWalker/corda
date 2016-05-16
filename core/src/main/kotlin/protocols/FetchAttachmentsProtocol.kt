package protocols

import core.contracts.Attachment
import core.crypto.SecureHash
import core.crypto.sha256
import core.messaging.SingleMessageRecipient
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Given a set of hashes either loads from from local storage  or requests them from the other peer. Downloaded
 * attachments are saved to local storage automatically.
 */
class FetchAttachmentsProtocol(requests: Set<SecureHash>,
                               otherSide: SingleMessageRecipient) : FetchDataProtocol<Attachment, ByteArray>(requests, otherSide) {
    companion object {
        const val TOPIC = "platform.fetch.attachment"
    }

    override fun load(txid: SecureHash): Attachment? = serviceHub.storageService.attachments.openAttachment(txid)

    override val queryTopic: String = TOPIC

    override fun convert(wire: ByteArray): Attachment {
        return object : Attachment {
            override fun open(): InputStream = ByteArrayInputStream(wire)
            override val id: SecureHash = wire.sha256()
            override fun equals(other: Any?) = (other is Attachment) && other.id == id
            override fun hashCode(): Int = id.hashCode()
        }
    }

    override fun maybeWriteToDisk(downloaded: List<Attachment>) {
        for (attachment in downloaded) {
            serviceHub.storageService.attachments.importAttachment(attachment.open())
        }
    }
}