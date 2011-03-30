/*
 * Copyright 2010 Outerthought bvba
 *
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
package org.lilyproject.rowlog.impl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.client.*;
import org.apache.hadoop.hbase.filter.ColumnPrefixFilter;
import org.apache.hadoop.hbase.util.Bytes;
import org.lilyproject.rowlock.RowLock;
import org.lilyproject.rowlock.RowLocker;
import org.lilyproject.rowlog.api.*;
import org.lilyproject.util.io.Closer;

/**
 * See {@link RowLog}
 */
public class RowLogImpl implements RowLog, SubscriptionsObserver, RowLogObserver {

    private static final byte PL_BYTE = (byte)1;
    private static final byte ES_BYTE = (byte)2;
    private static final byte[] SEQ_NR = Bytes.toBytes("SEQNR");
    private RowLogShard shard; // TODO: We only work with one shard for now
    private final HTableInterface rowTable;
    private final byte[] rowLogColumnFamily;
    private RowLogConfig rowLogConfig;
    
    private Map<String, RowLogSubscription> subscriptions = Collections.synchronizedMap(new HashMap<String, RowLogSubscription>());
    private final String id;
    private RowLogProcessorNotifier processorNotifier = null;
    private Log log = LogFactory.getLog(getClass());
    private RowLogConfigurationManager rowLogConfigurationManager;

    private final AtomicBoolean initialSubscriptionsLoaded = new AtomicBoolean(false);
    private final AtomicBoolean initialRowLogConfigLoaded = new AtomicBoolean(false);
    private final RowLocker rowLocker;
    private byte[] payloadPrefix;
    private byte[] executionStatePrefix;
    private byte[] seqNrQualifier;

    /**
     * The RowLog should be instantiated with information about the table that contains the rows the messages are 
     * related to, and the column families it can use within this table to put the payload and execution state of the
     * messages on.
     * @param rowTable the HBase table containing the rows to which the messages are related
     * @param rowLogColumnFamily the column family in which the payload and execution state of the messages can be stored
     * @param rowLogId a byte uniquely identifying the rowLog amongst all rowLogs in the system
     * @param rowLocker if given, the rowlog will take locks at row level; if null, the locks will be taken at executionstate level
     * @throws RowLogException
     */
    public RowLogImpl(String id, HTableInterface rowTable, byte[] rowLogColumnFamily, byte rowLogId,
            RowLogConfigurationManager rowLogConfigurationManager, RowLocker rowLocker) throws InterruptedException {
        this.id = id;
        this.rowTable = rowTable;
        this.rowLogColumnFamily = rowLogColumnFamily;
        this.payloadPrefix = new byte[]{rowLogId, PL_BYTE};
        this.executionStatePrefix = new byte[]{rowLogId, ES_BYTE};
        this.seqNrQualifier = Bytes.add(new byte[]{rowLogId}, SEQ_NR);
        this.rowLogConfigurationManager = rowLogConfigurationManager;
        this.rowLocker = rowLocker;
        rowLogConfigurationManager.addRowLogObserver(id, this);
        synchronized (initialRowLogConfigLoaded) {
            while(!initialRowLogConfigLoaded.get()) {
                initialRowLogConfigLoaded.wait();
            }
        }
        this.processorNotifier = new RowLogProcessorNotifier(rowLogConfigurationManager, rowLogConfig.getNotifyDelay());
        rowLogConfigurationManager.addSubscriptionsObserver(id, this);
        synchronized (initialSubscriptionsLoaded) {
            while (!initialSubscriptionsLoaded.get()) {
                initialSubscriptionsLoaded.wait();
            }
        }
    }

    public void stop() {
        rowLogConfigurationManager.removeRowLogObserver(id, this);
        synchronized (initialRowLogConfigLoaded) {
            initialRowLogConfigLoaded.set(false);
        }
        rowLogConfigurationManager.removeSubscriptionsObserver(id, this);
        synchronized (initialSubscriptionsLoaded) {
            initialSubscriptionsLoaded.set(false);
        }
        Closer.close(processorNotifier);
    }
    
    @Override
    protected void finalize() throws Throwable {
        stop();
        super.finalize();
    }
    
    public String getId() {
        return id;
    }
    
    public void registerShard(RowLogShard shard) {
        this.shard = shard;
    }
    
    public void unRegisterShard(RowLogShard shard) {
        this.shard = null;
    }
    
    private long putPayload(byte[] rowKey, byte[] payload, long timestamp, Put put) throws IOException {
        Get get = new Get(rowKey);
        get.addColumn(rowLogColumnFamily, seqNrQualifier);
        Result result = rowTable.get(get);
        byte[] value = result.getValue(rowLogColumnFamily, seqNrQualifier);
        long seqnr = -1;
        if (value != null) {
            seqnr = Bytes.toLong(value);
        }
        seqnr++;
        if (put != null) {
            put.add(rowLogColumnFamily, seqNrQualifier, Bytes.toBytes(seqnr));
            put.add(rowLogColumnFamily, payloadQualifier(seqnr, timestamp), payload);
        } else {
            put = new Put(rowKey);
            put.add(rowLogColumnFamily, seqNrQualifier, Bytes.toBytes(seqnr));
            put.add(rowLogColumnFamily, payloadQualifier(seqnr, timestamp), payload);
            rowTable.put(put);
        }
        return seqnr;
    }

    
    

    public byte[] getPayload(RowLogMessage message) throws RowLogException {
        byte[] rowKey = message.getRowKey();
        byte[] qualifier = payloadQualifier(message.getSeqNr(), message.getTimestamp());
        Get get = new Get(rowKey);
        get.addColumn(rowLogColumnFamily, qualifier);
        Result result;
        try {
            result = rowTable.get(get);
        } catch (IOException e) {
            throw new RowLogException("Exception while getting payload from the rowTable", e);
        }
        return result.getValue(rowLogColumnFamily, qualifier);
    }

    public RowLogMessage putMessage(byte[] rowKey, byte[] data, byte[] payload, Put put) throws InterruptedException, RowLogException {
        RowLogShard shard = getShard(); // Fail fast if no shards are registered
        
        try {
            // Take current snapshot of the subscriptions so that shard.putMessage and initializeSubscriptions
            // use the exact same set of subscriptions.
            List<RowLogSubscription> subscriptions = getSubscriptions();
            if (subscriptions.isEmpty()) 
                return null;
            
            long now = System.currentTimeMillis();
            long seqnr = putPayload(rowKey, payload, now, put);
                    
            RowLogMessage message = new RowLogMessageImpl(now, rowKey, seqnr, data, this);

            shard.putMessage(message, subscriptions);
            initializeSubscriptions(message, put, subscriptions);

            if (rowLogConfig.isEnableNotify()) {
                processorNotifier.notifyProcessor(id, shard.getId());
            }
            return message;
        } catch (IOException e) {
            throw new RowLogException("Failed to put message on RowLog", e);
        }
    }

    
    private void initializeSubscriptions(RowLogMessage message, Put put, List<RowLogSubscription> subscriptions)
            throws IOException {
        SubscriptionExecutionState executionState = new SubscriptionExecutionState(message.getTimestamp());
        for (RowLogSubscription subscription : subscriptions) {
            executionState.setState(subscription.getId(), false);
        }
        byte[] qualifier = executionStateQualifier(message.getSeqNr(), message.getTimestamp());
        if (put != null) {
            put.add(rowLogColumnFamily, qualifier, executionState.toBytes());
        } else {
            put = new Put(message.getRowKey());
            put.add(rowLogColumnFamily, qualifier, executionState.toBytes());
            rowTable.put(put);
        }
    }

    public boolean processMessage(RowLogMessage message, Object lock) throws RowLogException, InterruptedException {
        if (message == null)
            return true;
        byte[] rowKey = message.getRowKey();
        byte[] executionStateQualifier = executionStateQualifier(message.getSeqNr(), message.getTimestamp());
        Get get = new Get(rowKey);
        get.addColumn(rowLogColumnFamily, executionStateQualifier);
        try {
            Result result = rowTable.get(get);
            if (result.isEmpty()) {
                // No execution state was found indicating an orphan message on the global queue table
                // Treat this message as if it was processed
                return true;
            }
            byte[] previousValue = result.getValue(rowLogColumnFamily, executionStateQualifier);
            SubscriptionExecutionState executionState = SubscriptionExecutionState.fromBytes(previousValue);

            boolean allDone = processMessage(message, executionState);
            
            if (allDone) {
                if (rowLocker != null) {
                    return removeExecutionStateAndPayload(rowKey, executionStateQualifier, payloadQualifier(message.getSeqNr(), message.getTimestamp()), previousValue, (RowLock)lock);
                } else {
                    return removeExecutionStateAndPayload(rowKey, executionStateQualifier, payloadQualifier(message.getSeqNr(), message.getTimestamp()), previousValue);
                }
            } else {
                if (rowLocker != null) {
                    updateExecutionState(rowKey, executionStateQualifier, executionState, previousValue, (RowLock)lock);
                } else {
                    updateExecutionState(rowKey, executionStateQualifier, executionState, previousValue);
                }
                return false;
            }
        } catch (IOException e) {
            throw new RowLogException("Failed to process message", e);
        }
    }

    private boolean processMessage(RowLogMessage message, SubscriptionExecutionState executionState) throws RowLogException, InterruptedException {
        boolean allDone = true;
        List<RowLogSubscription> subscriptionsSnapshot = getSubscriptions();
        if (rowLogConfig.isRespectOrder()) {
            Collections.sort(subscriptionsSnapshot);
        }

        List<RowLogSubscription> subscriptions = getSubscriptions();

        if (log.isDebugEnabled()) {
            log.debug("Processing msg '" + formatId(message) + "' nr of subscriptions: " + subscriptions.size());
        }

        for (RowLogSubscription subscription : subscriptions) {
            String subscriptionId = subscription.getId();

            if (log.isDebugEnabled()) {
                log.debug("Processing msg '" + formatId(message) + "', subscr '" + subscriptionId + "' state: " +
                        executionState.getState(subscriptionId));
            }

            if (!executionState.getState(subscriptionId)) {
                boolean done = false;
                RowLogMessageListener listener = RowLogMessageListenerMapping.INSTANCE.get(subscriptionId);
                if (listener != null) {
                    done = listener.processMessage(message);
                    if (log.isDebugEnabled()) {
                        log.debug("Processing msg '" + formatId(message) + "', subscr '" + subscriptionId +
                                "' processing result: " + done);
                    }
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("Processing msg '" + formatId(message) + "', subscr '" + subscriptionId +
                                "' no listener present.");
                    }
                }
                executionState.setState(subscriptionId, done);
                if (!done) {
                    allDone = false;
                    if (rowLogConfig.isRespectOrder()) {
                        break;
                    }
                } else {
                    shard.removeMessage(message, subscriptionId);
                }
            }
        }
        return allDone;
    }

    private String formatId(RowLogMessage message) {
        return Bytes.toStringBinary(message.getRowKey()) + ":" + message.getSeqNr();
    }
    
    public List<RowLogSubscription> getSubscriptions() {
        synchronized (subscriptions) {
            return new ArrayList<RowLogSubscription>(subscriptions.values());
        }
    }
    
    public Object lockMessage(RowLogMessage message, String subscriptionId) throws RowLogException {
        if (rowLocker != null)
            return lockRow(message); // Take a lock on the row
        return lockMessage(message, subscriptionId, 0); // Take a lock in the executionState
    }
    
    private RowLock lockRow(RowLogMessage message) throws RowLogException {
        try {
            return rowLocker.lockRow(message.getRowKey());
        } catch (IOException e) {
            throw new RowLogException("Failed to lock message", e);
        }
    }
    
    // Lock the message at executionState
    private byte[] lockMessage(RowLogMessage message, String subscriptionId, int count) throws RowLogException {
        if (count >= 10) {
            return null;
        }
        byte[] rowKey = message.getRowKey();
        byte[] executionStateQualifier = executionStateQualifier(message.getSeqNr(), message.getTimestamp());
        Get get = new Get(rowKey);
        get.addColumn(rowLogColumnFamily, executionStateQualifier);
        try {
            Result result = rowTable.get(get);
            if (result.isEmpty()) {
                return null;
            }
            byte[] previousValue = result.getValue(rowLogColumnFamily, executionStateQualifier);
            SubscriptionExecutionState executionState = SubscriptionExecutionState.fromBytes(previousValue);
            byte[] previousLock = executionState.getLock(subscriptionId);
            long now = System.currentTimeMillis();
            if (previousLock == null) {
                return putLock(message, subscriptionId, rowKey, executionStateQualifier, previousValue, executionState, now, count);
            } else {
                long previousTimestamp = Bytes.toLong(previousLock);
                if (previousTimestamp + rowLogConfig.getLockTimeout() < now) {
                    return putLock(message, subscriptionId, rowKey, executionStateQualifier, previousValue, executionState, now, count);
                } else {
                    return null;
                }
            }
        } catch (IOException e) {
            throw new RowLogException("Failed to lock message", e);
        }
    }

    private byte[] putLock(RowLogMessage message, String subscriptionId, byte[] rowKey, byte[] executionStateQualifier, byte[] previousValue,
            SubscriptionExecutionState executionState, long now, int count) throws RowLogException {
        byte[] lock = Bytes.toBytes(now);
        executionState.setLock(subscriptionId, lock);
        Put put = new Put(rowKey);
        put.add(rowLogColumnFamily, executionStateQualifier, executionState.toBytes());
        try {
            if (!rowTable.checkAndPut(rowKey, rowLogColumnFamily, executionStateQualifier, previousValue, put)) {
                return lockMessage(message, subscriptionId, count+1); // Retry
            } else {
                return lock;
            }
        } catch (IOException e) {
            return lockMessage(message, subscriptionId, count+1); // Retry
        }
    }
    
    public boolean unlockMessage(RowLogMessage message, String subscriptionId, Object lock) throws RowLogException {
        if (rowLocker != null) { // If rowLocker exists, the lock must be a RowLock
            try {
                rowLocker.unlockRow((RowLock)lock);
                return true;
            } catch (IOException e) {
                throw new RowLogException("Failed to unlock message", e);
            }
        } else { // Else, the lock is a lock in the executionState
            RowLogSubscription subscription = subscriptions.get(subscriptionId);
            if (subscription == null)
                throw new RowLogException("Failed to unlock message, subscription " + subscriptionId +
                        " no longer exists for rowlog " + this.getId());
            byte[] rowKey = message.getRowKey();
            byte[] execStateQualifier = executionStateQualifier(message.getSeqNr(), message.getTimestamp());
            Get get = new Get(rowKey);
            get.addColumn(rowLogColumnFamily, execStateQualifier);
            Result result;
            try {
                result = rowTable.get(get);

                if (result.isEmpty()) return false; // The execution state does not exist anymore, thus no lock to unlock

                byte[] previousValue = result.getValue(rowLogColumnFamily, execStateQualifier);
                SubscriptionExecutionState executionState = SubscriptionExecutionState.fromBytes(previousValue);
                byte[] previousLock = executionState.getLock(subscriptionId);
                if (!Bytes.equals((byte[])lock, previousLock)) return false; // The lock was lost

                executionState.setLock(subscriptionId, null);
                Put put = new Put(rowKey);
                put.add(rowLogColumnFamily, execStateQualifier, executionState.toBytes());
                return rowTable.checkAndPut(rowKey, rowLogColumnFamily, execStateQualifier, previousValue, put);
            } catch (IOException e) {
                throw new RowLogException("Failed to unlock message", e);
            }
        }
    }

    public boolean isMessageLocked(RowLogMessage message, String subscriptionId) throws RowLogException {
        byte[] rowKey = message.getRowKey();
        byte[] executionStateQualifier = executionStateQualifier(message.getSeqNr(), message.getTimestamp());
        Get get = new Get(rowKey);
        get.addColumn(rowLogColumnFamily, executionStateQualifier);
        try {
            Result result = rowTable.get(get);
            if (result.isEmpty()) return false;
            
            SubscriptionExecutionState executionState = SubscriptionExecutionState.fromBytes(result.getValue(rowLogColumnFamily, executionStateQualifier));
            byte[] lock = executionState.getLock(subscriptionId);
            if (lock == null) return false;
        
            return (Bytes.toLong(lock) + rowLogConfig.getLockTimeout() > System.currentTimeMillis());
        } catch (IOException e) {
            throw new RowLogException("Failed to check if message is locked", e);
        }
    }
    
    public boolean messageDone(RowLogMessage message, String subscriptionId, Object lock) throws RowLogException {
        if (rowLocker != null) { // If the rowLocker exists the lock should be a RowLock
            return messageDoneRowLocked(message, subscriptionId, (RowLock)lock);
        }
        // else, the lock is an executionState level lock
        return messageDone(message, subscriptionId, (byte[])lock, 0); 
    }
    
    private boolean messageDoneRowLocked(RowLogMessage message, String subscriptionId, RowLock rowLock) throws RowLogException {
        RowLogShard shard = getShard(); // Fail fast if no shards are registered
        byte[] rowKey = message.getRowKey();
        byte[] executionStateQualifier = executionStateQualifier(message.getSeqNr(), message.getTimestamp());
        Get get = new Get(rowKey);
        get.addColumn(rowLogColumnFamily, executionStateQualifier);
        try {
            Result result = rowTable.get(get);
            if (!result.isEmpty()) {
                byte[] previousValue = result.getValue(rowLogColumnFamily, executionStateQualifier);
                SubscriptionExecutionState executionState = SubscriptionExecutionState.fromBytes(previousValue);
                executionState.setState(subscriptionId, true);
                executionState.setLock(subscriptionId, null);
                if (executionState.allDone()) {
                    removeExecutionStateAndPayload(rowKey, executionStateQualifier, payloadQualifier(message.getSeqNr(), message.getTimestamp()), previousValue, rowLock);
                } else {
                    if (!updateExecutionState(rowKey, executionStateQualifier, executionState, previousValue, rowLock))
                        return false;
                }
            }
            shard.removeMessage(message, subscriptionId);
            return true;
        } catch (IOException e) {
            throw new RowLogException("Failed to put message to done", e);
        }
    
    }
    
    private boolean messageDone(RowLogMessage message, String subscriptionId, byte[] lock, int count) throws RowLogException {
        if (count >= 10) {
            return false;
        }
        RowLogShard shard = getShard(); // Fail fast if no shards are registered
        byte[] rowKey = message.getRowKey();
        byte[] executionStateQualifier = executionStateQualifier(message.getSeqNr(), message.getTimestamp());
        Get get = new Get(rowKey);
        get.addColumn(rowLogColumnFamily, executionStateQualifier);
        try {
            Result result = rowTable.get(get);
            if (!result.isEmpty()) {
                byte[] previousValue = result.getValue(rowLogColumnFamily, executionStateQualifier);
                SubscriptionExecutionState executionState = SubscriptionExecutionState.fromBytes(previousValue);
                if (!Bytes.equals(lock,executionState.getLock(subscriptionId))) {
                    return false; // Not owning the lock
                }
                executionState.setState(subscriptionId, true);
                executionState.setLock(subscriptionId, null);
                if (executionState.allDone()) {
                    removeExecutionStateAndPayload(rowKey, executionStateQualifier, payloadQualifier(message.getSeqNr(), message.getTimestamp()), previousValue);
                } else {
                    if (!updateExecutionState(rowKey, executionStateQualifier, executionState, previousValue)) {
                        return messageDone(message, subscriptionId, lock, count+1); // Retry
                    }
                }
            }
            shard.removeMessage(message, subscriptionId);
            return true;
        } catch (IOException e) {
            throw new RowLogException("Failed to put message to done", e);
        }
    }
    
    public boolean isMessageDone(RowLogMessage message, String subscriptionId) throws RowLogException {
        SubscriptionExecutionState executionState = getExecutionState(message);
        if (executionState == null) {
            checkOrphanMessage(message, subscriptionId);
            return true;
        }
        return executionState.getState(subscriptionId);
    }

    private SubscriptionExecutionState getExecutionState(RowLogMessage message) throws RowLogException {
        byte[] rowKey = message.getRowKey();
        byte[] executionStateQualifier = executionStateQualifier(message.getSeqNr(), message.getTimestamp());
        Get get = new Get(rowKey);
        get.addColumn(rowLogColumnFamily, executionStateQualifier);
        SubscriptionExecutionState executionState = null;
        try {
            Result result = rowTable.get(get);
            byte[] previousValue = result.getValue(rowLogColumnFamily, executionStateQualifier);
            if (previousValue != null)
                executionState = SubscriptionExecutionState.fromBytes(previousValue);
        } catch (IOException e) {
            throw new RowLogException("Failed to check if message is done", e);
        }
        return executionState;
    }

    public boolean isMessageAvailable(RowLogMessage message, String subscriptionId) throws RowLogException {
        SubscriptionExecutionState executionState = getExecutionState(message);
        if (executionState == null) {
            checkOrphanMessage(message, subscriptionId);
            return false;
        }
        if (rowLogConfig.isRespectOrder()) {
            List<RowLogSubscription> subscriptions = getSubscriptions();
            Collections.sort(subscriptions);
            for (RowLogSubscription subscriptionContext : subscriptions) {
                if (subscriptionId.equals(subscriptionContext.getId()))
                    break;
                if (!executionState.getState(subscriptionContext.getId())) {
                    return false; // There is a previous subscription to be processed first
                }
            }
        }
        return !executionState.getState(subscriptionId);
    }
    
    private boolean updateExecutionState(byte[] rowKey, byte[] executionStateQualifier, SubscriptionExecutionState executionState, byte[] previousValue) throws IOException {
        Put put = new Put(rowKey);
        put.add(rowLogColumnFamily, executionStateQualifier, executionState.toBytes());
        return rowTable.checkAndPut(rowKey, rowLogColumnFamily, executionStateQualifier, previousValue, put);
    }
    
    private boolean updateExecutionState(byte[] rowKey, byte[] executionStateQualifier, SubscriptionExecutionState executionState, byte[] previousValue, RowLock rowLock) throws IOException {
        Put put = new Put(rowKey);
        put.add(rowLogColumnFamily, executionStateQualifier, executionState.toBytes());
        return rowLocker.put(put, rowLock);
    }

    private boolean removeExecutionStateAndPayload(byte[] rowKey, byte[] executionStateQualifier, byte[] payloadQualifier, byte[] previousValue) throws IOException {
        Delete delete = new Delete(rowKey); 
        delete.deleteColumns(rowLogColumnFamily, executionStateQualifier);
        delete.deleteColumns(rowLogColumnFamily, payloadQualifier);
        return rowTable.checkAndDelete(rowKey, rowLogColumnFamily, executionStateQualifier, previousValue, delete);
    }

    private boolean removeExecutionStateAndPayload(byte[] rowKey, byte[] executionStateQualifier, byte[] payloadQualifier, byte[] previousValue, RowLock rowLock) throws IOException {
        Delete delete = new Delete(rowKey); 
        delete.deleteColumns(rowLogColumnFamily, executionStateQualifier);
        delete.deleteColumns(rowLogColumnFamily, payloadQualifier);
        return rowLocker.delete(delete, rowLock);
    }
    
    // For now we work with only one shard
    private RowLogShard getShard() throws RowLogException {
        if (shard == null) {
            throw new RowLogException("No shards registerd");
        }
        return shard;
    }

    public List<RowLogMessage> getMessages(byte[] rowKey, String ... subscriptionIds) throws RowLogException {
        List<RowLogMessage> messages = new ArrayList<RowLogMessage>();
        Get get = new Get(rowKey);
        get.addFamily(rowLogColumnFamily);
        get.setFilter(new ColumnPrefixFilter(executionStatePrefix));
        try {
            Result result = rowTable.get(get);
            if (!result.isEmpty()) {
                NavigableMap<byte[], byte[]> familyMap = result.getFamilyMap(rowLogColumnFamily);
                for (Entry<byte[], byte[]> entry : familyMap.entrySet()) {
                    SubscriptionExecutionState executionState = SubscriptionExecutionState.fromBytes(entry.getValue());
                    boolean add = false;
                    if (subscriptionIds.length == 0)
                        add = true;
                    else {
                        for (String subscriptionId : subscriptionIds) {
                            if (!executionState.getState(subscriptionId))
                                add = true;
                        }
                    }
                    if (add) {
                        ByteBuffer buffer = ByteBuffer.wrap(entry.getKey());
                        messages.add(new RowLogMessageImpl(executionState.getTimestamp(), rowKey, buffer.getLong(2), null, this));
                    }
                }
            }
        } catch (IOException e) {
            throw new RowLogException("Failed to get messages", e);
        }
        return messages;
    }
    
    /**
     * Checks if the message is orphaned, meaning there is a message on the global queue which has no representative on the row-local queue.
     * If the message is orphaned it is removed from the shard.
     * @param message the message to check
     * @param subscriptionId the subscription to check the message for
     */
    private void checkOrphanMessage(RowLogMessage message, String subscriptionId) throws RowLogException {
        Get get = new Get(message.getRowKey());
        byte[] executionStateQualifier = executionStateQualifier(message.getSeqNr(), message.getTimestamp());
        get.addColumn(rowLogColumnFamily, executionStateQualifier);
        Result result;
        try {
            result = rowTable.get(get);
            if (result.isEmpty()) {
                shard.removeMessage(message, subscriptionId);
            }
        } catch (IOException e) {
            throw new RowLogException("Failed to check is message "+message+" is orphaned for subscription " + subscriptionId, e);
        }
    }
    
    public void subscriptionsChanged(List<RowLogSubscription> newSubscriptions) {
        synchronized (subscriptions) {
            for (RowLogSubscription subscription : newSubscriptions) {
                subscriptions.put(subscription.getId(), subscription);
            }
            Iterator<RowLogSubscription> iterator = subscriptions.values().iterator();
            while (iterator.hasNext()) {
                RowLogSubscription subscription = iterator.next();
                if (!newSubscriptions.contains(subscription))
                    iterator.remove();
            }
        }
        if (!initialSubscriptionsLoaded.get()) {
            synchronized (initialSubscriptionsLoaded) {
                initialSubscriptionsLoaded.set(true);
                initialSubscriptionsLoaded.notifyAll();
            }
        }
    }

    public List<RowLogShard> getShards() {
        List<RowLogShard> shards = new ArrayList<RowLogShard>();
        shards.add(shard);
        return shards;
    }
    
    public void rowLogConfigChanged(RowLogConfig rowLogConfig) {
        this.rowLogConfig = rowLogConfig;
        if (!initialRowLogConfigLoaded.get()) {
            synchronized(initialRowLogConfigLoaded) {
                initialRowLogConfigLoaded.set(true);
                initialRowLogConfigLoaded.notifyAll();
            }
        }
    }

    private byte[] payloadQualifier(long seqnr, long timestamp) {
        ByteBuffer buffer = ByteBuffer.allocate(2 + 8 + 8); // payload-prefix + seqnr + timestamp
        buffer.put(payloadPrefix);
        buffer.putLong(seqnr);
        buffer.putLong(timestamp);
        return buffer.array();
    }
    
    private byte[] executionStateQualifier(long seqnr, long timestamp) {
        ByteBuffer buffer = ByteBuffer.allocate(2 + 8 + 8); // executionState-prefix + seqnr + timestamp
        buffer.put(executionStatePrefix);
        buffer.putLong(seqnr);
        buffer.putLong(timestamp);
        return buffer.array();
    }
 }
