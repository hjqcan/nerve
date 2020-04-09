/*
 * *
 *  * MIT License
 *  *
 *  * Copyright (c) 2017-2019 nuls.io
 *  *
 *  * Permission is hereby granted, free of charge, to any person obtaining a copy
 *  * of this software and associated documentation files (the "Software"), to deal
 *  * in the Software without restriction, including without limitation the rights
 *  * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  * copies of the Software, and to permit persons to whom the Software is
 *  * furnished to do so, subject to the following conditions:
 *  *
 *  * The above copyright notice and this permission notice shall be included in all
 *  * copies or substantial portions of the Software.
 *  *
 *  * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  * SOFTWARE.
 *
 */
package nerve.network.pocbft.model.bo.round;
import io.nuls.core.rpc.model.ApiModel;
import io.nuls.core.rpc.model.ApiModelProperty;
import nerve.network.pocbft.constant.ConsensusConstant;
import nerve.network.pocbft.model.bo.tx.txdata.Agent;
import io.nuls.core.crypto.Sha256Hash;
import io.nuls.core.model.ByteUtils;
import io.nuls.core.parse.SerializeUtils;
/**
 * 轮次成员信息类
 * Round Membership Information Class
 *
 * @author: Jason
 * 2018/11/12
 */
@ApiModel(name = "轮次成员信息")
public class MeetingMember implements Comparable<MeetingMember> {
    /**
    * 轮次下标
    * Subscript in order
    * */
    @ApiModelProperty(description = "轮次下标")
    private long roundIndex;
    /**
    * 轮次开始打包时间
    * Round start packing time
    * */
    @ApiModelProperty(description = "轮次开始时间")
    private long roundStartTime;
    /**
    * 节点在轮次中的下标（第几个出块）
    * Subscription of Nodes in Rounds (Number of Blocks)
    * */
    @ApiModelProperty(description = "该节点在本轮次中第几个出块")
    private int packingIndexOfRound;
    /**
    * 共识节点对象
    * Consensus node object
    * */
    @ApiModelProperty(description = "共识节点信息")
    private Agent agent;
    /**
    * 排序值
    * Ranking value
    * */
    @ApiModelProperty(description = "排序值")
    private String sortValue;

    /**
     * 计算节点打包排序值
     * Computing Packing Sort Value of Nodes
     * */
    public String getSortValue() {
        if (this.sortValue == null) {
            byte[] hash;
            if(roundIndex == ConsensusConstant.INIT_ROUND_INDEX){
                hash = agent.getPackingAddress();
            }else{
                hash = ByteUtils.concatenate(agent.getPackingAddress(), SerializeUtils.uint64ToByteArray(roundStartTime));
            }
            sortValue = Sha256Hash.twiceOf(hash).toString();
        }
        return sortValue;
    }

    public int getPackingIndexOfRound() {
        return packingIndexOfRound;
    }

    public void setPackingIndexOfRound(int packingIndexOfRound) {
        this.packingIndexOfRound = packingIndexOfRound;
    }

    @Override
    public int compareTo(MeetingMember o2) {
        return this.getSortValue().compareTo(o2.getSortValue());
    }

    public Agent getAgent() {
        return agent;
    }

    public void setAgent(Agent agent) {
        this.agent = agent;
    }


    public void setSortValue(String sortValue) {
        this.sortValue = sortValue;
    }

    public long getRoundStartTime() {
        return roundStartTime;
    }

    public void setRoundStartTime(long roundStartTime) {
        this.roundStartTime = roundStartTime;
    }

    public long getRoundIndex() {
        return roundIndex;
    }

    public void setRoundIndex(long roundIndex) {
        this.roundIndex = roundIndex;
    }
}
