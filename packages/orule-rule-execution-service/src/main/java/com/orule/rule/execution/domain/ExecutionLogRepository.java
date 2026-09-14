package com.orule.rule.execution.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 鎵ц鏃ュ織 Repository锛圧FC-0040 搂3.6 / 搂21 TASK-1.1.3锛夈€?
 *
 * <p>缁ф壙 {@link JpaRepository} 鍗冲彲鑾峰緱 CRUD / 鍒嗛〉 / 鎺掑簭绛夐€氱敤鑳藉姏銆?
 * TASK-1.1.3 楠屾敹锛歿@code findByTaskId()} 蹇呴』鍙敤锛屾晠姝ゅ鏄惧紡澹版槑銆?
 *
 * <p>鍏朵綑绱㈠紩瀵瑰簲鐨勬煡璇㈡柟娉曪紙{@code findByRuleCode} / {@code findByTenantIdAndCreatedAtDesc} 绛夛級
 * 鍦ㄥ悗缁?Sprint 寮曞叆 Service 鏃舵寜闇€琛ュ厖锛岄伩鍏嶆湰鎺ュ彛杩囧害鑶ㄨ儉銆?
 */
@Repository
public interface ExecutionLogRepository extends JpaRepository<ExecutionLog, Long> {

    /**
     * 鎸?taskId 绮剧‘鏌ヨ锛埪?.6 ExecutionLogService.findByTaskId 渚濊禆锛夈€?
     * 鏁版嵁搴撲晶鐢?{@code uk_exec_task} UNIQUE 绾︽潫淇濊瘉 taskId 鍏ㄥ眬鍞竴銆?
     */
    Optional<ExecutionLog> findByTaskId(String taskId);

    /**
     * 鎸?ruleCode 鍊掑簭鏌ヨ鏈€杩?N 鏉★紙搂14.2 idx_exec_rule_code + 搂15 L03 濂戠害锛夈€?
     */
    @Query("SELECT e FROM ExecutionLog e WHERE e.ruleCode = :ruleCode ORDER BY e.createdAt DESC")
    List<ExecutionLog> findRecentByRuleCode(@Param("ruleCode") String ruleCode,
                                            org.springframework.data.domain.Pageable pageable);
}
