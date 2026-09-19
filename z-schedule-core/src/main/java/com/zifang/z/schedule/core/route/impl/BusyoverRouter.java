package com.zifang.z.schedule.core.route.impl;

import com.zifang.z.schedule.core.route.ExecutorRouter;

import java.util.List;

/**
 * 忙碌转移路由策略（Busyover）
 * <p>
 * 按顺序依次检测每个执行器的空闲状态，
 * 返回第一个空闲（无运行中任务）的执行器。
 * <p>
 * 如果所有执行器都忙碌，则降级返回第一个地址。
 * <p>
 * 注意：实际的忙碌检测依赖执行器心跳接口（/executor/beat）上报的运行状态。
 * 当前实现为返回第一个地址，完整实现需配合 JobRegistryService 的空闲状态查询。
 */
public class BusyoverRouter implements ExecutorRouter {

    /**
     * 空闲检测回调，由调用方设置。
     * 返回 true 表示该地址空闲。
     */
    public interface IdleChecker {
        boolean isIdle(String address);
    }

    private static volatile IdleChecker idleChecker;

    /**
     * 设置空闲检测器（由 Spring Bean 初始化时注入）。
     */
    public static void setIdleChecker(IdleChecker checker) {
        BusyoverRouter.idleChecker = checker;
    }

    @Override
    public String route(List<String> addressList, int jobId) {
        if (addressList == null || addressList.isEmpty()) {
            return null;
        }

        IdleChecker checker = idleChecker;
        if (checker != null) {
            // 按顺序检测，返回第一个空闲节点
            for (String address : addressList) {
                try {
                    if (checker.isIdle(address)) {
                        return address;
                    }
                } catch (Exception e) {
                    // 检测异常视为忙碌，跳过
                }
            }
        }

        // 所有节点都忙碌或无检测器时，降级返回第一个
        return addressList.get(0);
    }
}
