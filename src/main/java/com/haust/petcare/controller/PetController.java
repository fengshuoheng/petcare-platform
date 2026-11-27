package com.haust.petcare.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.haust.petcare.common.BusinessException;
import com.haust.petcare.common.Result;
import com.haust.petcare.common.UserContext;
import com.haust.petcare.entity.Pet;
import com.haust.petcare.mapper.PetMapper;
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
 * 宠物档案管理（用户端业务域）。
 * <p>
 * 架构位置：/api/pets 下的 CRUD 控制器，被 AuthInterceptor 保护，登录态从 UserContext 取。
 * 全部查询/修改都强制按 UserContext.userId() 过滤，宠物属于「用户私有资源」，
 * 因此 create 里覆盖 id/userId、update/delete 前用 requireMine 校验归属，防止越权操作他人数据。
 */
@RestController
@RequestMapping("/api/pets")
public class PetController {

    private final PetMapper petMapper;

    public PetController(PetMapper petMapper) {
        this.petMapper = petMapper;
    }

    /**
     * 我的宠物列表。
     * URL: GET /api/pets；调用方：用户端（已登录）。
     * 入参：无；按 UserContext.userId() 过滤，按 id 倒序。
     * 权限：需登录态；只看得到自己的宠物。返回：Pet 列表。
     */
    @GetMapping
    public Result<List<Pet>> list() {
        return Result.ok(petMapper.selectList(new LambdaQueryWrapper<Pet>()
                .eq(Pet::getUserId, UserContext.userId())
                .orderByDesc(Pet::getId)));
    }

    /**
     * 新增宠物。
     * URL: POST /api/pets；调用方：用户端（已登录）。
     * 入参：Pet 请求体，仅 name 做非空校验；id 强制置 null、userId 强制写当前用户，
     * 防止前端伪造 id 覆盖他人记录或把宠物挂到别人名下。
     * 权限：需登录态。返回：带自增 id 的 Pet。
     */
    @PostMapping
    public Result<Pet> create(@RequestBody Pet pet) {
        if (pet.getName() == null || pet.getName().trim().isEmpty()) {
            throw new BusinessException("宠物名字不能为空");
        }
        pet.setId(null);
        pet.setUserId(UserContext.userId());
        petMapper.insert(pet);
        return Result.ok(pet);
    }

    /**
     * 修改宠物。
     * URL: PUT /api/pets/{id}；调用方：用户端（已登录）。
     * 入参：路径 id + Pet 请求体；先 requireMine 校验归属，再把 id/userId 强制回填为原值，避免越权改归属。
     * 权限：需登录态且仅本人。返回：更新后的 Pet。
     */
    @PutMapping("/{id}")
    public Result<Pet> update(@PathVariable Long id, @RequestBody Pet pet) {
        Pet exist = requireMine(id);
        pet.setId(exist.getId());
        pet.setUserId(exist.getUserId());
        petMapper.updateById(pet);
        return Result.ok(petMapper.selectById(id));
    }

    /**
     * 删除宠物。
     * URL: DELETE /api/pets/{id}；调用方：用户端（已登录）。
     * 入参：路径 id；requireMine 校验归属后才删除。
     * 权限：需登录态且仅本人。返回：空 data 的 Result.ok()。
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        requireMine(id);
        petMapper.deleteById(id);
        return Result.ok();
    }

    /**
     * 归属校验：查得到且 userId 等于当前登录用户才返回，否则抛「宠物不存在」。
     * 用统一的「不存在」文案而不区分「无权限」，避免通过报错枚举他人宠物 id 是否存在。
     */
    private Pet requireMine(Long id) {
        Pet pet = petMapper.selectById(id);
        if (pet == null || !pet.getUserId().equals(UserContext.userId())) {
            throw new BusinessException("宠物不存在");
        }
        return pet;
    }
}
