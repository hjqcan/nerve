package io.nuls.base.protocol.cmd;

import io.nuls.base.RPCUtil;
import io.nuls.base.data.BlockHeader;
import io.nuls.base.data.Transaction;
import io.nuls.base.protocol.CommonAdvice;
import io.nuls.base.protocol.ModuleTxPackageProcessor;
import io.nuls.base.protocol.TransactionProcessor;
import io.nuls.core.constant.BaseConstant;
import io.nuls.core.constant.CommonCodeConstanst;
import io.nuls.core.core.annotation.Autowired;
import io.nuls.core.core.annotation.Component;
import io.nuls.core.exception.NulsException;
import io.nuls.core.log.Log;
import io.nuls.core.model.ObjectUtils;
import io.nuls.core.model.StringUtils;
import io.nuls.core.rpc.cmd.BaseCmd;
import io.nuls.core.rpc.info.Constants;
import io.nuls.core.rpc.model.CmdAnnotation;
import io.nuls.core.rpc.model.Parameter;
import io.nuls.core.rpc.model.message.Response;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 交易分发器
 *
 * @author captain
 * @version 1.0
 * @date 2019/5/24 19:02
 */
@Component
public final class TransactionDispatcher extends BaseCmd {

    private List<TransactionProcessor> processors;
    /**
     * 打包交易时,模块统一交易内部生成处理器
     */
    private ModuleTxPackageProcessor moduleTxPackageProcessor;

    public void setModuleTxPackageProcessor(ModuleTxPackageProcessor moduleTxPackageProcessor) {
        this.moduleTxPackageProcessor = moduleTxPackageProcessor;
    }

    /**
     * 各模块打包交易处理器接口
     * k:模块code, v:模块处理器
     */
//    private Map<String, ModuleTxPackageProcessor> mapPackageProcessor = new HashMap<>();
//
//    public void setPackageProcessor(ModuleTxPackageProcessor packageProcessor) {
//        this.mapPackageProcessor.put(packageProcessor.getModuleCode(), packageProcessor);
//    }
//
//    public List<TransactionProcessor> getProcessors() {
//        return processors;
//    }

    public void setProcessors(List<TransactionProcessor> processors) {
        processors.forEach(e -> Log.info("register TransactionProcessor-" + e.toString()));
        processors.sort(TransactionProcessor.COMPARATOR);
        this.processors = processors;
    }

    @Autowired("EmptyCommonAdvice")
    private CommonAdvice commitAdvice;
    @Autowired("EmptyCommonAdvice")
    private CommonAdvice rollbackAdvice;

    public void register(CommonAdvice commitAdvice, CommonAdvice rollbackAdvice) {
        if (commitAdvice != null) {
            this.commitAdvice = commitAdvice;
        }
        if (rollbackAdvice != null) {
            this.rollbackAdvice = rollbackAdvice;
        }
    }

    /**
     * 打包交易处理器
     * @param params
     * @return
     */
    @CmdAnnotation(cmd = BaseConstant.TX_PACKPRODUCE, version = 1.0, description = "")
    @Parameter(parameterName = "chainId", parameterType = "int")
    @Parameter(parameterName = "txList", parameterType = "List")
    public Response txPackProduce(Map params) {
        ObjectUtils.canNotEmpty(params.get(Constants.CHAIN_ID), CommonCodeConstanst.PARAMETER_ERROR.getMsg());
        ObjectUtils.canNotEmpty(params.get("txList"), CommonCodeConstanst.PARAMETER_ERROR.getMsg());
        try {
            int chainId = Integer.parseInt(params.get(Constants.CHAIN_ID).toString());
            List<String> txList = (List<String>) params.get("txList");
            List<Transaction> txs = new ArrayList<>();
            for (String txStr : txList) {
                Transaction tx = RPCUtil.getInstanceRpcStr(txStr, Transaction.class);
                txs.add(tx);
            }
//            ModuleTxPackageProcessor processor = mapPackageProcessor.get(ConnectManager.LOCAL.getAbbreviation());
//            List<String> list = new ArrayList<>();
//            if(null != processor){
//                list = processor.packProduce(chainId, txs);
//            }
            List<String> list = moduleTxPackageProcessor.packProduce(chainId, txs);
            Map<String, Object> resultMap = new HashMap<>(2);
            resultMap.put("list", list);
            return success(resultMap);
        } catch (NulsException e) {
            Log.error(e);
            return failed(e.getErrorCode());
        } catch (Exception e) {
            Log.error(e);
            return failed(CommonCodeConstanst.SYS_UNKOWN_EXCEPTION);
        }

    }


    /**
     * 交易的模块验证器
     * @param params
     * @return
     */
    @CmdAnnotation(cmd = BaseConstant.TX_VALIDATOR, version = 1.0, description = "")
    @Parameter(parameterName = "chainId", parameterType = "int")
    @Parameter(parameterName = "txList", parameterType = "List")
    @Parameter(parameterName = "blockHeader", parameterType = "String")
    public Response txValidator(Map params) {
        ObjectUtils.canNotEmpty(params.get(Constants.CHAIN_ID), CommonCodeConstanst.PARAMETER_ERROR.getMsg());
        ObjectUtils.canNotEmpty(params.get("txList"), CommonCodeConstanst.PARAMETER_ERROR.getMsg());
        int chainId = Integer.parseInt(params.get(Constants.CHAIN_ID).toString());
        String blockHeaderStr = (String) params.get("blockHeader");
        BlockHeader blockHeader = null;
        if (StringUtils.isNotBlank(blockHeaderStr)) {
            blockHeader = RPCUtil.getInstanceRpcStr(blockHeaderStr, BlockHeader.class);
        }
        List<String> txList = (List<String>) params.get("txList");
        List<Transaction> txs = new ArrayList<>();
        List<Transaction> finalInvalidTxs = new ArrayList<>();
        for (String txStr : txList) {
            Transaction tx = RPCUtil.getInstanceRpcStr(txStr, Transaction.class);
            txs.add(tx);
        }
        Map<Integer, List<Transaction>> map = new HashMap<>();
        for (TransactionProcessor processor : processors) {
            for (Transaction tx : txs) {
                List<Transaction> transactions = map.computeIfAbsent(processor.getType(), k -> new ArrayList<>());
                if (tx.getType() == processor.getType()) {
                    transactions.add(tx);
                }
            }
        }
        String errorCode = "";
        for (TransactionProcessor processor : processors) {
            Map<String, Object> validateMap = processor.validate(chainId, map.get(processor.getType()), map, blockHeader);
            if (validateMap == null) {
                continue;
            }
            List<Transaction> invalidTxs = (List<Transaction>) validateMap.get("txList");
            //List<Transaction> invalidTxs = processor.validate(chainId, map.get(processor.getType()), map, blockHeader);
            if (invalidTxs != null && !invalidTxs.isEmpty()) {
                errorCode = (String) validateMap.get("errorCode");
                finalInvalidTxs.addAll(invalidTxs);
                invalidTxs.forEach(e -> map.get(e.getType()).remove(e));
            }
        }
        Map<String, Object> resultMap = new HashMap<>(2);
        List<String> list = finalInvalidTxs.stream().map(e -> e.getHash().toHex()).collect(Collectors.toList());
        resultMap.put("errorCode", errorCode);
        resultMap.put("list", list);
        return success(resultMap);
    }

    /**
     * 交易业务提交
     * @param params
     * @return
     */
    @CmdAnnotation(cmd = BaseConstant.TX_COMMIT, version = 1.0, description = "")
    @Parameter(parameterName = "chainId", parameterType = "int")
    @Parameter(parameterName = "txList", parameterType = "List")
    @Parameter(parameterName = "blockHeader", parameterType = "String")
    @Parameter(parameterName = "syncStatus", parameterType = "int")
    public Response txCommit(Map params) {
        ObjectUtils.canNotEmpty(params.get(Constants.CHAIN_ID), CommonCodeConstanst.PARAMETER_ERROR.getMsg());
        ObjectUtils.canNotEmpty(params.get("txList"), CommonCodeConstanst.PARAMETER_ERROR.getMsg());
        ObjectUtils.canNotEmpty(params.get("blockHeader"), CommonCodeConstanst.PARAMETER_ERROR.getMsg());
        ObjectUtils.canNotEmpty(params.get("syncStatus"), CommonCodeConstanst.PARAMETER_ERROR.getMsg());

        int chainId = Integer.parseInt(params.get(Constants.CHAIN_ID).toString());
        String blockHeaderStr = (String) params.get("blockHeader");
        BlockHeader blockHeader = RPCUtil.getInstanceRpcStr(blockHeaderStr, BlockHeader.class);
        List<String> txList = (List<String>) params.get("txList");
        List<Transaction> txs = new ArrayList<>();
        for (String txStr : txList) {
            Transaction tx = RPCUtil.getInstanceRpcStr(txStr, Transaction.class);
            txs.add(tx);
        }
        int syncStatus = (int) params.get("syncStatus");
        commitAdvice.begin(chainId, txs, blockHeader, syncStatus);
        Map<Integer, List<Transaction>> map = new HashMap<>();
        for (TransactionProcessor processor : processors) {
            for (Transaction tx : txs) {
                List<Transaction> transactions = map.computeIfAbsent(processor.getType(), k -> new ArrayList<>());
                if (tx.getType() == processor.getType()) {
                    transactions.add(tx);
                }
            }
        }

        Map<String, Boolean> resultMap = new HashMap<>(2);
        List<TransactionProcessor> completedProcessors = new ArrayList<>();
        for (TransactionProcessor processor : processors) {
            boolean commit = processor.commit(chainId, map.get(processor.getType()), blockHeader, syncStatus);
            if (!commit) {
                completedProcessors.forEach(e -> e.rollback(chainId, map.get(e.getType()), blockHeader));
                resultMap.put("value", commit);
                return success(resultMap);
            } else {
                completedProcessors.add(processor);
            }
        }
        resultMap.put("value", true);
        commitAdvice.end(chainId, txs, blockHeader);
        return success(resultMap);
    }

    /**
     * 交易业务回滚
     * @param params
     * @return
     */
    @CmdAnnotation(cmd = BaseConstant.TX_ROLLBACK, version = 1.0, description = "")
    @Parameter(parameterName = "chainId", parameterType = "int")
    @Parameter(parameterName = "txList", parameterType = "List")
    @Parameter(parameterName = "blockHeader", parameterType = "String")
    public Response txRollback(Map params) {
        ObjectUtils.canNotEmpty(params.get(Constants.CHAIN_ID), CommonCodeConstanst.PARAMETER_ERROR.getMsg());
        ObjectUtils.canNotEmpty(params.get("txList"), CommonCodeConstanst.PARAMETER_ERROR.getMsg());
        ObjectUtils.canNotEmpty(params.get("blockHeader"), CommonCodeConstanst.PARAMETER_ERROR.getMsg());
        int chainId = Integer.parseInt(params.get(Constants.CHAIN_ID).toString());
        String blockHeaderStr = (String) params.get("blockHeader");
        BlockHeader blockHeader = RPCUtil.getInstanceRpcStr(blockHeaderStr, BlockHeader.class);
        List<String> txList = (List<String>) params.get("txList");
        List<Transaction> txs = new ArrayList<>();
        for (String txStr : txList) {
            Transaction tx = RPCUtil.getInstanceRpcStr(txStr, Transaction.class);
            txs.add(tx);
        }
        rollbackAdvice.begin(chainId, txs, blockHeader, 0);
        Map<Integer, List<Transaction>> map = new HashMap<>();
        for (TransactionProcessor processor : processors) {
            for (Transaction tx : txs) {
                List<Transaction> transactions = map.computeIfAbsent(processor.getType(), k -> new ArrayList<>());
                if (tx.getType() == processor.getType()) {
                    transactions.add(tx);
                }
            }
        }
        Map<String, Boolean> resultMap = new HashMap<>(2);
        List<TransactionProcessor> completedProcessors = new ArrayList<>();
        for (TransactionProcessor processor : processors) {
            boolean rollback = processor.rollback(chainId, map.get(processor.getType()), blockHeader);
            if (!rollback) {
                completedProcessors.forEach(e -> e.commit(chainId, map.get(e.getType()), blockHeader, 0));
                resultMap.put("value", rollback);
                return success(resultMap);
            } else {
                completedProcessors.add(processor);
            }
        }
        resultMap.put("value", true);
        rollbackAdvice.end(chainId, txs, blockHeader);
        return success(resultMap);
    }

}
