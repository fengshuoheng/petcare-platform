package com.haust.petcare.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 应用启动后预热未来 14 天时间槽，对应 time_slot 表，属于主链路的「库存准备」环节。
 * <p>
 * 实现 ApplicationRunner：run() 在 Spring 容器就绪后执行一次；每天凌晨的 ScheduledJobs.generateSlots
 * 负责持续补槽，两者都复用 SlotService.generateNextDays 的 INSERT IGNORE + 唯一索引幂等逻辑，
 * 重复执行不会产生重复槽。
 */
@Component
public class SlotInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SlotInitializer.class);

    private final SlotService slotService;

    /**
     * 注入 SlotService；启动器本身无状态，只负责在容器就绪后触发一次批量生成。
     */
    public SlotInitializer(SlotService slotService) {
        this.slotService = slotService;
    }

    /**
     * 容器启动完成后生成未来 14 天时间槽；异常只打 warn 不抛出，
     * 避免数据库未就绪/排班数据缺失时整个应用启动失败——槽可以之后由定时任务补齐。
     *
     * @param args 应用启动参数，本实现不使用
     */
    @Override
    public void run(ApplicationArguments args) {
        try {
            // 14 天与 ScheduledJobs 每天凌晨补槽的跨度一致，启动预热 + 定时补齐覆盖滚动窗口。
            slotService.generateNextDays(14);
        } catch (Exception e) {
            // 预热失败不阻断主应用：库存可由定时任务补齐，是「启动可降级、运行可自愈」的取舍。
            log.warn("启动时生成时间槽失败，请检查数据库与排班数据", e);
        }
    }
}
