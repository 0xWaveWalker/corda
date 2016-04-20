package core.testing

import com.google.common.jimfs.Jimfs
import com.google.common.util.concurrent.MoreExecutors
import core.Party
import core.messaging.MessagingService
import core.messaging.SingleMessageRecipient
import core.node.AbstractNode
import core.node.NodeConfiguration
import core.node.NodeInfo
import core.node.PhysicalLocation
import core.node.services.FixedIdentityService
import core.node.services.ServiceType
import core.node.services.TimestamperService
import core.utilities.loggerFor
import org.slf4j.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A mock node brings up a suite of in-memory services in a fast manner suitable for unit testing.
 * Components that do IO are either swapped out for mocks, or pointed to a [Jimfs] in memory filesystem.
 *
 * Mock network nodes require manual pumping by default: they will not run asynchronous. This means that
 * for message exchanges to take place (and associated handlers to run), you must call the [runNetwork]
 * method.
 */
class MockNetwork(private val threadPerNode: Boolean = false,
                  private val defaultFactory: Factory = MockNetwork.DefaultFactory) {
    private var counter = 0
    val filesystem = Jimfs.newFileSystem(com.google.common.jimfs.Configuration.unix())
    val messagingNetwork = InMemoryMessagingNetwork()

    val identities = ArrayList<Party>()

    private val _nodes = ArrayList<MockNode>()
    /** A read only view of the current set of executing nodes. */
    val nodes: List<MockNode> = _nodes

    init {
        Files.createDirectory(filesystem.getPath("/nodes"))
    }

    /** Allows customisation of how nodes are created. */
    interface Factory {
        fun create(dir: Path, config: NodeConfiguration, network: MockNetwork,
                   timestamperAddr: NodeInfo?, id: Int): MockNode
    }

    object DefaultFactory : Factory {
        override fun create(dir: Path, config: NodeConfiguration, network: MockNetwork,
                            timestamperAddr: NodeInfo?, id: Int): MockNode {
            return MockNode(dir, config, network, timestamperAddr, id)
        }
    }

    open class MockNode(dir: Path, config: NodeConfiguration, val mockNet: MockNetwork,
                        withTimestamper: NodeInfo?, val id: Int) : AbstractNode(dir, config, withTimestamper, Clock.systemUTC()) {
        override val log: Logger = loggerFor<MockNode>()
        override val serverThread: ExecutorService =
                if (mockNet.threadPerNode)
                    Executors.newSingleThreadExecutor()
                else
                    MoreExecutors.newDirectExecutorService()

        // We only need to override the messaging service here, as currently everything that hits disk does so
        // through the java.nio API which we are already mocking via Jimfs.

        override fun makeMessagingService(): MessagingService {
            require(id >= 0) { "Node ID must be zero or positive, was passed: " + id }
            return mockNet.messagingNetwork.createNodeWithID(!mockNet.threadPerNode, id).start().get()
        }

        override fun makeIdentityService() = FixedIdentityService(mockNet.identities)

        // There is no need to slow down the unit tests by initialising CityDatabase
        override fun findMyLocation(): PhysicalLocation? = null

        override fun start(): MockNode {
            super.start()
            mockNet.identities.add(storage.myLegalIdentity)
            return this
        }

        val place: PhysicalLocation get() = info.physicalLocation!!
    }

    /** Returns a started node, optionally created by the passed factory method */
    fun createNode(withTimestamper: NodeInfo?, forcedID: Int = -1, nodeFactory: Factory = defaultFactory,
                   advertisedServices: Set<ServiceType> = emptySet()): MockNode {
        val newNode = forcedID == -1
        val id = if (newNode) counter++ else forcedID

        val path = filesystem.getPath("/nodes/$id")
        if (newNode)
            Files.createDirectories(path.resolve("attachments"))
        val config = object : NodeConfiguration {
            override val myLegalName: String = "Mock Company $id"
            override val exportJMXto: String = ""
            override val nearestCity: String = "Atlantis"
        }
        val node = nodeFactory.create(path, config, this, withTimestamper, id).start()
        node.info.advertisedServices = advertisedServices
        _nodes.add(node)
        return node
    }

    /**
     * Asks every node in order to process any queued up inbound messages. This may in turn result in nodes
     * sending more messages to each other, thus, a typical usage is to call runNetwork with the [rounds]
     * parameter set to -1 (the default) which simply runs as many rounds as necessary to result in network
     * stability (no nodes sent any messages in the last round).
     */
    fun runNetwork(rounds: Int = -1) {
        fun pumpAll() = messagingNetwork.endpoints.map { it.pump(false) }
        if (rounds == -1)
            while (pumpAll().any { it }) {
            }
        else
            repeat(rounds) { pumpAll() }
    }

    /**
     * Sets up a two node network in which the first node runs a timestamping service and the other doesn't.
     */
    fun createTwoNodes(nodeFactory: Factory = defaultFactory): Pair<MockNode, MockNode> {
        require(nodes.isEmpty())
        return Pair(
                createNode(null, -1, nodeFactory, setOf(TimestamperService.Type)),
                createNode(nodes[0].info, -1, nodeFactory)
        )
    }

    fun addressToNode(address: SingleMessageRecipient): MockNode = nodes.single { it.net.myAddress == address }
}