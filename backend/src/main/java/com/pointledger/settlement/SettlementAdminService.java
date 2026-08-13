package com.pointledger.settlement;

import com.pointledger.common.error.DomainException;
import com.pointledger.common.error.ErrorCode;
import com.pointledger.settlement.dto.SettlementDtos.MerchantView;
import com.pointledger.settlement.dto.SettlementDtos.SettlementView;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SettlementAdminService {

    private final MerchantRepository merchantRepository;
    private final SettlementRepository settlementRepository;

    @Transactional
    public MerchantView createMerchant(String name) {
        if (merchantRepository.existsByName(name)) {
            throw new DomainException(ErrorCode.MERCHANT_ALREADY_EXISTS, Map.of("name", name));
        }
        return MerchantView.from(merchantRepository.save(new Merchant(name)));
    }

    @Transactional(readOnly = true)
    public List<MerchantView> listMerchants() {
        return merchantRepository.findAll().stream().map(MerchantView::from).toList();
    }

    @Transactional(readOnly = true)
    public List<SettlementView> listSettlements(LocalDate settleDate, Long merchantId) {
        List<Settlement> settlements = settlementRepository.search(settleDate, merchantId);
        Map<Long, String> names = merchantRepository.findAllById(
                        settlements.stream().map(Settlement::getMerchantId).distinct().toList())
                .stream().collect(Collectors.toMap(Merchant::getId, Merchant::getName));
        return settlements.stream()
                .map(s -> SettlementView.from(s, names.get(s.getMerchantId())))
                .toList();
    }

    /**
     * 정산 확정 — 이후 재정산(재집계)에서 제외돼 동결된다. 확정 뒤 도착하는
     * 취소는 다음 날 정산서에 음수 라인으로 이월된다 (차감 정산).
     */
    @Transactional
    public SettlementView confirm(Long settlementId, String operator) {
        Settlement settlement = settlementRepository.findById(settlementId)
                .orElseThrow(() -> new DomainException(ErrorCode.SETTLEMENT_NOT_FOUND));
        settlement.confirm(operator);
        String name = merchantRepository.findById(settlement.getMerchantId())
                .map(Merchant::getName).orElse(null);
        return SettlementView.from(settlement, name);
    }
}
