package com.example.news.aggregation.core.fsm;

import com.example.news.aggregation.core.domain.FSMContext;
import com.example.news.aggregation.core.domain.FSMState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 会话状态机 - 手动实现版
 * <p>
 * 实现15状态之间的转换逻辑，对齐agent架构终版.md
 *
 * @author agent
 */
@Slf4j
@Component
public class ConversationFSM {

    /**
     * 定义允许的状态转换 (白名单)
     */
    private static final Map<FSMState, Set<FSMState>> ALLOWED_TRANSITIONS = Map.ofEntries(
            Map.entry(FSMState.START, EnumSet.of(FSMState.ROUTE)),
            Map.entry(FSMState.ROUTE, EnumSet.of(
                    FSMState.NEED_CLARIFY,
                    FSMState.PLAN,
                    FSMState.RETRIEVE,
                    FSMState.FAIL_SAFE
            )),
            Map.entry(FSMState.NEED_CLARIFY, EnumSet.of(
                    FSMState.ROUTE,
                    FSMState.FAIL_SAFE
            )),
            Map.entry(FSMState.PLAN, EnumSet.of(
                    FSMState.RETRIEVE,
                    FSMState.FAIL_SAFE
            )),
            Map.entry(FSMState.RETRIEVE, EnumSet.of(
                    FSMState.RERANK,
                    FSMState.CANONICALIZE,
                    FSMState.BUILD_EVIDENCE,
                    FSMState.EVIDENCE_INSUFFICIENT,
                    FSMState.FAIL_SAFE
            )),
            Map.entry(FSMState.RERANK, EnumSet.of(
                    FSMState.CANONICALIZE,
                    FSMState.BUILD_EVIDENCE,
                    FSMState.FAIL_SAFE
            )),
            Map.entry(FSMState.CANONICALIZE, EnumSet.of(
                    FSMState.BUILD_EVIDENCE,
                    FSMState.FAIL_SAFE
            )),
            Map.entry(FSMState.BUILD_EVIDENCE, EnumSet.of(
                    FSMState.GENERATE,
                    FSMState.EVIDENCE_INSUFFICIENT,
                    FSMState.FAIL_SAFE
            )),
            Map.entry(FSMState.EVIDENCE_INSUFFICIENT, EnumSet.of(
                    FSMState.RETRIEVE,
                    FSMState.DONE,
                    FSMState.FAIL_SAFE
            )),
            Map.entry(FSMState.GENERATE, EnumSet.of(
                    FSMState.VALIDATE,
                    FSMState.FAIL_SAFE
            )),
            Map.entry(FSMState.VALIDATE, EnumSet.of(
                    FSMState.GENERATE,  // 重新生成（最多1次）
                    FSMState.CONFIRM,
                    FSMState.DONE,
                    FSMState.FAIL_SAFE
            )),
            Map.entry(FSMState.CONFIRM, EnumSet.of(
                    FSMState.DISPATCH_ASYNC,
                    FSMState.DONE,
                    FSMState.FAIL_SAFE
            )),
            Map.entry(FSMState.DISPATCH_ASYNC, EnumSet.of(
                    FSMState.RUNNING_ASYNC,
                    FSMState.FAIL_SAFE
            )),
            Map.entry(FSMState.RUNNING_ASYNC, EnumSet.of(
                    FSMState.DONE,
                    FSMState.FAIL_SAFE
            )),
            Map.entry(FSMState.DONE, EnumSet.noneOf(FSMState.class)),
            Map.entry(FSMState.FAIL_SAFE, EnumSet.noneOf(FSMState.class))
    );

    /**
     * 计算下一个状态
     *
     * @param current 当前状态
     * @param context FSM上下文
     * @return 下一个状态
     */
    public FSMState nextState(FSMState current, FSMContext context) {
        log.debug("计算下一状态: current={}, context={}", current, context);

        FSMState next = switch (current) {
            case START -> FSMState.ROUTE;

            case ROUTE -> {
                // Router失败 → 失败兜底
                if (context.getRouterResult() == null) {
                    yield FSMState.FAIL_SAFE;
                }
                // 需要追问 → 追问状态
                if (context.needsClarify()) {
                    yield FSMState.NEED_CLARIFY;
                }
                // 复杂任务 → 规划
                if (context.isComplexTask()) {
                    yield FSMState.PLAN;
                }
                // 简单任务 → 直接检索
                yield FSMState.RETRIEVE;
            }

            case NEED_CLARIFY -> {
                // 用户补充信息后，重新路由
                yield FSMState.ROUTE;
            }

            case PLAN -> {
                // Plan失败 → 失败兜底
                if (context.getPlan() == null) {
                    yield FSMState.FAIL_SAFE;
                }
                // Plan完成 → 检索
                yield FSMState.RETRIEVE;
            }

            case RETRIEVE -> {
                // 候选为空 → 证据不足恢复
                if (!context.hasCandidates()) {
                    yield FSMState.EVIDENCE_INSUFFICIENT;
                }
                // 需要Rerank → 重排序
                if (context.needsRerank()) {
                    yield FSMState.RERANK;
                }
                // 需要去重聚合 → Canonicalize
                if (context.needsCanonicalize()) {
                    yield FSMState.CANONICALIZE;
                }
                // 直接构建证据包
                yield FSMState.BUILD_EVIDENCE;
            }

            case RERANK -> {
                // Rerank后检查是否需要Canonicalize
                if (context.needsCanonicalize()) {
                    yield FSMState.CANONICALIZE;
                }
                yield FSMState.BUILD_EVIDENCE;
            }

            case CANONICALIZE -> FSMState.BUILD_EVIDENCE;

            case BUILD_EVIDENCE -> {
                // 证据不达标 → 证据不足恢复
                if (!context.hasEvidenceMet()) {
                    yield FSMState.EVIDENCE_INSUFFICIENT;
                }
                // 证据达标 → 生成
                yield FSMState.GENERATE;
            }

            case EVIDENCE_INSUFFICIENT -> {
                // 还能重试 → 回到检索（扩大参数）
                if (context.canRetryEvidence()) {
                    context.incrementEvidenceRetry();
                    yield FSMState.RETRIEVE;
                }
                // 超过3次 → 直接完成（返回保守答复）
                yield FSMState.DONE;
            }

            case GENERATE -> {
                // 生成失败 → 失败兜底
                if (context.getDraft() == null) {
                    yield FSMState.FAIL_SAFE;
                }
                // 生成成功 → 校验
                yield FSMState.VALIDATE;
            }

            case VALIDATE -> {
                // 校验不通过 且 重试次数<1 → 重新生成
                if (context.getRetryCount() < 1) {
                    context.incrementRetry();
                    yield FSMState.GENERATE;
                }
                // 有写操作 → 确认
                if (context.hasWriteOperation()) {
                    yield FSMState.CONFIRM;
                }
                // 无写操作或校验通过 → 完成
                yield FSMState.DONE;
            }

            case CONFIRM -> {
                // 用户拒绝或超时 → 完成
                if (Boolean.FALSE.equals(context.getUserConfirmed())) {
                    yield FSMState.DONE;
                }
                // 用户同意 且 需要异步任务 → 分发异步
                if (context.isRequiresConfirmation()) {
                    yield FSMState.DISPATCH_ASYNC;
                }
                // 同步写操作 → 完成
                yield FSMState.DONE;
            }

            case DISPATCH_ASYNC -> FSMState.RUNNING_ASYNC;

            case RUNNING_ASYNC -> {
                // 异步任务完成 → 完成
                yield FSMState.DONE;
            }

            case DONE, FAIL_SAFE -> {
                // 终态，不再转换
                log.warn("尝试从终态 {} 转换，保持当前状态", current);
                yield current;
            }
        };

        // 验证转换是否合法
        validateTransition(current, next);

        log.info("状态转换: {} -> {}", current, next);
        return next;
    }

    /**
     * 检查转换是否允许
     *
     * @param from 起始状态
     * @param to   目标状态
     * @return 是否允许
     */
    public boolean canTransition(FSMState from, FSMState to) {
        Set<FSMState> allowedStates = ALLOWED_TRANSITIONS.get(from);
        if (allowedStates == null) {
            return false;
        }
        return allowedStates.contains(to);
    }

    /**
     * 验证转换合法性
     *
     * @param from 起始状态
     * @param to   目标状态
     * @throws IllegalStateException 如果转换不合法
     */
    public void validateTransition(FSMState from, FSMState to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateException(
                    String.format("非法状态转换: %s -> %s", from, to));
        }
    }

    /**
     * 检查是否是终态
     *
     * @param state 状态
     * @return 是否是终态
     */
    public boolean isTerminalState(FSMState state) {
        return state == FSMState.DONE || state == FSMState.FAIL_SAFE;
    }

    /**
     * 获取状态的后继状态列表
     *
     * @param state 状态
     * @return 可转换到的状态集合
     */
    public Set<FSMState> getNextStates(FSMState state) {
        return ALLOWED_TRANSITIONS.getOrDefault(state, EnumSet.noneOf(FSMState.class));
    }

    /**
     * 重置上下文的重试计数器
     *
     * @param context 上下文
     */
    public void resetRetryCounters(FSMContext context) {
        context.setRetryCount(0);
        context.setEvidenceRetryCount(0);
    }
}
