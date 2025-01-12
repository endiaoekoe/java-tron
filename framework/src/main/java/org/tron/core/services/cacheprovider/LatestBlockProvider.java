package org.tron.core.services.cacheprovider;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tron.core.Wallet;
import org.tron.core.services.http.JsonFormat;
import org.tron.protos.Protocol.Block;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class LatestBlockProvider {
    private static final Logger logger = LoggerFactory.getLogger(LatestBlockProvider.class);

    private final Wallet wallet;
    private static volatile LatestBlockProvider instance;

    // Cache configuration
    private static final Cache<String, BlockWrapper> localCache = Caffeine.newBuilder()
            .expireAfterWrite(2900, TimeUnit.MILLISECONDS)
            .maximumSize(1)
            .build();

    private static final String LATEST_BLOCK_KEY = "LATEST_BLOCK";
    private static volatile long lastBlockNum = 0;
    private static volatile long lastUpdateTime = 0;

    // Scheduled executor for background tasks
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private LatestBlockProvider(Wallet wallet) {
        this.wallet = wallet;
        startBlockFreshnessChecker();
    }

    // Singleton pattern with double-check locking
    public static LatestBlockProvider getInstance(Wallet wallet) {
        if (instance == null) {
            synchronized (LatestBlockProvider.class) {
                if (instance == null) {
                    instance = new LatestBlockProvider(wallet);
                }
            }
        }
        return instance;
    }

    // Block wrapper class
    private static class BlockWrapper {
        final Block block;
        final long timestamp;
        final long blockNum;
        final String visibleJson;
        final String invisibleJson;

        BlockWrapper(Block block) throws Exception {
            this.block = block;
            this.timestamp = System.currentTimeMillis();
            this.blockNum = block.getNumber();
            this.visibleJson = JsonFormat.printToString(block, true);
            this.invisibleJson = JsonFormat.printToString(block, false);
        }

        boolean isStale() {
            long currentTime = System.currentTimeMillis();
            long timeDiff = currentTime - timestamp;

            if (timeDiff > 3000) {
                return true;
            }

            long expectedBlockNum = lastBlockNum + (timeDiff / 3000);
            return blockNum < expectedBlockNum;
        }
    }

    // Main public method to get latest block JSON
    public String getLatestBlockJson(boolean visible) {
        try {
            BlockWrapper blockWrapper = getLatestBlockWrapper();
            if (blockWrapper == null) {
                return "{}";
            }
            return visible ? blockWrapper.visibleJson : blockWrapper.invisibleJson;
        } catch (Exception e) {
            logger.error("Error getting latest block", e);
            return "{}";
        }
    }

    // Get the actual Block object if needed
    public Block getLatestBlock() {
        BlockWrapper wrapper = getLatestBlockWrapper();
        return wrapper != null ? wrapper.block : null;
    }

    private BlockWrapper getLatestBlockWrapper() {
        BlockWrapper cached = localCache.getIfPresent(LATEST_BLOCK_KEY);

        if (cached == null || cached.isStale()) {
            return refreshBlock();
        }

        return cached;
    }

    private synchronized BlockWrapper refreshBlock() {
        try {
            Block newBlock = wallet.getNowBlock();
            if (newBlock == null) {
                return null;
            }

            BlockWrapper wrapper = new BlockWrapper(newBlock);
            long newBlockNum = wrapper.blockNum;
            long currentTime = System.currentTimeMillis();

            if (newBlockNum <= lastBlockNum) {
                long timeSinceLastUpdate = currentTime - lastUpdateTime;
                if (timeSinceLastUpdate > 4000) {
                    logger.warn("Received old block number: {} (last: {}), forcing refresh",
                            newBlockNum, lastBlockNum);
                    newBlock = wallet.getNowBlock();
                    if (newBlock != null) {
                        wrapper = new BlockWrapper(newBlock);
                        newBlockNum = wrapper.blockNum;
                    }
                }
            }

            if (newBlockNum > lastBlockNum) {
                lastBlockNum = newBlockNum;
                lastUpdateTime = currentTime;
                localCache.put(LATEST_BLOCK_KEY, wrapper);
                logger.debug("Updated to new block: {}", newBlockNum);
            }

            return wrapper;
        } catch (Exception e) {
            logger.error("Failed to refresh block", e);
            return null;
        }
    }

    private void startBlockFreshnessChecker() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                BlockWrapper current = localCache.getIfPresent(LATEST_BLOCK_KEY);
                if (current != null && current.isStale()) {
                    logger.info("Detected stale block, triggering refresh");
                    refreshBlock();
                }
            } catch (Exception e) {
                logger.error("Error in block freshness verification", e);
            }
        }, 3, 3, TimeUnit.SECONDS);
    }

    // Cleanup method
    public void shutdown() {
        scheduler.shutdown();
    }
}