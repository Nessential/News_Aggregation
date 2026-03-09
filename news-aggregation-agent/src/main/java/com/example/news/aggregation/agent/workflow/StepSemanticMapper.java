package com.example.news.aggregation.agent.workflow;

import com.example.news.aggregation.llm.springai.contract.ExecutionEnums;
import com.example.news.aggregation.llm.springai.contract.ExecutionStep;
import org.springframework.stereotype.Component;

/**
 * 鎵ц姝ラ璇箟鏄犲皠鍣ㄣ€? */
@Component
public class StepSemanticMapper {

    /**
     * 瑙ｆ瀽姝ラ鍓綔鐢ㄨ涔夈€?     * 褰?Planner 鏈樉寮忔彁渚涘壇浣滅敤鏃讹紝缁熶竴鎸?NONE 澶勭悊锛岄伩鍏嶆墽琛屽眰鍑虹幇绌哄€煎垎鏀€?     */
    public String resolveSideEffect(ExecutionStep step) {
        if (step == null || step.getSideEffect() == null) {
            return ExecutionEnums.SideEffectType.NONE.name();
        }
        return step.getSideEffect().name();
    }

    /**
     * 瑙ｆ瀽瀹屾垚鍒ゅ畾瑙勫垯寮曠敤銆?     * 浼樺厛浣跨敤 doneCheck.expression锛涙湭鎻愪緵鏃跺洖閫€鍒?required_fields銆?     */
    public String resolveDoneCheckRef(ExecutionStep step) {
        if (step == null || step.getDoneCheck() == null) {
            return "";
        }
        if (step.getDoneCheck().getExpression() != null && !step.getDoneCheck().getExpression().isBlank()) {
            return step.getDoneCheck().getExpression();
        }
        return "required_fields";
    }
}

