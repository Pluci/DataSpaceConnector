package org.eclipse.dataspaceconnector.catalog.cache;

import net.jodah.failsafe.RetryPolicy;
import org.eclipse.dataspaceconnector.catalog.cache.controller.CatalogController;
import org.eclipse.dataspaceconnector.catalog.cache.crawler.CrawlerImpl;
import org.eclipse.dataspaceconnector.catalog.cache.loader.LoaderManagerImpl;
import org.eclipse.dataspaceconnector.catalog.cache.management.PartitionManagerImpl;
import org.eclipse.dataspaceconnector.catalog.cache.query.DefaultCacheQueryAdapter;
import org.eclipse.dataspaceconnector.catalog.cache.query.QueryEngineImpl;
import org.eclipse.dataspaceconnector.catalog.spi.CacheQueryAdapterRegistry;
import org.eclipse.dataspaceconnector.catalog.spi.CatalogQueryAdapterRegistry;
import org.eclipse.dataspaceconnector.catalog.spi.Crawler;
import org.eclipse.dataspaceconnector.catalog.spi.CrawlerErrorHandler;
import org.eclipse.dataspaceconnector.catalog.spi.FederatedCacheNodeDirectory;
import org.eclipse.dataspaceconnector.catalog.spi.FederatedCacheStore;
import org.eclipse.dataspaceconnector.catalog.spi.LoaderManager;
import org.eclipse.dataspaceconnector.catalog.spi.PartitionConfiguration;
import org.eclipse.dataspaceconnector.catalog.spi.PartitionManager;
import org.eclipse.dataspaceconnector.catalog.spi.QueryEngine;
import org.eclipse.dataspaceconnector.catalog.spi.WorkItem;
import org.eclipse.dataspaceconnector.catalog.spi.WorkItemQueue;
import org.eclipse.dataspaceconnector.catalog.spi.model.UpdateResponse;
import org.eclipse.dataspaceconnector.spi.monitor.Monitor;
import org.eclipse.dataspaceconnector.spi.protocol.web.WebService;
import org.eclipse.dataspaceconnector.spi.system.ServiceExtension;
import org.eclipse.dataspaceconnector.spi.system.ServiceExtensionContext;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static java.lang.String.format;

public class FederatedCatalogCacheExtension implements ServiceExtension {
    public static final int DEFAULT_NUM_CRAWLERS = 1;
    private static final int DEFAULT_QUEUE_LENGTH = 50;
    private static final int DEFAULT_BATCH_SIZE = 1;
    private static final int DEFAULT_RETRY_TIMEOUT_MILLIS = 2000;
    private LoaderManager loaderManager;
    private PartitionManager partitionManager;
    private PartitionConfiguration partitionManagerConfig;
    private Monitor monitor;
    private ArrayBlockingQueue<UpdateResponse> updateResponseQueue;

    @Override
    public Set<String> provides() {
        return Set.of(Crawler.FEATURE, LoaderManager.FEATURE, QueryEngine.FEATURE);
    }

    @Override
    public Set<String> requires() {
        return Set.of("edc:retry-policy", FederatedCacheNodeDirectory.FEATURE, CatalogQueryAdapterRegistry.FEATURE, CacheQueryAdapterRegistry.FEATURE, "edc:webservice", FederatedCacheStore.FEATURE);
    }

    @Override
    public void initialize(ServiceExtensionContext context) {
        // QUERY SUBSYSTEM
        var queryAdapterRegistry = context.getService(CacheQueryAdapterRegistry.class);

        var store = context.getService(FederatedCacheStore.class);
        queryAdapterRegistry.register(new DefaultCacheQueryAdapter(store));
        var webService = context.getService(WebService.class);
        var queryEngine = new QueryEngineImpl(queryAdapterRegistry);
        context.registerService(QueryEngine.class, queryEngine);
        monitor = context.getMonitor();
        var catalogController = new CatalogController(monitor, queryEngine);
        webService.registerController(catalogController);

        // CRAWLER SUBSYSTEM
        updateResponseQueue = new ArrayBlockingQueue<>(DEFAULT_QUEUE_LENGTH);

        //todo: maybe get this from a database or somewhere else?
        partitionManagerConfig = new PartitionConfiguration(context);

        // lets create a simple partition manager
        partitionManager = createPartitionManager(context, updateResponseQueue);

        // and a loader manager
        loaderManager = createLoaderManager(store);

        monitor.info("Federated Catalog Cache extension initialized");
    }

    @Override
    public void start() {
        partitionManager.schedule(partitionManagerConfig.getExecutionPlan());
        loaderManager.start(updateResponseQueue);
        monitor.info("Federated Catalog Cache extension started");
    }

    @Override
    public void shutdown() {
        partitionManager.stop();
        loaderManager.stop();
        monitor.info("Federated Catalog Cache extension stopped");
    }

    @NotNull
    private LoaderManager createLoaderManager(FederatedCacheStore store) {
        return LoaderManagerImpl.Builder.newInstance()
                .loaders(List.of(new DefaultLoader(store)))
                .batchSize(partitionManagerConfig.getLoaderBatchSize(DEFAULT_BATCH_SIZE))
                .waitStrategy(() -> partitionManagerConfig.getLoaderRetryTimeout(DEFAULT_RETRY_TIMEOUT_MILLIS))
                .monitor(monitor)
                .build();
    }

    @NotNull
    private PartitionManager createPartitionManager(ServiceExtensionContext context, ArrayBlockingQueue<UpdateResponse> updateResponseQueue) {

        // protocol registry - must be supplied by another extension
        var protocolAdapterRegistry = context.getService(CatalogQueryAdapterRegistry.class);

        // get all known nodes from node directory - must be supplied by another extension
        var directory = context.getService(FederatedCacheNodeDirectory.class);

        // use all nodes EXCEPT self
        Supplier<List<WorkItem>> nodes = () -> directory.getAll().stream()
                .filter(node -> !node.getName().equals(context.getConnectorId()))
                .map(n -> new WorkItem(n.getTargetUrl(), selectProtocol(n.getSupportedProtocols()))).collect(Collectors.toList());

        return new PartitionManagerImpl(monitor,
                new DefaultWorkItemQueue(partitionManagerConfig.getWorkItemQueueSize(10)),
                workItems -> createCrawler(workItems, context, protocolAdapterRegistry, updateResponseQueue),
                partitionManagerConfig.getNumCrawlers(DEFAULT_NUM_CRAWLERS),
                nodes);
    }


    private String selectProtocol(List<String> supportedProtocols) {
        //just take the first matching one.
        return supportedProtocols.isEmpty() ? null : supportedProtocols.get(0);
    }

    private Crawler createCrawler(WorkItemQueue workItems, ServiceExtensionContext context, CatalogQueryAdapterRegistry protocolAdapters, ArrayBlockingQueue<UpdateResponse> updateQueue) {
        var retryPolicy = (RetryPolicy<Object>) context.getService(RetryPolicy.class);
        return CrawlerImpl.Builder.newInstance()
                .monitor(context.getMonitor())
                .retryPolicy(retryPolicy)
                .workItems(workItems)
                .queue(updateQueue)
                .errorReceiver(getErrorWorkItemConsumer(context, workItems))
                .protocolAdapters(protocolAdapters)
                .workQueuePollTimeout(() -> Duration.ofMillis(2000 + new Random().nextInt(3000)))
                .build();
    }

    @NotNull
    private CrawlerErrorHandler getErrorWorkItemConsumer(ServiceExtensionContext context, WorkItemQueue workItems) {
        return workItem -> {
            if (workItem.getErrors().size() > 7) {
                context.getMonitor().severe(format("The following workitem has errored out more than 5 times. We'll discard it now: [%s]", workItem));
            } else {
                var random = new Random();
                var to = 5 + random.nextInt(20);
                context.getMonitor().info(format("The following work item has errored out. will re-queue after a small delay (%s)", to));
                Executors.newSingleThreadScheduledExecutor().schedule(() -> workItems.offer(workItem), to, TimeUnit.SECONDS);
            }
        };
    }
}