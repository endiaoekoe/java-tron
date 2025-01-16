package org.tron.core.services.cacheprovider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tron.core.Wallet;
import org.tron.core.services.http.JsonFormat;
import org.tron.protos.Protocol.Block;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class LatestBlockProvider {
    private static final Logger logger = LoggerFactory.getLogger(LatestBlockProvider.class);

    private final Wallet wallet;
    private static volatile LatestBlockProvider instance;

    // 使用ConcurrentHashMap替代Caffeine Cache
    private static final ConcurrentHashMap<String, BlockWrapper> localCache = new ConcurrentHashMap<>();
    private static final String LATEST_BLOCK_KEY = "LATEST_BLOCK";
    private static volatile long lastBlockNum = 0;
    private static volatile long lastUpdateTime = 0;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private LatestBlockProvider(Wallet wallet) {
        this.wallet = wallet;
        startBlockFreshnessChecker();
    }

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

    private static class BlockWrapper {
        final Block block;
        final long timestamp;
        final long blockNum;
        final String visibleJson;
        final String invisibleJson;

        BlockWrapper(Block block) throws Exception {
            this.block = block;
            this.timestamp = System.currentTimeMillis();
            this.blockNum = block.getBlockHeader().getRawData().getNumber();
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

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > 2900;
        }
    }

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

    public Block getLatestBlock() {
        BlockWrapper wrapper = getLatestBlockWrapper();
        return wrapper != null ? wrapper.block : null;
    }

    private BlockWrapper getLatestBlockWrapper() {
        BlockWrapper cached = localCache.get(LATEST_BLOCK_KEY);

        if (cached == null || cached.isExpired() || cached.isStale()) {
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
                BlockWrapper current = localCache.get(LATEST_BLOCK_KEY);
                if (current != null && (current.isStale() || current.isExpired())) {
                    logger.info("Detected stale block, triggering refresh");
                    refreshBlock();
                }
            } catch (Exception e) {
                logger.error("Error in block freshness verification", e);
            }
        }, 3, 3, TimeUnit.SECONDS);

        // 添加清理过期缓存的定时任务
        scheduler.scheduleAtFixedRate(() -> {
            try {
                BlockWrapper current = localCache.get(LATEST_BLOCK_KEY);
                if (current != null && current.isExpired()) {
                    localCache.remove(LATEST_BLOCK_KEY, current);
                }
            } catch (Exception e) {
                logger.error("Error in cache cleanup", e);
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    public void shutdown() {
        scheduler.shutdown();
    }
}