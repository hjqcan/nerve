/**
 * MIT License
 * <p>
 Copyright (c) 2019-2020 nerve.network
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

package nerve.network.converter.heterogeneouschain.eth.storage.impl;

import nerve.network.converter.constant.ConverterErrorCode;
import nerve.network.converter.heterogeneouschain.eth.constant.EthConstant;
import nerve.network.converter.heterogeneouschain.eth.constant.EthDBConstant;
import nerve.network.converter.heterogeneouschain.eth.model.EthERC20Po;
import nerve.network.converter.heterogeneouschain.eth.storage.EthERC20StorageService;
import nerve.network.converter.model.po.StringSetPo;
import nerve.network.converter.utils.ConverterDBUtil;
import io.nuls.core.core.annotation.Component;
import io.nuls.core.exception.NulsException;
import io.nuls.core.model.ByteUtils;
import io.nuls.core.model.StringUtils;
import io.nuls.core.rockdb.service.RocksDBService;

import java.util.*;

import static nerve.network.converter.heterogeneouschain.eth.context.EthContext.logger;
import static nerve.network.converter.utils.ConverterDBUtil.bytesToString;
import static nerve.network.converter.utils.ConverterDBUtil.stringToBytes;

/**
 * @author: Chino
 * @date: 2020-02-20
 */
@Component
public class EthERC20StorageServiceImpl implements EthERC20StorageService {

    private final String baseArea = EthDBConstant.DB_ETH;
    private final String KEY_PREFIX = "ERC20-";
    private final String KEY_ASSETID_PREFIX = "ERC20_ASSETID-";
    private final String KEY_SYMBOL_PREFIX = "ERC20_SYMBOL-";
    private final byte[] MAX_INITIALIZED_ASSETID_KEY = stringToBytes("ERC20-MAX_INITIALIZED_ASSETID");
    private final byte[] MAX_ASSETID_KEY = stringToBytes("ERC20-MAX_ASSETID");
    private final byte[] HAD_INIT_DB_KEY = stringToBytes("ERC20-HAD_INIT_DB");

    @Override
    public int save(EthERC20Po po) throws Exception {
        if (po == null) {
            return 0;
        }
        if (isExistsByAssetId(po.getAssetId())) {
            logger().error("资产ID已存在[{}].", po.getAssetId());
            throw new NulsException(ConverterErrorCode.ASSET_ID_EXIST);
        }
        Map<byte[], byte[]> values = new HashMap<>(8);
        String address = po.getAddress();
        byte[] addressBytes = stringToBytes(address);
        values.put(stringToBytes(KEY_PREFIX + address), ConverterDBUtil.getModelSerialize(po));
        values.put(stringToBytes(KEY_ASSETID_PREFIX + po.getAssetId()), addressBytes);
        StringSetPo setPo = ConverterDBUtil.getModel(baseArea, stringToBytes(KEY_SYMBOL_PREFIX + po.getSymbol()), StringSetPo.class);
        if (setPo == null) {
            setPo = new StringSetPo();
            Set<String> set = new HashSet<>();
            set.add(address);
            setPo.setCollection(set);
            values.put(stringToBytes(KEY_SYMBOL_PREFIX + po.getSymbol()), ConverterDBUtil.getModelSerialize(setPo));
        } else {
            Set<String> set = setPo.getCollection();
            if (set.add(address)) {
                values.put(stringToBytes(KEY_SYMBOL_PREFIX + po.getSymbol()), ConverterDBUtil.getModelSerialize(setPo));
            }
        }
        RocksDBService.batchPut(baseArea, values);
        //ConverterDBUtil.putModel(baseArea, stringToBytes(KEY_PREFIX + po.getAddress()), po);
        //RocksDBService.put(baseArea, stringToBytes(KEY_ASSETID_PREFIX + po.getAssetId()), stringToBytes(po.getAddress()));
        //RocksDBService.put(baseArea, stringToBytes(KEY_SYMBOL_PREFIX + po.getSymbol()), stringToBytes(po.getAddress()));
        return 1;
    }

    @Override
    public EthERC20Po findByAddress(String address) {
        EthERC20Po po = ConverterDBUtil.getModel(baseArea, stringToBytes(KEY_PREFIX + address), EthERC20Po.class);
        if (po == null) {
            return null;
        }
        po.setAddress(address);
        return po;
    }

    @Override
    public void deleteByAddress(String address) throws Exception {
        EthERC20Po po = this.findByAddress(address);
        if (po == null) {
            return;
        }
        this.deleteAddressByAssetId(po.getAssetId());
        this.deleteAddressBySymbol(po.getSymbol());
        RocksDBService.delete(baseArea, stringToBytes(KEY_PREFIX + address));
    }

    @Override
    public boolean isExistsByAddress(String address) {
        byte[] bytes = RocksDBService.get(baseArea, stringToBytes(KEY_PREFIX + address));
        if (bytes == null) {
            return false;
        }
        return true;
    }

    @Override
    public EthERC20Po findByAssetId(int assetId) {
        String address = this.findAddressByAssetId(assetId);
        if (StringUtils.isBlank(address)) {
            return null;
        }
        return this.findByAddress(address);
    }

    @Override
    public void deleteByAssetId(int assetId) throws Exception {
        String address = this.findAddressByAssetId(assetId);
        if (StringUtils.isBlank(address)) {
            return;
        }
        this.deleteByAddress(address);
    }

    @Override
    public String findAddressByAssetId(int assetId) {
        byte[] bytes = RocksDBService.get(baseArea, stringToBytes(KEY_ASSETID_PREFIX + assetId));
        if (bytes == null) {
            return null;
        }
        return bytesToString(bytes);
    }

    private void deleteAddressByAssetId(int assetId) throws Exception {
        RocksDBService.delete(baseArea, stringToBytes(KEY_ASSETID_PREFIX + assetId));
    }

    @Override
    public boolean isExistsByAssetId(int assetId) {
        byte[] bytes = RocksDBService.get(baseArea, stringToBytes(KEY_ASSETID_PREFIX + assetId));
        if (bytes == null) {
            return false;
        }
        return true;
    }

    @Override
    public List<EthERC20Po> findBySymbol(String symbol) {
        Set<String> addressSet = this.findAddressBySymbol(symbol);
        if (addressSet == null || addressSet.isEmpty()) {
            return null;
        }
        List<EthERC20Po> erc20PoList = new ArrayList<>(addressSet.size());
        for (String address : addressSet) {
            erc20PoList.add(this.findByAddress(address));
        }
        return erc20PoList;
    }

    private void deleteAddressBySymbol(String symbol) throws Exception {
        RocksDBService.delete(baseArea, stringToBytes(KEY_SYMBOL_PREFIX + symbol));
    }

    @Override
    public Set<String> findAddressBySymbol(String symbol) {
        StringSetPo setPo = ConverterDBUtil.getModel(baseArea, stringToBytes(KEY_SYMBOL_PREFIX + symbol), StringSetPo.class);
        if (setPo == null) {
            return null;
        }
        return setPo.getCollection();
    }

    @Override
    public boolean isExistsBySymbol(String symbol) {
        byte[] bytes = RocksDBService.get(baseArea, stringToBytes(KEY_SYMBOL_PREFIX + symbol));
        if (bytes == null) {
            return false;
        }
        return true;
    }

    @Override
    public boolean hadInitDB() {
        byte[] bytes = RocksDBService.get(baseArea, HAD_INIT_DB_KEY);
        if (bytes == null) {
            return false;
        }
        return true;
    }

    @Override
    public void initDBCompleted(int maxAssetId) throws Exception {
        RocksDBService.put(baseArea, HAD_INIT_DB_KEY, EthConstant.EMPTY_BYTE);
        RocksDBService.put(baseArea, MAX_INITIALIZED_ASSETID_KEY, ByteUtils.intToBytes(maxAssetId));
    }

    @Override
    public void saveMaxAssetId(int maxAssetId) throws Exception {
        RocksDBService.put(baseArea, MAX_ASSETID_KEY, ByteUtils.intToBytes(maxAssetId));
    }

    @Override
    public int getMaxAssetId() {
        byte[] bytes = RocksDBService.get(baseArea, MAX_ASSETID_KEY);
        return ByteUtils.bytesToInt(bytes);
    }

    @Override
    public int getMaxInitializedAssetId() {
        byte[] bytes = RocksDBService.get(baseArea, MAX_INITIALIZED_ASSETID_KEY);
        return ByteUtils.bytesToInt(bytes);
    }

    @Override
    public List<EthERC20Po> getAllInitializedERC20() {
        int maxInitializedAssetId = this.getMaxInitializedAssetId();
        if (maxInitializedAssetId < 2) {
            return Collections.emptyList();
        }
        List<EthERC20Po> list = new ArrayList<>(maxInitializedAssetId - 1);
        for (int i = 2; i <= maxInitializedAssetId; i++) {
            list.add(this.findByAssetId(i));
        }
        return list;
    }

    @Override
    public void increaseAssetId() throws Exception {
        int maxAssetId = this.getMaxAssetId();
        maxAssetId++;
        this.saveMaxAssetId(maxAssetId);
    }
}
