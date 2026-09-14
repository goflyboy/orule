package com.orule.rule.execution;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RFC-0040 搂14.2 + TASK-1.1.2 楠岃瘉锛?
 * Flyway H2 鍏煎鑴氭湰 V1 蹇呴』鑳芥垚鍔熷缓琛紝涓?搂3.3 鍏ㄩ儴鍒椾笌绱㈠紩鐢熸晥銆?
 *
 * 娴嬭瘯鐩殑锛?
 *  1. 涓嶈 schema 婕傜Щ鍙樻垚"鍚姩 OK 浣嗚〃涓嶅瓨鍦?鐨勯殣鎬уけ璐ワ紱
 *  2. 鐢ㄤ緥涓€鏃﹀彉缁匡紝鎰忓懗鐫€鍚庣画 EF/Repository 鍦?H2 鐜涓嬪彲鏀惧績缂栧啓銆?
 */
@SpringBootTest
class FlywayV1MigrationTest {

    @Autowired
    Flyway flyway;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void v1ExecutionLogTableAndIndexesExist() {
        // 1. 瑙﹀彂 Flyway 宸?apply V1锛圚2 dev profile锛?
        java.util.List<Integer> versions = new java.util.ArrayList<>();
        for (org.flywaydb.core.api.MigrationInfo mi : flyway.info().applied()) {
            versions.add(Integer.parseInt(mi.getVersion().getVersion()));
        }
        assertThat(versions).containsExactly(1);

        // 2. 琛ㄥ瓨鍦?
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = ?",
                Long.class, "execution_log");
        assertThat(count).isEqualTo(1L);

        // 3. 搂3.3 蹇呭鍒楅綈鍏紙鍏抽敭鍒楋級
        Long colCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = ? AND column_name IN (?,?,?,?,?,?,?,?)",
                Long.class,
                "execution_log",
                "task_id", "execution_type", "rule_code", "rule_set_code",
                "executor_type", "status", "input_context", "output_context");
        assertThat(colCount).isEqualTo(8L);

        // 4. 搂14.2 蹇呭绱㈠紩榻愬叏
        Long idxCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.indexes WHERE table_name = ? AND index_name LIKE 'idx_exec_%'",
                Long.class, "execution_log");
        assertThat(idxCount).isEqualTo(6L);

        // 5. CHECK 绾︽潫鐢熸晥锛氶潪娉?status 鎷掔粷锛圚2 閿欒娑堟伅涓?"Check constraint violation"锛?
        assertThat(org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                jdbc.update("INSERT INTO execution_log (task_id, execution_type, executor_type, status) VALUES (?,?,?,?)",
                        "x", "RULE", "JavaSourceExecutor", "INVALID_STATUS"))
                .hasMessageContaining("Check constraint"));
    }
}
