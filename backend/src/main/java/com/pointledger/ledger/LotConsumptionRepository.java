package com.pointledger.ledger;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LotConsumptionRepository extends JpaRepository<LotConsumption, Long> {

    /** 소비 순서(id) 그대로 — 복원은 이 목록의 역순으로 걷는다 (LotPlanner) */
    List<LotConsumption> findByConsumingEntryIdOrderById(Long consumingEntryId);
}
