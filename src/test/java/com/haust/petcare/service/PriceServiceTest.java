package com.haust.petcare.service;

import com.haust.petcare.config.PetcareProperties;
import com.haust.petcare.entity.Address;
import com.haust.petcare.entity.Pet;
import com.haust.petcare.entity.ServiceItem;
import com.haust.petcare.entity.TimeSlot;
import com.haust.petcare.mapper.ServiceItemMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 价格试算：体重分档（S/M/L）与「五段计价」边界。
 * 五段 = 基础服务费 + 附加项 + 距离费 + 夜间费 + 加急费，对应 PriceService.quote 里的 total；
 * 本类分别覆盖分档边界、附加项/加急费、夜间费与超免费公里后的里程费三组场景。
 * 纯 Mockito 单测（不启 Spring 容器、不连 MySQL/Redis）：quote 只依赖入参对象与
 * PetcareProperties，ServiceItemMapper 用 @Mock 占位即可；门店经纬度、免费公里数、每公里费
 * 在 setUp 里写死，保证金额断言稳定、不受真实配置影响。
 */
@ExtendWith(MockitoExtension.class)
class PriceServiceTest {

    @Mock
    private ServiceItemMapper serviceItemMapper;

    private PriceService priceService;

    @BeforeEach
    void setUp() {
        PetcareProperties properties = new PetcareProperties();
        properties.getStore().setLat(30.276);
        properties.getStore().setLng(120.135);
        properties.getStore().setFreeKm(5);
        properties.getStore().setPerKmFee(5);
        priceService = new PriceService(properties, serviceItemMapper);
    }

    /**
     * 场景：体重 4.5 / 18 / 30 kg 以及 null（未填体重）四种输入。
     * 断言：分别得到 S / M / L / M（<=10 为 S，<=25 为 M，其余 L，缺失默认 M）。
     * 业务风险：分档决定按哪档单价收费，10/25 边界算错会多收或少收；缺体重给默认值避免 NPE。
     */
    @Test
    void sizeShouldFollowWeight() {
        assertEquals("S", priceService.sizeOf(pet(4.5)));
        assertEquals("M", priceService.sizeOf(pet(18.0)));
        assertEquals("L", priceService.sizeOf(pet(30.0)));
        assertEquals("M", priceService.sizeOf(pet(null)));
    }

    /**
     * 场景：S 档基础价 100、附加项 nail 20、加急 true（基础价 ×30% = 30）；地址与门店同坐标、10 点时段。
     * 断言：basePrice=100、addonAmount=20、urgentFee=30.00、total=150.00，距离费与夜间费均为 0。
     * 业务风险：验证加急费按基础价而非总价计算，且五个分项相加才等于应付，防止漏收或重复计费。
     */
    @Test
    void quoteShouldSumBaseAddonAndUrgent() {
        ServiceItem item = item();
        Pet pet = pet(4.5);
        Address address = address(30.276, 120.135);
        TimeSlot slot = slot(10);

        Map<String, Object> quote = priceService.quote(item, pet, address, slot,
                Collections.singletonList("nail"), true);

        assertEquals(new BigDecimal("100"), quote.get("basePrice"));
        assertEquals(new BigDecimal("20"), quote.get("addonAmount"));
        assertEquals(new BigDecimal("30.00"), quote.get("urgentFee"));
        assertEquals(new BigDecimal("150.00"), quote.get("total"));
    }

    /**
     * 场景：M 档基础价 150、23 点夜间时段、地址偏移到 5 公里之外、无附加项、不加急。
     * 断言：nightFee=30、distanceKm>5、distanceFee>0。
     * 业务风险：夜间服务费与超出免费公里后的里程费是最容易漏算的两段，漏算直接造成平台亏损。
     */
    @Test
    void quoteShouldChargeNightFeeAndDistance() {
        ServiceItem item = item();
        Pet pet = pet(18.0);
        Address address = address(30.376, 120.235);
        TimeSlot slot = slot(23);

        Map<String, Object> quote = priceService.quote(item, pet, address, slot,
                Collections.emptyList(), false);

        assertEquals(new BigDecimal("150"), quote.get("basePrice"));
        assertEquals(new BigDecimal("30"), quote.get("nightFee"));
        double km = (double) quote.get("distanceKm");
        assertTrue(km > 5, "测试地址应超过 5 公里");
        assertTrue(((BigDecimal) quote.get("distanceFee")).compareTo(BigDecimal.ZERO) > 0);
    }

    // 以下为构造入参的测试夹具：字段与 PriceService.quote 实际读取的字段一一对应
    private Pet pet(Double weight) {
        Pet pet = new Pet();
        pet.setWeightKg(weight == null ? null : BigDecimal.valueOf(weight));
        return pet;
    }

    private ServiceItem item() {
        ServiceItem item = new ServiceItem();
        item.setId(1L);
        item.setName("上门洗护");
        item.setDurationMin(60);
        item.setPriceS(new BigDecimal("100"));
        item.setPriceM(new BigDecimal("150"));
        item.setPriceL(new BigDecimal("200"));
        return item;
    }

    private Address address(double lat, double lng) {
        Address address = new Address();
        address.setLat(BigDecimal.valueOf(lat));
        address.setLng(BigDecimal.valueOf(lng));
        return address;
    }

    private TimeSlot slot(int hour) {
        TimeSlot slot = new TimeSlot();
        slot.setSlotStart(LocalDateTime.of(2026, 11, 11, hour, 0));
        slot.setSlotEnd(LocalDateTime.of(2026, 11, 11, hour, 0).plusHours(1));
        return slot;
    }
}
