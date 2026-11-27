package com.haust.petcare.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.haust.petcare.common.Result;
import com.haust.petcare.entity.Staff;
import com.haust.petcare.mapper.StaffMapper;
import com.haust.petcare.service.PriceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务目录与展示（公共查询业务域，登录即可访问）。
 * <p>
 * 架构位置：挂在 /api 根路径下，提供下单前的只读数据——在售服务、附加项、服务者列表。
 * 这些接口不含用户私有数据，但仍落在 /api/** 内，会被 AuthInterceptor 拦截，需带 authorization。
 * 价格与加项来自 PriceService / Mapper，不在 Controller 里写死业务规则。
 */
@RestController
@RequestMapping("/api")
public class CatalogController {

    private final PriceService priceService;
    private final StaffMapper staffMapper;

    public CatalogController(PriceService priceService, StaffMapper staffMapper) {
        this.priceService = priceService;
        this.staffMapper = staffMapper;
    }

    /**
     * 在售服务列表。
     * URL: GET /api/services；调用方：用户端（浏览/下单前选服务）。
     * 入参：无。权限：需登录态。返回：PriceService.listOnSale() 的在售 ServiceItem 列表。
     */
    @GetMapping("/services")
    public Result<?> services() {
        return Result.ok(priceService.listOnSale());
    }

    /**
     * 附加服务项列表。
     * URL: GET /api/services/addons；调用方：用户端（下单勾选加项）。
     * 入参：无。权限：需登录态。
     * 返回：把 PriceService.ADDONS / ADDON_NAMES 两个常量整理成 code / name / price 的列表，
     * 让前端按 code 回传加项、后端按常量定价，避免价格由前端传参决定。
     */
    @GetMapping("/services/addons")
    public Result<List<Map<String, Object>>> addons() {
        List<Map<String, Object>> list = new ArrayList<>();
        PriceService.ADDONS.forEach((code, price) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", code);
            item.put("name", PriceService.ADDON_NAMES.get(code));
            item.put("price", price);
            list.add(item);
        });
        return Result.ok(list);
    }

    /**
     * 服务者列表。
     * URL: GET /api/staffs；调用方：用户端（查看可服务人员）。
     * 入参：无。权限：需登录态。
     * 返回：过滤 status=3（离职/停用）后按 rating 降序的 Staff 列表，评分高的排前面。
     */
    @GetMapping("/staffs")
    public Result<?> staffs() {
        return Result.ok(staffMapper.selectList(new LambdaQueryWrapper<Staff>()
                .ne(Staff::getStatus, 3)
                .orderByDesc(Staff::getRating)));
    }
}
