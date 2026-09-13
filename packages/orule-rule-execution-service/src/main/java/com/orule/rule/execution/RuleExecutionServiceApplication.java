package com.orule.rule.execution;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * orule-rule-execution-service 鎵ц鏈嶅姟鍏ュ彛銆?
 *
 * <p>璐熻矗瑙勫垯瀹為檯鎵ц銆佹壒閲忚皟鐢ㄣ€丷uleSet 璋冨害銆並afka 瀹屾垚浜嬩欢鎶曢€掔瓑銆?
 * 瀵瑰鏆撮湶 RFC-0040 搂16 瀹氫箟鐨?REST API銆?
 *
 * <p>鏈ā鍧楃敱 {@code packages/orule-runtime}锛圧FC-0040 v0.6 涔嬪墠鐗堟湰锛夋敼鍚嶈€屾潵銆?
 *
 * @see <a href="https://github.com/goflyboy/orule/blob/main/docs/rfcs/RFC-0040-%E8%A7%84%E5%88%99%E6%89%A7%E8%A1%8C%E6%9C%8D%E5%8A%A1.md">RFC-0040 瑙勫垯鎵ц鏈嶅姟</a>
 */
@SpringBootApplication(scanBasePackages = "com.orule.rule.execution")
@EnableAsync
public class RuleExecutionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RuleExecutionServiceApplication.class, args);
    }
}
