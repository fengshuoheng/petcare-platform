package com.haust.petcare.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.haust.petcare.common.BusinessException;
import com.haust.petcare.common.Result;
import com.haust.petcare.common.UserContext;
import com.haust.petcare.entity.Address;
import com.haust.petcare.mapper.AddressMapper;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 服务地址管理（用户端业务域）。
 * <p>
 * 架构位置：/api/addresses 下的 CRUD 控制器，受 AuthInterceptor 保护，归属校验思路与 PetController 一致。
 * 额外维护「默认地址」唯一性：新增/修改时若指定 isDefault=1，先用 clearDefault 把该用户其余地址清零，
 * 再写入当前这条，保证同一用户最多只有一个默认地址。
 */
@RestController
@RequestMapping("/api/addresses")
public class AddressController {

    private final AddressMapper addressMapper;

    public AddressController(AddressMapper addressMapper) {
        this.addressMapper = addressMapper;
    }

    /**
     * 我的地址列表。
     * URL: GET /api/addresses；调用方：用户端（已登录）。
     * 入参：无；按 UserContext.userId() 过滤，默认地址优先、再按 id 倒序。
     * 权限：需登录态；只看得到自己的地址。返回：Address 列表。
     */
    @GetMapping
    public Result<List<Address>> list() {
        return Result.ok(addressMapper.selectList(new LambdaQueryWrapper<Address>()
                .eq(Address::getUserId, UserContext.userId())
                .orderByDesc(Address::getIsDefault)
                .orderByDesc(Address::getId)));
    }

    /**
     * 新增地址。
     * URL: POST /api/addresses；调用方：用户端（已登录）。
     * 入参：Address 请求体，detail 非空校验；id 置 null、userId 写当前用户防伪造归属；
     * 若 isDefault=1 先 clearDefault 清掉旧默认地址。
     * 权限：需登录态。返回：带自增 id 的 Address。
     */
    @PostMapping
    public Result<Address> create(@RequestBody Address address) {
        if (address.getDetail() == null || address.getDetail().trim().isEmpty()) {
            throw new BusinessException("详细地址不能为空");
        }
        address.setId(null);
        address.setUserId(UserContext.userId());
        if (address.getIsDefault() != null && address.getIsDefault() == 1) {
            clearDefault();
        }
        addressMapper.insert(address);
        return Result.ok(address);
    }

    /**
     * 修改地址。
     * URL: PUT /api/addresses/{id}；调用方：用户端（已登录）。
     * 入参：路径 id + Address 请求体；requireMine 校验归属并回填 id/userId 防越权，
     * isDefault=1 时先 clearDefault。
     * 权限：需登录态且仅本人。返回：更新后的 Address。
     */
    @PutMapping("/{id}")
    public Result<Address> update(@PathVariable Long id, @RequestBody Address address) {
        Address exist = requireMine(id);
        address.setId(id);
        address.setUserId(exist.getUserId());
        if (address.getIsDefault() != null && address.getIsDefault() == 1) {
            clearDefault();
        }
        addressMapper.updateById(address);
        return Result.ok(addressMapper.selectById(id));
    }

    /**
     * 删除地址。
     * URL: DELETE /api/addresses/{id}；调用方：用户端（已登录）。
     * 入参：路径 id；requireMine 校验归属后才删除。
     * 权限：需登录态且仅本人。返回：空 data 的 Result.ok()。
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        requireMine(id);
        addressMapper.deleteById(id);
        return Result.ok();
    }

    /**
     * 把当前用户名下所有地址的 isDefault 置 0（一条带 userId 条件的批量 UPDATE）。
     * 在设置新默认地址之前调用，配合新增/修改保证「默认地址唯一」；范围限定当前用户，不会影响他人。
     */
    private void clearDefault() {
        addressMapper.update(null, new LambdaUpdateWrapper<Address>()
                .set(Address::getIsDefault, 0)
                .eq(Address::getUserId, UserContext.userId()));
    }

    /**
     * 归属校验：查得到且 userId 等于当前登录用户才返回，否则抛「地址不存在」。
     * 统一用「不存在」文案，避免借报错枚举他人地址 id。
     */
    private Address requireMine(Long id) {
        Address address = addressMapper.selectById(id);
        if (address == null || !address.getUserId().equals(UserContext.userId())) {
            throw new BusinessException("地址不存在");
        }
        return address;
    }
}
