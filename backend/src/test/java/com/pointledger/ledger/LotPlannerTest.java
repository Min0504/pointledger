package com.pointledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pointledger.ledger.LotPlanner.PlannedConsumption;
import com.pointledger.ledger.LotPlanner.PlannedRestore;
import com.pointledger.ledger.LotPlanner.RestoreSource;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 로트 차감·복원 규칙의 단위 검증 — DB 없이 순수 도메인만 (기획서 §11 Unit).
 * 트랜잭션 적용(엔티티 저장·원장 기장)과 결합한 검증은 통합 테스트가 담당한다.
 */
class LotPlannerTest {

    private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");

    private PointLot lot(long amount, Instant expiresAt) {
        return new PointLot(1L, 1L, amount, expiresAt);
    }

    private Instant days(int d) {
        return NOW.plus(d, ChronoUnit.DAYS);
    }

    @Nested
    @DisplayName("FIFO 차감")
    class Consumption {

        @Test
        @DisplayName("만료 임박한 로트부터 앞에서부터 소진한다")
        void consumesInFifoOrder() {
            PointLot first = lot(2000, days(10));
            PointLot second = lot(5000, days(30));

            List<PlannedConsumption> plan =
                    LotPlanner.planConsumption(List.of(first, second), 3000);

            assertThat(plan).hasSize(2);
            assertThat(plan.get(0).lot()).isSameAs(first);
            assertThat(plan.get(0).amount()).isEqualTo(2000);
            assertThat(plan.get(1).lot()).isSameAs(second);
            assertThat(plan.get(1).amount()).isEqualTo(1000);
        }

        @Test
        @DisplayName("첫 로트로 정확히 충족되면 다음 로트는 건드리지 않는다")
        void exactBoundaryStopsAtFirstLot() {
            PointLot first = lot(3000, days(10));
            PointLot second = lot(5000, days(30));

            List<PlannedConsumption> plan =
                    LotPlanner.planConsumption(List.of(first, second), 3000);

            assertThat(plan).hasSize(1);
            assertThat(plan.get(0).amount()).isEqualTo(3000);
        }

        @Test
        @DisplayName("이미 소진된 로트(remaining 0)는 건너뛴다")
        void skipsExhaustedLots() {
            PointLot exhausted = lot(1000, days(5));
            exhausted.consume(1000);
            PointLot active = lot(2000, days(10));

            List<PlannedConsumption> plan =
                    LotPlanner.planConsumption(List.of(exhausted, active), 1500);

            assertThat(plan).hasSize(1);
            assertThat(plan.get(0).lot()).isSameAs(active);
        }

        @Test
        @DisplayName("로트 합계 부족은 불변식 위반 — 복구가 아니라 즉시 실패")
        void insufficientLotsIsInvariantViolation() {
            assertThatThrownBy(() -> LotPlanner.planConsumption(List.of(lot(1000, days(10))), 1500))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("불변식");
        }
    }

    @Nested
    @DisplayName("취소 복원")
    class Restore {

        private RestoreSource source(long consumed, PointLot lot) {
            return new RestoreSource(new LotConsumption(1L, 0L, consumed), lot);
        }

        @Test
        @DisplayName("소비의 역순 — 가장 늦게 만료되는 로트부터 되돌린다")
        void restoresInReverseConsumptionOrder() {
            RestoreSource early = source(2000, lot(2000, days(10)));  // 먼저 소비됨
            RestoreSource late = source(1000, lot(5000, days(30)));   // 나중 소비됨

            List<PlannedRestore> plan =
                    LotPlanner.planRestore(List.of(early, late), 1500, NOW);

            assertThat(plan).hasSize(2);
            assertThat(plan.get(0).source()).isSameAs(late);   // 역순: 늦은 소비부터
            assertThat(plan.get(0).amount()).isEqualTo(1000);
            assertThat(plan.get(1).source()).isSameAs(early);
            assertThat(plan.get(1).amount()).isEqualTo(500);
        }

        @Test
        @DisplayName("부분 취소를 거듭하면 이미 복원한 몫(restored)은 제외된다")
        void excludesAlreadyRestoredPortion() {
            LotConsumption consumption = new LotConsumption(1L, 0L, 3000);
            consumption.markRestored(2500); // 이전 부분 취소
            RestoreSource src = new RestoreSource(consumption, lot(3000, days(10)));

            List<PlannedRestore> plan = LotPlanner.planRestore(List.of(src), 500, NOW);

            assertThat(plan).hasSize(1);
            assertThat(plan.get(0).amount()).isEqualTo(500);

            assertThatThrownBy(() -> LotPlanner.planRestore(List.of(src), 501, NOW))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("만료 시점이 지난 로트 몫은 유예 로트로 — 복원 즉시 소멸을 막는다")
        void expiredLotPortionGoesToGraceLot() {
            RestoreSource expired = source(1000, lot(1000, days(-1)));
            RestoreSource alive = source(1000, lot(1000, days(30)));

            List<PlannedRestore> plan =
                    LotPlanner.planRestore(List.of(expired, alive), 2000, NOW);

            assertThat(plan).hasSize(2);
            assertThat(plan.get(0).toGraceLot()).isFalse(); // 살아있는 로트는 제자리 복원
            assertThat(plan.get(1).toGraceLot()).isTrue();  // 만료된 로트는 유예 몫
        }

        @Test
        @DisplayName("만료 판정은 경계 포함 — expires_at == now도 만료다")
        void expiryBoundaryIsInclusive() {
            assertThat(lot(1000, NOW).isExpiredAt(NOW)).isTrue();
            assertThat(lot(1000, NOW.plusNanos(1)).isExpiredAt(NOW)).isFalse();
        }

        @Test
        @DisplayName("복원 가능 합계 부족은 원장과의 불일치 — 즉시 실패")
        void insufficientRestorableIsInvariantViolation() {
            RestoreSource src = source(1000, lot(1000, days(10)));
            assertThatThrownBy(() -> LotPlanner.planRestore(List.of(src), 1500, NOW))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("불일치");
        }
    }
}
