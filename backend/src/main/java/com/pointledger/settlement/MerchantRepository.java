package com.pointledger.settlement;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MerchantRepository extends JpaRepository<Merchant, Long> {

    boolean existsByIdAndStatus(Long id, Merchant.Status status);

    boolean existsByName(String name);
}
