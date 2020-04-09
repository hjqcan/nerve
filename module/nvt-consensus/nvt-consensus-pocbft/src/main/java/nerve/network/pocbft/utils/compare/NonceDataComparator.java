package nerve.network.pocbft.utils.compare;
import nerve.network.pocbft.model.po.nonce.NonceDataPo;

import java.util.Comparator;

public class NonceDataComparator implements Comparator<NonceDataPo> {
    @Override
    public int compare(NonceDataPo o1, NonceDataPo o2) {
        return o1.getDeposit().compareTo(o2.getDeposit());
    }
}
