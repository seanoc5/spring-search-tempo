package com.oconeco.spring_search_tempo.batch.onedrivesync

import com.microsoft.graph.models.BaseDeltaFunctionResponse
import com.microsoft.graph.models.DriveItem
import com.microsoft.graph.serviceclient.GraphServiceClient
import com.oconeco.spring_search_tempo.base.OneDriveAccountService
import com.oconeco.spring_search_tempo.base.service.OneDriveConnectionService
import org.slf4j.LoggerFactory
import org.springframework.batch.core.StepExecution
import org.springframework.batch.core.StepExecutionListener
import org.springframework.batch.item.ItemReader


/**
 * Wrapper around a Graph DriveItem with account context.
 */
data class GraphDriveItemWrapper(
    val driveItem: DriveItem,
    val accountId: Long,
    val driveId: String,
    val isDeleted: Boolean = false
)

/**
 * Pass 1 Reader: Reads items from OneDrive via Microsoft Graph delta API.
 *
 * If a delta token exists, performs incremental sync (only changes since last sync).
 * If no delta token (first sync or forced), performs full enumeration.
 *
 * Handles pagination via @odata.nextLink and saves the delta token from @odata.deltaLink.
 */
class OneDriveDeltaReader(
    private val connectionService: OneDriveConnectionService,
    private val accountService: OneDriveAccountService,
    private val accountId: Long,
    private val forceFullSync: Boolean = false
) : ItemReader<GraphDriveItemWrapper>, StepExecutionListener {

    companion object {
        private val log = LoggerFactory.getLogger(OneDriveDeltaReader::class.java)
        const val DELTA_TOKEN_KEY = "newDeltaToken"
        const val ITEMS_READ_KEY = "deltaItemsRead"
    }

    private var items: MutableList<GraphDriveItemWrapper> = mutableListOf()
    private var currentIndex = 0
    private var initialized = false
    private var newDeltaToken: String? = null
    private lateinit var stepExecution: StepExecution

    override fun beforeStep(stepExecution: StepExecution) {
        this.stepExecution = stepExecution
    }

    override fun afterStep(stepExecution: StepExecution): org.springframework.batch.core.ExitStatus {
        // Save the new delta token for the account
        if (newDeltaToken != null) {
            try {
                accountService.updateDeltaToken(accountId, newDeltaToken)
                log.info("Saved delta token for account {} after reading {} items",
                    accountId, items.size)
            } catch (e: Exception) {
                log.error("Failed to save delta token for account {}", accountId, e)
            }
        }

        // Store stats in execution context
        stepExecution.executionContext.putLong(ITEMS_READ_KEY, items.size.toLong())

        return org.springframework.batch.core.ExitStatus.COMPLETED
    }

    override fun read(): GraphDriveItemWrapper? {
        if (!initialized) {
            initialize()
        }

        if (currentIndex >= items.size) {
            return null
        }

        return items[currentIndex++]
    }

    private fun initialize() {
        initialized = true
        log.info("Initializing OneDrive delta sync for account {} (forceFullSync={})",
            accountId, forceFullSync)

        try {
            val client = connectionService.getGraphClient(accountId)
            val account = accountService.get(accountId)
            val driveId = account.driveId
                ?: throw RuntimeException("No drive ID configured for account $accountId")

            val savedDeltaToken = if (forceFullSync) null else account.deltaToken

            if (savedDeltaToken != null) {
                log.info("Performing incremental delta sync for account {}", accountId)
                fetchDeltaWithToken(client, driveId, savedDeltaToken)
            } else {
                log.info("Performing full delta enumeration for account {}", accountId)
                fetchFullDelta(client, driveId)
            }

            log.info("Delta sync loaded {} items for account {}", items.size, accountId)

        } catch (e: Exception) {
            log.error("Error initializing delta sync for account {}: {}", accountId, e.message, e)
            // Record error on account
            try {
                accountService.recordError(accountId, "Delta sync failed: ${e.message}")
            } catch (re: Exception) {
                log.warn("Failed to record error on account: {}", re.message)
            }
        }
    }

    private fun fetchFullDelta(client: GraphServiceClient, driveId: String) {
        var page: BaseDeltaFunctionResponse? = client.drives().byDriveId(driveId)
            .items().byDriveItemId("root").delta().get { config ->
                config.queryParameters.select = arrayOf(
                    "id", "name", "parentReference", "file", "folder",
                    "size", "deleted", "createdDateTime", "lastModifiedDateTime"
                )
            }

        while (page != null) {
            addItemsFromPage(page, driveId)

            val nextLink = page.odataNextLink
            if (nextLink == null) {
                newDeltaToken = page.odataDeltaLink
                break
            }

            // Fetch next page using the nextLink as token
            page = client.drives().byDriveId(driveId)
                .items().byDriveItemId("root").deltaWithToken(nextLink).get()
        }
    }

    private fun fetchDeltaWithToken(client: GraphServiceClient, driveId: String, deltaToken: String) {
        try {
            var page: BaseDeltaFunctionResponse? = client.drives().byDriveId(driveId)
                .items().byDriveItemId("root").deltaWithToken(deltaToken).get()

            while (page != null) {
                addItemsFromPage(page, driveId)

                val nextLink = page.odataNextLink
                if (nextLink == null) {
                    newDeltaToken = page.odataDeltaLink
                    break
                }

                page = client.drives().byDriveId(driveId)
                    .items().byDriveItemId("root").deltaWithToken(nextLink).get()
            }
        } catch (e: Exception) {
            // Handle 410 Gone - delta token expired, need full sync
            if (e.message?.contains("410") == true || e.message?.contains("resyncRequired") == true) {
                log.warn("Delta token expired for account {}, falling back to full enumeration", accountId)
                accountService.clearDeltaToken(accountId)
                items.clear()
                fetchFullDelta(client = connectionService.getGraphClient(accountId), driveId = driveId)
            } else {
                throw e
            }
        }
    }

    /**
     * Extract DriveItems from a delta response page and add to the items list.
     */
    private fun addItemsFromPage(page: BaseDeltaFunctionResponse, driveId: String) {
        // BaseDeltaFunctionResponse doesn't expose getValue() directly,
        // but both DeltaGetResponse and DeltaWithTokenGetResponse have it.
        // Use additionalData to get the value list, or cast to the specific type.
        val driveItems: List<DriveItem>? = when (page) {
            is com.microsoft.graph.drives.item.items.item.delta.DeltaGetResponse -> page.value
            is com.microsoft.graph.drives.item.items.item.deltawithtoken.DeltaWithTokenGetResponse -> page.value
            else -> null
        }

        driveItems?.forEach { driveItem: DriveItem ->
            val isDeleted = driveItem.deleted != null
            items.add(GraphDriveItemWrapper(
                driveItem = driveItem,
                accountId = accountId,
                driveId = driveId,
                isDeleted = isDeleted
            ))
        }
    }
}
