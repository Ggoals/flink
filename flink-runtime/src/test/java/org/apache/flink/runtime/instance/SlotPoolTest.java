/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.instance;

import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.runtime.clusterframework.types.AllocationID;
import org.apache.flink.runtime.clusterframework.types.ResourceID;
import org.apache.flink.runtime.clusterframework.types.ResourceProfile;
import org.apache.flink.runtime.executiongraph.Execution;
import org.apache.flink.runtime.jobmanager.scheduler.ScheduledUnit;
import org.apache.flink.runtime.jobmanager.slots.AllocatedSlot;
import org.apache.flink.runtime.jobmanager.slots.TaskManagerGateway;
import org.apache.flink.runtime.jobmaster.JobMasterId;
import org.apache.flink.runtime.messages.Acknowledge;
import org.apache.flink.runtime.resourcemanager.ResourceManagerGateway;
import org.apache.flink.runtime.resourcemanager.SlotRequest;
import org.apache.flink.runtime.resourcemanager.utils.TestingResourceManagerGateway;
import org.apache.flink.runtime.rpc.RpcService;
import org.apache.flink.runtime.rpc.RpcUtils;
import org.apache.flink.runtime.rpc.TestingRpcService;
import org.apache.flink.runtime.taskmanager.TaskManagerLocation;
import org.apache.flink.util.FlinkException;
import org.apache.flink.util.TestLogger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.apache.flink.runtime.instance.AvailableSlotsTest.DEFAULT_TESTING_PROFILE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.RETURNS_MOCKS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class SlotPoolTest extends TestLogger {

	private static final Logger LOG = LoggerFactory.getLogger(SlotPoolTest.class);

	private final Time timeout = Time.seconds(10L);

	private RpcService rpcService;

	private JobID jobId;

	@Before
	public void setUp() throws Exception {
		this.rpcService = new TestingRpcService();
		this.jobId = new JobID();
	}

	@After
	public void tearDown() throws Exception {
		rpcService.stopService();
	}

	@Test
	public void testAllocateSimpleSlot() throws Exception {
		ResourceManagerGateway resourceManagerGateway = createResourceManagerGatewayMock();
		final SlotPool slotPool = new SlotPool(rpcService, jobId);

		try {
			SlotPoolGateway slotPoolGateway = setupSlotPool(slotPool, resourceManagerGateway);
			ResourceID resourceID = new ResourceID("resource");
			slotPoolGateway.registerTaskManager(resourceID);

			SlotPoolGateway.SlotRequestID requestId = new SlotPoolGateway.SlotRequestID();
			CompletableFuture<SimpleSlot> future = slotPoolGateway.allocateSlot(requestId, mock(ScheduledUnit.class), DEFAULT_TESTING_PROFILE, null, timeout);
			assertFalse(future.isDone());

			ArgumentCaptor<SlotRequest> slotRequestArgumentCaptor = ArgumentCaptor.forClass(SlotRequest.class);
			verify(resourceManagerGateway, Mockito.timeout(timeout.toMilliseconds())).requestSlot(any(JobMasterId.class), slotRequestArgumentCaptor.capture(), any(Time.class));

			final SlotRequest slotRequest = slotRequestArgumentCaptor.getValue();

			AllocatedSlot allocatedSlot = createAllocatedSlot(resourceID, slotRequest.getAllocationId(), jobId, DEFAULT_TESTING_PROFILE);
			assertTrue(slotPoolGateway.offerSlot(allocatedSlot).get());

			SimpleSlot slot = future.get(1, TimeUnit.SECONDS);
			assertTrue(future.isDone());
			assertTrue(slot.isAlive());
			assertEquals(resourceID, slot.getTaskManagerID());
			assertEquals(jobId, slot.getJobID());
			assertEquals(slotPool.getSlotOwner(), slot.getOwner());
			assertEquals(slotPool.getAllocatedSlots().get(slot.getAllocatedSlot().getSlotAllocationId()), slot);
		} finally {
			slotPool.shutDown();
		}
	}

	@Test
	public void testAllocationFulfilledByReturnedSlot() throws Exception {
		ResourceManagerGateway resourceManagerGateway = createResourceManagerGatewayMock();
		final SlotPool slotPool = new SlotPool(rpcService, jobId);

		try {
			SlotPoolGateway slotPoolGateway = setupSlotPool(slotPool, resourceManagerGateway);
			ResourceID resourceID = new ResourceID("resource");
			slotPool.registerTaskManager(resourceID);

			CompletableFuture<SimpleSlot> future1 = slotPoolGateway.allocateSlot(new SlotPoolGateway.SlotRequestID(), mock(ScheduledUnit.class), DEFAULT_TESTING_PROFILE, null, timeout);
			CompletableFuture<SimpleSlot> future2 = slotPoolGateway.allocateSlot(new SlotPoolGateway.SlotRequestID(), mock(ScheduledUnit.class), DEFAULT_TESTING_PROFILE, null, timeout);

			assertFalse(future1.isDone());
			assertFalse(future2.isDone());

			ArgumentCaptor<SlotRequest> slotRequestArgumentCaptor = ArgumentCaptor.forClass(SlotRequest.class);
			verify(resourceManagerGateway, Mockito.timeout(timeout.toMilliseconds()).times(2))
				.requestSlot(any(JobMasterId.class), slotRequestArgumentCaptor.capture(), any(Time.class));

			final List<SlotRequest> slotRequests = slotRequestArgumentCaptor.getAllValues();

			AllocatedSlot allocatedSlot = createAllocatedSlot(resourceID, slotRequests.get(0).getAllocationId(), jobId, DEFAULT_TESTING_PROFILE);
			assertTrue(slotPoolGateway.offerSlot(allocatedSlot).get());

			SimpleSlot slot1 = future1.get(1, TimeUnit.SECONDS);
			assertTrue(future1.isDone());
			assertFalse(future2.isDone());

			// return this slot to pool
			slot1.releaseSlot();

			// second allocation fulfilled by previous slot returning
			SimpleSlot slot2 = future2.get(1, TimeUnit.SECONDS);
			assertTrue(future2.isDone());

			assertNotEquals(slot1, slot2);
			assertTrue(slot1.isReleased());
			assertTrue(slot2.isAlive());
			assertEquals(slot1.getTaskManagerID(), slot2.getTaskManagerID());
			assertEquals(slot1.getSlotNumber(), slot2.getSlotNumber());
			assertEquals(slotPool.getAllocatedSlots().get(slot1.getAllocatedSlot().getSlotAllocationId()), slot2);
		} finally {
			slotPool.shutDown();
		}
	}

	@Test
	public void testAllocateWithFreeSlot() throws Exception {
		ResourceManagerGateway resourceManagerGateway = createResourceManagerGatewayMock();
		final SlotPool slotPool = new SlotPool(rpcService, jobId);

		try {
			SlotPoolGateway slotPoolGateway = setupSlotPool(slotPool, resourceManagerGateway);
			ResourceID resourceID = new ResourceID("resource");
			slotPoolGateway.registerTaskManager(resourceID);

			CompletableFuture<SimpleSlot> future1 = slotPoolGateway.allocateSlot(new SlotPoolGateway.SlotRequestID(), mock(ScheduledUnit.class), DEFAULT_TESTING_PROFILE, null, timeout);
			assertFalse(future1.isDone());

			ArgumentCaptor<SlotRequest> slotRequestArgumentCaptor = ArgumentCaptor.forClass(SlotRequest.class);
			verify(resourceManagerGateway, Mockito.timeout(timeout.toMilliseconds())).requestSlot(any(JobMasterId.class), slotRequestArgumentCaptor.capture(), any(Time.class));

			final SlotRequest slotRequest = slotRequestArgumentCaptor.getValue();

			AllocatedSlot allocatedSlot = createAllocatedSlot(resourceID, slotRequest.getAllocationId(), jobId, DEFAULT_TESTING_PROFILE);
			assertTrue(slotPoolGateway.offerSlot(allocatedSlot).get());

			SimpleSlot slot1 = future1.get(1, TimeUnit.SECONDS);
			assertTrue(future1.isDone());

			// return this slot to pool
			slot1.releaseSlot();

			CompletableFuture<SimpleSlot> future2 = slotPoolGateway.allocateSlot(new SlotPoolGateway.SlotRequestID(), mock(ScheduledUnit.class), DEFAULT_TESTING_PROFILE, null, timeout);

			// second allocation fulfilled by previous slot returning
			SimpleSlot slot2 = future2.get(1, TimeUnit.SECONDS);
			assertTrue(future2.isDone());

			assertNotEquals(slot1, slot2);
			assertTrue(slot1.isReleased());
			assertTrue(slot2.isAlive());
			assertEquals(slot1.getTaskManagerID(), slot2.getTaskManagerID());
			assertEquals(slot1.getSlotNumber(), slot2.getSlotNumber());
		} finally {
			slotPool.shutDown();
		}
	}

	@Test
	public void testOfferSlot() throws Exception {
		ResourceManagerGateway resourceManagerGateway = createResourceManagerGatewayMock();
		final SlotPool slotPool = new SlotPool(rpcService, jobId);

		try {
			SlotPoolGateway slotPoolGateway = setupSlotPool(slotPool, resourceManagerGateway);
			ResourceID resourceID = new ResourceID("resource");
			slotPoolGateway.registerTaskManager(resourceID);

			CompletableFuture<SimpleSlot> future = slotPoolGateway.allocateSlot(new SlotPoolGateway.SlotRequestID(), mock(ScheduledUnit.class), DEFAULT_TESTING_PROFILE, null, timeout);
			assertFalse(future.isDone());

			ArgumentCaptor<SlotRequest> slotRequestArgumentCaptor = ArgumentCaptor.forClass(SlotRequest.class);
			verify(resourceManagerGateway, Mockito.timeout(timeout.toMilliseconds())).requestSlot(any(JobMasterId.class), slotRequestArgumentCaptor.capture(), any(Time.class));

			final SlotRequest slotRequest = slotRequestArgumentCaptor.getValue();

			// slot from unregistered resource
			AllocatedSlot invalid = createAllocatedSlot(new ResourceID("unregistered"), slotRequest.getAllocationId(), jobId, DEFAULT_TESTING_PROFILE);
			assertFalse(slotPoolGateway.offerSlot(invalid).get());

			AllocatedSlot notRequested = createAllocatedSlot(resourceID, new AllocationID(), jobId, DEFAULT_TESTING_PROFILE);

			// we'll also accept non requested slots
			assertTrue(slotPoolGateway.offerSlot(notRequested).get());

			AllocatedSlot allocatedSlot = createAllocatedSlot(resourceID, slotRequest.getAllocationId(), jobId, DEFAULT_TESTING_PROFILE);

			// accepted slot
			assertTrue(slotPoolGateway.offerSlot(allocatedSlot).get());
			SimpleSlot slot = future.get(timeout.toMilliseconds(), TimeUnit.MILLISECONDS);
			assertTrue(slot.isAlive());

			// duplicated offer with using slot
			assertTrue(slotPoolGateway.offerSlot(allocatedSlot).get());
			assertTrue(slot.isAlive());

			// duplicated offer with free slot
			slot.releaseSlot();
			assertTrue(slotPoolGateway.offerSlot(allocatedSlot).get());
		} finally {
			slotPool.shutDown();
		}
	}

	@Test
	public void testReleaseResource() throws Exception {
		ResourceManagerGateway resourceManagerGateway = createResourceManagerGatewayMock();

		final CompletableFuture<Boolean> slotReturnFuture = new CompletableFuture<>();

		final SlotPool slotPool = new SlotPool(rpcService, jobId) {
			@Override
			public void returnAllocatedSlot(Slot slot) {
				super.returnAllocatedSlot(slot);

				slotReturnFuture.complete(true);
			}
		};

		try {
			SlotPoolGateway slotPoolGateway = setupSlotPool(slotPool, resourceManagerGateway);
			ResourceID resourceID = new ResourceID("resource");
			slotPoolGateway.registerTaskManager(resourceID);

			CompletableFuture<SimpleSlot> future1 = slotPoolGateway.allocateSlot(new SlotPoolGateway.SlotRequestID(), mock(ScheduledUnit.class), DEFAULT_TESTING_PROFILE, null, timeout);

			ArgumentCaptor<SlotRequest> slotRequestArgumentCaptor = ArgumentCaptor.forClass(SlotRequest.class);
			verify(resourceManagerGateway, Mockito.timeout(timeout.toMilliseconds())).requestSlot(any(JobMasterId.class), slotRequestArgumentCaptor.capture(), any(Time.class));

			final SlotRequest slotRequest = slotRequestArgumentCaptor.getValue();

			CompletableFuture<SimpleSlot> future2 = slotPoolGateway.allocateSlot(new SlotPoolGateway.SlotRequestID(), mock(ScheduledUnit.class), DEFAULT_TESTING_PROFILE, null, timeout);

			AllocatedSlot allocatedSlot = createAllocatedSlot(resourceID, slotRequest.getAllocationId(), jobId, DEFAULT_TESTING_PROFILE);
			assertTrue(slotPoolGateway.offerSlot(allocatedSlot).get());

			SimpleSlot slot1 = future1.get(1, TimeUnit.SECONDS);
			assertTrue(future1.isDone());
			assertFalse(future2.isDone());

			slotPoolGateway.releaseTaskManager(resourceID);

			// wait until the slot has been returned
			slotReturnFuture.get();

			assertTrue(slot1.isReleased());

			// slot released and not usable, second allocation still not fulfilled
			Thread.sleep(10);
			assertFalse(future2.isDone());
		} finally {
			slotPool.shutDown();
		}
	}

	/**
	 * Tests that a slot request is cancelled if it failed with an exception (e.g. TimeoutException).
	 *
	 * <p>See FLINK-7870
	 */
	@Test
	public void testSlotRequestCancellationUponFailingRequest() throws Exception {
		final SlotPool slotPool = new SlotPool(rpcService, jobId);
		final CompletableFuture<Acknowledge> requestSlotFuture = new CompletableFuture<>();
		final CompletableFuture<AllocationID> cancelSlotFuture = new CompletableFuture<>();
		final CompletableFuture<AllocationID> requestSlotFutureAllocationId = new CompletableFuture<>();

		final TestingResourceManagerGateway resourceManagerGateway = new TestingResourceManagerGateway();
		resourceManagerGateway.setRequestSlotFuture(requestSlotFuture);
		resourceManagerGateway.setRequestSlotConsumer(slotRequest -> requestSlotFutureAllocationId.complete(slotRequest.getAllocationId()));
		resourceManagerGateway.setCancelSlotConsumer(allocationID -> cancelSlotFuture.complete(allocationID));

		final ScheduledUnit scheduledUnit = new ScheduledUnit(mock(Execution.class));

		try {
			slotPool.start(JobMasterId.generate(), "localhost");

			final SlotPoolGateway slotPoolGateway = slotPool.getSelfGateway(SlotPoolGateway.class);

			slotPoolGateway.connectToResourceManager(resourceManagerGateway);

			CompletableFuture<SimpleSlot> slotFuture = slotPoolGateway.allocateSlot(
				new SlotPoolGateway.SlotRequestID(),
				scheduledUnit,
				ResourceProfile.UNKNOWN,
				Collections.emptyList(),
				timeout);

			requestSlotFuture.completeExceptionally(new FlinkException("Testing exception."));

			try {
				slotFuture.get();
				fail("The slot future should not have been completed properly.");
			} catch (Exception ignored) {
				// expected
			}

			// check that a failure triggered the slot request cancellation
			// with the correct allocation id
			assertEquals(requestSlotFutureAllocationId.get(), cancelSlotFuture.get());
		} finally {
			try {
				RpcUtils.terminateRpcEndpoint(slotPool, timeout);
			} catch (Exception e) {
				LOG.warn("Could not properly terminate the SlotPool.", e);
			}
		}
	}

	private static ResourceManagerGateway createResourceManagerGatewayMock() {
		ResourceManagerGateway resourceManagerGateway = mock(ResourceManagerGateway.class);
		when(resourceManagerGateway
			.requestSlot(any(JobMasterId.class), any(SlotRequest.class), any(Time.class)))
			.thenReturn(mock(CompletableFuture.class, RETURNS_MOCKS));

		return resourceManagerGateway;
	}

	private static SlotPoolGateway setupSlotPool(
			SlotPool slotPool,
			ResourceManagerGateway resourceManagerGateway) throws Exception {
		final String jobManagerAddress = "foobar";

		slotPool.start(JobMasterId.generate(), jobManagerAddress);

		slotPool.connectToResourceManager(resourceManagerGateway);

		return slotPool.getSelfGateway(SlotPoolGateway.class);
	}

	static AllocatedSlot createAllocatedSlot(
			final ResourceID resourceId,
			final AllocationID allocationId,
			final JobID jobId,
			final ResourceProfile resourceProfile) {
		TaskManagerLocation mockTaskManagerLocation = mock(TaskManagerLocation.class);
		when(mockTaskManagerLocation.getResourceID()).thenReturn(resourceId);

		TaskManagerGateway mockTaskManagerGateway = mock(TaskManagerGateway.class);

		return new AllocatedSlot(
			allocationId,
			jobId,
			mockTaskManagerLocation,
			0,
			resourceProfile,
			mockTaskManagerGateway);
	}

}
