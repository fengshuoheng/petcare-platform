package com.haust.petcare.controller;

import com.haust.petcare.common.Result;
import com.haust.petcare.service.SlotService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 可预约时间槽查询（用户端下单流程业务域）。
 * <p>
 * 架构位置：/api/slots 下唯一的只读接口，受 AuthInterceptor 保护。
 * 时间槽由 ScheduledJobs 每天定时生成、下单时用条件 UPDATE 抢占，本接口只负责按「日期 + 宠物体型」查可约槽。
 */
@RestController
@RequestMapping("/api/slots")
public class SlotController {

    private final SlotService slotService;

    public SlotController(SlotService slotService) {
        this.slotService = slotService;
    }

    /**
     * 查询指定日期可预约的时间槽。
     * URL: GET /api/slots；调用方：用户端（下单选时间）。
     * 入参：date（@DateTimeFormat ISO 日期，必填）、petId（必填，SlotService 据此取宠物体型筛选可接待的槽）。
     * 校验：date 早于今天直接返回空列表、不查库；petId 对应宠物不存在时由 SlotService 抛业务异常。
     * 权限：需登录态。返回：该日可约时间槽列表（Map 形式）。
     */
    @GetMapping
    public Result<?> list(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                          @RequestParam Long petId) {
        if (date.isBefore(LocalDate.now())) {
            return Result.ok(java.util.Collections.emptyList());
        }
        return Result.ok(slotService.listByDate(date, petId));
    }
}
