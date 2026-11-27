package com.haust.petcare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.haust.petcare.common.BusinessException;
import com.haust.petcare.config.PetcareProperties;
import com.haust.petcare.entity.Address;
import com.haust.petcare.entity.Pet;
import com.haust.petcare.entity.ServiceItem;
import com.haust.petcare.entity.TimeSlot;
import com.haust.petcare.mapper.ServiceItemMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 价格试算：服务费 + 附加项 + 距离费 + 夜间费 + 加急费。
 * 所有价格在下单时快照，后续调价不影响已支付订单。
 * <p>
 * 业务域：下单前的「五段计价」试算，对应 service_item（S/M/L 三档体型价）、
 * PetcareProperties.Store（门店坐标/免费里程/每公里费）以及内存静态附加项价目表 ADDONS；
 * 本类不落库，结果由 OrderService.create 快照进 orders 的 base_price / addon_amount /
 * distance_fee / night_fee / urgent_fee / pay_amount 等字段。
 * <p>
 * 链路位置：查可约时间槽之后、OrderService.create 下单之前调用 quote()；
 * 面试要点：金额全程用 BigDecimal（double 有二进制浮点误差），统一 setScale(2, HALF_UP) 舍入，
 * 距离用 Haversine 球面公式但只作计费输入，费用换算回 BigDecimal。
 * 注意：服务者等级（staff.level）只在时间槽列表作为展示字段返回，不参与计价；
 * 体型也是「档位价」而非系数，实际五段是 基础价 + 附加费 + 超里程费 + 夜间费 + 加急费。
 */
@Service
public class PriceService {

    // 附加项静态价目表（内存常量，非数据库配置）：剪指甲/洁耳/药浴/口腔清洁；
    // 用 LinkedHashMap 保证试算明细顺序稳定；与下方 ADDON_NAMES 的 key 一一对应。
    public static final Map<String, BigDecimal> ADDONS = new LinkedHashMap<>();

    static {
        ADDONS.put("nail", new BigDecimal("20"));
        ADDONS.put("ear", new BigDecimal("15"));
        ADDONS.put("bath", new BigDecimal("40"));
        ADDONS.put("teeth", new BigDecimal("25"));
    }

    // 附加项展示名，下单时连同价格写进 orders.addon_json 快照，后续改名不影响历史订单。
    public static final Map<String, String> ADDON_NAMES = new LinkedHashMap<>();

    static {
        ADDON_NAMES.put("nail", "剪指甲");
        ADDON_NAMES.put("ear", "洁耳");
        ADDON_NAMES.put("bath", "药浴");
        ADDON_NAMES.put("teeth", "口腔清洁");
    }

    private final PetcareProperties properties;
    private final ServiceItemMapper serviceItemMapper;

    /**
     * 注入配置（门店坐标、免费里程、费率）与在售服务 Mapper；本类无状态，单例即可。
     */
    public PriceService(PetcareProperties properties, ServiceItemMapper serviceItemMapper) {
        this.properties = properties;
        this.serviceItemMapper = serviceItemMapper;
    }

    /**
     * 取在售服务项，下单与试算前统一做「存在且 status=1 上架」校验。
     *
     * @param serviceId 服务项 ID
     * @return 服务项（含 S/M/L 三档价与时长）
     * @throws BusinessException 服务不存在或已下架
     */
    public ServiceItem requireService(Long serviceId) {
        ServiceItem item = serviceItemMapper.selectById(serviceId);
        if (item == null || item.getStatus() == null || item.getStatus() != 1) {
            throw new BusinessException("服务不存在或已下架");
        }
        return item;
    }

    /**
     * 按体重划分体型档位：<=10kg 为 S、<=25kg 为 M、其余 L；体重为空默认 M。
     * <p>
     * 这个档位既决定取哪档基础价，也用于过滤服务者 size_types 能力。
     *
     * @param pet 宠物档案
     * @return "S" / "M" / "L"
     */
    public String sizeOf(Pet pet) {
        // 体重缺失时默认 M，避免 NPE 导致下单失败；业务上宁可按中型计费也要能完成试算。
        if (pet.getWeightKg() == null) {
            return "M";
        }
        double weight = pet.getWeightKg().doubleValue();
        if (weight <= 10) {
            return "S";
        }
        if (weight <= 25) {
            return "M";
        }
        return "L";
    }

    /**
     * 第一段·基础价：按体型档位取服务项的 priceS/priceM/priceL。
     * <p>
     * 注意这里是「三档档位价」，不是按系数乘算；价格为 null 时按 0 处理（配置缺失兜底）。
     *
     * @param item 服务项
     * @param size 体型档位 S/M/L
     * @return 该档基础价（BigDecimal）
     */
    public BigDecimal basePrice(ServiceItem item, String size) {
        // 金额一律用 BigDecimal：double 无法精确表示 0.1 这类十进制小数，累加会产生分厘误差。
        BigDecimal price;
        if ("S".equals(size)) {
            price = item.getPriceS();
        } else if ("L".equals(size)) {
            price = item.getPriceL();
        } else {
            price = item.getPriceM();
        }
        return price == null ? BigDecimal.ZERO : price;
    }

    /**
     * 门店到用户地址的球面距离（公里），Haversine 公式。
     * <p>
     * 门店坐标取自 petcare.store.lat/lng；这里返回 double 是地理量而非金额，
     * 只在 quote() 里换算成费用时才转回 BigDecimal。
     *
     * @param address 用户地址（含经纬度）
     * @return 距离公里数；地址或缺经纬度时返回 0（按不超免费里程处理）
     */
    public double distanceKm(Address address) {
        if (address == null || address.getLat() == null || address.getLng() == null) {
            return 0;
        }
        // 经纬度转弧度后用 Haversine 算大圆距离，6371 为地球平均半径(km)；
        // 门店坐标来自配置而非硬编码，换门店位置不用改代码。
        double lat1 = Math.toRadians(properties.getStore().getLat());
        double lon1 = Math.toRadians(properties.getStore().getLng());
        double lat2 = Math.toRadians(address.getLat().doubleValue());
        double lon2 = Math.toRadians(address.getLng().doubleValue());
        double dLat = lat2 - lat1;
        double dLon = lon2 - lon1;
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        // 结果仅作为计费输入，超里程费用在 quote() 里用 BigDecimal 计算，避免浮点误差进金额。
        return 6371 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /**
     * 五段计价试算（下单前的价格预览）：基础价 + 附加费 + 超里程费 + 夜间费 + 加急费。
     * <p>
     * 链路：SlotController 查完可约槽后、OrderService.create 下单前调用；返回 Map 里的每一项
     * 都会在下单时快照进 orders，保证「所见即所付」且后续调价不影响已下订单。
     *
     * @param item       在售服务项
     * @param pet        宠物（决定体型档位）
     * @param address    服务地址（决定超里程费）
     * @param slot       时间槽（决定夜间费，可为 null 表示不试算时段）
     * @param addonCodes 附加项编码列表，可为 null
     * @param urgent     是否加急
     * @return 含各段金额、明细、距离、total 的试算结果
     * @throws BusinessException 传入了未知的附加项编码
     */
    public Map<String, Object> quote(ServiceItem item, Pet pet, Address address,
                                     TimeSlot slot, List<String> addonCodes, boolean urgent) {
        String size = sizeOf(pet);
        // 第 1 段·基础价：按体型档位取 S/M/L 三档价。
        BigDecimal base = basePrice(item, size);

        // 第 2 段·附加费：逐项查静态价目表累加，未知编码直接拒绝；
        // 同时收集 addonItems 明细（编码/名称/单价）供下单写进 addon_json 快照。
        BigDecimal addonAmount = BigDecimal.ZERO;
        List<Map<String, Object>> addonItems = new ArrayList<>();
        if (addonCodes != null) {
            for (String code : addonCodes) {
                BigDecimal price = ADDONS.get(code);
                if (price == null) {
                    throw new BusinessException("未知的附加项：" + code);
                }
                addonAmount = addonAmount.add(price);
                Map<String, Object> addon = new LinkedHashMap<>();
                addon.put("code", code);
                addon.put("name", ADDON_NAMES.get(code));
                addon.put("price", price);
                addonItems.add(addon);
            }
        }

        // 第 3 段·超里程费：freeKm(默认 5km) 内免费，超出部分先 Math.ceil 向上取整到整公里，
        // 再乘 perKmFee(默认 5 元/km)；用整公里避免小数公里产生分厘金额，
        // 结果经 BigDecimal.valueOf 转回，不把 double 直接累加进金额。
        double km = distanceKm(address);
        int freeKm = properties.getStore().getFreeKm();
        BigDecimal distanceFee = BigDecimal.ZERO;
        if (km > freeKm) {
            long extra = (long) Math.ceil(km) - freeKm;
            distanceFee = BigDecimal.valueOf(extra * properties.getStore().getPerKmFee());
        }

        // 第 4 段·夜间费：时间槽落在 22:00~次日 8:00 时固定加 30 元；
        // slot 为 null（仅试算不选时段）时跳过。
        BigDecimal nightFee = BigDecimal.ZERO;
        if (slot != null && isNight(slot.getSlotStart())) {
            nightFee = new BigDecimal("30");
        }

        // 第 5 段·加急费：基础价 × 30%，单独先 setScale(2, HALF_UP) 再累加；
        // 用 BigDecimal 字符串构造 0.3，避免 new BigDecimal(0.3) 带入 double 误差。
        BigDecimal urgentFee = urgent ? base.multiply(new BigDecimal("0.3")).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        // 五段统一用 BigDecimal 累加并 setScale(2, HALF_UP) 四舍五入到分，
        // 保证前端展示、订单快照、支付金额三处完全一致；舍入模式固定 HALF_UP 避免银行家舍入争议。
        BigDecimal total = base.add(addonAmount).add(distanceFee).add(nightFee).add(urgentFee)
                .setScale(2, RoundingMode.HALF_UP);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("serviceId", item.getId());
        result.put("serviceName", item.getName());
        result.put("size", size);
        result.put("durationMin", item.getDurationMin());
        result.put("basePrice", base);
        result.put("addonAmount", addonAmount);
        result.put("addonItems", addonItems);
        result.put("distanceKm", Math.round(km * 10) / 10.0);
        result.put("distanceFee", distanceFee);
        result.put("nightFee", nightFee);
        result.put("urgentFee", urgentFee);
        result.put("total", total);
        if (slot != null) {
            result.put("slotStart", slot.getSlotStart());
            result.put("slotEnd", slot.getSlotEnd());
            result.put("staffId", slot.getStaffId());
        }
        return result;
    }

    /**
     * 查询全部在售服务项，按 sort 升序，供目录页与 AI 工具「查价格」使用。
     *
     * @return 在售服务项列表
     */
    public List<ServiceItem> listOnSale() {
        return serviceItemMapper.selectList(new LambdaQueryWrapper<ServiceItem>()
                .eq(ServiceItem::getStatus, 1)
                .orderByAsc(ServiceItem::getSort));
    }

    /**
     * 判断时间是否属于夜间时段（22:00 起或 8:00 前），夜间费与 AI 系统提示词共用该规则。
     *
     * @param time 时间槽开始时间
     * @return 是否夜间
     */
    public boolean isNight(LocalDateTime time) {
        int hour = time.getHour();
        return hour >= 22 || hour < 8;
    }
}
