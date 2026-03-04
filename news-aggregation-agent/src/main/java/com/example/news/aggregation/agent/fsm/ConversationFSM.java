package com.example.news.aggregation.agent.fsm;

import com.example.news.aggregation.agent.enums.ConversationState;
import com.example.news.aggregation.agent.enums.TaskFamily;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 对话状态机。
 * 提供状态迁移与校验逻辑。
 */
@Slf4j
@Component
public class ConversationFSM {

    /** 允许的状态迁移规则 */
    private static final Map<ConversationState, List<ConversationState>> ALLOWED_TRANSITIONS =
            Map.ofEntries(
                    Map.entry(ConversationState.START, List.of(ConversationState.ROUTE, ConversationState.FAIL_SAFE)),
                    Map.entry(ConversationState.ROUTE, List.of(ConversationState.NEED_CLARIFY, ConversationState.PLAN,
                            ConversationState.RETRIEVE, ConversationState.BUILD_EVIDENCE,
                            ConversationState.WAITING, ConversationState.FAIL_SAFE)),
                    Map.entry(ConversationState.NEED_CLARIFY, List.of(ConversationState.WAITING, ConversationState.DONE,
                            ConversationState.FAIL_SAFE)),
                    Map.entry(ConversationState.WAITING, List.of(ConversationState.ROUTE, ConversationState.PLAN,
                            ConversationState.RETRIEVE, ConversationState.DONE, ConversationState.FAIL_SAFE)),
                    Map.entry(ConversationState.PLAN, List.of(ConversationState.RETRIEVE, ConversationState.FAIL_SAFE)),
                    Map.entry(ConversationState.RETRIEVE, List.of(ConversationState.RERANK, ConversationState.BUILD_EVIDENCE,
                            ConversationState.EVIDENCE_INSUFFICIENT, ConversationState.FAIL_SAFE)),
                    Map.entry(ConversationState.RERANK, List.of(ConversationState.CANONICALIZE, ConversationState.BUILD_EVIDENCE,
                            ConversationState.FAIL_SAFE)),
                    Map.entry(ConversationState.CANONICALIZE, List.of(ConversationState.BUILD_EVIDENCE, ConversationState.FAIL_SAFE)),
                    Map.entry(ConversationState.BUILD_EVIDENCE, List.of(ConversationState.GENERATE,
                            ConversationState.EVIDENCE_INSUFFICIENT, ConversationState.FAIL_SAFE)),
                    Map.entry(ConversationState.EVIDENCE_INSUFFICIENT, List.of(ConversationState.RETRIEVE,
                            ConversationState.DONE, ConversationState.FAIL_SAFE)),
                    Map.entry(ConversationState.GENERATE, List.of(ConversationState.VALIDATE, ConversationState.FAIL_SAFE)),
                    Map.entry(ConversationState.VALIDATE, List.of(ConversationState.DONE, ConversationState.CONFIRM,
                            ConversationState.DISPATCH_ASYNC, ConversationState.FAIL_SAFE)),
                    Map.entry(ConversationState.CONFIRM, List.of(ConversationState.DISPATCH_ASYNC, ConversationState.FAIL_SAFE)),
                    Map.entry(ConversationState.DISPATCH_ASYNC, List.of(ConversationState.RUNNING_ASYNC,
                            ConversationState.DONE, ConversationState.FAIL_SAFE)),
                    Map.entry(ConversationState.RUNNING_ASYNC, List.of(ConversationState.DONE, ConversationState.FAIL_SAFE)),
                    Map.entry(ConversationState.FAIL_SAFE, List.of(ConversationState.DONE)),
                    Map.entry(ConversationState.DONE, List.of(ConversationState.ROUTE, ConversationState.DONE))
            );

    /**
     * 获取下一状态。
     */
    public ConversationState nextState(ConversationState current, FSMContext context) {
        if (current == null) {
            return ConversationState.START;
        }

        return switch (current) {
            case START -> ConversationState.ROUTE;
            case ROUTE -> routeNext(context);
            case NEED_CLARIFY -> ConversationState.WAITING;
            case WAITING -> waitingNext(context);
            case PLAN -> context.getExecutionPlan() != null ? ConversationState.RETRIEVE : ConversationState.FAIL_SAFE;
            case RETRIEVE -> ConversationState.RERANK;
            case RERANK -> ConversationState.CANONICALIZE;
            case CANONICALIZE -> ConversationState.BUILD_EVIDENCE;
            case BUILD_EVIDENCE -> buildEvidenceNext(context);
            case EVIDENCE_INSUFFICIENT -> ConversationState.RETRIEVE;
            case GENERATE -> ConversationState.VALIDATE;
            case VALIDATE -> validateNext(context);
            case CONFIRM -> ConversationState.DISPATCH_ASYNC;
            case DISPATCH_ASYNC -> ConversationState.RUNNING_ASYNC;
            case RUNNING_ASYNC -> ConversationState.DONE;
            case FAIL_SAFE -> ConversationState.DONE;
            case DONE -> ConversationState.DONE;
        };
    }

    /**
     * 校验状态迁移是否合法。
     */
    public void validateTransition(ConversationState from, ConversationState to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateException("Invalid transition: " + from + " -> " + to);
        }
    }

    /**
     * 判断是否允许迁移。
     */
    public boolean canTransition(ConversationState from, ConversationState to) {
        if (from == null || to == null) {
            return false;
        }
        if (to == ConversationState.FAIL_SAFE) {
            return true;
        }
        List<ConversationState> allowed = ALLOWED_TRANSITIONS.get(from);
        if (allowed == null) {
            return false;
        }
        return allowed.contains(to);
    }

    // ======== 状态分支判断 ========

    private ConversationState routeNext(FSMContext context) {
        if (Boolean.TRUE.equals(context.getNeedsClarification())) {
            return ConversationState.NEED_CLARIFY;
        }
        if (Boolean.TRUE.equals(context.getDirectAnswer())) {
            return ConversationState.BUILD_EVIDENCE;
        }
        if (context.getRouterResult() != null) {
            String retrievalMode = context.getRouterResult().getRetrievalMode();
            if ("NONE".equalsIgnoreCase(retrievalMode)) {
                return ConversationState.BUILD_EVIDENCE;
            }
            TaskFamily taskFamily = TaskFamily.valueOf(context.getRouterResult().getTaskFamily());
            if (taskFamily == TaskFamily.COMPARE
                    || taskFamily == TaskFamily.DEEP_DIVE) {
                return ConversationState.PLAN;
            }
        }
        return ConversationState.RETRIEVE;
    }

    private ConversationState buildEvidenceNext(FSMContext context) {
        if (Boolean.TRUE.equals(context.getDirectAnswer())) {
            return ConversationState.GENERATE;
        }
        Integer count = context.getEvidenceCount();
        if (count == null && context.getEvidence() != null) {
            count = context.getEvidence().size();
        }
        if (count == null || count <= 0) {
            return ConversationState.EVIDENCE_INSUFFICIENT;
        }
        return ConversationState.GENERATE;
    }

    private ConversationState validateNext(FSMContext context) {
        if (Boolean.TRUE.equals(context.getRequiresConfirmation())) {
            return ConversationState.CONFIRM;
        }
        if (Boolean.TRUE.equals(context.getDispatchAsync())) {
            return ConversationState.DISPATCH_ASYNC;
        }
        return ConversationState.DONE;
    }

    private ConversationState waitingNext(FSMContext context) {
        if (context == null) {
            return ConversationState.ROUTE;
        }
        if (context.getExecutionPlan() != null) {
            return ConversationState.PLAN;
        }
        if (context.getRouterResult() != null) {
            return ConversationState.RETRIEVE;
        }
        return ConversationState.ROUTE;
    }
}
