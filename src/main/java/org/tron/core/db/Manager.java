package org.tron.core.db;

import static org.tron.protos.Protocol.Transaction.Contract.ContractType.TransferAssertContract;
import static org.tron.protos.Protocol.Transaction.Contract.ContractType.TransferContract;

import com.carrotsearch.sizeof.RamUsageEstimator;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import javafx.util.Pair;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tron.common.storage.leveldb.LevelDbDataSourceImpl;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.DialogOptional;
import org.tron.common.utils.RandomGenerator;
import org.tron.common.utils.Sha256Hash;
import org.tron.core.actuator.Actuator;
import org.tron.core.actuator.ActuatorFactory;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.BlockCapsule.BlockId;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.capsule.TransactionResultCapsule;
import org.tron.core.capsule.WitnessCapsule;
import org.tron.core.capsule.utils.BlockUtil;
import org.tron.core.config.args.Args;
import org.tron.core.config.args.GenesisBlock;
import org.tron.core.db.AbstractRevokingStore.Dialog;
import org.tron.core.exception.BalanceInsufficientException;
import org.tron.core.exception.ContractExeException;
import org.tron.core.exception.ContractValidateException;
import org.tron.core.exception.HighFreqException;
import org.tron.core.exception.RevokingStoreIllegalStateException;
import org.tron.core.exception.UnLinkedBlockException;
import org.tron.core.exception.ValidateSignatureException;
import org.tron.protos.Protocol.AccountType;
import org.tron.protos.Protocol.Transaction;

public class Manager {

  private static final Logger logger = LoggerFactory.getLogger("Manager");

  private static final long BLOCK_INTERVAL_SEC = 1;
  private static final int MAX_ACTIVE_WITNESS_NUM = 21;
  private static final long TRXS_SIZE = 2_000_000; // < 2MiB
  public static final long LOOP_INTERVAL = Args.getInstance()
      .getBlockInterval(); // must be divisible by 60. millisecond

  private AccountStore accountStore;
  private TransactionStore transactionStore;
  private BlockStore blockStore;
  private UtxoStore utxoStore;
  private WitnessStore witnessStore;
  private AssetIssueStore assetIssueStore;
  private DynamicPropertiesStore dynamicPropertiesStore;
  private BlockCapsule genesisBlock;


  private LevelDbDataSourceImpl numHashCache;
  private KhaosDatabase khaosDb;
  private BlockCapsule head;
  private RevokingDatabase revokingStore;
  private DialogOptional<Dialog> dialog = DialogOptional.empty();


  @Getter
  @Setter
  protected List<WitnessCapsule> shuffledWitnessStates;


  public WitnessStore getWitnessStore() {
    return this.witnessStore;
  }

  private void setWitnessStore(final WitnessStore witnessStore) {
    this.witnessStore = witnessStore;
  }

  public DynamicPropertiesStore getDynamicPropertiesStore() {
    return this.dynamicPropertiesStore;
  }

  public void setDynamicPropertiesStore(final DynamicPropertiesStore dynamicPropertiesStore) {
    this.dynamicPropertiesStore = dynamicPropertiesStore;
  }

  public List<TransactionCapsule> getPendingTrxs() {
    return this.pendingTrxs;
  }


  // transaction cache
  private List<TransactionCapsule> pendingTrxs;

  private List<WitnessCapsule> wits = new ArrayList<>();

  // witness

  public List<WitnessCapsule> getWitnesses() {
    return this.wits;
  }

  public void setWitnesses(List<WitnessCapsule> wits) {
    this.wits = wits;
  }

  public BlockId getHeadBlockId() {
    return head.getBlockId();
    //return Sha256Hash.wrap(this.dynamicPropertiesStore.getLatestBlockHeaderHash());
  }

  public long getHeadBlockNum() {
    return this.head.getNum();
  }

  public long getHeadBlockTimeStamp() {
    return this.head.getTimeStamp();
  }

  public void addWitness(final WitnessCapsule witnessCapsule) {
    this.wits.add(witnessCapsule);
  }


  /**
   * get ScheduledWitness by slot.
   */
  public ByteString getScheduledWitness(final long slot) {

    final long currentSlot = getHeadSlot() + slot;

    if (currentSlot < 0) {
      throw new RuntimeException("currentSlot should be positive.");
    }
    final List<WitnessCapsule> currentShuffledWitnesses = this.getShuffledWitnessStates();
    if (CollectionUtils.isEmpty(currentShuffledWitnesses)) {
      throw new RuntimeException("ShuffledWitnesses is null.");
    }
    final int witnessIndex = (int) currentSlot % currentShuffledWitnesses.size();

    final ByteString scheduledWitness = currentShuffledWitnesses.get(witnessIndex).getAddress();
    //logger.info("scheduled_witness:" + scheduledWitness.toStringUtf8() + ",slot:" + currentSlot);

    return scheduledWitness;
  }

  private long getHeadSlot() {
    return (head.getTimeStamp() - genesisBlock.getTimeStamp()) / blockInterval();
  }

  public int calculateParticipationRate() {
    return 100 * this.dynamicPropertiesStore.getBlockFilledSlots().calculateFilledSlotsCount()
        / BlockFilledSlots.SLOT_NUMBER;
  }

  /**
   * all db should be init here.
   */
  public void init() {
    this.setAccountStore(AccountStore.create("account"));
    this.setTransactionStore(TransactionStore.create("trans"));
    this.setBlockStore(BlockStore.create("block"));
    this.setUtxoStore(UtxoStore.create("utxo"));
    this.setWitnessStore(WitnessStore.create("witness"));
    this.setAssetIssueStore(AssetIssueStore.create("asset-issue"));
    this.setDynamicPropertiesStore(DynamicPropertiesStore.create("properties"));

    revokingStore = RevokingStore.getInstance();
    revokingStore.enable();

    this.numHashCache = new LevelDbDataSourceImpl(
        Args.getInstance().getOutputDirectory(), "block" + "_NUM_HASH");
    this.numHashCache.initDB();
    this.khaosDb = new KhaosDatabase("block" + "_KDB");

    this.pendingTrxs = new ArrayList<>();
    try {
      this.initGenesis();
    } catch (ContractValidateException e) {
      logger.error(e.getMessage());
      System.exit(-1);
    } catch (ContractExeException e) {
      logger.error(e.getMessage());
      System.exit(-1);
    } catch (ValidateSignatureException e) {
      logger.error(e.getMessage());
      System.exit(-1);
    } catch (UnLinkedBlockException e) {
      logger.error(e.getMessage());
      System.exit(-1);
    }
    this.initHeadBlock(Sha256Hash.wrap(this.dynamicPropertiesStore.getLatestBlockHeaderHash()));
    this.khaosDb.start(head);
  }

  public BlockId getGenesisBlockId() {
    return this.genesisBlock.getBlockId();
  }

  public BlockCapsule getGenesisBlock() {
    return genesisBlock;
  }

  /**
   * init genesis block.
   */
  public void initGenesis()
      throws ContractValidateException, ContractExeException,
      ValidateSignatureException, UnLinkedBlockException {
    this.genesisBlock = BlockUtil.newGenesisBlockCapsule();
    if (this.containBlock(this.genesisBlock.getBlockId())) {
      Args.getInstance().setChainId(this.genesisBlock.getBlockId().toString());
    } else {
      if (this.hasBlocks()) {
        logger.error("genesis block modify, please delete database directory({}) and restart",
            Args.getInstance().getOutputDirectory());
        System.exit(1);
      } else {
        logger.info("create genesis block");
        Args.getInstance().setChainId(this.genesisBlock.getBlockId().toString());

        this.pushBlock(this.genesisBlock);

        this.dynamicPropertiesStore.saveLatestBlockHeaderNumber(0);
        this.dynamicPropertiesStore.saveLatestBlockHeaderHash(
            this.genesisBlock.getBlockId().getByteString());
        this.dynamicPropertiesStore.saveLatestBlockHeaderTimestamp(
            this.genesisBlock.getTimeStamp());
        this.initAccount();
        this.initWitness();

      }
    }
  }

  /**
   * save account into database.
   */
  public void initAccount() {
    final Args args = Args.getInstance();
    final GenesisBlock genesisBlockArg = args.getGenesisBlock();
    genesisBlockArg.getAssets().forEach(account -> {
      account.setAccountType("Normal");//to be set in conf
      final AccountCapsule accountCapsule = new AccountCapsule(account.getAccountName(),
          ByteString.copyFrom(account.getAddressBytes()),
          account.getAccountType(),
          account.getBalance());
      this.accountStore.put(account.getAddressBytes(), accountCapsule);
    });
  }

  /**
   * save witnesses into database.
   */
  private void initWitness() {
    final Args args = Args.getInstance();
    final GenesisBlock genesisBlockArg = args.getGenesisBlock();
    genesisBlockArg.getWitnesses().forEach(key -> {
      byte[] keyAddress = ByteArray.fromHexString(key.getAddress());
      ByteString address = ByteString.copyFrom(keyAddress);

      final AccountCapsule accountCapsule = new AccountCapsule(
          ByteString.EMPTY, address, AccountType.AssetIssue, 0L);
      final WitnessCapsule witnessCapsule = new WitnessCapsule(
          address, key.getVoteCount(), key.getUrl());
      witnessCapsule.setIsJobs(true);
      this.accountStore.put(keyAddress, accountCapsule);
      this.witnessStore.put(keyAddress, witnessCapsule);
      this.wits.add(witnessCapsule);
    });
  }

  public AccountStore getAccountStore() {
    return this.accountStore;
  }

  /**
   * judge balance.
   */
  public void adjustBalance(byte[] accountAddress, long amount)
      throws BalanceInsufficientException {
    AccountCapsule account = getAccountStore().get(accountAddress);
    long balance = account.getBalance();
    if (amount == 0) {
      return;
    }

    if (amount < 0 && balance < -amount) {
      throw new BalanceInsufficientException(accountAddress + " Insufficient");
    }
    account.setBalance(balance + amount);
    this.getAccountStore().put(account.getAddress().toByteArray(), account);
  }

  /**
   * push transaction into db.
   */
  public synchronized boolean pushTransactions(final TransactionCapsule trx)
      throws ValidateSignatureException, ContractValidateException, ContractExeException, HighFreqException {
    logger.info("push transaction");
    if (!trx.validateSignature()) {
      throw new ValidateSignatureException("trans sig validate failed");
    }

    validateFreq(trx);

    if (!dialog.valid()) {
      dialog = DialogOptional.of(revokingStore.buildDialog());
    }

    try (
        RevokingStore.Dialog tmpDialog = revokingStore.buildDialog()) {
      processTransaction(trx);
      pendingTrxs.add(trx);

      tmpDialog.merge();
    } catch (
        RevokingStoreIllegalStateException e) {
      e.printStackTrace();
    }
    getTransactionStore().dbSource.putData(trx.getTransactionId().getBytes(), trx.getData());
    return true;
  }

  void validateFreq(TransactionCapsule trx) throws HighFreqException {
    List<org.tron.protos.Protocol.Transaction.Contract> contracts = trx.getInstance().getRawData()
        .getContractList();
    for (Transaction.Contract contract : contracts) {
      if (contract.getType() == TransferContract ||
          contract.getType() == TransferAssertContract) {
        byte[] address = TransactionCapsule.getOwner(contract);
        AccountCapsule accountCapsule = this.getAccountStore().get(address);
        long balacne = accountCapsule.getBalance();
        long latestOperationTime = accountCapsule.getLatestOperationTime();
        int latstTransNumberInBlock = this.head.getTransactions().size();
        doValidateFreq(balacne, latstTransNumberInBlock, latestOperationTime);
      }
    }
  }

  void doValidateFreq(long balance, int transNumber, long latestOperationTime)
      throws HighFreqException {
    long now = System.currentTimeMillis();
    // todo: avoid ddos, design more smoothly formula later.
    if (balance < 1000000 * 1000) {
      if (now - latestOperationTime < 5 * 60 * 1000) {
        throw new HighFreqException("try later");
      }
    }
  }

  /**
   * when switch fork need erase blocks on fork branch.
   */
  public void eraseBlock() {
    dialog.reset();
    BlockCapsule oldHeadBlock = getBlockStore().get(head.getBlockId().getBytes());
    try {
      revokingStore.pop();
      head = getBlockStore().get(getBlockIdByNum(oldHeadBlock.getNum() - 1).getBytes());
      getDynamicPropertiesStore().saveLatestBlockHeaderHash(head.getBlockId().getByteString());
      getDynamicPropertiesStore().saveLatestBlockHeaderNumber(head.getNum());
      getDynamicPropertiesStore().saveLatestBlockHeaderTimestamp(head.getTimeStamp());
    } catch (RevokingStoreIllegalStateException e) {
      e.printStackTrace();
    }
    khaosDb.pop();
    // todo process the trans in the poped block.

  }

  private void switchFork(BlockCapsule newHead) {
    Pair<LinkedList<BlockCapsule>, LinkedList<BlockCapsule>> binaryTree = khaosDb
        .getBranch(newHead.getBlockId(), head.getBlockId());

    if (CollectionUtils.isNotEmpty(binaryTree.getValue())) {
      while (!head.getBlockId().equals(binaryTree.getValue().peekLast().getParentHash())) {
        eraseBlock();
      }
    }

    if (CollectionUtils.isNotEmpty(binaryTree.getKey())) {
      LinkedList<BlockCapsule> branch = binaryTree.getKey();
      Collections.reverse(branch);
      branch.forEach(item -> {
        // todo  process the exception carefully later
        try (Dialog tmpDialog = revokingStore.buildDialog()) {
          processBlock(item);
          tmpDialog.commit();
          head = item;
          getDynamicPropertiesStore()
              .saveLatestBlockHeaderHash(head.getBlockId().getByteString());
          getDynamicPropertiesStore().saveLatestBlockHeaderNumber(head.getNum());
          getDynamicPropertiesStore().saveLatestBlockHeaderTimestamp(head.getTimeStamp());
        } catch (ValidateSignatureException e) {
          e.printStackTrace();
        } catch (ContractValidateException e) {
          e.printStackTrace();
        } catch (ContractExeException e) {
          e.printStackTrace();
        } catch (RevokingStoreIllegalStateException e) {
          e.printStackTrace();
        }
      });
      return;
    }
  }

  //TODO: if error need to rollback.


  /**
   * validate witness schedule
   */
  private boolean validateWitnessSchedule(BlockCapsule block) {

    ByteString witnessAddress = block.getInstance().getBlockHeader().getRawData()
        .getWitnessAddress();
    //to deal with other condition later
    if (head.getBlockId().equals(block.getParentHash())) {
      long slot = getSlotAtTime(block.getTimeStamp());
      final ByteString scheduledWitness = getScheduledWitness(slot);
      if (!scheduledWitness.equals(witnessAddress)) {
        logger.warn(
            "Witness is out of order, scheduledWitness[{}],blockWitnessAddress[{}],blockTimeStamp[{}],slot[{}]",
            ByteArray.toHexString(scheduledWitness.toByteArray()),
            ByteArray.toHexString(witnessAddress.toByteArray()), new DateTime(block.getTimeStamp()),
            slot);
        return false;
      }
    }

    logger.debug("Validate witnessSchedule successfully,scheduledWitness:{}",
        ByteArray.toHexString(witnessAddress.toByteArray()));
    return true;
  }

  private synchronized void filterPendingTrx(List<TransactionCapsule> listTrx) {

  }

  /**
   * save a block.
   */
  public void pushBlock(final BlockCapsule block)
      throws ValidateSignatureException, ContractValidateException,
      ContractExeException, UnLinkedBlockException {

    List<TransactionCapsule> pendingTrxsTmp = new LinkedList<>();
    //TODO: optimize performance here.
    pendingTrxsTmp.addAll(pendingTrxs);
    pendingTrxs.clear();
    dialog.reset();

    //todo: check block's validity
    if (!block.generatedByMyself) {
      if (!block.validateSignature()) {
        logger.info("The siganature is not validated.");
        //TODO: throw exception here.
        return;
      }

      try {
        validateWitnessSchedule(block); // direct return ,need test
      } catch (Exception ex) {
        logger.error("validateWitnessSchedule error", ex);
      }

      if (!block.calcMerkleRoot().equals(block.getMerkleRoot())) {
        logger.info("The merkler root doesn't match, Calc result is " + block.calcMerkleRoot()
            + " , the headers is " + block.getMerkleRoot());
        //TODO: throw exception here.
        return;
      }
    }

    BlockCapsule newBlock = this.khaosDb.push(block);
    //DB don't need lower block
    if (head == null) {
      if (newBlock.getNum() != 0) {
        return;
      }
    } else {
      if (newBlock.getNum() <= head.getNum()) {
        return;
      }
      //switch fork
      if (!newBlock.getParentHash().equals(head.getBlockId())) {
        switchFork(newBlock);
      }

      try (Dialog tmpDialog = revokingStore.buildDialog()) {
        this.processBlock(newBlock);
        tmpDialog.commit();
      } catch (RevokingStoreIllegalStateException e) {
        e.printStackTrace();
      }
    }

    //filter trxs
    pendingTrxsTmp.stream()
        .filter(trx -> getTransactionStore().dbSource.getData(trx.getTransactionId().getBytes())
            == null)
        .forEach(trx -> {
          try {
            pushTransactions(trx);
          } catch (ValidateSignatureException e) {
            e.printStackTrace();
          } catch (ContractValidateException e) {
            e.printStackTrace();
          } catch (ContractExeException e) {
            e.printStackTrace();
          } catch (HighFreqException e) {
            e.printStackTrace();
          }
        });

    this.getBlockStore().dbSource.putData(block.getBlockId().getBytes(), block.getData());
    this.numHashCache.putData(ByteArray.fromLong(block.getNum()), block.getBlockId().getBytes());
    refreshHead(newBlock);
    logger.info("save block: " + newBlock);
  }


  private void refreshHead(BlockCapsule block) {
    this.head = block;
    this.dynamicPropertiesStore
        .saveLatestBlockHeaderHash(block.getBlockId().getByteString());
    this.dynamicPropertiesStore.saveLatestBlockHeaderNumber(block.getNum());
    this.dynamicPropertiesStore.saveLatestBlockHeaderTimestamp(block.getTimeStamp());
    updateWitnessSchedule();
  }


  /**
   * Get the fork branch.
   */
  public LinkedList<BlockId> getBlockChainHashesOnFork(final BlockId forkBlockHash) {
    final Pair<LinkedList<BlockCapsule>, LinkedList<BlockCapsule>> branch =
        this.khaosDb.getBranch(this.head.getBlockId(), forkBlockHash);
    return branch.getValue().stream()
        .map(blockCapsule -> blockCapsule.getBlockId())
        .collect(Collectors.toCollection(LinkedList::new));
  }

  /**
   * judge id.
   *
   * @param blockHash blockHash
   */
  public boolean containBlock(final Sha256Hash blockHash) {
    return this.khaosDb.containBlock(blockHash)
        || this.getBlockStore().dbSource.getData(blockHash.getBytes()) != null;
  }

  public boolean containBlockInMainChain(BlockId blockId) {
    return getBlockStore().dbSource.getData(blockId.getBytes()) != null;
  }

  /**
   * find a block packed data by id.
   */
  public byte[] findBlockByHash(final Sha256Hash hash) {
    return this.khaosDb.containBlock(hash) ? this.khaosDb.getBlock(hash).getData()
        : this.getBlockStore().dbSource.getData(hash.getBytes());
  }

  /**
   * Get a BlockCapsule by id.
   */

  public BlockCapsule getBlockById(final Sha256Hash hash) {
    return this.khaosDb.containBlock(hash) ? this.khaosDb.getBlock(hash)
        : new BlockCapsule(this.getBlockStore().dbSource.getData(hash.getBytes()));
  }

  /**
   * Delete a block.
   */

  public void deleteBlock(final Sha256Hash blockHash) {
    final BlockCapsule block = this.getBlockById(blockHash);
    this.khaosDb.removeBlk(blockHash);
    this.getBlockStore().dbSource.deleteData(blockHash.getBytes());
    this.numHashCache.deleteData(ByteArray.fromLong(block.getNum()));
    this.head = this.khaosDb.getHead();
  }

  /**
   * judge has blocks.
   */
  public boolean hasBlocks() {
    return this.getBlockStore().dbSource.allKeys().size() > 0 || this.khaosDb.hasData();
  }

  /**
   * Process transaction.
   */
  public boolean processTransaction(final TransactionCapsule trxCap)
      throws ValidateSignatureException, ContractValidateException, ContractExeException {

    TransactionResultCapsule transRet;
    if (trxCap == null || !trxCap.validateSignature()) {
      return false;
    }
    final List<Actuator> actuatorList = ActuatorFactory.createActuator(trxCap, this);
    TransactionResultCapsule ret = new TransactionResultCapsule();

    for (Actuator act : actuatorList) {

      act.validate();
      act.execute(ret);
      trxCap.setResult(ret);
    }
    return true;
  }

  /**
   * Get the block id from the number.
   */
  public BlockId getBlockIdByNum(final long num) {
    final byte[] hash = this.numHashCache.getData(ByteArray.fromLong(num));
    return ArrayUtils.isEmpty(hash)
        ? this.genesisBlock.getBlockId()
        : new BlockId(Sha256Hash.wrap(hash), num);
  }

  /**
   * Get number of block by the block id.
   */
  public long getBlockNumById(final Sha256Hash hash) {
    if (this.khaosDb.containBlock(hash)) {
      return this.khaosDb.getBlock(hash).getNum();
    }

    //TODO: optimize here
    final byte[] blockByte = this.getBlockStore().dbSource.getData(hash.getBytes());
    return ArrayUtils.isNotEmpty(blockByte) ? new BlockCapsule(blockByte).getNum() : 0;
  }


  public void initHeadBlock(final Sha256Hash id) {
    this.head = this.getBlockById(id);
  }

  /**
   * Generate a block.
   */
  public synchronized BlockCapsule generateBlock(final WitnessCapsule witnessCapsule,
      final long when, final byte[] privateKey)
      throws ValidateSignatureException, ContractValidateException,
      ContractExeException, UnLinkedBlockException {

    final long timestamp = this.dynamicPropertiesStore.getLatestBlockHeaderTimestamp();
    final long number = this.dynamicPropertiesStore.getLatestBlockHeaderNumber();
    final ByteString preHash = this.dynamicPropertiesStore.getLatestBlockHeaderHash();

    // judge create block time
    if (when < timestamp) {
      throw new IllegalArgumentException("generate block timestamp is invalid.");
    }

    long currentTrxSize = 0;
    long postponedTrxCount = 0;

    final BlockCapsule blockCapsule = new BlockCapsule(number + 1, preHash, when,
        witnessCapsule.getAddress());

    dialog.reset();
    dialog = DialogOptional.of(revokingStore.buildDialog());

    Iterator iterator = pendingTrxs.iterator();
    while (iterator.hasNext()) {
      TransactionCapsule trx = (TransactionCapsule) iterator.next();
      currentTrxSize += RamUsageEstimator.sizeOf(trx);
      // judge block size
      if (currentTrxSize > TRXS_SIZE) {
        postponedTrxCount++;
        continue;
      }
      // apply transaction
      try (Dialog tmpDialog = revokingStore.buildDialog()) {
        processTransaction(trx);
        tmpDialog.merge();
        // push into block
        blockCapsule.addTransaction(trx);
        iterator.remove();
      } catch (ContractExeException e) {
        logger.info("contract not processed during execute");
        e.printStackTrace();
      } catch (ContractValidateException e) {
        logger.info("contract not processed during validate");
        e.printStackTrace();
      } catch (RevokingStoreIllegalStateException e) {
        e.printStackTrace();
      }
    }

    dialog.reset();

    if (postponedTrxCount > 0) {
      logger.info("{} transactions over the block size limit", postponedTrxCount);
    }

    logger.info("postponedTrxCount[" + postponedTrxCount + "],TrxLeft[" + pendingTrxs.size() + "]");

    blockCapsule.setMerkleRoot();
    blockCapsule.sign(privateKey);
    blockCapsule.generatedByMyself = true;
    this.pushBlock(blockCapsule);
    return blockCapsule;
  }

  private void setAccountStore(final AccountStore accountStore) {
    this.accountStore = accountStore;
  }

  public TransactionStore getTransactionStore() {
    return this.transactionStore;
  }

  private void setTransactionStore(final TransactionStore transactionStore) {
    this.transactionStore = transactionStore;
  }

  public BlockStore getBlockStore() {
    return this.blockStore;
  }

  private void setBlockStore(final BlockStore blockStore) {
    this.blockStore = blockStore;
  }

  public UtxoStore getUtxoStore() {
    return this.utxoStore;
  }

  private void setUtxoStore(final UtxoStore utxoStore) {
    this.utxoStore = utxoStore;
  }

  /**
   * process block.
   */
  public void processBlock(BlockCapsule block)
      throws ValidateSignatureException, ContractValidateException, ContractExeException {
    for (TransactionCapsule transactionCapsule : block.getTransactions()) {
      processTransaction(transactionCapsule);
      this.updateSignedWitness(block);
    }
    if (needMaintenance(block.getTimeStamp())) {

      if (block.getNum() == 1) {
        this.dynamicPropertiesStore.updateNextMaintenanceTime(block.getTimeStamp());
      } else {
        this.processMaintenance(block);
      }
    }
  }

  /**
   * Determine if the current time is maintenance time.
   */
  public boolean needMaintenance(long blockTime) {
    return this.dynamicPropertiesStore.getNextMaintenanceTime().getMillis() <= blockTime;
  }

  /**
   * Perform maintenance.
   */
  private void processMaintenance(BlockCapsule block) {
    this.updateWitness();
    this.dynamicPropertiesStore.updateNextMaintenanceTime(block.getTimeStamp());
  }


  /**
   * update signed witness.
   */
  public void updateSignedWitness(BlockCapsule block) {
    //TODO: add verification
    WitnessCapsule witnessCapsule = witnessStore
        .get(block.getInstance().getBlockHeader().getRawData().getWitnessAddress().toByteArray());

    long latestSlotNum = 0L;

    //    dynamicPropertiesStore.current_aslot + getSlotAtTime(new DateTime(block.getTimeStamp()));

    witnessCapsule.getInstance().toBuilder().setLatestBlockNum(block.getNum())
        .setLatestSlotNum(latestSlotNum)
        .build();

    processFee();
  }

  private void processFee() {

  }

  private long blockInterval() {
    return LOOP_INTERVAL; // millisecond todo getFromDb
  }

  /**
   * get slot at time.
   */
  public long getSlotAtTime(long when) {
    long firstSlotTime = getSlotTime(1);
    if (when < firstSlotTime) {
      return 0;
    }
    logger.warn("nextFirstSlotTime:[{}],now[{}]", new DateTime(firstSlotTime), new DateTime(when));
    return (when - firstSlotTime) / blockInterval() + 1;
  }


  /**
   * get slot time.
   */
  public long getSlotTime(long slotNum) {
    if (slotNum == 0) {
      return System.currentTimeMillis();
    }
    long interval = blockInterval();

    if (getHeadBlockNum() == 0) {
      return getGenesisBlock().getTimeStamp() + slotNum * interval;
    }

    if (lastHeadBlockIsMaintenance()) {
      slotNum += getSkipSlotInMaintenance();
    }

    long headSlotTime = getHeadBlockTimestamp();
    headSlotTime = headSlotTime
        - ((headSlotTime - getGenesisBlock().getTimeStamp()) % interval);

    return headSlotTime + interval * slotNum;
  }


  private boolean lastHeadBlockIsMaintenance() {
    return getDynamicPropertiesStore().getStateFlag() == 1;
  }

  private long getHeadBlockTimestamp() {
    return head.getTimeStamp();
  }


  // To be added
  private long getSkipSlotInMaintenance() {
    return 0;
  }

  /**
   * update witness.
   */
  public void updateWitness() {
    final Map<ByteString, Long> countWitness = Maps.newHashMap();
    final List<AccountCapsule> accountList = this.accountStore.getAllAccounts();
    logger.info("there is account List size is {}", accountList.size());
    accountList.forEach(account -> {
      logger.info("there is account ,account address is {}",
          ByteArray.toHexString(account.getAddress().toByteArray()));

      Optional<Long> sum = account.getVotesList().stream().map(vote -> vote.getVoteCount())
          .reduce((a, b) -> a + b);
      if (sum.isPresent()) {
        if (sum.get() <= account.getShare()) {
          account.getVotesList().forEach(vote -> {
            //TODO validate witness //active_witness
            ByteString voteAddress = vote.getVoteAddress();
            long voteCount = vote.getVoteCount();
            if (countWitness.containsKey(voteAddress)) {
              countWitness.put(voteAddress, countWitness.get(voteAddress) + voteCount);
            } else {
              countWitness.put(voteAddress, voteCount);
            }
          });
        } else {
          logger.info(
              "account" + account.getAddress() + ",share[" + account.getShare() + "] > voteSum["
                  + sum.get() + "]");
        }
      }
    });

    witnessStore.getAllWitnesses().forEach(witnessCapsule -> {
      witnessCapsule.setVoteCount(0);
      witnessCapsule.setIsJobs(false);
      this.witnessStore.put(witnessCapsule.getAddress().toByteArray(), witnessCapsule);
    });
    final List<WitnessCapsule> witnessCapsuleList = Lists.newArrayList();
    logger.info("countWitnessMap size is {}", countWitness.keySet().size());

    //Only possible during the initialization phase
    if (countWitness.size() == 0) {
      witnessCapsuleList.addAll(this.witnessStore.getAllWitnesses());
    }

    countWitness.forEach((address, voteCount) -> {
      final WitnessCapsule witnessCapsule = this.witnessStore.get(address.toByteArray());
      if (null == witnessCapsule) {
        logger.warn("witnessCapsule is null.address is {}", address);
        return;
      }

      ByteString witnessAddress = witnessCapsule.getInstance().getAddress();
      AccountCapsule witnessAccountCapsule = accountStore.get(witnessAddress.toByteArray());
      if (witnessAccountCapsule == null) {
        logger.warn("witnessAccount[" + witnessAddress + "] not exists");
      } else {
        if (witnessAccountCapsule.getBalance() < WitnessCapsule.MIN_BALANCE) {
          logger.warn("witnessAccount[" + witnessAddress + "] has balance[" + witnessAccountCapsule
              .getBalance() + "] < MIN_BALANCE[" + WitnessCapsule.MIN_BALANCE + "]");
        } else {
          witnessCapsule.setVoteCount(witnessCapsule.getVoteCount() + voteCount);
          witnessCapsule.setIsJobs(false);
          witnessCapsuleList.add(witnessCapsule);
          this.witnessStore.put(witnessCapsule.getAddress().toByteArray(), witnessCapsule);
          logger.info("address is {}  ,countVote is {}", witnessCapsule.getAddress().toStringUtf8(),
              witnessCapsule.getVoteCount());
        }
      }
    });
    sortWitness(witnessCapsuleList);
    if (witnessCapsuleList.size() > MAX_ACTIVE_WITNESS_NUM) {
      this.wits = witnessCapsuleList.subList(0, MAX_ACTIVE_WITNESS_NUM);
    }else{
      this.wits = witnessCapsuleList;
    }

    this.wits.forEach(witnessCapsule -> {
      witnessCapsule.setIsJobs(true);
      this.witnessStore.put(witnessCapsule.getAddress().toByteArray(), witnessCapsule);
    });
  }


  /**
   * update wits sync to store.
   */
  public void updateWits() {
    wits.clear();
    witnessStore.getAllWitnesses().forEach(witnessCapsule -> {
      if (witnessCapsule.getIsJobs()) {
        wits.add(witnessCapsule);
      }
    });
    sortWitness(wits);
  }


  private void sortWitness(List<WitnessCapsule> list) {
    list.sort((a, b) -> {
      if (b.getVoteCount() != a.getVoteCount()) {
        return (int) (b.getVoteCount() - a.getVoteCount());
      } else {
        return b.getAddress().hashCode() - a.getAddress().hashCode();
      }
    });
  }

  public AssetIssueStore getAssetIssueStore() {
    return assetIssueStore;
  }

  public void setAssetIssueStore(AssetIssueStore assetIssueStore) {
    this.assetIssueStore = assetIssueStore;
  }

  /**
   * shuffle witnesses
   */
  private void updateWitnessSchedule() {
    if (CollectionUtils.isEmpty(getWitnesses())) {
      logger.warn("Witnesses is empty");
      return;
    }

    if (getHeadBlockNum() != 0 && getHeadBlockNum() % getWitnesses().size() == 0) {
      logger.info("updateWitnessSchedule number:{},HeadBlockTimeStamp:{}", getHeadBlockNum(),
          getHeadBlockTimeStamp());
      setShuffledWitnessStates(new RandomGenerator<WitnessCapsule>()
          .shuffle(getWitnesses(), getHeadBlockTimeStamp()));

      logger.debug(
          "updateWitnessSchedule,before:{} ", getWitnessStringList(getWitnesses()).toString()
              + ",\nafter:{} " + getWitnessStringList(getShuffledWitnessStates()));
    }
  }

  private List<String> getWitnessStringList(List<WitnessCapsule> witnessStates) {
    return witnessStates.stream()
        .map(witnessCapsule -> ByteArray.toHexString(witnessCapsule.getAddress().toByteArray()))
        .collect(Collectors.toList());
  }


}
