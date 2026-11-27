package com.haust.petcare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 投诉/售后工单，对应表 complaint，属于「履约售后」环节。
 * <p>
 * 用户就服务问题提交投诉，客服（管理员）受理后写回 result 并推进状态；
 * 服务已开始后订单不允许直接取消，只能走投诉/售后这一路径（见 OrderService.cancel）。
 * 状态流转 0 待受理 → 1 处理中 → 2 已解决 / 3 已关闭。
 */
@Data
@TableName("complaint")
public class Complaint {

    /** 主键，数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 关联订单 id */
    private Long orderId;
    /** 投诉人（app_user.id），非管理员只能看自己的投诉 */
    private Long userId;
    /** 投诉类型（服务态度/服务质量/宠物安全等） */
    private String type;
    /** 投诉正文 */
    private String content;
    /** 凭证图片，逗号分隔的 URL，供客服判定 */
    private String images;
    /** 0 待受理 1 处理中 2 已解决 3 已关闭 */
    private Integer status;
    /** 客服处理结果，resolveComplaint 时写回并触发通知 */
    private String result;
    /** 提交时间，DB 默认 CURRENT_TIMESTAMP */
    private LocalDateTime createTime;
    /** 处理完成时间，工单闭环时写入 */
    private LocalDateTime finishTime;
}
