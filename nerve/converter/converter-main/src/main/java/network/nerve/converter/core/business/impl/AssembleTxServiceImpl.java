/**
 * MIT License
 * <p>
 * Copyright (c) 2019-2020 nerve.network
 * <p>
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * <p>
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package network.nerve.converter.core.business.impl;

import io.nuls.base.basic.AddressTool;
import io.nuls.base.basic.TransactionFeeCalculator;
import io.nuls.base.data.*;
import io.nuls.base.signture.P2PHKSignature;
import io.nuls.core.constant.TxType;
import io.nuls.core.core.annotation.Autowired;
import io.nuls.core.core.annotation.Component;
import io.nuls.core.crypto.HexUtil;
import io.nuls.core.exception.NulsException;
import io.nuls.core.model.BigIntegerUtils;
import io.nuls.core.model.StringUtils;
import io.nuls.core.rpc.util.NulsDateUtils;
import network.nerve.converter.config.ConverterContext;
import network.nerve.converter.constant.ConverterCmdConstant;
import network.nerve.converter.constant.ConverterConstant;
import network.nerve.converter.constant.ConverterErrorCode;
import network.nerve.converter.core.business.AssembleTxService;
import network.nerve.converter.core.context.HeterogeneousChainManager;
import network.nerve.converter.core.validator.*;
import network.nerve.converter.enums.ProposalTypeEnum;
import network.nerve.converter.enums.ProposalVoteChoiceEnum;
import network.nerve.converter.enums.ProposalVoteRangeTypeEnum;
import network.nerve.converter.message.BroadcastHashSignMessage;
import network.nerve.converter.message.NewTxMessage;
import network.nerve.converter.model.bo.*;
import network.nerve.converter.model.dto.ProposalTxDTO;
import network.nerve.converter.model.dto.RechargeTxDTO;
import network.nerve.converter.model.dto.SignAccountDTO;
import network.nerve.converter.model.dto.WithdrawalTxDTO;
import network.nerve.converter.model.po.TransactionPO;
import network.nerve.converter.model.txdata.*;
import network.nerve.converter.rpc.call.LedgerCall;
import network.nerve.converter.rpc.call.NetWorkCall;
import network.nerve.converter.rpc.call.TransactionCall;
import network.nerve.converter.storage.HeterogeneousAssetConverterStorageService;
import network.nerve.converter.storage.TxStorageService;
import network.nerve.converter.utils.ConverterSignUtil;
import network.nerve.converter.utils.ConverterUtil;
import network.nerve.converter.utils.VirtualBankUtil;
import org.web3j.utils.Numeric;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author: Loki
 * @date: 2020-02-28
 */
@Component
public class AssembleTxServiceImpl implements AssembleTxService {

    /**
     * 普通交易为非解锁交易：0，解锁金额交易（退出共识，退出委托）：-1
     */
    private static final byte NORMAL_TX_LOCKED = 0;

    @Autowired
    private HeterogeneousChainManager heterogeneousChainManager;
    @Autowired
    private network.nerve.converter.core.business.HeterogeneousService HeterogeneousService;
    @Autowired
    private TxStorageService txStorageService;
    @Autowired
    private RechargeVerifier rechargeVerifier;
    @Autowired
    private ConfirmedChangeVirtualBankVerifier confirmedChangeVirtualBankVerifier;
    @Autowired
    private ConfirmWithdrawalVerifier confirmWithdrawalVerifier;
    @Autowired
    private HeterogeneousContractAssetRegCompleteVerifier heterogeneousContractAssetRegCompleteVerifier;
    @Autowired
    private ProposalVerifier proposalVerifier;
    @Autowired
    private ConfirmProposalVerifier confirmProposalVerifier;
    @Autowired
    private HeterogeneousAssetConverterStorageService heterogeneousAssetConverterStorageService;


    @Override
    public Transaction createChangeVirtualBankTx(Chain chain, List<byte[]> inAgentList, List<byte[]> outAgentList, long outHeight, long txTime) throws NulsException {
        Transaction tx = assembleChangeVirtualBankTx(chain, inAgentList, outAgentList, outHeight, txTime);
        TransactionCall.newTx(chain, tx);
        return tx;
    }

    @Override
    public Transaction assembleChangeVirtualBankTx(Chain chain, List<byte[]> inAgentList, List<byte[]> outAgentList, long outHeight, long txTime) throws NulsException {
        ChangeVirtualBankTxData txData = new ChangeVirtualBankTxData();
        txData.setInAgents(inAgentList);
        txData.setOutAgents(outAgentList);
        txData.setOutHeight(outHeight);
        byte[] txDataBytes = null;
        try {
            txDataBytes = txData.serialize();
        } catch (IOException e) {
            throw new NulsException(ConverterErrorCode.SERIALIZE_ERROR);
        }
        Transaction tx = assembleUnsignTxWithoutCoinData(TxType.CHANGE_VIRTUAL_BANK, txDataBytes, txTime);
        ConverterSignUtil.signTxCurrentVirtualBankAgent(chain, tx);
        chain.getLogger().debug(tx.format(ChangeVirtualBankTxData.class));
        return tx;
    }

    @Override
    public Transaction createConfirmedChangeVirtualBankTx(Chain chain, NulsHash changeVirtualBankTxHash, List<HeterogeneousConfirmedVirtualBank> listConfirmed, long txTime) throws NulsException {
        Transaction tx = this.createConfirmedChangeVirtualBankTxWithoutSign(chain, changeVirtualBankTxHash, listConfirmed, txTime);
        P2PHKSignature p2PHKSignature = ConverterSignUtil.signTxCurrentVirtualBankAgent(chain, tx);
//        confirmedChangeVirtualBankVerifier.validate(chain, tx);
        saveWaitingProcess(chain, tx);

        BroadcastHashSignMessage message = new BroadcastHashSignMessage(tx, p2PHKSignature, changeVirtualBankTxHash.toHex());
        List<HeterogeneousHash> heterogeneousHashList = new ArrayList<>();
        for (HeterogeneousConfirmedVirtualBank bank : listConfirmed) {
            heterogeneousHashList.add(new HeterogeneousHash(bank.getHeterogeneousChainId(), bank.getHeterogeneousTxHash()));
        }
        message.setHeterogeneousHashList(heterogeneousHashList);
        NetWorkCall.broadcast(chain, message, ConverterCmdConstant.NEW_HASH_SIGN_MESSAGE);
        chain.getLogger().debug(tx.format(ConfirmedChangeVirtualBankTxData.class));
        return tx;
    }

    @Override
    public Transaction createConfirmedChangeVirtualBankTxWithoutSign(Chain chain, NulsHash changeVirtualBankTxHash, List<HeterogeneousConfirmedVirtualBank> listConfirmed, long txTime) throws NulsException {
        ConfirmedChangeVirtualBankTxData txData = new ConfirmedChangeVirtualBankTxData();
        txData.setChangeVirtualBankTxHash(changeVirtualBankTxHash);
        txData.setListConfirmed(listConfirmed);
        List<byte[]> agentList = new ArrayList<>();
        List<VirtualBankDirector> directorList = new ArrayList<>(chain.getMapVirtualBank().values());
        directorList.sort((o1, o2) -> o2.getAgentAddress().compareTo(o1.getAgentAddress()));
        for (VirtualBankDirector director : directorList) {
            byte[] address = AddressTool.getAddress(director.getAgentAddress());
            agentList.add(address);
        }
        txData.setListAgents(agentList);
        byte[] txDataBytes = null;
        try {
            txDataBytes = txData.serialize();
        } catch (IOException e) {
            throw new NulsException(ConverterErrorCode.SERIALIZE_ERROR);
        }
        return assembleUnsignTxWithoutCoinData(TxType.CONFIRM_CHANGE_VIRTUAL_BANK, txDataBytes, txTime);
    }

    @Override
    public Transaction createInitializeHeterogeneousTx(Chain chain, int heterogeneousChainId, long txTime) throws NulsException {
        InitializeHeterogeneousTxData txData = new InitializeHeterogeneousTxData();
        txData.setHeterogeneousChainId(heterogeneousChainId);
        byte[] txDataBytes;
        try {
            txDataBytes = txData.serialize();
        } catch (IOException e) {
            throw new NulsException(ConverterErrorCode.SERIALIZE_ERROR);
        }
        Transaction tx = assembleUnsignTxWithoutCoinData(TxType.INITIALIZE_HETEROGENEOUS, txDataBytes, txTime);
        ConverterSignUtil.signTxCurrentVirtualBankAgent(chain, tx);
        chain.getLogger().debug(tx.format(InitializeHeterogeneousTxData.class));
        TransactionCall.newTx(chain, tx);
        return tx;
    }

    @Override
    public Transaction createRechargeTx(Chain chain, RechargeTxDTO rechargeTxDTO) throws NulsException {
        Transaction tx = this.createRechargeTxWithoutSign(chain, rechargeTxDTO);
        P2PHKSignature p2PHKSignature = ConverterSignUtil.signTxCurrentVirtualBankAgent(chain, tx);

        // 调验证器验证
        rechargeVerifier.validate(chain, tx);
        saveWaitingProcess(chain, tx);


        List<HeterogeneousHash> heterogeneousHashList = new ArrayList<>();
        heterogeneousHashList.add(new HeterogeneousHash(rechargeTxDTO.getHeterogeneousChainId(), rechargeTxDTO.getOriginalTxHash()));
        BroadcastHashSignMessage message = new BroadcastHashSignMessage(tx, p2PHKSignature, heterogeneousHashList);
        NetWorkCall.broadcast(chain, message, ConverterCmdConstant.NEW_HASH_SIGN_MESSAGE);
        // 完成
        chain.getLogger().debug(tx.format(RechargeTxData.class));
        return tx;
    }

    @Override
    public Transaction createRechargeTxWithoutSign(Chain chain, RechargeTxDTO rechargeTxDTO) throws NulsException {
        RechargeTxData txData = new RechargeTxData(rechargeTxDTO.getOriginalTxHash());
        byte[] toAddress = AddressTool.getAddress(rechargeTxDTO.getToAddress());
        int nerveAssetId = heterogeneousAssetConverterStorageService.getNerveAssetId(
                rechargeTxDTO.getHeterogeneousChainId(),
                rechargeTxDTO.getHeterogeneousAssetId());
        CoinTo coinTo = new CoinTo(
                toAddress,
                chain.getChainId(),
                nerveAssetId,
                rechargeTxDTO.getAmount());
        List<CoinTo> tos = new ArrayList<>();
        tos.add(coinTo);
        CoinData coinData = new CoinData();
        coinData.setTo(tos);
        byte[] coinDataBytes = null;
        byte[] txDataBytes = null;
        try {
            txDataBytes = txData.serialize();
            coinDataBytes = coinData.serialize();
        } catch (IOException e) {
            throw new NulsException(ConverterErrorCode.SERIALIZE_ERROR);
        }
        Transaction tx = assembleUnsignTxWithoutCoinData(TxType.RECHARGE, txDataBytes, rechargeTxDTO.getTxtime());
        tx.setCoinData(coinDataBytes);
        return tx;
    }

    /**
     * 交易验证成功保存并广播签名,然后等待处理
     *
     * @param chain
     * @param tx
     */
    private void saveWaitingProcess(Chain chain, Transaction tx) {
        // 保存进txStorageService
        txStorageService.save(chain, new TransactionPO(tx));
        // 如果待处理签名集合 中有此交易的签名列表 则拿出来放入处理队列
        List<UntreatedMessage> listMsg = chain.getFutureMessageMap().get(tx.getHash());
        if (null != listMsg) {
            for (UntreatedMessage msg : listMsg) {
                chain.getSignMessageByzantineQueue().offer(msg);
            }
            // 清空缓存的签名
            chain.getFutureMessageMap().remove(tx.getHash());
        }
    }

    @Override
    public Transaction createWithdrawalTx(Chain chain, WithdrawalTxDTO withdrawalTxDTO) throws NulsException {
        HeterogeneousAssetInfo heterogeneousAssetInfo = heterogeneousAssetConverterStorageService.getHeterogeneousAssetInfo(withdrawalTxDTO.getAssetId());

        String heterogeneousAddress = withdrawalTxDTO.getHeterogeneousAddress().toLowerCase();
        if (null == heterogeneousAssetInfo ||
                null == heterogeneousChainManager.getHeterogeneousChainByChainId(heterogeneousAssetInfo.getChainId())) {
            throw new NulsException(ConverterErrorCode.HETEROGENEOUS_CHAINID_ERROR);
        }
        if (StringUtils.isBlank(heterogeneousAddress)) {
            throw new NulsException(ConverterErrorCode.HETEROGENEOUS_ADDRESS_NULL);
        }
        WithdrawalTxData txData = new WithdrawalTxData(heterogeneousAddress);
        byte[] txDataBytes = null;
        try {
            txDataBytes = txData.serialize();
        } catch (IOException e) {
            throw new NulsException(ConverterErrorCode.SERIALIZE_ERROR);
        }

        Transaction tx = assembleUnsignTxWithoutCoinData(TxType.WITHDRAWAL, txDataBytes, withdrawalTxDTO.getRemark());
        byte[] coinData = assembleWithdrawalCoinData(chain, withdrawalTxDTO);
        tx.setCoinData(coinData);
        //签名
        ConverterSignUtil.signTx(tx, withdrawalTxDTO.getSignAccount());
        chain.getLogger().debug(tx.format(WithdrawalTxData.class));
        //广播
        TransactionCall.newTx(chain, tx);
        return tx;
    }


    @Override
    public Transaction createConfirmWithdrawalTx(Chain chain, ConfirmWithdrawalTxData confirmWithdrawalTxData, long txTime) throws NulsException {
        Transaction tx = this.createConfirmWithdrawalTxWithoutSign(chain, confirmWithdrawalTxData, txTime);
        P2PHKSignature p2PHKSignature = ConverterSignUtil.signTxCurrentVirtualBankAgent(chain, tx);
        confirmWithdrawalVerifier.validate(chain, tx);
        saveWaitingProcess(chain, tx);

        List<HeterogeneousHash> heterogeneousHashList = new ArrayList<>();
        heterogeneousHashList.add(new HeterogeneousHash(confirmWithdrawalTxData.getHeterogeneousChainId(), confirmWithdrawalTxData.getHeterogeneousTxHash()));
        BroadcastHashSignMessage message = new BroadcastHashSignMessage(tx, p2PHKSignature, confirmWithdrawalTxData.getWithdrawalTxHash().toHex(), heterogeneousHashList);
        NetWorkCall.broadcast(chain, message, ConverterCmdConstant.NEW_HASH_SIGN_MESSAGE);

        chain.getLogger().debug(tx.format(ConfirmWithdrawalTxData.class));
        return tx;
    }

    @Override
    public Transaction createConfirmWithdrawalTxWithoutSign(Chain chain, ConfirmWithdrawalTxData confirmWithdrawalTxData, long txTime) throws NulsException {
        byte[] txDataBytes = null;
        try {
            txDataBytes = confirmWithdrawalTxData.serialize();
        } catch (IOException e) {
            throw new NulsException(ConverterErrorCode.SERIALIZE_ERROR);
        }
        Transaction tx = assembleUnsignTxWithoutCoinData(TxType.CONFIRM_WITHDRAWAL, txDataBytes, txTime);
        return tx;
    }

    @Override
    public Transaction processProposalTx(Chain chain, Transaction tx) throws NulsException {
        return processProposalTx(chain, tx, null, null);
    }

    public Transaction processProposalTx(Chain chain, Transaction tx, Integer heterogeneousChainId, String heterogeneousTxHash) throws NulsException {
        proposalVerifier.validate(chain, tx);
        saveWaitingProcess(chain, tx);
        //广播完整交易
        NewTxMessage newTxMessage = new NewTxMessage();
        newTxMessage.setTx(tx);
        NetWorkCall.broadcast(chain, newTxMessage, ConverterCmdConstant.NEW_TX_MESSAGE);

        if (VirtualBankUtil.isCurrentDirector(chain)) {
            if (null == heterogeneousChainId || StringUtils.isBlank(heterogeneousTxHash)) {
                ProposalTxData txdata = ConverterUtil.getInstance(tx.getTxData(), ProposalTxData.class);
                heterogeneousChainId = txdata.getHeterogeneousChainId();
                heterogeneousTxHash = txdata.getHeterogeneousTxHash();
            }
            // 如果当前是虚拟银行节点, 直接开始处理.
            List<HeterogeneousHash> heterogeneousHashList = new ArrayList<>();
            heterogeneousHashList.add(new HeterogeneousHash(heterogeneousChainId, heterogeneousTxHash));
            NulsHash txHash = tx.getHash();
            P2PHKSignature p2PHKSignature = ConverterSignUtil.getSignatureByDirector(chain, txHash);
            BroadcastHashSignMessage message = new BroadcastHashSignMessage(tx, p2PHKSignature, heterogeneousHashList);
            NetWorkCall.broadcast(chain, message, ConverterCmdConstant.NEW_HASH_SIGN_MESSAGE);

            UntreatedMessage untreatedMessage = new UntreatedMessage(chain.getChainId(), null, message, txHash);
            chain.getSignMessageByzantineQueue().offer(untreatedMessage);
        }

        chain.getLogger().debug(tx.format(ProposalTxData.class));
        return tx;
    }

    @Override
    public Transaction createProposalTx(Chain chain, ProposalTxDTO proposalTxDTO) throws NulsException {
        if (null == ProposalTypeEnum.getEnum(proposalTxDTO.getType())) {
            //枚举
            throw new NulsException(ConverterErrorCode.PROPOSAL_TYPE_ERROR);
        }
        ProposalTxData txData = new ProposalTxData();
        txData.setType(proposalTxDTO.getType());
        if (ProposalTypeEnum.getEnum(proposalTxDTO.getType()) == ProposalTypeEnum.OTHER) {
            txData.setVoteRangeType(proposalTxDTO.getVoteRangeType());
        } else {
            txData.setVoteRangeType(ProposalVoteRangeTypeEnum.BANK.value());
        }
        txData.setContent(proposalTxDTO.getContent());
        txData.setHeterogeneousChainId(proposalTxDTO.getHeterogeneousChainId());
        String heterogeneousTxHash = proposalTxDTO.getHeterogeneousTxHash();
        if (StringUtils.isNotBlank(heterogeneousTxHash)) {
            txData.setHeterogeneousTxHash(heterogeneousTxHash.toLowerCase());
        }
        String businessAddress = proposalTxDTO.getBusinessAddress();
        if (StringUtils.isNotBlank(businessAddress)) {
            if (Numeric.containsHexPrefix(businessAddress)) {
                txData.setAddress(Numeric.hexStringToByteArray(businessAddress));
            } else {
                txData.setAddress(AddressTool.getAddress(proposalTxDTO.getBusinessAddress()));
            }
        }

        String hash = proposalTxDTO.getHash();
        if (StringUtils.isNotBlank(hash)) {
            txData.setHash(HexUtil.decode(proposalTxDTO.getHash()));
        }

        byte[] txDataBytes = null;
        try {
            txDataBytes = txData.serialize();
        } catch (IOException e) {
            throw new NulsException(ConverterErrorCode.SERIALIZE_ERROR);
        }
        Transaction tx = assembleUnsignTxWithoutCoinData(TxType.PROPOSAL, txDataBytes, proposalTxDTO.getRemark());
        tx.setCoinData(assembleFeeCoinData(chain, proposalTxDTO.getSignAccountDTO(), ConverterContext.PROPOSAL_PRICE));

        ConverterSignUtil.signTx(tx, proposalTxDTO.getSignAccountDTO());
        return processProposalTx(chain, tx, proposalTxDTO.getHeterogeneousChainId(), proposalTxDTO.getHeterogeneousTxHash());
    }

    @Override
    public Transaction createVoteProposalTx(Chain chain, NulsHash proposalTxHash, byte choice, String remark, SignAccountDTO signAccount) throws NulsException {
        if (null == proposalTxHash) {
            throw new NulsException(ConverterErrorCode.PROPOSAL_TX_HASH_NULL);
        }
        if (null == ProposalVoteChoiceEnum.getEnum(choice)) {
            //枚举
            throw new NulsException(ConverterErrorCode.PROPOSAL_VOTE_INVALID);
        }
        VoteProposalTxData voteProposalTxData = new VoteProposalTxData(proposalTxHash, choice);
        byte[] txDataBytes = null;
        try {
            txDataBytes = voteProposalTxData.serialize();
        } catch (IOException e) {
            throw new NulsException(ConverterErrorCode.SERIALIZE_ERROR);
        }
        Transaction tx = assembleUnsignTxWithFee(chain, TxType.VOTE_PROPOSAL, txDataBytes, remark, signAccount);
        ConverterSignUtil.signTx(tx, signAccount);
        TransactionCall.newTx(chain, tx);

        chain.getLogger().debug(tx.format(VoteProposalTxData.class));
        return tx;
    }


    @Override
    public Transaction createConfirmProposalTx(Chain chain, ConfirmProposalTxData confirmProposalTxData, long txTime) throws NulsException {
        Transaction tx = this.createConfirmProposalTxWithoutSign(chain, confirmProposalTxData, txTime);
        // 拜占庭交易流程
        P2PHKSignature p2PHKSignature = ConverterSignUtil.signTxCurrentVirtualBankAgent(chain, tx);
        confirmProposalVerifier.validate(chain, tx);
        saveWaitingProcess(chain, tx);
        byte proposalType = confirmProposalTxData.getType();
        BroadcastHashSignMessage message;
        if (ProposalTypeEnum.REFUND.value() == proposalType
                || ProposalTypeEnum.EXPELLED.value() == proposalType
                || ProposalTypeEnum.WITHDRAW.value() == proposalType) {
            List<HeterogeneousHash> heterogeneousHashList = new ArrayList<>();
            ProposalExeBusinessData business = ConverterUtil.getInstance(
                    confirmProposalTxData.getBusinessData(),
                    ProposalExeBusinessData.class);
            heterogeneousHashList.add(new HeterogeneousHash(business.getHeterogeneousChainId(), business.getHeterogeneousTxHash()));
            message = new BroadcastHashSignMessage(tx, p2PHKSignature, business.getProposalTxHash().toHex(), heterogeneousHashList);
        } else if(ProposalTypeEnum.UPGRADE.value() == proposalType){
            List<HeterogeneousHash> heterogeneousHashList = new ArrayList<>();
            ConfirmUpgradeTxData business = ConverterUtil.getInstance(
                    confirmProposalTxData.getBusinessData(),
                    ConfirmUpgradeTxData.class);
            heterogeneousHashList.add(new HeterogeneousHash(business.getHeterogeneousChainId(), business.getHeterogeneousTxHash()));
            message = new BroadcastHashSignMessage(tx, p2PHKSignature, business.getNerveTxHash().toHex(), heterogeneousHashList);
        } else {
            ProposalExeBusinessData business = ConverterUtil.getInstance(
                    confirmProposalTxData.getBusinessData(),
                    ProposalExeBusinessData.class);
            message = new BroadcastHashSignMessage(tx, p2PHKSignature, business.getProposalTxHash().toHex());
        }
        NetWorkCall.broadcast(chain, message, ConverterCmdConstant.NEW_HASH_SIGN_MESSAGE);
        chain.getLogger().debug(tx.format(ConfirmProposalTxData.class));
        return tx;
    }

    @Override
    public Transaction createConfirmProposalTxWithoutSign(Chain chain, ConfirmProposalTxData confirmProposalTxData, long txTime) throws NulsException {
        ProposalTypeEnum typeEnum = ProposalTypeEnum.getEnum(confirmProposalTxData.getType());
        if (null == typeEnum) {
            //枚举
            throw new NulsException(ConverterErrorCode.PROPOSAL_TYPE_ERROR);
        }
        byte[] txDataBytes;
        try {
            txDataBytes = confirmProposalTxData.serialize();
        } catch (IOException e) {
            throw new NulsException(ConverterErrorCode.SERIALIZE_ERROR);
        }
        Transaction tx = assembleUnsignTxWithoutCoinData(TxType.CONFIRM_PROPOSAL, txDataBytes, txTime);
        return tx;
    }

    @Override
    public Transaction createDistributionFeeTx(Chain chain, NulsHash basisTxHash, List<byte[]> listRewardAddress, long txTime, boolean isProposal) throws NulsException {
        DistributionFeeTxData distributionFeeTxData = new DistributionFeeTxData();
        distributionFeeTxData.setBasisTxHash(basisTxHash);
        byte[] txData = null;
        try {
            txData = distributionFeeTxData.serialize();
        } catch (IOException e) {
            throw new NulsException(ConverterErrorCode.SERIALIZE_ERROR);
        }
        Transaction tx = assembleUnsignTxWithoutCoinData(TxType.DISTRIBUTION_FEE, txData, txTime);
        byte[] coinData = assembleDistributionFeeCoinData(chain, listRewardAddress, isProposal);
        tx.setCoinData(coinData);
        ConverterSignUtil.signTxCurrentVirtualBankAgent(chain, tx);
        chain.getLogger().debug(tx.format(DistributionFeeTxData.class));
        TransactionCall.newTx(chain, tx);
        return tx;
    }


    @Override
    public Transaction createHeterogeneousContractAssetRegPendingTx(Chain chain, String from, String password,
                                                                    int heterogeneousChainId, int decimals, String symbol,
                                                                    String contractAddress, String remark) throws NulsException {
        if (null == heterogeneousChainManager.getHeterogeneousChainByChainId(heterogeneousChainId)) {
            throw new NulsException(ConverterErrorCode.HETEROGENEOUS_CHAINID_ERROR);
        }
        HeterogeneousContractAssetRegPendingTxData txData = new HeterogeneousContractAssetRegPendingTxData();
        txData.setChainId(heterogeneousChainId);
        txData.setDecimals((byte) decimals);
        txData.setSymbol(symbol);
        txData.setContractAddress(contractAddress.toLowerCase());
        byte[] txDataBytes;
        try {
            txDataBytes = txData.serialize();
        } catch (IOException e) {
            throw new NulsException(ConverterErrorCode.SERIALIZE_ERROR);
        }
        Transaction tx = new Transaction(TxType.HETEROGENEOUS_CONTRACT_ASSET_REG_PENDING);
        tx.setTxData(txDataBytes);
        tx.setTime(NulsDateUtils.getCurrentTimeSeconds());
        tx.setRemark(StringUtils.isBlank(remark) ? null : StringUtils.bytes(remark));

        SignAccountDTO signAccountDTO = new SignAccountDTO(from, password);
        byte[] coinData = assembleFeeCoinData(chain, signAccountDTO);
        tx.setCoinData(coinData);
        //签名
        ConverterSignUtil.signTx(tx, signAccountDTO);
        //广播
        TransactionCall.newTx(chain, tx);

        chain.getLogger().debug(tx.format(HeterogeneousContractAssetRegPendingTxData.class));
        return tx;
    }

    @Override
    public Transaction createHeterogeneousContractAssetRegCompleteTx(Chain chain, Transaction pendingTx) throws NulsException {
        if (pendingTx == null || pendingTx.getType() != TxType.HETEROGENEOUS_CONTRACT_ASSET_REG_PENDING) {
            chain.getLogger().error("交易信息为空或类型不正确");
            throw new NulsException(ConverterErrorCode.NULL_PARAMETER);
        }
        HeterogeneousContractAssetRegPendingTxData pendingTxData = new HeterogeneousContractAssetRegPendingTxData();
        pendingTxData.parse(pendingTx.getTxData(), 0);

        HeterogeneousContractAssetRegCompleteTxData txData = new HeterogeneousContractAssetRegCompleteTxData();
        txData.setPendingHash(pendingTx.getHash());
        txData.setChainId(pendingTxData.getChainId());
        txData.setDecimals(pendingTxData.getDecimals());
        txData.setSymbol(pendingTxData.getSymbol());
        txData.setContractAddress(pendingTxData.getContractAddress());
        byte[] txDataBytes;
        try {
            txDataBytes = txData.serialize();
        } catch (IOException e) {
            throw new NulsException(ConverterErrorCode.SERIALIZE_ERROR);
        }
        Transaction tx = new Transaction(TxType.HETEROGENEOUS_CONTRACT_ASSET_REG_COMPLETE);
        tx.setTxData(txDataBytes);
        tx.setTime(pendingTx.getTime());
        tx.setRemark(pendingTx.getRemark());
        P2PHKSignature p2PHKSignature = ConverterSignUtil.signTxCurrentVirtualBankAgent(chain, tx);

        // 调验证器验证
        heterogeneousContractAssetRegCompleteVerifier.validate(chain.getChainId(), tx);
        saveWaitingProcess(chain, tx);
        BroadcastHashSignMessage message = new BroadcastHashSignMessage();
        message.setHash(tx.getHash());
        message.setP2PHKSignature(p2PHKSignature);
        message.setType(tx.getType());
        NetWorkCall.broadcast(chain, message, ConverterCmdConstant.NEW_HASH_SIGN_MESSAGE);

        // 完成
        if (chain.getLogger().isDebugEnabled()) {
            chain.getLogger().debug(tx.format(HeterogeneousContractAssetRegCompleteTxData.class));
        }
        return tx;
    }

    @Override
    public Transaction createResetVirtualBankTx(Chain chain, int heterogeneousChainId, SignAccountDTO signAccount) throws NulsException {
        ResetVirtualBankTxData txData = new ResetVirtualBankTxData();
        txData.setHeterogeneousChainId(heterogeneousChainId);
        byte[] txDataBytes;
        try {
            txDataBytes = txData.serialize();
        } catch (IOException e) {
            throw new NulsException(ConverterErrorCode.SERIALIZE_ERROR);
        }
        Transaction tx = assembleUnsignTxWithoutCoinData(TxType.RESET_HETEROGENEOUS_VIRTUAL_BANK, txDataBytes);
        ConverterSignUtil.signTx(tx, signAccount);
        chain.getLogger().debug(tx.format(ResetVirtualBankTxData.class));
        TransactionCall.newTx(chain, tx);
        return tx;
    }

    @Override
    public Transaction createConfirmResetVirtualBankTx(Chain chain, ConfirmResetVirtualBankTxData txData, long txTime) throws NulsException {
        byte[] txDataBytes;
        try {
            txDataBytes = txData.serialize();
        } catch (IOException e) {
            throw new NulsException(ConverterErrorCode.SERIALIZE_ERROR);
        }
        Transaction tx = assembleUnsignTxWithoutCoinData(TxType.CONFIRM_HETEROGENEOUS_RESET_VIRTUAL_BANK, txDataBytes, txTime);
        ConverterSignUtil.signTxCurrentVirtualBankAgent(chain, tx);
        chain.getLogger().debug(tx.format(ConfirmResetVirtualBankTxData.class));
        TransactionCall.newTx(chain, tx);
        return tx;
    }

    /**
     * 组装提现交易CoinData
     *
     * @param chain
     * @param withdrawalTxDTO
     * @return
     * @throws NulsException
     */
    private byte[] assembleWithdrawalCoinData(Chain chain, WithdrawalTxDTO withdrawalTxDTO) throws NulsException {
        int withdrawalAssetId = withdrawalTxDTO.getAssetId();


        int chainId = chain.getConfig().getChainId();
        int assetId = chain.getConfig().getAssetId();
        BigInteger amount = withdrawalTxDTO.getAmount();
        String address = withdrawalTxDTO.getSignAccount().getAddress();
        //提现资产from
        CoinFrom withdrawalCoinFrom = getWithdrawalCoinFrom(chain, address, amount, chainId, withdrawalAssetId);

        CoinFrom withdrawalFeeCoinFrom = null;
        //手续费from 包含异构链补贴手续费
        withdrawalFeeCoinFrom = getWithdrawalFeeCoinFrom(chain, address, ConverterContext.DISTRIBUTION_FEE);
        List<CoinFrom> listFrom = new ArrayList<>();
        listFrom.add(withdrawalCoinFrom);
        listFrom.add(withdrawalFeeCoinFrom);

        //组装to
        List<CoinTo> listTo = new ArrayList<>();
        CoinTo withdrawalCoinTo = new CoinTo(
                AddressTool.getAddress(ConverterContext.WITHDRAWAL_BLACKHOLE_PUBKEY, chain.getChainId()),
                chainId,
                withdrawalAssetId,
                amount);

        listTo.add(withdrawalCoinTo);
        // 判断组装异构链补贴手续费暂存to
        CoinTo withdrawalFeeCoinTo = new CoinTo(
                AddressTool.getAddress(ConverterContext.FEE_PUBKEY, chain.getChainId()),
                chainId,
                assetId,
                ConverterContext.DISTRIBUTION_FEE);
        listTo.add(withdrawalFeeCoinTo);
        CoinData coinData = new CoinData(listFrom, listTo);
        try {
            return coinData.serialize();
        } catch (IOException e) {
            throw new NulsException(ConverterErrorCode.SERIALIZE_ERROR);
        }
    }


    /**
     * 组装提现资产CoinFrom
     *
     * @param chain
     * @param address
     * @param amount
     * @param heterogeneousAssetId
     * @return
     * @throws NulsException
     */
    private CoinFrom getWithdrawalCoinFrom(
            Chain chain,
            String address,
            BigInteger amount,
            int heterogeneousChainId,
            int heterogeneousAssetId) throws NulsException {
        //提现资产
        if (BigIntegerUtils.isEqualOrLessThan(amount, BigInteger.ZERO)) {
            chain.getLogger().error("提现金额不能小于0, amount:{}", amount);
            throw new NulsException(ConverterErrorCode.PARAMETER_ERROR);
        }
        NonceBalance withdrawalNonceBalance = LedgerCall.getBalanceNonce(
                chain,
                heterogeneousChainId,
                heterogeneousAssetId,
                address);

        BigInteger withdrawalAssetBalance = withdrawalNonceBalance.getAvailable();

        if (BigIntegerUtils.isLessThan(withdrawalAssetBalance, amount)) {
            chain.getLogger().error("提现资产余额不足 chainId:{}, assetId:{}, withdrawal amount:{}, available balance:{} ",
                    heterogeneousChainId, heterogeneousAssetId, amount, withdrawalAssetBalance);
            throw new NulsException(ConverterErrorCode.INSUFFICIENT_BALANCE);
        }

        return new CoinFrom(
                AddressTool.getAddress(address),
                heterogeneousChainId,
                heterogeneousAssetId,
                amount,
                withdrawalNonceBalance.getNonce(),
                (byte) 0);
    }

    /**
     * 组装提现交易手续费(包含链内打包手续费, 异构链补贴手续费)
     *
     * @param chain
     * @param address
     * @param withdrawalSignFeeNvt
     * @return
     * @throws NulsException
     */
    private CoinFrom getWithdrawalFeeCoinFrom(Chain chain, String address, BigInteger withdrawalSignFeeNvt) throws NulsException {
        int chainId = chain.getConfig().getChainId();
        int assetId = chain.getConfig().getAssetId();
        NonceBalance currentChainNonceBalance = LedgerCall.getBalanceNonce(
                chain,
                chainId,
                assetId,
                address);
        // 本链资产余额
        BigInteger balance = currentChainNonceBalance.getAvailable();

        // 总手续费 = 链内打包手续费 + 异构链转账(或签名)手续费[都以链内主资产结算]
        BigInteger totalFee = TransactionFeeCalculator.NORMAL_PRICE_PRE_1024_BYTES.add(withdrawalSignFeeNvt);
        if (BigIntegerUtils.isLessThan(balance, totalFee)) {
            chain.getLogger().error("Insufficient balance of withdrawal fee. amount to be paid:{}, available balance:{} ", totalFee, balance);
            throw new NulsException(ConverterErrorCode.INSUFFICIENT_BALANCE);
        }
        // 查询账本获取nonce值
        byte[] nonce = currentChainNonceBalance.getNonce();

        return new CoinFrom(AddressTool.getAddress(address), chainId, assetId, totalFee, nonce, (byte) 0);
    }

    /**
     * 组装提现交易打包手续费(只包含链内打包手续费)
     *
     * @param chain
     * @param address
     * @return
     * @throws NulsException
     */
    private CoinFrom getWithdrawalFeeCoinFrom(Chain chain, String address) throws NulsException {
        int chainId = chain.getConfig().getChainId();
        int assetId = chain.getConfig().getAssetId();
        NonceBalance currentChainNonceBalance = LedgerCall.getBalanceNonce(
                chain,
                chainId,
                assetId,
                address);
        // 本链资产余额
        BigInteger balance = currentChainNonceBalance.getAvailable();
        //打包手续费
        if (BigIntegerUtils.isLessThan(balance, TransactionFeeCalculator.NORMAL_PRICE_PRE_1024_BYTES)) {
            chain.getLogger().error("The balance is insufficient to cover the package fee");
            throw new NulsException(ConverterErrorCode.INSUFFICIENT_BALANCE);
        }
        // 查询账本获取nonce值
        byte[] nonce = currentChainNonceBalance.getNonce();

        return new CoinFrom(
                AddressTool.getAddress(address),
                chainId,
                assetId,
                TransactionFeeCalculator.NORMAL_PRICE_PRE_1024_BYTES,
                nonce,
                (byte) 0);
    }


    /**
     * 组装补贴手续费交易CoinData
     *
     * @param chain
     * @param listRewardAddress
     * @return
     * @throws NulsException
     */
    private byte[] assembleDistributionFeeCoinData(Chain chain, List<byte[]> listRewardAddress, boolean isProposal) throws NulsException {
        byte[] feeFromAdddress = AddressTool.getAddress(ConverterContext.FEE_PUBKEY, chain.getChainId());
        List<CoinFrom> listFrom = assembleDistributionFeeCoinFrom(chain, feeFromAdddress, isProposal);
        List<CoinTo> listTo = assembleDistributionFeeCoinTo(chain, listRewardAddress, isProposal);
        CoinData coinData = new CoinData(listFrom, listTo);
        try {
            return coinData.serialize();
        } catch (IOException e) {
            throw new NulsException(ConverterErrorCode.SERIALIZE_ERROR);
        }
    }

    /**
     * 组装补贴手续费交易CoinFrom
     *
     * @param chain
     * @param feeFromAdddress
     * @return
     * @throws NulsException
     */
    private List<CoinFrom> assembleDistributionFeeCoinFrom(Chain chain, byte[] feeFromAdddress, boolean isProposal) throws NulsException {
        int assetChainId = chain.getConfig().getChainId();
        int assetId = chain.getConfig().getAssetId();

        BigInteger amountFee = null;
        if (!isProposal) {
            long height = chain.getLatestBasicBlock().getHeight();
            if (height < ConverterContext.FEE_EFFECTIVE_HEIGHT) {
                amountFee = ConverterConstant.DISTRIBUTION_FEE_OLD;
            } else {
                amountFee = ConverterContext.DISTRIBUTION_FEE;
            }
        } else {
            amountFee = ConverterContext.PROPOSAL_PRICE;
        }
        //查询手续费暂存地址余额够不够
        NonceBalance currentChainNonceBalance = LedgerCall.getBalanceNonce(
                chain,
                assetChainId,
                assetId,
                AddressTool.getStringAddressByBytes(feeFromAdddress));
        // 查询账本获取nonce值
        byte[] nonce = currentChainNonceBalance.getNonce();
        CoinFrom coinFrom = new CoinFrom(feeFromAdddress,
                assetChainId,
                assetId,
                amountFee,
                nonce,
                (byte) 0);
        List<CoinFrom> listFrom = new ArrayList<>();
        listFrom.add(coinFrom);
        return listFrom;
    }

    /**
     * 组装补贴手续费交易CoinTo
     *
     * @param chain
     * @param listRewardAddress
     * @return
     * @throws NulsException
     */
    private List<CoinTo> assembleDistributionFeeCoinTo(Chain chain, List<byte[]> listRewardAddress, boolean isProposal) throws NulsException {
        // 计算 每个节点补贴多少手续费
        BigInteger count = BigInteger.valueOf(listRewardAddress.size());

        BigInteger distributionFee = null;
        if (!isProposal) {
            long height = chain.getLatestBasicBlock().getHeight();
            if (height < ConverterContext.FEE_EFFECTIVE_HEIGHT) {
                distributionFee = ConverterConstant.DISTRIBUTION_FEE_OLD;
            } else {
                distributionFee = ConverterContext.DISTRIBUTION_FEE;
            }
        } else {
            distributionFee = ConverterContext.PROPOSAL_PRICE;
        }

        BigInteger amount = distributionFee.divide(count);
        Map<String, BigInteger> map = calculateDistributionFeeCoinToAmount(listRewardAddress, amount);
        // 组装cointo
        List<CoinTo> listTo = new ArrayList<>();
        for (Map.Entry<String, BigInteger> entry : map.entrySet()) {
            CoinTo distributionFeeCoinTo = new CoinTo(
                    AddressTool.getAddress(entry.getKey()),
                    chain.getConfig().getChainId(),
                    chain.getConfig().getAssetId(),
                    entry.getValue());
            listTo.add(distributionFeeCoinTo);
        }
        return listTo;
    }

    @Override
    public Map<String, BigInteger> calculateDistributionFeeCoinToAmount(List<byte[]> listRewardAddress, BigInteger amount) {
        Map<String, BigInteger> map = new HashMap<>();
        for (byte[] address : listRewardAddress) {
            String addr = AddressTool.getStringAddressByBytes(address);
            map.computeIfPresent(addr, (k, v) -> v.add(amount));
            map.putIfAbsent(addr, amount);
        }
        return map;
    }

    /**
     * 组装不含CoinData的交易
     *
     * @param txData
     * @return
     * @throws NulsException
     */
    private Transaction assembleUnsignTxWithoutCoinData(int type, byte[] txData, Long txTime, String remark) throws NulsException {
        Transaction tx = new Transaction(type);
        tx.setTxData(txData);
        tx.setTime(null == txTime ? NulsDateUtils.getCurrentTimeSeconds() : txTime);
        tx.setRemark(StringUtils.isBlank(remark) ? null : StringUtils.bytes(remark));
        return tx;
    }

    private Transaction assembleUnsignTxWithoutCoinData(int type, byte[] txData, long txTime) throws NulsException {
        return assembleUnsignTxWithoutCoinData(type, txData, txTime, null);
    }

    private Transaction assembleUnsignTxWithoutCoinData(int type, byte[] txData, String remark) throws NulsException {
        return assembleUnsignTxWithoutCoinData(type, txData, null, remark);
    }

    private Transaction assembleUnsignTxWithoutCoinData(int type, byte[] txData) throws NulsException {
        return assembleUnsignTxWithoutCoinData(type, txData, null, null);
    }

    /**
     * 组装交易，CoinData只包含手续费
     *
     * @param chain
     * @param type
     * @param txData
     * @param remark
     * @param signAccountDTO
     * @return
     * @throws NulsException
     */
    private Transaction assembleUnsignTxWithFee(Chain chain, int type, byte[] txData, String remark, SignAccountDTO signAccountDTO) throws NulsException {
        Transaction tx = assembleUnsignTxWithoutCoinData(type, txData, remark);
        tx.setCoinData(assembleFeeCoinData(chain, signAccountDTO));
        return tx;
    }

    /**
     * 组装手续费（CoinData）
     *
     * @param chain
     * @param signAccountDTO
     * @return
     * @throws NulsException
     */
    private byte[] assembleFeeCoinData(Chain chain, SignAccountDTO signAccountDTO) throws NulsException {
        return assembleFeeCoinData(chain, signAccountDTO, null);
    }

    /**
     * 组装手续费（CoinData）
     *
     * @param chain
     * @param signAccountDTO
     * @param extraFee       向公共手续费收集地址 支付额外的业务费用(例如提案费用等), 用于后续费用的补偿
     * @return
     * @throws NulsException
     */
    private byte[] assembleFeeCoinData(Chain chain, SignAccountDTO signAccountDTO, BigInteger extraFee) throws NulsException {
        String address = signAccountDTO.getAddress();
        //转账交易转出地址必须是本链地址
        if (!AddressTool.validAddress(chain.getChainId(), address)) {
            throw new NulsException(ConverterErrorCode.IS_NOT_CURRENT_CHAIN_ADDRESS);
        }

        int assetChainId = chain.getConfig().getChainId();
        int assetId = chain.getConfig().getAssetId();
        NonceBalance nonceBalance = LedgerCall.getBalanceNonce(
                chain,
                assetChainId,
                assetId,
                address);
        BigInteger balance = nonceBalance.getAvailable();
        BigInteger amount = TransactionFeeCalculator.NORMAL_PRICE_PRE_1024_BYTES;
        if (null != extraFee && extraFee.compareTo(BigInteger.ZERO) > 0) {
            amount = amount.add(extraFee);
        }
        if (BigIntegerUtils.isLessThan(balance, amount)) {
            chain.getLogger().error("The balance is insufficient to cover the package fee");
            throw new NulsException(ConverterErrorCode.INSUFFICIENT_BALANCE);
        }
        //查询账本获取nonce值
        byte[] nonce = nonceBalance.getNonce();
        CoinFrom coinFrom = new CoinFrom(
                AddressTool.getAddress(address),
                assetChainId,
                assetId,
                amount,
                nonce,
                NORMAL_TX_LOCKED);
        CoinData coinData = new CoinData();
        List<CoinFrom> froms = new ArrayList<>();
        froms.add(coinFrom);

        List<CoinTo> tos = new ArrayList<>();
        if (null != extraFee && extraFee.compareTo(BigInteger.ZERO) > 0) {
            // 额外费用 如果有
            if (BigIntegerUtils.isLessThan(balance, TransactionFeeCalculator.NORMAL_PRICE_PRE_1024_BYTES.add(extraFee))) {
                chain.getLogger().error("The balance is insufficient to cover the package fee and the extra fee");
                throw new NulsException(ConverterErrorCode.INSUFFICIENT_BALANCE);
            }
            CoinTo extraFeeCoinTo = new CoinTo(
                    AddressTool.getAddress(ConverterContext.FEE_PUBKEY, chain.getChainId()),
                    assetChainId,
                    assetId,
                    extraFee);
            tos.add(extraFeeCoinTo);
        } else {
            // 如果没有额外费用组装到cointo ,则需要组装一个金额为0的coinTo, coinTo金额为0, from的金额成为手续费
            CoinTo coinTo = new CoinTo(
                    AddressTool.getAddress(address),
                    assetChainId,
                    assetId,
                    BigInteger.ZERO);
            tos.add(coinTo);
        }

        coinData.setFrom(froms);
        coinData.setTo(tos);
        try {
            return coinData.serialize();
        } catch (IOException e) {
            throw new NulsException(ConverterErrorCode.SERIALIZE_ERROR);
        }
    }


}
