/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.memory;

import io.airlift.stats.TestingGcMonitor;
import io.airlift.units.DataSize;
import io.trino.ExceededMemoryLimitException;
import io.trino.execution.StageId;
import io.trino.execution.TaskId;
import io.trino.execution.TaskStateMachine;
import io.trino.memory.context.LocalMemoryContext;
import io.trino.memory.context.MemoryTrackingContext;
import io.trino.operator.DriverContext;
import io.trino.operator.DriverStats;
import io.trino.operator.OperatorContext;
import io.trino.operator.OperatorStats;
import io.trino.operator.PipelineContext;
import io.trino.operator.PipelineStats;
import io.trino.operator.TaskContext;
import io.trino.operator.TaskStats;
import io.trino.spi.QueryId;
import io.trino.spi.memory.MemoryPoolId;
import io.trino.spiller.SpillSpaceTracker;
import io.trino.sql.planner.plan.PlanNodeId;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

import static com.google.common.collect.Iterables.getOnlyElement;
import static io.airlift.concurrent.Threads.daemonThreadsNamed;
import static io.airlift.units.DataSize.Unit.GIGABYTE;
import static io.trino.testing.TestingSession.testSessionBuilder;
import static java.lang.String.format;
import static java.util.concurrent.Executors.newCachedThreadPool;
import static java.util.concurrent.Executors.newScheduledThreadPool;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

@Test(singleThreaded = true)
public class TestMemoryTracking
{
    private static final DataSize queryMaxMemory = DataSize.of(1, GIGABYTE);
    private static final DataSize queryMaxTotalMemory = DataSize.of(1, GIGABYTE);
    private static final DataSize memoryPoolSize = DataSize.of(1, GIGABYTE);
    private static final DataSize maxSpillSize = DataSize.of(1, GIGABYTE);
    private static final DataSize queryMaxSpillSize = DataSize.of(1, GIGABYTE);
    private static final SpillSpaceTracker spillSpaceTracker = new SpillSpaceTracker(maxSpillSize);

    private QueryContext queryContext;
    private TaskContext taskContext;
    private PipelineContext pipelineContext;
    private DriverContext driverContext;
    private OperatorContext operatorContext;
    private MemoryPool memoryPool;
    private ExecutorService notificationExecutor;
    private ScheduledExecutorService yieldExecutor;

    @BeforeClass
    public void setUp()
    {
        notificationExecutor = newCachedThreadPool(daemonThreadsNamed("local-query-runner-executor-%s"));
        yieldExecutor = newScheduledThreadPool(2, daemonThreadsNamed("local-query-runner-scheduler-%s"));
    }

    @AfterClass(alwaysRun = true)
    public void tearDown()
    {
        notificationExecutor.shutdownNow();
        yieldExecutor.shutdownNow();
        queryContext = null;
        taskContext = null;
        pipelineContext = null;
        driverContext = null;
        operatorContext = null;
        memoryPool = null;
    }

    @BeforeMethod
    public void setUpTest()
    {
        memoryPool = new MemoryPool(new MemoryPoolId("test"), memoryPoolSize);
        queryContext = new QueryContext(
                new QueryId("test_query"),
                queryMaxMemory,
                queryMaxTotalMemory,
                memoryPool,
                new TestingGcMonitor(),
                notificationExecutor,
                yieldExecutor,
                queryMaxSpillSize,
                spillSpaceTracker);
        taskContext = queryContext.addTaskContext(
                new TaskStateMachine(new TaskId(new StageId("query", 0), 0, 0), notificationExecutor),
                testSessionBuilder().build(),
                () -> {},
                true,
                true);
        pipelineContext = taskContext.addPipelineContext(0, true, true, false);
        driverContext = pipelineContext.addDriverContext();
        operatorContext = driverContext.addOperatorContext(1, new PlanNodeId("a"), "test-operator");
    }

    @Test
    public void testOperatorAllocations()
    {
        MemoryTrackingContext operatorMemoryContext = operatorContext.getOperatorMemoryContext();
        LocalMemoryContext systemMemory = operatorContext.newLocalSystemMemoryContext("test");
        LocalMemoryContext userMemory = operatorContext.localUserMemoryContext();
        LocalMemoryContext revocableMemory = operatorContext.localRevocableMemoryContext();
        userMemory.setBytes(100);
        assertOperatorMemoryAllocations(operatorMemoryContext, 100, 0, 0);
        systemMemory.setBytes(1_000_000);
        assertOperatorMemoryAllocations(operatorMemoryContext, 100, 1_000_000, 0);
        systemMemory.setBytes(2_000_000);
        assertOperatorMemoryAllocations(operatorMemoryContext, 100, 2_000_000, 0);
        userMemory.setBytes(500);
        assertOperatorMemoryAllocations(operatorMemoryContext, 500, 2_000_000, 0);
        userMemory.setBytes(userMemory.getBytes() - 500);
        assertOperatorMemoryAllocations(operatorMemoryContext, 0, 2_000_000, 0);
        revocableMemory.setBytes(300);
        assertOperatorMemoryAllocations(operatorMemoryContext, 0, 2_000_000, 300);
        assertThatThrownBy(() -> userMemory.setBytes(userMemory.getBytes() - 500))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("bytes cannot be negative");
        operatorContext.destroy();
        assertOperatorMemoryAllocations(operatorMemoryContext, 0, 0, 0);
    }

    @Test
    public void testLocalTotalMemoryLimitExceeded()
    {
        LocalMemoryContext systemMemoryContext = operatorContext.newLocalSystemMemoryContext("test");
        systemMemoryContext.setBytes(100);
        assertOperatorMemoryAllocations(operatorContext.getOperatorMemoryContext(), 0, 100, 0);
        systemMemoryContext.setBytes(queryMaxTotalMemory.toBytes());
        assertOperatorMemoryAllocations(operatorContext.getOperatorMemoryContext(), 0, queryMaxTotalMemory.toBytes(), 0);
        assertThatThrownBy(() -> systemMemoryContext.setBytes(queryMaxTotalMemory.toBytes() + 1))
                .isInstanceOf(ExceededMemoryLimitException.class)
                .hasMessage(format("Query exceeded per-node total memory limit of %1$s [Allocated: %1$s, Delta: 1B, Top Consumers: {test=%1$s}]", queryMaxTotalMemory));
    }

    @Test
    public void testLocalSystemAllocations()
    {
        long pipelineLocalAllocation = 1_000_000;
        long taskLocalAllocation = 10_000_000;
        LocalMemoryContext pipelineLocalSystemMemoryContext = pipelineContext.localSystemMemoryContext();
        pipelineLocalSystemMemoryContext.setBytes(pipelineLocalAllocation);
        assertLocalMemoryAllocations(pipelineContext.getPipelineMemoryContext(),
                pipelineLocalAllocation,
                0,
                pipelineLocalAllocation);
        LocalMemoryContext taskLocalSystemMemoryContext = taskContext.localSystemMemoryContext();
        taskLocalSystemMemoryContext.setBytes(taskLocalAllocation);
        assertLocalMemoryAllocations(
                taskContext.getTaskMemoryContext(),
                pipelineLocalAllocation + taskLocalAllocation,
                0,
                taskLocalAllocation);
        assertEquals(pipelineContext.getPipelineStats().getSystemMemoryReservation().toBytes(),
                pipelineLocalAllocation,
                "task level allocations should not be visible at the pipeline level");
        pipelineLocalSystemMemoryContext.setBytes(pipelineLocalSystemMemoryContext.getBytes() - pipelineLocalAllocation);
        assertLocalMemoryAllocations(
                pipelineContext.getPipelineMemoryContext(),
                taskLocalAllocation,
                0,
                0);
        taskLocalSystemMemoryContext.setBytes(taskLocalSystemMemoryContext.getBytes() - taskLocalAllocation);
        assertLocalMemoryAllocations(
                taskContext.getTaskMemoryContext(),
                0,
                0,
                0);
    }

    @Test
    public void testStats()
    {
        LocalMemoryContext systemMemory = operatorContext.newLocalSystemMemoryContext("test");
        LocalMemoryContext userMemory = operatorContext.localUserMemoryContext();
        userMemory.setBytes(100_000_000);
        systemMemory.setBytes(200_000_000);

        assertStats(
                operatorContext.getNestedOperatorStats(),
                driverContext.getDriverStats(),
                pipelineContext.getPipelineStats(),
                taskContext.getTaskStats(),
                100_000_000,
                0,
                200_000_000);

        // allocate more and check peak memory reservation
        userMemory.setBytes(600_000_000);
        assertStats(
                operatorContext.getNestedOperatorStats(),
                driverContext.getDriverStats(),
                pipelineContext.getPipelineStats(),
                taskContext.getTaskStats(),
                600_000_000,
                0,
                200_000_000);

        userMemory.setBytes(userMemory.getBytes() - 300_000_000);
        assertStats(
                operatorContext.getNestedOperatorStats(),
                driverContext.getDriverStats(),
                pipelineContext.getPipelineStats(),
                taskContext.getTaskStats(),
                300_000_000,
                0,
                200_000_000);

        userMemory.setBytes(userMemory.getBytes() - 300_000_000);
        assertStats(
                operatorContext.getNestedOperatorStats(),
                driverContext.getDriverStats(),
                pipelineContext.getPipelineStats(),
                taskContext.getTaskStats(),
                0,
                0,
                200_000_000);

        operatorContext.destroy();

        assertStats(
                operatorContext.getNestedOperatorStats(),
                driverContext.getDriverStats(),
                pipelineContext.getPipelineStats(),
                taskContext.getTaskStats(),
                0,
                0,
                0);
    }

    @Test
    public void testRevocableMemoryAllocations()
    {
        LocalMemoryContext systemMemory = operatorContext.newLocalSystemMemoryContext("test");
        LocalMemoryContext userMemory = operatorContext.localUserMemoryContext();
        LocalMemoryContext revocableMemory = operatorContext.localRevocableMemoryContext();
        revocableMemory.setBytes(100_000_000);
        assertStats(
                operatorContext.getNestedOperatorStats(),
                driverContext.getDriverStats(),
                pipelineContext.getPipelineStats(),
                taskContext.getTaskStats(),
                0,
                100_000_000,
                0);
        userMemory.setBytes(100_000_000);
        systemMemory.setBytes(100_000_000);
        revocableMemory.setBytes(200_000_000);
        assertStats(
                operatorContext.getNestedOperatorStats(),
                driverContext.getDriverStats(),
                pipelineContext.getPipelineStats(),
                taskContext.getTaskStats(),
                100_000_000,
                200_000_000,
                100_000_000);
    }

    @Test
    public void testTrySetBytes()
    {
        LocalMemoryContext localMemoryContext = operatorContext.localUserMemoryContext();
        assertTrue(localMemoryContext.trySetBytes(100_000_000));
        assertStats(
                operatorContext.getNestedOperatorStats(),
                driverContext.getDriverStats(),
                pipelineContext.getPipelineStats(),
                taskContext.getTaskStats(),
                100_000_000,
                0,
                0);

        assertTrue(localMemoryContext.trySetBytes(200_000_000));
        assertStats(
                operatorContext.getNestedOperatorStats(),
                driverContext.getDriverStats(),
                pipelineContext.getPipelineStats(),
                taskContext.getTaskStats(),
                200_000_000,
                0,
                0);

        assertTrue(localMemoryContext.trySetBytes(100_000_000));
        assertStats(
                operatorContext.getNestedOperatorStats(),
                driverContext.getDriverStats(),
                pipelineContext.getPipelineStats(),
                taskContext.getTaskStats(),
                100_000_000,
                0,
                0);

        // allocating more than the pool size should fail and we should have the same stats as before
        assertFalse(localMemoryContext.trySetBytes(memoryPool.getMaxBytes() + 1));
        assertStats(
                operatorContext.getNestedOperatorStats(),
                driverContext.getDriverStats(),
                pipelineContext.getPipelineStats(),
                taskContext.getTaskStats(),
                100_000_000,
                0,
                0);
    }

    @Test
    public void testTrySetZeroBytesFullPool()
    {
        LocalMemoryContext localMemoryContext = operatorContext.localUserMemoryContext();
        // fill up the pool
        memoryPool.reserve(new QueryId("test_query"), "test", memoryPool.getFreeBytes());
        // try to reserve 0 bytes in the full pool
        assertTrue(localMemoryContext.trySetBytes(localMemoryContext.getBytes()));
    }

    @Test
    public void testDestroy()
    {
        LocalMemoryContext newLocalSystemMemoryContext = operatorContext.newLocalSystemMemoryContext("test");
        LocalMemoryContext newLocalUserMemoryContext = operatorContext.localUserMemoryContext();
        LocalMemoryContext newLocalRevocableMemoryContext = operatorContext.localRevocableMemoryContext();
        newLocalSystemMemoryContext.setBytes(100_000);
        newLocalRevocableMemoryContext.setBytes(200_000);
        newLocalUserMemoryContext.setBytes(400_000);
        assertEquals(operatorContext.getOperatorMemoryContext().getSystemMemory(), 100_000);
        assertEquals(operatorContext.getOperatorMemoryContext().getUserMemory(), 400_000);
        operatorContext.destroy();
        assertOperatorMemoryAllocations(operatorContext.getOperatorMemoryContext(), 0, 0, 0);
    }

    private void assertStats(
            List<OperatorStats> nestedOperatorStats,
            DriverStats driverStats,
            PipelineStats pipelineStats,
            TaskStats taskStats,
            long expectedUserMemory,
            long expectedRevocableMemory,
            long expectedSystemMemory)
    {
        OperatorStats operatorStats = getOnlyElement(nestedOperatorStats);
        assertEquals(operatorStats.getUserMemoryReservation().toBytes(), expectedUserMemory);
        assertEquals(driverStats.getUserMemoryReservation().toBytes(), expectedUserMemory);
        assertEquals(pipelineStats.getUserMemoryReservation().toBytes(), expectedUserMemory);
        assertEquals(taskStats.getUserMemoryReservation().toBytes(), expectedUserMemory);

        assertEquals(operatorStats.getSystemMemoryReservation().toBytes(), expectedSystemMemory);
        assertEquals(driverStats.getSystemMemoryReservation().toBytes(), expectedSystemMemory);
        assertEquals(pipelineStats.getSystemMemoryReservation().toBytes(), expectedSystemMemory);
        assertEquals(taskStats.getSystemMemoryReservation().toBytes(), expectedSystemMemory);

        assertEquals(operatorStats.getRevocableMemoryReservation().toBytes(), expectedRevocableMemory);
        assertEquals(driverStats.getRevocableMemoryReservation().toBytes(), expectedRevocableMemory);
        assertEquals(pipelineStats.getRevocableMemoryReservation().toBytes(), expectedRevocableMemory);
        assertEquals(taskStats.getRevocableMemoryReservation().toBytes(), expectedRevocableMemory);
    }

    // the allocations that are done at the operator level are reflected at that level and all the way up to the pools
    private void assertOperatorMemoryAllocations(
            MemoryTrackingContext memoryTrackingContext,
            long expectedUserMemory,
            long expectedSystemMemory,
            long expectedRevocableMemory)
    {
        assertEquals(memoryTrackingContext.getUserMemory(), expectedUserMemory, "User memory verification failed");
        // both user and system memory are allocated from the same memoryPool
        assertEquals(memoryPool.getReservedBytes(), expectedUserMemory + expectedSystemMemory, "Memory pool verification failed");
        assertEquals(memoryTrackingContext.getSystemMemory(), expectedSystemMemory, "System memory verification failed");
        assertEquals(memoryTrackingContext.getRevocableMemory(), expectedRevocableMemory, "Revocable memory verification failed");
    }

    // the local allocations are reflected only at that level and all the way up to the pools
    private void assertLocalMemoryAllocations(
            MemoryTrackingContext memoryTrackingContext,
            long expectedPoolMemory,
            long expectedContextUserMemory,
            long expectedContextSystemMemory)
    {
        assertEquals(memoryTrackingContext.getUserMemory(), expectedContextUserMemory, "User memory verification failed");
        assertEquals(memoryPool.getReservedBytes(), expectedPoolMemory, "Memory pool verification failed");
        assertEquals(memoryTrackingContext.localSystemMemoryContext().getBytes(), expectedContextSystemMemory, "Local system memory verification failed");
    }
}
