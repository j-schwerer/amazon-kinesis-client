/*
 * Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Amazon Software License (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 * http://aws.amazon.com/asl/
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.amazonaws.services.kinesis.clientlibrary.lib.worker;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.lang.Thread.State;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.amazonaws.services.dynamodbv2.local.embedded.DynamoDBEmbedded;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.kinesis.clientlibrary.exceptions.KinesisClientLibNonRetryableException;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.ICheckpoint;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessorCheckpointer;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.v2.IRecordProcessor;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.v2.IRecordProcessorFactory;
import com.amazonaws.services.kinesis.clientlibrary.lib.worker.Worker.WorkerCWMetricsFactory;
import com.amazonaws.services.kinesis.clientlibrary.lib.worker.Worker.WorkerThreadPoolExecutor;
import com.amazonaws.services.kinesis.clientlibrary.proxies.IKinesisProxy;
import com.amazonaws.services.kinesis.clientlibrary.proxies.KinesisLocalFileProxy;
import com.amazonaws.services.kinesis.clientlibrary.proxies.util.KinesisLocalFileDataCreator;
import com.amazonaws.services.kinesis.clientlibrary.types.ExtendedSequenceNumber;
import com.amazonaws.services.kinesis.clientlibrary.types.InitializationInput;
import com.amazonaws.services.kinesis.clientlibrary.types.ProcessRecordsInput;
import com.amazonaws.services.kinesis.clientlibrary.types.ShutdownInput;
import com.amazonaws.services.kinesis.clientlibrary.types.ShutdownReason;
import com.amazonaws.services.kinesis.leases.impl.KinesisClientLease;
import com.amazonaws.services.kinesis.leases.impl.KinesisClientLeaseManager;
import com.amazonaws.services.kinesis.leases.impl.LeaseManager;
import com.amazonaws.services.kinesis.leases.interfaces.ILeaseManager;
import com.amazonaws.services.kinesis.metrics.impl.CWMetricsFactory;
import com.amazonaws.services.kinesis.metrics.impl.NullMetricsFactory;
import com.amazonaws.services.kinesis.metrics.interfaces.IMetricsFactory;
import com.amazonaws.services.kinesis.model.HashKeyRange;
import com.amazonaws.services.kinesis.model.Record;
import com.amazonaws.services.kinesis.model.SequenceNumberRange;
import com.amazonaws.services.kinesis.model.Shard;
import com.amazonaws.services.kinesis.model.ShardIteratorType;

/**
 * Unit tests of Worker.
 */
public class WorkerTest {

    private static final Log LOG = LogFactory.getLog(WorkerTest.class);

    @Rule
    public Timeout timeout = new Timeout((int)TimeUnit.SECONDS.toMillis(30));

    private final NullMetricsFactory nullMetricsFactory = new NullMetricsFactory();
    private final long taskBackoffTimeMillis = 1L;
    private final long failoverTimeMillis = 5L;
    private final boolean callProcessRecordsForEmptyRecordList = false;
    private final long parentShardPollIntervalMillis = 5L;
    private final long shardSyncIntervalMillis = 5L;
    private final boolean cleanupLeasesUponShardCompletion = true;
    // We don't want any of these tests to run checkpoint validation
    private final boolean skipCheckpointValidationValue = false;
    private final InitialPositionInStream initialPositionInStream = InitialPositionInStream.LATEST;

    // CHECKSTYLE:IGNORE AnonInnerLengthCheck FOR NEXT 50 LINES
    private static final com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessorFactory SAMPLE_RECORD_PROCESSOR_FACTORY = 
            new com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessorFactory() {

        @Override
        public com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessor createProcessor() {
            return new com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessor() {

                @Override
                public void shutdown(IRecordProcessorCheckpointer checkpointer, ShutdownReason reason) {
                    if (reason == ShutdownReason.TERMINATE) {
                        try {
                            checkpointer.checkpoint();
                        } catch (KinesisClientLibNonRetryableException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }

                @Override
                public void processRecords(List<Record> dataRecords, IRecordProcessorCheckpointer checkpointer) {
                    try {
                        checkpointer.checkpoint();
                    } catch (KinesisClientLibNonRetryableException e) {
                        throw new RuntimeException(e);
                    }
                }

                @Override
                public void initialize(String shardId) {
                }
            };
        }
    };
    
    private static final IRecordProcessorFactory SAMPLE_RECORD_PROCESSOR_FACTORY_V2 = 
            new V1ToV2RecordProcessorFactoryAdapter(SAMPLE_RECORD_PROCESSOR_FACTORY);

    /**
     * Test method for {@link com.amazonaws.services.kinesis.clientlibrary.lib.worker.Worker#getApplicationName()}.
     */
    @Test
    public final void testGetStageName() {
        final String stageName = "testStageName";
        final KinesisClientLibConfiguration clientConfig =
                new KinesisClientLibConfiguration(stageName, null, null, null);
        Worker worker = 
                new Worker(mock(com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessorFactory.class), 
                        clientConfig);
        Assert.assertEquals(stageName, worker.getApplicationName());
    }

    @Test
    public final void testCreateOrGetShardConsumer() {
        final String stageName = "testStageName";
        IRecordProcessorFactory streamletFactory = SAMPLE_RECORD_PROCESSOR_FACTORY_V2;
        IKinesisProxy proxy = null;
        ICheckpoint checkpoint = null;
        int maxRecords = 1;
        int idleTimeInMilliseconds = 1000;
        StreamConfig streamConfig =
                new StreamConfig(proxy,
                        maxRecords,
                        idleTimeInMilliseconds,
                        callProcessRecordsForEmptyRecordList,
                        skipCheckpointValidationValue,
                        initialPositionInStream);
        final String testConcurrencyToken = "testToken";
        final String anotherConcurrencyToken = "anotherTestToken";
        final String dummyKinesisShardId = "kinesis-0-0";
        ExecutorService execService = null;

        KinesisClientLibLeaseCoordinator leaseCoordinator = mock(KinesisClientLibLeaseCoordinator.class);
        @SuppressWarnings("unchecked")
        ILeaseManager<KinesisClientLease> leaseManager = mock(ILeaseManager.class);
        when(leaseCoordinator.getLeaseManager()).thenReturn(leaseManager);

        Worker worker =
                new Worker(stageName,
                        streamletFactory,
                        streamConfig,
                        InitialPositionInStream.LATEST,
                        parentShardPollIntervalMillis,
                        shardSyncIntervalMillis,
                        cleanupLeasesUponShardCompletion,
                        checkpoint,
                        leaseCoordinator,
                        execService,
                        nullMetricsFactory,
                        taskBackoffTimeMillis,
                        failoverTimeMillis);
        ShardInfo shardInfo = new ShardInfo(dummyKinesisShardId, testConcurrencyToken, null);
        ShardConsumer consumer = worker.createOrGetShardConsumer(shardInfo, streamletFactory);
        Assert.assertNotNull(consumer);
        ShardConsumer consumer2 = worker.createOrGetShardConsumer(shardInfo, streamletFactory);
        Assert.assertSame(consumer, consumer2);
        ShardInfo shardInfoWithSameShardIdButDifferentConcurrencyToken =
                new ShardInfo(dummyKinesisShardId, anotherConcurrencyToken, null);
        ShardConsumer consumer3 =
                worker.createOrGetShardConsumer(shardInfoWithSameShardIdButDifferentConcurrencyToken, streamletFactory);
        Assert.assertNotNull(consumer3);
        Assert.assertNotSame(consumer3, consumer);
    }

    @Test
    public final void testCleanupShardConsumers() {
        final String stageName = "testStageName";
        IRecordProcessorFactory streamletFactory = SAMPLE_RECORD_PROCESSOR_FACTORY_V2;
        IKinesisProxy proxy = null;
        ICheckpoint checkpoint = null;
        int maxRecords = 1;
        int idleTimeInMilliseconds = 1000;
        StreamConfig streamConfig =
                new StreamConfig(proxy,
                        maxRecords,
                        idleTimeInMilliseconds,
                        callProcessRecordsForEmptyRecordList,
                        skipCheckpointValidationValue,
                        initialPositionInStream);
        final String concurrencyToken = "testToken";
        final String anotherConcurrencyToken = "anotherTestToken";
        final String dummyKinesisShardId = "kinesis-0-0";
        final String anotherDummyKinesisShardId = "kinesis-0-1";
        ExecutorService execService = null;

        KinesisClientLibLeaseCoordinator leaseCoordinator = mock(KinesisClientLibLeaseCoordinator.class);
        @SuppressWarnings("unchecked")
        ILeaseManager<KinesisClientLease> leaseManager = mock(ILeaseManager.class);
        when(leaseCoordinator.getLeaseManager()).thenReturn(leaseManager);

        Worker worker =
                new Worker(stageName,
                        streamletFactory,
                        streamConfig,
                        InitialPositionInStream.LATEST,
                        parentShardPollIntervalMillis,
                        shardSyncIntervalMillis,
                        cleanupLeasesUponShardCompletion,
                        checkpoint,
                        leaseCoordinator,
                        execService,
                        nullMetricsFactory,
                        taskBackoffTimeMillis,
                        failoverTimeMillis);

        ShardInfo shardInfo1 = new ShardInfo(dummyKinesisShardId, concurrencyToken, null);
        ShardInfo duplicateOfShardInfo1ButWithAnotherConcurrencyToken =
                new ShardInfo(dummyKinesisShardId, anotherConcurrencyToken, null);
        ShardInfo shardInfo2 = new ShardInfo(anotherDummyKinesisShardId, concurrencyToken, null);

        ShardConsumer consumerOfShardInfo1 = worker.createOrGetShardConsumer(shardInfo1, streamletFactory);
        ShardConsumer consumerOfDuplicateOfShardInfo1ButWithAnotherConcurrencyToken =
                worker.createOrGetShardConsumer(duplicateOfShardInfo1ButWithAnotherConcurrencyToken, streamletFactory);
        ShardConsumer consumerOfShardInfo2 = worker.createOrGetShardConsumer(shardInfo2, streamletFactory);

        Set<ShardInfo> assignedShards = new HashSet<ShardInfo>();
        assignedShards.add(shardInfo1);
        assignedShards.add(shardInfo2);
        worker.cleanupShardConsumers(assignedShards);

        // verify shard consumer not present in assignedShards is shut down
        Assert.assertTrue(consumerOfDuplicateOfShardInfo1ButWithAnotherConcurrencyToken.isBeginShutdown());
        // verify shard consumers present in assignedShards aren't shut down
        Assert.assertFalse(consumerOfShardInfo1.isBeginShutdown());
        Assert.assertFalse(consumerOfShardInfo2.isBeginShutdown());
    }

    @Test
    public final void testInitializationFailureWithRetries() {
        String stageName = "testInitializationWorker";
        IRecordProcessorFactory recordProcessorFactory = new TestStreamletFactory(null, null);
        IKinesisProxy proxy = mock(IKinesisProxy.class);
        int count = 0;
        when(proxy.getShardList()).thenThrow(new RuntimeException(Integer.toString(count++)));
        int maxRecords = 2;
        long idleTimeInMilliseconds = 1L;
        StreamConfig streamConfig =
                new StreamConfig(proxy,
                        maxRecords,
                        idleTimeInMilliseconds,
                        callProcessRecordsForEmptyRecordList,
                        skipCheckpointValidationValue,
                        initialPositionInStream);
        KinesisClientLibLeaseCoordinator leaseCoordinator = mock(KinesisClientLibLeaseCoordinator.class);
        @SuppressWarnings("unchecked")
        ILeaseManager<KinesisClientLease> leaseManager = mock(ILeaseManager.class);
        when(leaseCoordinator.getLeaseManager()).thenReturn(leaseManager);
        ExecutorService execService = Executors.newSingleThreadExecutor();
        long shardPollInterval = 0L;
        Worker worker =
                new Worker(stageName,
                        recordProcessorFactory,
                        streamConfig,
                        InitialPositionInStream.TRIM_HORIZON,
                        shardPollInterval,
                        shardSyncIntervalMillis,
                        cleanupLeasesUponShardCompletion,
                        leaseCoordinator,
                        leaseCoordinator,
                        execService,
                        nullMetricsFactory,
                        taskBackoffTimeMillis,
                        failoverTimeMillis);
        worker.run();
        Assert.assertTrue(count > 0);
    }

    /**
     * Runs worker with threadPoolSize == numShards
     * Test method for {@link com.amazonaws.services.kinesis.clientlibrary.lib.worker.Worker#run()}.
     */
    @Test
    public final void testRunWithThreadPoolSizeEqualToNumShards() throws Exception {
        final int numShards = 1;
        final int threadPoolSize = numShards;
        runAndTestWorker(numShards, threadPoolSize);
    }

    /**
     * Runs worker with threadPoolSize < numShards
     * Test method for {@link com.amazonaws.services.kinesis.clientlibrary.lib.worker.Worker#run()}.
     */
    @Test
    public final void testRunWithThreadPoolSizeLessThanNumShards() throws Exception {
        final int numShards = 3;
        final int threadPoolSize = 2;
        runAndTestWorker(numShards, threadPoolSize);
    }

    /**
     * Runs worker with threadPoolSize > numShards
     * Test method for {@link com.amazonaws.services.kinesis.clientlibrary.lib.worker.Worker#run()}.
     */
    @Test
    public final void testRunWithThreadPoolSizeMoreThanNumShards() throws Exception {
        final int numShards = 3;
        final int threadPoolSize = 5;
        runAndTestWorker(numShards, threadPoolSize);
    }

    /**
     * Runs worker with threadPoolSize < numShards
     * Test method for {@link com.amazonaws.services.kinesis.clientlibrary.lib.worker.Worker#run()}.
     */
    @Test
    public final void testOneSplitShard2Threads() throws Exception {
        final int threadPoolSize = 2;
        final int numberOfRecordsPerShard = 10;
        List<Shard> shardList = createShardListWithOneSplit();
        List<KinesisClientLease> initialLeases = new ArrayList<KinesisClientLease>();
        KinesisClientLease lease = ShardSyncer.newKCLLease(shardList.get(0));
        lease.setCheckpoint(new ExtendedSequenceNumber("2"));
        initialLeases.add(lease);
        runAndTestWorker(shardList, threadPoolSize, initialLeases, callProcessRecordsForEmptyRecordList, numberOfRecordsPerShard);
    }

    /**
     * Runs worker with threadPoolSize < numShards
     * Test method for {@link com.amazonaws.services.kinesis.clientlibrary.lib.worker.Worker#run()}.
     */
    @Test
    public final void testOneSplitShard2ThreadsWithCallsForEmptyRecords() throws Exception {
        final int threadPoolSize = 2;
        final int numberOfRecordsPerShard = 10;
        List<Shard> shardList = createShardListWithOneSplit();
        List<KinesisClientLease> initialLeases = new ArrayList<KinesisClientLease>();
        KinesisClientLease lease = ShardSyncer.newKCLLease(shardList.get(0));
        lease.setCheckpoint(new ExtendedSequenceNumber("2"));
        initialLeases.add(lease);
        boolean callProcessRecordsForEmptyRecordList = true;
        runAndTestWorker(shardList, threadPoolSize, initialLeases, callProcessRecordsForEmptyRecordList, numberOfRecordsPerShard);
    }

    @Test
    public final void testWorkerShutsDownOwnedResources() throws Exception {
        final WorkerThreadPoolExecutor executorService = mock(WorkerThreadPoolExecutor.class);
        final WorkerCWMetricsFactory cwMetricsFactory = mock(WorkerCWMetricsFactory.class);
        final long failoverTimeMillis = 20L;

        // Make sure that worker thread is run before invoking shutdown.
        final CountDownLatch workerStarted = new CountDownLatch(1);
        doAnswer(new Answer<Boolean>() {
            @Override
            public Boolean answer(InvocationOnMock invocation) throws Throwable {
                workerStarted.countDown();
                return false;
            }
        }).when(executorService).isShutdown();

        final WorkerThread workerThread = runWorker(Collections.<Shard>emptyList(),
                Collections.<KinesisClientLease>emptyList(),
                callProcessRecordsForEmptyRecordList,
                failoverTimeMillis,
                10,
                mock(IKinesisProxy.class),
                mock(IRecordProcessorFactory.class),
                executorService,
                cwMetricsFactory);

        // Give some time for thread to run.
        workerStarted.await();

        workerThread.getWorker().shutdown();
        workerThread.join();

        Assert.assertTrue(workerThread.getState() == State.TERMINATED);
        verify(executorService, times(1)).shutdownNow();
        verify(cwMetricsFactory, times(1)).shutdown();
    }

    @Test
    public final void testWorkerDoesNotShutdownClientResources() throws Exception {
        final ExecutorService executorService = mock(ThreadPoolExecutor.class);
        final CWMetricsFactory cwMetricsFactory = mock(CWMetricsFactory.class);
        final long failoverTimeMillis = 20L;

        // Make sure that worker thread is run before invoking shutdown.
        final CountDownLatch workerStarted = new CountDownLatch(1);
        doAnswer(new Answer<Boolean>() {
            @Override
            public Boolean answer(InvocationOnMock invocation) throws Throwable {
                workerStarted.countDown();
                return false;
            }
        }).when(executorService).isShutdown();

        final WorkerThread workerThread = runWorker(Collections.<Shard>emptyList(),
                Collections.<KinesisClientLease>emptyList(),
                callProcessRecordsForEmptyRecordList,
                failoverTimeMillis,
                10,
                mock(IKinesisProxy.class),
                mock(IRecordProcessorFactory.class),
                executorService,
                cwMetricsFactory);

        // Give some time for thread to run.
        workerStarted.await();

        workerThread.getWorker().shutdown();
        workerThread.join();

        Assert.assertTrue(workerThread.getState() == State.TERMINATED);
        verify(executorService, times(0)).shutdownNow();
        verify(cwMetricsFactory, times(0)).shutdown();
    }

    @Test
    public final void testWorkerNormalShutdown() throws Exception {
        final List<Shard> shardList = createShardListWithOneShard();
        final boolean callProcessRecordsForEmptyRecordList = true;
        final long failoverTimeMillis = 50L;
        final int numberOfRecordsPerShard = 1000;

        final List<KinesisClientLease> initialLeases = new ArrayList<KinesisClientLease>();
        for (Shard shard : shardList) {
            KinesisClientLease lease = ShardSyncer.newKCLLease(shard);
            lease.setCheckpoint(ExtendedSequenceNumber.TRIM_HORIZON);
            initialLeases.add(lease);
        }

        final File file = KinesisLocalFileDataCreator.generateTempDataFile(
                shardList, numberOfRecordsPerShard, "normalShutdownUnitTest");
        final IKinesisProxy fileBasedProxy = new KinesisLocalFileProxy(file.getAbsolutePath());

        final ExecutorService executorService = Executors.newCachedThreadPool();

        // Make test case as efficient as possible.
        final CountDownLatch processRecordsLatch = new CountDownLatch(1);
        IRecordProcessorFactory recordProcessorFactory = mock(IRecordProcessorFactory.class);
        IRecordProcessor recordProcessor = mock(IRecordProcessor.class);
        when(recordProcessorFactory.createProcessor()).thenReturn(recordProcessor);

        doAnswer(new Answer<Object> () {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                // Signal that record processor has started processing records.
                processRecordsLatch.countDown();
                return null;
            }
        }).when(recordProcessor).processRecords(any(ProcessRecordsInput.class));

        WorkerThread workerThread = runWorker(shardList,
                initialLeases,
                callProcessRecordsForEmptyRecordList,
                failoverTimeMillis,
                numberOfRecordsPerShard,
                fileBasedProxy,
                recordProcessorFactory,
                executorService,
                nullMetricsFactory);

        // Only sleep for time that is required.
        processRecordsLatch.await();

        // Make sure record processor is initialized and processing records.
        verify(recordProcessorFactory, times(1)).createProcessor();
        verify(recordProcessor, times(1)).initialize(any(InitializationInput.class));
        verify(recordProcessor, atLeast(1)).processRecords(any(ProcessRecordsInput.class));
        verify(recordProcessor, times(0)).shutdown(any(ShutdownInput.class));

        workerThread.getWorker().shutdown();
        workerThread.join();

        Assert.assertTrue(workerThread.getState() == State.TERMINATED);
        verify(recordProcessor, times(1)).shutdown(any(ShutdownInput.class));
    }

    @Test
    public final void testWorkerForcefulShutdown() throws Exception {
        final List<Shard> shardList = createShardListWithOneShard();
        final boolean callProcessRecordsForEmptyRecordList = true;
        final long failoverTimeMillis = 50L;
        final int numberOfRecordsPerShard = 10;

        final List<KinesisClientLease> initialLeases = new ArrayList<KinesisClientLease>();
        for (Shard shard : shardList) {
            KinesisClientLease lease = ShardSyncer.newKCLLease(shard);
            lease.setCheckpoint(ExtendedSequenceNumber.TRIM_HORIZON);
            initialLeases.add(lease);
        }

        final File file = KinesisLocalFileDataCreator.generateTempDataFile(
                shardList, numberOfRecordsPerShard, "normalShutdownUnitTest");
        final IKinesisProxy fileBasedProxy = new KinesisLocalFileProxy(file.getAbsolutePath());

        // Get executor service that will be owned by the worker, so we can get interrupts.
        ExecutorService executorService = getWorkerThreadPoolExecutor();

        // Make test case as efficient as possible.
        final CountDownLatch processRecordsLatch = new CountDownLatch(1);
        final AtomicBoolean recordProcessorInterrupted = new AtomicBoolean(false);
        IRecordProcessorFactory recordProcessorFactory = mock(IRecordProcessorFactory.class);
        IRecordProcessor recordProcessor = mock(IRecordProcessor.class);
        when(recordProcessorFactory.createProcessor()).thenReturn(recordProcessor);

        doAnswer(new Answer<Object> () {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                // Signal that record processor has started processing records.
                processRecordsLatch.countDown();

                // Block for some time now to test forceful shutdown. Also, check if record processor
                // was interrupted or not.
                final long totalSleepTimeMillis = failoverTimeMillis * 10;
                final long startTimeMillis = System.currentTimeMillis();
                long elapsedTimeMillis = 0;
                while (elapsedTimeMillis < totalSleepTimeMillis) {
                    try {
                        Thread.sleep(totalSleepTimeMillis);
                    } catch (InterruptedException e) {
                        recordProcessorInterrupted.getAndSet(true);
                    }
                    elapsedTimeMillis = System.currentTimeMillis() - startTimeMillis;
                }
                return null;
            }
        }).when(recordProcessor).processRecords(any(ProcessRecordsInput.class));

        WorkerThread workerThread = runWorker(shardList,
                initialLeases,
                callProcessRecordsForEmptyRecordList,
                failoverTimeMillis,
                numberOfRecordsPerShard,
                fileBasedProxy,
                recordProcessorFactory,
                executorService,
                nullMetricsFactory);

        // Only sleep for time that is required.
        processRecordsLatch.await();

        // Make sure record processor is initialized and processing records.
        verify(recordProcessorFactory, times(1)).createProcessor();
        verify(recordProcessor, times(1)).initialize(any(InitializationInput.class));
        verify(recordProcessor, atLeast(1)).processRecords(any(ProcessRecordsInput.class));
        verify(recordProcessor, times(0)).shutdown(any(ShutdownInput.class));

        workerThread.getWorker().shutdown();
        workerThread.join();

        Assert.assertTrue(workerThread.getState() == State.TERMINATED);
        // Shutdown should not be called in this case because record processor is blocked.
        verify(recordProcessor, times(0)).shutdown(any(ShutdownInput.class));
        Assert.assertTrue(recordProcessorInterrupted.get());
    }

    /**
     * Returns executor service that will be owned by the worker. This is useful to test the scenario
     * where worker shuts down the executor service also during shutdown flow.
     * @return Executor service that will be owned by the worker.
     */
    private WorkerThreadPoolExecutor getWorkerThreadPoolExecutor() {
        return new WorkerThreadPoolExecutor();
    }

    private List<Shard> createShardListWithOneShard() {
        List<Shard> shards = new ArrayList<Shard>();
        SequenceNumberRange range0 = ShardObjectHelper.newSequenceNumberRange("39428", "987324");
        HashKeyRange keyRange =
                ShardObjectHelper.newHashKeyRange(ShardObjectHelper.MIN_HASH_KEY, ShardObjectHelper.MAX_HASH_KEY);
        Shard shard0 = ShardObjectHelper.newShard("shardId-0", null, null, range0, keyRange);
        shards.add(shard0);

        return shards;
    }

    /**
     * @return
     */
    private List<Shard> createShardListWithOneSplit() {
        List<Shard> shards = new ArrayList<Shard>();
        SequenceNumberRange range0 = ShardObjectHelper.newSequenceNumberRange("39428", "987324");
        SequenceNumberRange range1 = ShardObjectHelper.newSequenceNumberRange("987325", null);
        HashKeyRange keyRange =
                ShardObjectHelper.newHashKeyRange(ShardObjectHelper.MIN_HASH_KEY, ShardObjectHelper.MAX_HASH_KEY);
        Shard shard0 = ShardObjectHelper.newShard("shardId-0", null, null, range0, keyRange);
        shards.add(shard0);

        Shard shard1 = ShardObjectHelper.newShard("shardId-1", "shardId-0", null, range1, keyRange);
        shards.add(shard1);

        return shards;
    }

    private void runAndTestWorker(int numShards, int threadPoolSize) throws Exception {
        final int numberOfRecordsPerShard = 10;
        final String kinesisShardPrefix = "kinesis-0-";
        final BigInteger startSeqNum = BigInteger.ONE;
        List<Shard> shardList = KinesisLocalFileDataCreator.createShardList(numShards, kinesisShardPrefix, startSeqNum);
        Assert.assertEquals(numShards, shardList.size());
        List<KinesisClientLease> initialLeases = new ArrayList<KinesisClientLease>();
        for (Shard shard : shardList) {
            KinesisClientLease lease = ShardSyncer.newKCLLease(shard);
            lease.setCheckpoint(ExtendedSequenceNumber.TRIM_HORIZON);
            initialLeases.add(lease);
        }
        runAndTestWorker(shardList, threadPoolSize, initialLeases, callProcessRecordsForEmptyRecordList, numberOfRecordsPerShard);
    }

    private void runAndTestWorker(List<Shard> shardList,
            int threadPoolSize,
            List<KinesisClientLease> initialLeases,
            boolean callProcessRecordsForEmptyRecordList,
            int numberOfRecordsPerShard) throws Exception {
        File file = KinesisLocalFileDataCreator.generateTempDataFile(shardList, numberOfRecordsPerShard, "unitTestWT001");
        IKinesisProxy fileBasedProxy = new KinesisLocalFileProxy(file.getAbsolutePath());

        Semaphore recordCounter = new Semaphore(0);
        ShardSequenceVerifier shardSequenceVerifier = new ShardSequenceVerifier(shardList);
        TestStreamletFactory recordProcessorFactory = new TestStreamletFactory(recordCounter, shardSequenceVerifier);

        ExecutorService executorService = Executors.newFixedThreadPool(threadPoolSize);

        WorkerThread workerThread = runWorker(
                shardList, initialLeases, callProcessRecordsForEmptyRecordList, failoverTimeMillis,
                numberOfRecordsPerShard, fileBasedProxy, recordProcessorFactory, executorService, nullMetricsFactory);

        // TestStreamlet will release the semaphore once for every record it processes
        recordCounter.acquire(numberOfRecordsPerShard * shardList.size());

        // Wait a bit to allow the worker to spin against the end of the stream.
        Thread.sleep(500L);

        testWorker(shardList, threadPoolSize, initialLeases, callProcessRecordsForEmptyRecordList,
                numberOfRecordsPerShard, fileBasedProxy, recordProcessorFactory);

        workerThread.getWorker().shutdown();
        executorService.shutdownNow();
        file.delete();
    }

    private WorkerThread runWorker(List<Shard> shardList,
            List<KinesisClientLease> initialLeases,
            boolean callProcessRecordsForEmptyRecordList,
            long failoverTimeMillis,
            int numberOfRecordsPerShard,
            IKinesisProxy kinesisProxy,
            IRecordProcessorFactory recordProcessorFactory,
            ExecutorService executorService,
            IMetricsFactory metricsFactory) throws Exception {
        final String stageName = "testStageName";
        final int maxRecords = 2;

        final long leaseDurationMillis = 10000L;
        final long epsilonMillis = 1000L;
        final long idleTimeInMilliseconds = 2L;

        AmazonDynamoDB ddbClient = DynamoDBEmbedded.create();
        LeaseManager<KinesisClientLease> leaseManager = new KinesisClientLeaseManager("foo", ddbClient);
        leaseManager.createLeaseTableIfNotExists(1L, 1L);
        for (KinesisClientLease initialLease : initialLeases) {
            leaseManager.createLeaseIfNotExists(initialLease);
        }

        KinesisClientLibLeaseCoordinator leaseCoordinator =
                new KinesisClientLibLeaseCoordinator(leaseManager,
                        stageName,
                        leaseDurationMillis,
                        epsilonMillis,
                        metricsFactory);

        StreamConfig streamConfig =
                new StreamConfig(kinesisProxy,
                        maxRecords,
                        idleTimeInMilliseconds,
                        callProcessRecordsForEmptyRecordList,
                        skipCheckpointValidationValue,
                        initialPositionInStream);

        Worker worker =
                new Worker(stageName,
                        recordProcessorFactory,
                        streamConfig,
                        InitialPositionInStream.TRIM_HORIZON,
                        parentShardPollIntervalMillis,
                        shardSyncIntervalMillis,
                        cleanupLeasesUponShardCompletion,
                        leaseCoordinator,
                        leaseCoordinator,
                        executorService,
                        metricsFactory,
                        taskBackoffTimeMillis,
                        failoverTimeMillis);

        WorkerThread workerThread = new WorkerThread(worker);
        workerThread.start();
        return workerThread;
    }

    private void testWorker(List<Shard> shardList,
            int threadPoolSize,
            List<KinesisClientLease> initialLeases,
            boolean callProcessRecordsForEmptyRecordList,
            int numberOfRecordsPerShard,
            IKinesisProxy kinesisProxy,
            TestStreamletFactory recordProcessorFactory) throws Exception {
        recordProcessorFactory.getShardSequenceVerifier().verify();

        // Gather values to compare across all processors of a given shard.
        Map<String, List<Record>> shardStreamletsRecords = new HashMap<String, List<Record>>();
        Map<String, ShutdownReason> shardsLastProcessorShutdownReason = new HashMap<String, ShutdownReason>();
        Map<String, Long> shardsNumProcessRecordsCallsWithEmptyRecordList = new HashMap<String, Long>();
        for (TestStreamlet processor : recordProcessorFactory.getTestStreamlets()) {
            String shardId = processor.getShardId();
            if (shardStreamletsRecords.get(shardId) == null) {
                shardStreamletsRecords.put(shardId, processor.getProcessedRecords());
            } else {
                List<Record> records = shardStreamletsRecords.get(shardId);
                records.addAll(processor.getProcessedRecords());
                shardStreamletsRecords.put(shardId, records);
            }
            if (shardsNumProcessRecordsCallsWithEmptyRecordList.get(shardId) == null) {
                shardsNumProcessRecordsCallsWithEmptyRecordList.put(shardId,
                        processor.getNumProcessRecordsCallsWithEmptyRecordList());
            } else {
                long totalShardsNumProcessRecordsCallsWithEmptyRecordList =
                        shardsNumProcessRecordsCallsWithEmptyRecordList.get(shardId)
                                + processor.getNumProcessRecordsCallsWithEmptyRecordList();
                shardsNumProcessRecordsCallsWithEmptyRecordList.put(shardId,
                        totalShardsNumProcessRecordsCallsWithEmptyRecordList);
            }
            shardsLastProcessorShutdownReason.put(processor.getShardId(), processor.getShutdownReason());
        }

        // verify that all records were processed at least once
        verifyAllRecordsOfEachShardWereConsumedAtLeastOnce(shardList, kinesisProxy, numberOfRecordsPerShard, shardStreamletsRecords);

        // within a record processor all the incoming records should be ordered
        verifyRecordsProcessedByEachProcessorWereOrdered(recordProcessorFactory);

        // for shards for which only one record processor was created, we verify that each record should be
        // processed exactly once
        verifyAllRecordsOfEachShardWithOnlyOneProcessorWereConsumedExactlyOnce(shardList,
                kinesisProxy,
                numberOfRecordsPerShard,
                shardStreamletsRecords,
                recordProcessorFactory);

        // if callProcessRecordsForEmptyRecordList flag is set then processors must have been invoked with empty record
        // sets else they shouldn't have seen invoked with empty record sets
        verifyNumProcessRecordsCallsWithEmptyRecordList(shardList,
                shardsNumProcessRecordsCallsWithEmptyRecordList,
                callProcessRecordsForEmptyRecordList);

        // verify that worker shutdown last processor of shards that were terminated
        verifyLastProcessorOfClosedShardsWasShutdownWithTerminate(shardList, shardsLastProcessorShutdownReason);
    }

    // within a record processor all the incoming records should be ordered
    private void verifyRecordsProcessedByEachProcessorWereOrdered(TestStreamletFactory recordProcessorFactory) {
        for (TestStreamlet processor : recordProcessorFactory.getTestStreamlets()) {
            List<Record> processedRecords = processor.getProcessedRecords();
            for (int i = 0; i < processedRecords.size() - 1; i++) {
                BigInteger sequenceNumberOfcurrentRecord = new BigInteger(processedRecords.get(i).getSequenceNumber());
                BigInteger sequenceNumberOfNextRecord = new BigInteger(processedRecords.get(i + 1).getSequenceNumber());
                Assert.assertTrue(sequenceNumberOfcurrentRecord.subtract(sequenceNumberOfNextRecord).signum() == -1);
            }
        }
    }

    // for shards for which only one record processor was created, we verify that each record should be
    // processed exactly once
    private void verifyAllRecordsOfEachShardWithOnlyOneProcessorWereConsumedExactlyOnce(List<Shard> shardList,
            IKinesisProxy fileBasedProxy,
            int numRecs,
            Map<String, List<Record>> shardStreamletsRecords,
            TestStreamletFactory recordProcessorFactory) {
        Map<String, TestStreamlet> shardIdsAndStreamLetsOfShardsWithOnlyOneProcessor =
                findShardIdsAndStreamLetsOfShardsWithOnlyOneProcessor(recordProcessorFactory);
        for (Shard shard : shardList) {
            String shardId = shard.getShardId();
            String iterator = fileBasedProxy.getIterator(shardId, ShardIteratorType.TRIM_HORIZON.toString(), null);
            List<Record> expectedRecords = fileBasedProxy.get(iterator, numRecs).getRecords();
            if (shardIdsAndStreamLetsOfShardsWithOnlyOneProcessor.containsKey(shardId)) {
                verifyAllRecordsWereConsumedExactlyOnce(expectedRecords,
                        shardIdsAndStreamLetsOfShardsWithOnlyOneProcessor.get(shardId).getProcessedRecords());
            }
        }
    }

    // verify that all records were processed at least once
    private void verifyAllRecordsOfEachShardWereConsumedAtLeastOnce(List<Shard> shardList,
            IKinesisProxy fileBasedProxy,
            int numRecs,
            Map<String, List<Record>> shardStreamletsRecords) {
        for (Shard shard : shardList) {
            String shardId = shard.getShardId();
            String iterator = fileBasedProxy.getIterator(shardId, ShardIteratorType.TRIM_HORIZON.toString(), null);
            List<Record> expectedRecords = fileBasedProxy.get(iterator, numRecs).getRecords();
            verifyAllRecordsWereConsumedAtLeastOnce(expectedRecords, shardStreamletsRecords.get(shardId));
        }

    }

    // verify that worker shutdown last processor of shards that were terminated
    private void verifyLastProcessorOfClosedShardsWasShutdownWithTerminate(List<Shard> shardList,
            Map<String, ShutdownReason> shardsLastProcessorShutdownReason) {
        for (Shard shard : shardList) {
            String shardId = shard.getShardId();
            String endingSequenceNumber = shard.getSequenceNumberRange().getEndingSequenceNumber();
            if (endingSequenceNumber != null) {
                LOG.info("Closed shard " + shardId + " has an endingSequenceNumber " + endingSequenceNumber);
                Assert.assertEquals(ShutdownReason.TERMINATE, shardsLastProcessorShutdownReason.get(shardId));
            }
        }
    }

    // if callProcessRecordsForEmptyRecordList flag is set then processors must have been invoked with empty record
    // sets else they shouldn't have seen invoked with empty record sets
    private void verifyNumProcessRecordsCallsWithEmptyRecordList(List<Shard> shardList,
            Map<String, Long> shardsNumProcessRecordsCallsWithEmptyRecordList,
            boolean callProcessRecordsForEmptyRecordList) {
        for (Shard shard : shardList) {
            String shardId = shard.getShardId();
            String endingSequenceNumber = shard.getSequenceNumberRange().getEndingSequenceNumber();
            // check only for open shards
            if (endingSequenceNumber == null) {
                if (callProcessRecordsForEmptyRecordList) {
                    Assert.assertTrue(shardsNumProcessRecordsCallsWithEmptyRecordList.get(shardId) > 0);
                } else {
                    Assert.assertEquals(0, (long) shardsNumProcessRecordsCallsWithEmptyRecordList.get(shardId));
                }
            }
        }
    }

    private Map<String, TestStreamlet>
            findShardIdsAndStreamLetsOfShardsWithOnlyOneProcessor(TestStreamletFactory recordProcessorFactory) {
        Map<String, TestStreamlet> shardIdsAndStreamLetsOfShardsWithOnlyOneProcessor =
                new HashMap<String, TestStreamlet>();
        Set<String> seenShardIds = new HashSet<String>();
        for (TestStreamlet processor : recordProcessorFactory.getTestStreamlets()) {
            String shardId = processor.getShardId();
            if (seenShardIds.add(shardId)) {
                shardIdsAndStreamLetsOfShardsWithOnlyOneProcessor.put(shardId, processor);
            } else {
                shardIdsAndStreamLetsOfShardsWithOnlyOneProcessor.remove(shardId);
            }
        }
        return shardIdsAndStreamLetsOfShardsWithOnlyOneProcessor;
    }

    //@formatter:off (gets the formatting wrong)
    private void verifyAllRecordsWereConsumedExactlyOnce(List<Record> expectedRecords,
            List<Record> actualRecords) {
        //@formatter:on
        Assert.assertEquals(expectedRecords.size(), actualRecords.size());
        ListIterator<Record> expectedIter = expectedRecords.listIterator();
        ListIterator<Record> actualIter = actualRecords.listIterator();
        for (int i = 0; i < expectedRecords.size(); ++i) {
            Assert.assertEquals(expectedIter.next(), actualIter.next());
        }
    }

    //@formatter:off (gets the formatting wrong)
    private void verifyAllRecordsWereConsumedAtLeastOnce(List<Record> expectedRecords,
            List<Record> actualRecords) {
        //@formatter:on
        ListIterator<Record> expectedIter = expectedRecords.listIterator();
        for (int i = 0; i < expectedRecords.size(); ++i) {
            Record expectedRecord = expectedIter.next();
            Assert.assertTrue(actualRecords.contains(expectedRecord));
        }
    }

    private static class WorkerThread extends Thread {
        private final Worker worker;

        private WorkerThread(Worker worker) {
            super(worker);
            this.worker = worker;
        }

        public Worker getWorker() {
            return worker;
        }
    }
}
