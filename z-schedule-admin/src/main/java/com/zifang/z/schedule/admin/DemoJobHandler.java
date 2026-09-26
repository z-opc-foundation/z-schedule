package com.zifang.z.schedule.admin;

import com.zifang.z.schedule.core.handler.IJobHandler;
import com.zifang.z.schedule.core.model.ReturnT;
import com.zifang.z.schedule.core.param.TriggerParam;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

/**
 * 演示应用自带的 handler：bean 名 {@code demoHandler}，形状是库里唯一的契约
 * {@link IJobHandler#execute(TriggerParam)}。
 *
 * <p>为什么要有这个文件：独立部署的 admin 此前**一个 handler bean 都没有**，
 * 所以在演示应用里建任何任务都只会得到"执行器 bean 未注册"，
 * 整条 派发 → 执行 → 结论落库 的链路在真机上无法被看见一次。
 * 它同时是 {@code IJobHandler} 派发路径的活体样本——那条路径此前只找
 * {@code execute(String)}，接口版永远走不到。
 */
@Component("demoHandler")
public class DemoJobHandler implements IJobHandler {

    private static final Logger logger = LogManager.getLogger(DemoJobHandler.class);

    @Override
    public ReturnT<String> execute(TriggerParam triggerParam) {
        logger.info("[z-schedule] demoHandler 执行 jobId={}, logId={}, param={}",
                triggerParam.getJobId(), triggerParam.getLogId(), triggerParam.getExecutorParams());
        return ReturnT.success();
    }
}
